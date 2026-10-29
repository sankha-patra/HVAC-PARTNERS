package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import static com.invoicepayments.billing.BillingStore.*;
import static com.invoicepayments.billing.BillingFinancialDatabaseTest.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Refunds, recovery, outbox publishing, reconciliation, retry and the ERP/dead-letter consumers own their database
 * work through named, proxied @Transactional command beans. The store in this context refuses the old template, so a
 * path that still opens its own transaction fails. Real PostgreSQL, the production transaction configuration and a
 * one-connection pool; no outer test transaction.
 */
@EnabledIfEnvironmentVariable(named="BILLING_TEST_JDBC_URL", matches="jdbc:postgresql://127\\.0\\.0\\.1:15432/invoice_payments_test")
class BillingTransactionExtractionDatabaseTest {
    static final List<String> COMMANDS=List.of("BillingRefundCommands","BillingRecoveryCommands","BillingOutboxCommands",
        "BillingReconciliationCommands","BillingRetryCommands","BillingEventCommands");
    BillingFinancialDatabaseTest fixture;
    @BeforeAll static void migrate() throws Exception {migrateOnlyIsolatedDatabase();}
    @BeforeEach void seed(){fixture=new BillingFinancialDatabaseTest();fixture.cleanAndSeedDedicatedDatabase();}
    @AfterEach void cleanup(){fixture.removeOnlyTestFaultTrigger();}

    @Test void everyExtractedCommandIsARealProxyWithTheBillingTransactionPolicy() throws Exception {
        try(var pool=BillingAnnotationHarnessDatabaseTest.pool();var context=context(pool,fixture.provider,null)) {
            var source=context.getBean(TransactionAttributeSource.class);
            for(String name:COMMANDS) {
                Class<?> type=Class.forName("com.invoicepayments.billing."+name);
                assertThat(AopUtils.isAopProxy(context.getBean(type))).as(name).isTrue();
                int count=0;
                for(var method:type.getDeclaredMethods()) if(Modifier.isPublic(method.getModifiers())) {
                    var attribute=source.getTransactionAttribute(method,type);
                    assertThat(attribute).as(method.toString()).isNotNull();
                    assertThat(attribute.getPropagationBehavior()).as(method.toString()).isEqualTo(Propagation.REQUIRED.value());
                    assertThat(attribute.getTimeout()).as(method.toString()).isEqualTo(10);
                    assertThat(attribute.getQualifier()).isEqualTo("transactionManager");
                    assertThat(attribute.getLabels()).contains("billing");
                    assertThat(attribute.rollbackOn(new IOException("checked"))).isTrue();count++;
                }
                assertThat(count).as(name+" public commands").isPositive();
            }
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        }
    }

    // The refund is saved and committed before Stripe is asked, with no connection held during the call; the answer is
    // recorded in a separate commit.
    @Test void aRefundIsPreparedAndCommittedBeforeTheProviderCallAndRecordedSeparately() throws Exception {
        var prepared=new AtomicLong();
        try(var pool=BillingAnnotationHarnessDatabaseTest.pool()) {
            var observed=new Captures(fixture.provider) {
                @Override public ProviderResult refund(RefundCommand command) {
                    assertNoTransactionOrConnection(pool);
                    var saved=fixture.db.one("SELECT status,xmin::text::bigint AS transaction_id FROM billing.refunds WHERE id=?",UUID.fromString(command.refundId()));
                    assertThat(saved).containsEntry("status","SUBMITTING");prepared.set(amount(saved,"transactionId"));
                    return super.refund(command);
                }
            };
            try(var context=context(pool,observed,null)) {
                UUID intent=captured(context);
                var refunds=context.getBean(BillingRefundService.class);
                UUID refund=id(refunds.request(FINANCE,intent,UUID.randomUUID().toString(),2500,"Customer overpaid").get("id"));
                assertThat(refunds.approve(APPROVER,refund)).containsEntry("status","REFUNDED");
                assertThat(prepared.get()).isPositive();
                assertThat(xmin("refunds",refund)).isNotEqualTo(prepared.get());
                assertThat(fixture.db.rows("SELECT 1 FROM billing.journals WHERE effect_key=?","refund:"+refund)).hasSize(1);
                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            }
        }
    }

