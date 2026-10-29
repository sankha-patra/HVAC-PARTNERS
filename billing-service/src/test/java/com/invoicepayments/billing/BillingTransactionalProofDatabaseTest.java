package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.atomic.*;
import static com.invoicepayments.billing.BillingStore.*;
import static com.invoicepayments.billing.BillingFinancialDatabaseTest.*;
import static org.assertj.core.api.Assertions.*;

/** Production payment commands own their transactions; no outer test template or transaction. */
@EnabledIfEnvironmentVariable(named="BILLING_TEST_JDBC_URL", matches="jdbc:postgresql://127\\.0\\.0\\.1:15432/invoice_payments_test")
class BillingTransactionalProofDatabaseTest {
    BillingFinancialDatabaseTest fixture;
    @BeforeAll static void migrate() throws Exception {migrateOnlyIsolatedDatabase();}
    @BeforeEach void seed(){fixture=new BillingFinancialDatabaseTest();fixture.cleanAndSeedDedicatedDatabase();}
    @AfterEach void cleanup(){
        jdbc.execute("DROP TRIGGER IF EXISTS billing_test_commit_fault ON billing.timeline");
        jdbc.execute("DROP FUNCTION IF EXISTS billing.test_commit_fault()");
        fixture.removeOnlyTestFaultTrigger();
    }
    @Test void productionCommandsHaveEffectiveBillingAttributesAndRealProxies() throws Exception {
        try(var pool=BillingAnnotationHarnessDatabaseTest.pool();var context=context(pool,fixture.provider)) {
            Class<?> type=Class.forName("com.invoicepayments.billing.BillingPaymentCommands");
            assertThat(AopUtils.isAopProxy(context.getBean(type))).isTrue();
            var source=context.getBean(TransactionAttributeSource.class);
            int count=0;
            for(var method:type.getDeclaredMethods()) if(Modifier.isPublic(method.getModifiers())) {
                var attribute=source.getTransactionAttribute(method,type);
                assertThat(attribute).as(method.toString()).isNotNull();
                assertThat(attribute.getPropagationBehavior()).isEqualTo(Propagation.REQUIRED.value());
                assertThat(attribute.getTimeout()).isEqualTo(10);
                assertThat(attribute.getQualifier()).isEqualTo("transactionManager");
                assertThat(attribute.getLabels()).contains("billing");
                assertThat(attribute.rollbackOn(new IOException("checked"))).isTrue();count++;
            }
            assertThat(count).isEqualTo(7);
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        }
    }
    @Test void preparationIsCommittedAndConnectionReleasedBeforeProviderIo() throws Exception {
        var prepared=new AtomicLong();var called=new AtomicBoolean();
        try(var pool=BillingAnnotationHarnessDatabaseTest.pool()) {
            var observed=new DelegateProvider(fixture.provider) {
                @Override public ProviderResult submit(ProviderCommand command) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
                    assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                    var saved=fixture.db.one("SELECT status,xmin::text::bigint AS transaction_id FROM billing.attempts WHERE intent_id=?",UUID.fromString(command.intentId()));
                    assertThat(saved).containsEntry("status","SUBMITTING");prepared.set(amount(saved,"transactionId"));called.set(true);
                    super.submit(command);return delegate.simulateResult(command.intentId(),"CAPTURED");
                }
            };
            try(var context=context(pool,observed)) {
                var service=context.getBean(BillingService.class);
                UUID intent=id(service.create(PAYER,ACCOUNT,UUID.randomUUID().toString(),List.of(INVOICE),"CARD").get("id"));
                assertThat(service.submit(PAYER,intent)).containsEntry("status","CAPTURED");
                assertThat(called).isTrue();
                long recorded=amount(fixture.db.one("SELECT xmin::text::bigint AS transaction_id FROM billing.journals WHERE intent_id=?",intent),"transactionId");
                assertThat(recorded).isNotEqualTo(prepared.get());
                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            }
        }
    }
    @Test void recorderRollbackPreservesExternalEvidenceAndReconciliationPostsOnce() throws Exception {
        var observed=new DelegateProvider(fixture.provider) {
            @Override public ProviderResult submit(ProviderCommand command){super.submit(command);return delegate.simulateResult(command.intentId(),"CAPTURED");}
        };
        try(var pool=BillingAnnotationHarnessDatabaseTest.pool();var context=context(pool,observed)) {
            var service=context.getBean(BillingService.class);
            UUID intent=id(service.create(PAYER,ACCOUNT,UUID.randomUUID().toString(),List.of(INVOICE),"CARD").get("id"));
            fixture.injectTransientFault("CAPTURED");
            assertThatThrownBy(()->service.submit(PAYER,intent)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(fixture.db.one("SELECT status FROM billing.intents WHERE id=?",intent)).containsEntry("status","SUBMITTING");
            assertThat(fixture.db.rows("SELECT id FROM billing.journals WHERE intent_id=?",intent)).isEmpty();
            assertThat(fixture.db.rows("SELECT id FROM billing.outbox WHERE aggregate_id=?",intent)).isEmpty();
            assertThat(fixture.db.one("SELECT state FROM billing.allocations WHERE intent_id=?",intent)).containsEntry("state","RESERVED");
            fixture.removeTransientFault();
            var result=fixture.provider.lookup(BillingService.operationKey(intent),null);
            assertThat(result.state()).isEqualTo("CAPTURED");
            service.recordResult(intent,null,result);service.recordResult(intent,null,result);
            assertThat(fixture.db.rows("SELECT id FROM billing.journals WHERE intent_id=?",intent)).hasSize(1);
            assertThat(fixture.db.rows("SELECT id FROM billing.outbox WHERE aggregate_id=?",intent)).hasSize(1);
        }
    }
    @Test void aCommitFailureAfterPreparationReturnsItsAdmissionPermitWithoutDispatch() throws Exception {
        var closed=new AtomicInteger();var sent=new AtomicInteger();
        var observed=new DelegateProvider(fixture.provider) {
            @Override public ProviderPermit admitNewPayment(){return closed::incrementAndGet;}
            @Override public ProviderResult submit(ProviderCommand command){sent.incrementAndGet();return super.submit(command);}
        };
        try(var pool=BillingAnnotationHarnessDatabaseTest.pool();var context=context(pool,observed)) {
            var service=context.getBean(BillingService.class);
            UUID intent=id(service.create(PAYER,ACCOUNT,UUID.randomUUID().toString(),List.of(INVOICE),"CARD").get("id"));
            jdbc.execute("CREATE FUNCTION billing.test_commit_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.type='SUBMITTING' THEN RAISE EXCEPTION 'deferred preparation fault' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
            jdbc.execute("CREATE CONSTRAINT TRIGGER billing_test_commit_fault AFTER INSERT ON billing.timeline DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION billing.test_commit_fault()");
            assertThatThrownBy(()->service.submit(PAYER,intent)).isInstanceOf(RuntimeException.class);
            assertThat(closed).hasValue(1);assertThat(sent).hasValue(0);
            assertThat(fixture.db.one("SELECT status FROM billing.intents WHERE id=?",intent)).containsEntry("status","CREATED");
            assertThat(fixture.db.rows("SELECT id FROM billing.attempts WHERE intent_id=?",intent)).isEmpty();
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        }
    }
    @Test void sharedFinancialWritesAndLocksRejectCallsWithoutAnActualTransaction() throws Exception {
        try(var pool=BillingAnnotationHarnessDatabaseTest.pool();var context=context(pool,fixture.provider)) {
            var store=context.getBean(BillingStore.class);
            assertThatThrownBy(()->store.event(INVOICE,"NO_TX","must reject"))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
            Object locks=context.getBean(Class.forName("com.invoicepayments.billing.BillingPaymentLocks"));
            assertThatThrownBy(()->locks.getClass().getMethod("locked",UUID.class).invoke(locks,INVOICE))
                .hasCauseInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        }
    }
    static AnnotationConfigApplicationContext context(DataSource source,BillingProvider provider) throws Exception {
        var context=new AnnotationConfigApplicationContext();context.getEnvironment().setActiveProfiles("billing");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("payment-proof",Map.of(
            "billing.simulator-enabled",true,"billing.public-url","http://localhost:4200","billing.provider-merchant-id",MERCHANT.toString())));
        context.registerBean(DataSource.class,()->source,definition->definition.setDestroyMethodName(""));
        context.registerBean(JdbcTemplate.class,()->new JdbcTemplate(source));
        context.registerBean(BillingStore.class,()->new BillingStore(context.getBean(JdbcTemplate.class)));
        context.registerBean(BillingProvider.class,()->provider);
        context.register(BillingTransactionConfiguration.class,BillingService.class);
        for(String name:List.of("BillingPaymentCommands","BillingPaymentAccess","BillingPaymentLocks"))
            context.register(Class.forName("com.invoicepayments.billing."+name));
        context.refresh();return context;
    }
}
