package com.invoicepayments.billing;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.*;
import org.springframework.aop.support.AopUtils;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static com.invoicepayments.billing.BillingFinancialDatabaseTest.*;
import static com.invoicepayments.billing.BillingStore.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@EnabledIfEnvironmentVariable(named="BILLING_TEST_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:15432/invoice_payments_test")
class BillingCdcOutboxDatabaseTest {
    BillingFinancialDatabaseTest fixture;AnnotationConfigApplicationContext context;BillingCdcCommands commands;
    @BeforeAll static void migrate() throws Exception {migrateOnlyIsolatedDatabase();}
    @BeforeEach void setup() {
        fixture=new BillingFinancialDatabaseTest();fixture.cleanAndSeedDedicatedDatabase();
        jdbc.update("UPDATE billing.outbox_relay_control SET mode='POLL',generation=1 WHERE id");
        context=BillingTestWiring.context(fixture.db,fixture.provider,new MockEnvironment(),true,"http://localhost:4200",MERCHANT.toString());
        context.registerBean(RelayIsolationProbe.class);
        context.refresh();commands=context.getBean(BillingCdcCommands.class);
    }
    @AfterEach void cleanup(){if(context!=null)context.close();if(jdbc!=null)jdbc.update("UPDATE billing.outbox_relay_control SET mode='POLL',generation=1 WHERE id");if(fixture!=null)fixture.removeOnlyTestFaultTrigger();}
    UUID event() {
        var created=fixture.payments.create(PAYER,ACCOUNT,UUID.randomUUID().toString(),List.of(INVOICE),"CARD");
        UUID intent=id(created.get("id"));fixture.payments.submit(PAYER,intent);fixture.payments.simulate(PAYER,intent,"CAPTURED");
        return id(fixture.db.one("SELECT id FROM billing.outbox WHERE aggregate_id=?",intent).get("id"));
    }
    @Test void pollEventsRemainOnTheExistingRelay() {
        event();assertThat(fixture.db.one("SELECT COUNT(*) AS n FROM billing.outbox_events").get("n")).isEqualTo(0L);
        assertThat(fixture.db.one("SELECT state FROM billing.outbox").get("state")).isEqualTo("NEW");
    }
    @Test void cdcCopiesTheCanonicalEventInTheFinancialCommitAndOldPollerCannotClaimIt() {
        commands.switchRelay("POLL","CDC",1);UUID id=event();
        var row=fixture.db.one("SELECT * FROM billing.outbox WHERE id=?",id);
        assertThat(row.get("state")).isEqualTo("CDC_PENDING");
        var stream=fixture.db.one("SELECT * FROM billing.outbox_events WHERE id=?",id);
        assertThat(stream.get("aggregateid").toString()).isEqualTo(row.get("aggregateId").toString());
        assertThat(stream.get("payload").toString()).isEqualTo(row.get("payload").toString());
        assertThat(context.getBean(BillingOutboxCommands.class).claim(10)).isEmpty();
        assertThatThrownBy(()->fixture.db.update("UPDATE billing.outbox_events SET type='changed' WHERE id=?",id)).isInstanceOf(RuntimeException.class);
    }
    @Test void relaySwitchRefusesUndrainedWorkAndStaleGeneration() {
        UUID id=event();
        assertThatThrownBy(()->commands.switchRelay("POLL","CDC",1)).isInstanceOf(BillingProblem.class)
            .satisfies(e->assertThat(((BillingProblem)e).code()).isEqualTo("RELAY_NOT_DRAINED"));
        fixture.db.update("UPDATE billing.outbox SET state='PUBLISHED' WHERE id=?",id);
        assertThat(commands.switchRelay("POLL","CDC",1)).containsEntry("mode","CDC");
        assertThatThrownBy(()->commands.switchRelay("POLL","CDC",1)).isInstanceOf(BillingProblem.class);
    }
    @Test void kafkaReceiptMustMatchAndRepeatedReceiptDoesNotCreateAnEffect() {
        commands.switchRelay("POLL","CDC",1);UUID id=event();
        var row=fixture.db.one("SELECT aggregate_id,payload::text AS payload FROM billing.outbox WHERE id=?",id);
        byte[] bytes=text(row,"payload").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(()->commands.recordDelivery(id,UUID.randomUUID().toString(),bytes)).isInstanceOf(IllegalArgumentException.class);
        assertThat(fixture.db.one("SELECT state FROM billing.outbox WHERE id=?",id).get("state")).isEqualTo("CDC_PENDING");
        commands.recordDelivery(id,text(row,"aggregateId"),bytes);commands.recordDelivery(id,text(row,"aggregateId"),bytes);
        assertThat(fixture.db.one("SELECT state FROM billing.outbox WHERE id=?",id).get("state")).isEqualTo("PUBLISHED");
        assertThat(fixture.db.one("SELECT COUNT(*) AS n FROM billing.journals WHERE effect_key LIKE 'capture:%'").get("n")).isEqualTo(1L);
    }
    @Test void rollingBackFinancialWorkCannotLeaveACdcEvent() throws Exception {
        commands.switchRelay("POLL","CDC",1);
        try(var connection=jdbc.getDataSource().getConnection()){
            connection.setAutoCommit(false);
            try(var statement=connection.createStatement()){
                statement.execute("INSERT INTO billing.outbox(id,aggregate_id,type,payload) VALUES('00000000-0000-0000-0000-000000000091','00000000-0000-0000-0000-000000000092','TEST','{}')");
            }
            connection.rollback();
        }
        assertThat(fixture.db.one("SELECT COUNT(*) AS n FROM billing.outbox_events").get("n")).isEqualTo(0L);
    }
    @Test void retentionCannotEraseAnUndeliveredEventOrItsCanonicalRecord(){
        commands.switchRelay("POLL","CDC",1);UUID id=event();
        assertThatThrownBy(()->fixture.db.update("DELETE FROM billing.outbox_events WHERE id=?",id)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->fixture.db.update("DELETE FROM billing.outbox WHERE id=?",id)).isInstanceOf(RuntimeException.class);
        assertThat(fixture.db.rows("SELECT id FROM billing.outbox_events WHERE id=?",id)).hasSize(1);
    }
    @Test void unresolvedPublisherQuarantineAlsoBlocksRelayHandover(){
        UUID id=event();fixture.db.update("UPDATE billing.outbox SET state='DEAD_LETTER' WHERE id=?",id);
        assertThatThrownBy(()->commands.switchRelay("POLL","CDC",1)).isInstanceOf(BillingProblem.class)
            .satisfies(e->assertThat(((BillingProblem)e).code()).isEqualTo("RELAY_NOT_DRAINED"));
    }
    @Test void relaySwitchRefusesAJoinedRepeatableReadTransactionBeforeChangingMode(){
        var caller=context.getBean(RelayIsolationProbe.class);
        assertThat(AopUtils.isAopProxy(caller)).isTrue();
        assertThat(AopUtils.isAopProxy(commands)).isTrue();
        assertThatThrownBy(caller::repeatableReadSwitch).isInstanceOf(IllegalTransactionStateException.class)
            .hasMessageContaining("READ COMMITTED");
        assertThat(fixture.db.one("SELECT mode,generation FROM billing.outbox_relay_control WHERE id"))
            .containsEntry("mode","POLL").containsEntry("generation",1L);
    }
    @Test void relaySwitchRefusesAJoinedSerializableTransactionBeforeChangingMode(){
        var caller=context.getBean(RelayIsolationProbe.class);
        assertThatThrownBy(caller::serializableSwitch).isInstanceOf(IllegalTransactionStateException.class)
            .hasMessageContaining("READ COMMITTED");
        assertThat(fixture.db.one("SELECT mode,generation FROM billing.outbox_relay_control WHERE id"))
            .containsEntry("mode","POLL").containsEntry("generation",1L);
    }
    @Test void readCommittedSwitchWaitsForAnEventWriterThenSeesItsCommittedBacklog() throws Exception {
        UUID outboxId=UUID.randomUUID();
        var executor=Executors.newSingleThreadExecutor();
        try(var writer=jdbc.getDataSource().getConnection()){
            writer.setAutoCommit(false);
            int writerPid;
            try(var statement=writer.createStatement();var result=statement.executeQuery("SELECT pg_backend_pid()")){
                result.next();writerPid=result.getInt(1);
            }
            // The real V23 insert trigger takes FOR SHARE on the relay-control row.
            try(var statement=writer.prepareStatement("INSERT INTO billing.outbox(id,aggregate_id,type,payload) VALUES(?,?,'TEST','{}')")){
                statement.setObject(1,outboxId);statement.setObject(2,UUID.randomUUID());statement.executeUpdate();
            }
            var switching=executor.submit(()->{
                try {commands.switchRelay("POLL","CDC",1);return "SWITCHED";}
                catch(BillingProblem failure){return failure.code();}
            });
            try {
                // Observe PostgreSQL's actual blocked lock acquisition, not a timing sleep.
                await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(20)).until(()->
                    Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM pg_stat_activity a
                        WHERE ?=ANY(pg_blocking_pids(a.pid))
                        AND a.query LIKE 'SELECT * FROM billing.outbox_relay_control WHERE id FOR UPDATE%')
                        """,Boolean.class,writerPid)));
                assertThat(switching.isDone()).isFalse();
                writer.commit();
                assertThat(switching.get(5,TimeUnit.SECONDS)).isEqualTo("RELAY_NOT_DRAINED");
            } finally {writer.rollback();}
        } finally {
            executor.shutdownNow();assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(fixture.db.one("SELECT mode,generation FROM billing.outbox_relay_control WHERE id"))
            .containsEntry("mode","POLL").containsEntry("generation",1L);
        assertThat(fixture.db.one("SELECT state FROM billing.outbox WHERE id=?",outboxId)).containsEntry("state","NEW");
        assertThat(fixture.db.rows("SELECT id FROM billing.outbox_events WHERE id=?",outboxId)).isEmpty();
    }

    /** Real outer annotation proxies prove that REQUIRED cannot replace a caller's isolation. */
    public static class RelayIsolationProbe {
        private final BillingStore db;private final BillingCdcCommands commands;
        RelayIsolationProbe(BillingStore db,BillingCdcCommands commands){this.db=db;this.commands=commands;}
        @Transactional(transactionManager="transactionManager",propagation=Propagation.REQUIRED,rollbackFor=Exception.class,timeout=10,label="billing",isolation=Isolation.REPEATABLE_READ)
        public void repeatableReadSwitch(){
            assertThat(db.one("SHOW transaction_isolation")).containsEntry("transactionIsolation","repeatable read");
            db.one("SELECT COUNT(*) AS n FROM billing.outbox"); // Establish the caller's snapshot.
            commands.switchRelay("POLL","CDC",1);
        }
        @Transactional(transactionManager="transactionManager",propagation=Propagation.REQUIRED,rollbackFor=Exception.class,timeout=10,label="billing",isolation=Isolation.SERIALIZABLE)
        public void serializableSwitch(){
            assertThat(db.one("SHOW transaction_isolation")).containsEntry("transactionIsolation","serializable");
            db.one("SELECT COUNT(*) AS n FROM billing.outbox");
            commands.switchRelay("POLL","CDC",1);
        }
    }
}