    // A signed refund webhook records the refund in the inbox's own transaction: the inbox mark and the refund commit
    // together, or neither does.
    @Test void aRefundWebhookIsRecordedInTheInboxTransaction() throws Exception {
        try(var pool=BillingAnnotationHarnessDatabaseTest.pool()) {
            var observed=new Captures(fixture.provider) {
                @Override public ProviderResult refund(RefundCommand command){throw new ProviderUncertainException("Response lost");}
            };
            try(var context=context(pool,observed,null)) {
                UUID intent=captured(context);
                var refunds=context.getBean(BillingRefundService.class);
                UUID refund=id(refunds.request(FINANCE,intent,UUID.randomUUID().toString(),2500,"Customer overpaid").get("id"));
                refunds.approve(APPROVER,refund);
                assertThat(text(fixture.db.one("SELECT status FROM billing.refunds WHERE id=?",refund),"status")).isEqualTo("UNKNOWN");
                var inbox=context.getBean(BillingInbox.class);
                String provider=fixture.provider.name();
                inbox.receive(provider,new ProviderWebhookEvent("evt_refund_"+refund,"re_"+refund,intent.toString(),"REFUNDED",2500,"USD",refund.toString()));
                inbox.process(provider,"evt_refund_"+refund);
                assertThat(text(fixture.db.one("SELECT status FROM billing.refunds WHERE id=?",refund),"status")).isEqualTo("REFUNDED");
                long marked=amount(fixture.db.one("SELECT xmin::text::bigint AS transaction_id FROM billing.webhook_inbox WHERE event_id=?","evt_refund_"+refund),"transactionId");
                assertThat(xmin("refunds",refund)).isEqualTo(marked);
            }
        }
    }

    // Recovery claims a stuck payment in one commit, asks Stripe with no connection held, and records in another.
    @Test void paymentRecoveryClaimsAndRecordsInSeparateCommitsAroundTheProviderCall() throws Exception {
        var calls=new AtomicInteger();
        try(var pool=BillingAnnotationHarnessDatabaseTest.pool()) {
            var observed=new Captures(fixture.provider) {
                @Override public ProviderResult submit(ProviderCommand command){super.submitOnly(command);throw new ProviderUncertainException("Response lost");}
                @Override public ProviderResult recoverPayment(ProviderCommand command,String version,java.time.Instant preparedAt,String reference) {
                    assertNoTransactionOrConnection(pool);calls.incrementAndGet();
                    return delegate.simulateResult(command.intentId(),"CAPTURED");
                }
            };
            try(var context=context(pool,observed,null)) {
                var service=context.getBean(BillingService.class);
                UUID intent=id(service.create(PAYER,ACCOUNT,UUID.randomUUID().toString(),List.of(INVOICE),"CARD").get("id"));
                assertThat(service.submit(PAYER,intent)).containsEntry("status","UNKNOWN");
                fixture.due(intent);
                var recovery=context.getBean(BillingRecovery.class);
                var claimed=recovery.claimPayments(1);
                assertThat(claimed).hasSize(1);
                long claim=xmin("intents",intent);
                recovery.recoverPayment(claimed.get(0));
                assertThat(text(fixture.db.one("SELECT status FROM billing.intents WHERE id=?",intent),"status")).isEqualTo("CAPTURED");
                assertThat(xmin("intents",intent)).isNotEqualTo(claim);
                assertThat(calls).hasValue(1);
                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            }
        }
    }

    // A refund whose answer was lost is claimed, replayed with its saved request and posted exactly once.
    @Test void refundRecoveryReplaysTheSavedRequestAndPostsOnce() throws Exception {
        var lose=new AtomicBoolean(true);
        try(var pool=BillingAnnotationHarnessDatabaseTest.pool()) {
            var observed=new Captures(fixture.provider) {
                @Override public ProviderResult refund(RefundCommand command) {
                    if(lose.getAndSet(false)) {super.refund(command);throw new ProviderUncertainException("Response lost");}
                    assertNoTransactionOrConnection(pool);
                    return super.refund(command);
                }
                @Override public ProviderResult recoverRefund(RefundCommand command,String version,java.time.Instant preparedAt,String reference) {
                    assertNoTransactionOrConnection(pool);
                    return refund(command);
                }
            };
            try(var context=context(pool,observed,null)) {
                UUID intent=captured(context);
                var refunds=context.getBean(BillingRefundService.class);
                UUID refund=id(refunds.request(FINANCE,intent,UUID.randomUUID().toString(),2500,"Customer overpaid").get("id"));
                refunds.approve(APPROVER,refund);
                assertThat(text(fixture.db.one("SELECT status FROM billing.refunds WHERE id=?",refund),"status")).isEqualTo("UNKNOWN");
                fixture.db.update("UPDATE billing.refunds SET lease_until=NOW()-INTERVAL '1 second',next_recovery_at=NOW()-INTERVAL '1 second' WHERE id=?",refund);
                var recovery=context.getBean(BillingRecovery.class);
                var claimed=recovery.claimRefunds(1);
                assertThat(claimed).hasSize(1);
                recovery.recoverRefund(claimed.get(0));
                recovery.recoverRefund(claimed.get(0));
                assertThat(text(fixture.db.one("SELECT status FROM billing.refunds WHERE id=?",refund),"status")).isEqualTo("REFUNDED");
                assertThat(fixture.db.rows("SELECT 1 FROM billing.journals WHERE effect_key=?","refund:"+refund)).hasSize(1);
            }
        }
    }

    // The outbox claim, the Kafka send (no transaction) and the published mark are separate; a send that can never
    // succeed is quarantined with its audit row in one commit.
    @Test void outboxClaimSendAndMarkAreSeparateAndAPermanentFailureIsQuarantined() throws Exception {
        try(var pool=BillingAnnotationHarnessDatabaseTest.pool()) {
            var kafka=new RecordingKafka(pool,CompletableFuture.completedFuture(null));
            try(var context=context(pool,fixture.provider,kafka)) {
                UUID intent=captured(context);
                UUID event=id(fixture.db.one("SELECT id FROM billing.outbox WHERE aggregate_id=? AND type='PAYMENT_CAPTURED'",intent).get("id"));
                var workers=context.getBean(BillingWorkers.class);
                var claimed=workers.claimOutbox(10);
                assertThat(claimed).extracting(row -> row.get("id")).contains(event.toString());
                long claim=xmin("outbox",event);
                for(var work:claimed) workers.publishOne(work);
                assertThat(kafka.sentWithoutTransaction).isTrue();
                assertThat(text(fixture.db.one("SELECT state FROM billing.outbox WHERE id=?",event),"state")).isEqualTo("PUBLISHED");
                assertThat(xmin("outbox",event)).isNotEqualTo(claim);

                UUID poison=UUID.randomUUID();
                fixture.db.update("INSERT INTO billing.outbox(id,aggregate_id,type,payload) VALUES(?,?,'TEST','{}'::jsonb)",poison,intent);
                var failing=new RecordingKafka(pool,CompletableFuture.failedFuture(new org.apache.kafka.common.errors.RecordTooLargeException("too large")));
                try(var other=context(pool,fixture.provider,failing)) {
                    var publisher=other.getBean(BillingWorkers.class);
                    for(var work:publisher.claimOutbox(10)) publisher.publishOne(work);
                }
                assertThat(text(fixture.db.one("SELECT state FROM billing.outbox WHERE id=?",poison),"state")).isEqualTo("DEAD_LETTER");
                assertThat(fixture.db.rows("SELECT 1 FROM billing.dead_letters WHERE source_outbox_id=? AND failure_reason='RecordTooLargeException'",poison)).hasSize(1);
            }
        }
    }

    // A settlement import is saved, matched, and confirmed against the bank by a different person; a mismatching
    // report is kept and opens a finance case in its own commit after the match rolled back.
    @Test void reconciliationSavesMatchesAndConfirmsThroughCommands() throws Exception {
        try(var pool=BillingAnnotationHarnessDatabaseTest.pool();var context=context(pool,fixture.provider,null)) {
            UUID intent=captured(context);
            var reconciliation=context.getBean(BillingReconciliation.class);
            var saved=reconciliation.receive(FINANCE,fixture.report(intent,"EXT-TX-1",10000,100));
            assertThat(saved).containsEntry("state","MATCHED");
            UUID settlement=id(saved.get("id"));
            var confirmed=reconciliation.confirmBank(APPROVER,settlement,new BillingReconciliation.BankCredit("BANK-EXT-TX-1",9900,"a".repeat(64)));
            assertThat(confirmed).containsEntry("status","MATCHED");
            assertThat(fixture.db.rows("SELECT 1 FROM billing.journals WHERE effect_key=?","settlement:"+settlement)).hasSize(1);

            var wrong=reconciliation.receive(FINANCE,fixture.report(intent,"EXT-TX-2",9999,0));
            assertThat(wrong).containsEntry("state","EXCEPTION");
            assertThat(fixture.db.rows("SELECT 1 FROM billing.cases WHERE reason='SETTLEMENT_REPORT_MISMATCH' AND reference=?",wrong.get("id").toString())).hasSize(1);
            assertThat(fixture.db.rows("SELECT 1 FROM billing.settlement_lines WHERE report_id=?",id(wrong.get("id")))).isEmpty();
        }
    }

    // The retry and the new payment it creates are one commit: the link, the reservation and the timeline entry.
    @Test void aRetryAndTheNewPaymentItCreatesCommitTogether() throws Exception {
        try(var pool=BillingAnnotationHarnessDatabaseTest.pool()) {
            var observed=new Captures(fixture.provider) {
                @Override public ProviderResult submit(ProviderCommand command){super.submitOnly(command);return delegate.simulateResult(command.intentId(),"FAILED");}
            };
            try(var context=context(pool,observed,null)) {
                var service=context.getBean(BillingService.class);
                UUID failed=id(service.create(PAYER,ACCOUNT,UUID.randomUUID().toString(),List.of(INVOICE),"CARD").get("id"));
                assertThat(service.submit(PAYER,failed)).containsEntry("status","FAILED");
                var retry=context.getBean(BillingPaymentRetry.class).retry(PAYER,failed,UUID.randomUUID().toString(),"CARD");
                UUID next=id(retry.get("id"));
                assertThat(retry).containsEntry("paymentReference",failed.toString()).containsEntry("status","CREATED");
                long created=xmin("intents",next);
                long linked=amount(fixture.db.one("SELECT xmin::text::bigint AS transaction_id FROM billing.timeline WHERE intent_id=? AND type='RETRY'",next),"transactionId");
                long reserved=amount(fixture.db.one("SELECT xmin::text::bigint AS transaction_id FROM billing.allocations WHERE intent_id=?",next),"transactionId");
                assertThat(linked).isEqualTo(created);assertThat(reserved).isEqualTo(created);
                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            }
        }
    }

    // The ERP consumer, the dead-letter audit and an operator's replay each commit through a command.
    @Test void erpDeadLetterAndReplayCommitThroughCommands() throws Exception {
        try(var pool=BillingAnnotationHarnessDatabaseTest.pool();var context=context(pool,fixture.provider,null)) {
            UUID intent=captured(context);
            byte[] payload=text(fixture.db.one("SELECT payload::text AS payload FROM billing.outbox WHERE aggregate_id=? AND type='PAYMENT_CAPTURED'",intent),"payload")
                .getBytes(StandardCharsets.UTF_8);
            var events=context.getBean(BillingEvents.class);
            events.applyErp(payload);events.applyErp(payload);
            assertThat(fixture.db.rows("SELECT 1 FROM billing.erp_postings WHERE intent_id=?",intent)).hasSize(1);
            var acknowledged=new AtomicBoolean();
            events.deadLetter(new ConsumerRecord<>("billing.events.v1.DLT",0,7L,intent.toString(),payload),()->acknowledged.set(true));
            assertThat(acknowledged).isTrue();
            UUID deadLetter=id(fixture.db.one("SELECT id FROM billing.dead_letters WHERE offset_id=7").get("id"));
            assertThat(events.replay("operator",deadLetter)).containsEntry("status","REPLAY_QUEUED");
            assertThat(fixture.db.rows("SELECT 1 FROM billing.outbox WHERE aggregate_id=? AND type='DLT_REPLAY'",intent)).hasSize(1);
        }
    }

    UUID captured(AnnotationConfigApplicationContext context) {
        var service=context.getBean(BillingService.class);
        UUID intent=id(service.create(PAYER,ACCOUNT,UUID.randomUUID().toString(),List.of(INVOICE),"CARD").get("id"));
        service.submit(PAYER,intent);
        if(!"CAPTURED".equals(text(fixture.db.one("SELECT status FROM billing.intents WHERE id=?",intent),"status")))
            service.recordResult(intent,null,fixture.provider.simulateResult(intent.toString(),"CAPTURED"));
        assertThat(text(fixture.db.one("SELECT status FROM billing.intents WHERE id=?",intent),"status")).isEqualTo("CAPTURED");
        return intent;
    }
    long xmin(String table,UUID id) {
        return amount(fixture.db.one("SELECT xmin::text::bigint AS transaction_id FROM billing."+table+" WHERE id=?",id),"transactionId");
    }
    static void assertNoTransactionOrConnection(HikariDataSource pool) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    /** Simulator double whose payments are captured on submission, so refunds have a captured payment to refund. */
    static class Captures extends DelegateProvider {
        Captures(SimulatorBillingProvider delegate){super(delegate);}
        ProviderResult submitOnly(ProviderCommand command){return delegate.submit(command);}
        @Override public ProviderResult submit(ProviderCommand command){delegate.submit(command);return delegate.simulateResult(command.intentId(),"CAPTURED");}
    }
    /** One scripted send result; records whether the send happened with no transaction and no borrowed connection. */
    static final class RecordingKafka extends org.springframework.kafka.core.KafkaTemplate<String,byte[]> {
        final HikariDataSource pool;final CompletableFuture<org.springframework.kafka.support.SendResult<String,byte[]>> result;
        final AtomicBoolean sentWithoutTransaction=new AtomicBoolean();
        RecordingKafka(HikariDataSource pool,CompletableFuture<org.springframework.kafka.support.SendResult<String,byte[]>> result) {
            super(new org.springframework.kafka.core.DefaultKafkaProducerFactory<>(Map.of()));this.pool=pool;this.result=result;
        }
        @Override public CompletableFuture<org.springframework.kafka.support.SendResult<String,byte[]>> send(String topic,String key,byte[] data) {
            sentWithoutTransaction.set(!TransactionSynchronizationManager.isActualTransactionActive() && pool.getHikariPoolMXBean().getActiveConnections()==0);
            return result;
        }
    }
    /** Production has no tx method; BillingTransactionPolicyTest enforces its absence. */
    static class NoTemplateStore extends BillingStore {
        NoTemplateStore(JdbcTemplate jdbc,PlatformTransactionManager manager){super(jdbc);}
    }
    static AnnotationConfigApplicationContext context(DataSource source,BillingProvider provider,org.springframework.kafka.core.KafkaTemplate<String,byte[]> kafka) throws Exception {
        var context=new AnnotationConfigApplicationContext();context.getEnvironment().setActiveProfiles("billing");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("extraction",Map.of(
            "billing.simulator-enabled",true,"billing.public-url","http://localhost:4200","billing.provider-merchant-id",MERCHANT.toString())));
        context.registerBean(DataSource.class,()->source,definition->definition.setDestroyMethodName(""));
        context.registerBean(JdbcTemplate.class,()->new JdbcTemplate(source));
        context.registerBean(BillingStore.class,()->new NoTemplateStore(context.getBean(JdbcTemplate.class),context.getBean(PlatformTransactionManager.class)));
        context.registerBean(BillingProvider.class,()->provider);
        if(kafka!=null) context.registerBean("testKafka",RecordingKafka.class,()->(RecordingKafka)kafka);
        context.register(BillingTransactionConfiguration.class);
        var scanner=new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
        scanner.setEnvironment(context.getEnvironment());
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile("com\\.invoicepayments\\.billing\\.Billing.*(Commands|Participants|Access|Locks)$")));
        for(var definition:scanner.findCandidateComponents("com.invoicepayments.billing")) context.register(Class.forName(definition.getBeanClassName()));
        for(Class<?> facade:List.of(BillingService.class,BillingRefundService.class,BillingRecovery.class,BillingReconciliation.class,
                BillingPaymentRetry.class,BillingEvents.class,BillingInbox.class,BillingWorkers.class)) context.registerBean(facade);
        context.refresh();return context;
    }
}
