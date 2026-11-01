package com.invoicepayments.billing;

import com.invoicepayments.billing.recovery.*;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;
import static com.invoicepayments.billing.BillingFinancialDatabaseTest.*;
import static org.assertj.core.api.Assertions.*;

/** Actual PostgreSQL admission/fencing/socket checks. Remote AWS responses are deterministic test doubles. */
@EnabledIfEnvironmentVariable(named="BILLING_TEST_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:15432/invoice_payments_test")
class BillingAwsRecoveryDatabaseTest {
    BillingFinancialDatabaseTest fixture;RecoverySettings settings;RecoveryDispatchStore store;FakeRemote remote;
    static final String VERSION="arn:aws:states:ap-southeast-2:123456789012:stateMachine:recovery-fixture:7";
    @BeforeAll static void migrate() throws Exception{migrateOnlyIsolatedDatabase();}
    @BeforeEach void seed(){
        fixture=new BillingFinancialDatabaseTest();fixture.cleanAndSeedDedicatedDatabase();
        jdbc.execute("TRUNCATE billing.recovery_dispatch_audit,billing.recovery_dispatch,billing.recovery_campaign_operations,billing.recovery_campaign CASCADE");
        settings=new RecoverySettings(UUID.randomUUID(),VERSION,8,16,100,1,Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        store=new RecoveryDispatchStore(fixture.db,transactions,settings);store.initialize();remote=new FakeRemote();
    }
    @AfterEach void close(){BillingTestWiring.closeContexts();}
    UUID due(){UUID id=id(fixture.payments.create(PAYER,ACCOUNT,UUID.randomUUID().toString(),List.of(INVOICE),"CARD").get("id"));
        fixture.payments.submit(PAYER,id);jdbc.update("UPDATE billing.intents SET next_recovery_at=NOW()-INTERVAL '1 second',lease_until=NULL WHERE id=?",id);store.enroll("PAYMENT",id);return id;}
    void next(){jdbc.update("UPDATE billing.recovery_dispatch SET next_at=NOW()-INTERVAL '1 second'");}
    Map<String,Object> dispatch(){return fixture.db.one("SELECT * FROM billing.recovery_dispatch");}
    @Test void lostStartAcknowledgementResumesTheSameExecutionAfterDispatcherRestart(){
        UUID payment=due();remote.loseAck=true;new RecoveryDispatcher(store,remote).tick();
        String name=text(dispatch(),"executionName"),input=text(dispatch(),"inputText");
        assertThat(remote.starts).isEqualTo(1);assertThat(dispatch()).containsEntry("state","PENDING");
        var restarted=new RecoveryDispatchStore(fixture.db,transactions,settings);restarted.initialize();next();new RecoveryDispatcher(restarted,remote).tick();
        assertThat(remote.starts).isEqualTo(1);assertThat(dispatch()).containsEntry("state","RUNNING").containsEntry("executionName",name).containsEntry("inputText",input);
        assertThat(fixture.db.one("SELECT starts,submissions,describes FROM billing.recovery_campaign")).containsEntry("starts",1).containsEntry("submissions",1).containsEntry("describes",2);
        assertThat(fixture.db.one("SELECT status FROM billing.intents WHERE id=?",payment)).containsEntry("status","PROCESSING");
    }
    @Test void failedWorkflowGetsAReviewOwnerWithoutDeclaringPaymentFailed(){
        UUID payment=due();var dispatcher=new RecoveryDispatcher(store,remote);dispatcher.tick();next();remote.status="FAILED";dispatcher.tick();
        assertThat(dispatch()).containsEntry("state","REVIEW").containsEntry("owner","TERMINAL_REVIEW").containsEntry("remoteStatus","FAILED");
        assertThat(fixture.db.one("SELECT status FROM billing.intents WHERE id=?",payment)).containsEntry("status","PROCESSING");
        assertThat(fixture.db.rows("SELECT * FROM billing.journals")).isEmpty();
        assertThat(fixture.db.rows("SELECT id FROM billing.cases WHERE intent_id=? AND reason='PAYMENT_UNRESOLVED'",payment)).hasSize(1);
    }
    @Test void successfulWorkflowStillNeedsMoneyEvidence(){
        due();var dispatcher=new RecoveryDispatcher(store,remote);dispatcher.tick();next();remote.status="SUCCEEDED";dispatcher.tick();
        assertThat(dispatch()).containsEntry("state","REVIEW").containsEntry("owner","TERMINAL_REVIEW");
    }
    @Test void capturedPaymentAndTerminalWorkflowCloseTheDispatchExactlyOnce(){
        UUID payment=due();var dispatcher=new RecoveryDispatcher(store,remote);dispatcher.tick();
        fixture.payments.recordResult(payment,null,fixture.provider.simulateResult(payment.toString(),"CAPTURED"));
        remote.status="SUCCEEDED";next();dispatcher.tick();
        assertThat(dispatch()).containsEntry("state","DONE").containsEntry("owner","FINANCIAL_DONE");
        assertThat(dispatcher.tick()).isFalse();assertThat(remote.starts).isEqualTo(1);
        assertThat(fixture.db.rows("SELECT * FROM billing.journals WHERE intent_id=?",payment)).hasSize(1);
    }
    @Test void offlineRemoteCallIsDurableAndReconnectionUsesTheOriginalIdentity(){
        due();remote.offline=true;var dispatcher=new RecoveryDispatcher(store,remote);dispatcher.tick();
        String name=text(dispatch(),"executionName");assertThat(remote.starts).isZero();assertThat(dispatch()).containsEntry("lastError","NETWORK_UNAVAILABLE");
        remote.offline=false;next();dispatcher.tick();assertThat(remote.starts).isEqualTo(1);assertThat(dispatch()).containsEntry("executionName",name);
    }
    @Test void restartingCannotIncreaseAllocationOrResetCounters(){
        due();new RecoveryDispatcher(store,remote).tick();
        var different=new RecoverySettings(settings.campaign(),VERSION,9,16,100,1,settings.deadline());
        assertThatThrownBy(()->new RecoveryDispatchStore(fixture.db,transactions,different).initialize()).isInstanceOf(IllegalStateException.class);
        assertThat(fixture.db.one("SELECT starts,submissions FROM billing.recovery_campaign")).containsEntry("starts",1).containsEntry("submissions",1);
    }
    @Test void mismatchedRemoteVersionNeverDeclaresRecoveryComplete(){
        due();var dispatcher=new RecoveryDispatcher(store,remote);dispatcher.tick();remote.wrongVersion=true;next();dispatcher.tick();
        assertThat(dispatch()).containsEntry("state","REVIEW").containsEntry("lastError","REMOTE_IDENTITY_MISMATCH");
    }
    @Test void preInvocationGateReplacesARealTerminatedPooledSocketWithoutLeakingConnections() throws Exception{
        try(var pool=pool()) {
            int before;try(var connection=pool.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT pg_backend_pid()")){rows.next();before=rows.getInt(1);}
            assertThat(jdbc.queryForObject("SELECT pg_terminate_backend(?)",Boolean.class,before)).isTrue();
            RecoveryConnectionGate.beforeInvocation(pool);
            int after;try(var connection=pool.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT pg_backend_pid()")){rows.next();after=rows.getInt(1);}
            assertThat(after).isNotEqualTo(before);assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            for(int i=0;i<5;i++)RecoveryConnectionGate.beforeInvocation(pool);
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        }
    }
    @Test void gateRefusesToEvictAConnectionBorrowedByUnfinishedWork() throws Exception{
        try(var pool=pool();var connection=pool.getConnection()) {
            assertThatThrownBy(()->RecoveryConnectionGate.beforeInvocation(pool)).isInstanceOf(IllegalStateException.class).hasMessageContaining("borrowed");
            assertThat(connection.isValid(2)).isTrue();
        }
    }
    @Test void namedTaskClaimsOnlyItsSavedOperationAndRespectsTheNextCheckLease(){
        UUID payment=due();var task=new RecoveryTask(fixture.db,fixture.recovery,transactions);
        assertThat(task.run("PAYMENT",payment)).containsEntry("ran",true).containsEntry("recoverable",true);
        assertThat(task.run("PAYMENT",payment)).containsEntry("ran",false);
        assertThat(fixture.db.one("SELECT recovery_count,lease_token FROM billing.intents WHERE id=?",payment)).containsEntry("recoveryCount",1).containsEntry("leaseToken",null);
    }
    @Test void aTerminalMoneyResultIsObservedWithoutClaimingItAgain(){
        UUID payment=due();fixture.payments.recordResult(payment,null,fixture.provider.simulateResult(payment.toString(),"CAPTURED"));
        assertThat(new RecoveryTask(fixture.db,fixture.recovery,transactions).run("PAYMENT",payment)).containsEntry("ran",false).containsEntry("recoverable",false).containsEntry("status","CAPTURED");
        assertThat(fixture.db.one("SELECT recovery_count FROM billing.intents WHERE id=?",payment)).containsEntry("recoveryCount",0);
    }
    @Test void unrelatedDueApplicationDataIsExcludedUntilExplicitlyEnrolled(){
        UUID payment=id(fixture.payments.create(PAYER,ACCOUNT,UUID.randomUUID().toString(),List.of(INVOICE),"CARD").get("id"));fixture.payments.submit(PAYER,payment);
        jdbc.update("UPDATE billing.intents SET next_recovery_at=NOW()-INTERVAL '1 second' WHERE id=?",payment);
        assertThat(new RecoveryDispatcher(store,remote).tick()).isFalse();assertThat(remote.starts).isZero();
        store.enroll("PAYMENT",payment);assertThat(new RecoveryDispatcher(store,remote).tick()).isTrue();assertThat(remote.starts).isEqualTo(1);
    }
    static HikariDataSource pool(){var c=new HikariConfig();c.setJdbcUrl(System.getenv("BILLING_TEST_JDBC_URL"));c.setUsername(System.getenv("BILLING_TEST_DB_USER"));c.setPassword(System.getenv("BILLING_TEST_DB_PASSWORD"));c.setMaximumPoolSize(1);c.setMinimumIdle(0);c.setConnectionTimeout(3000);c.setValidationTimeout(2000);c.addDataSourceProperty("socketTimeout","5");return new HikariDataSource(c);}
    @Test void lambdaCampaignScopeRejectsUnenrolledOrWrongMerchantOperationsBeforeClaim(){
        UUID payment=id(fixture.payments.create(PAYER,ACCOUNT,UUID.randomUUID().toString(),List.of(INVOICE),"CARD").get("id"));
        fixture.payments.submit(PAYER,payment);jdbc.update("UPDATE billing.intents SET next_recovery_at=NOW()-INTERVAL '1 second' WHERE id=?",payment);
        var scoped=new RecoveryTask(fixture.db,fixture.recovery,transactions,settings.campaign(),MERCHANT,"SIMULATOR");
        assertThatThrownBy(()->scoped.run("PAYMENT",payment)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("enrolled");
        store.enroll("PAYMENT",payment);
        var wrong=new RecoveryTask(fixture.db,fixture.recovery,transactions,settings.campaign(),OTHER_MERCHANT,"SIMULATOR");
        assertThatThrownBy(()->wrong.run("PAYMENT",payment)).isInstanceOf(IllegalArgumentException.class);
        assertThat(fixture.db.one("SELECT recovery_count FROM billing.intents WHERE id=?",payment)).containsEntry("recoveryCount",0);
        assertThat(scoped.run("PAYMENT",payment)).containsEntry("ran",true);
    }
    @Test void lambdaCampaignDeadlineRefusesNewClaimsWithoutChangingMoneyState(){
        UUID payment=due();jdbc.update("UPDATE billing.recovery_campaign SET deadline=NOW()-INTERVAL '1 second' WHERE id=?",settings.campaign());
        var scoped=new RecoveryTask(fixture.db,fixture.recovery,transactions,settings.campaign(),MERCHANT,"SIMULATOR");
        assertThatThrownBy(()->scoped.run("PAYMENT",payment)).isInstanceOf(IllegalArgumentException.class);
        assertThat(fixture.db.one("SELECT status,recovery_count FROM billing.intents WHERE id=?",payment)).containsEntry("status","PROCESSING").containsEntry("recoveryCount",0);
    }
    RecoveryDispatchStore auto(int maxAttempts,int maxInFlight){
        var autoSettings=new RecoverySettings(UUID.randomUUID(),VERSION,8,16,100,maxInFlight,settings.deadline(),true,"SIMULATOR",MERCHANT,maxAttempts);
        settings=autoSettings;var s=new RecoveryDispatchStore(fixture.db,transactions,autoSettings);s.initialize();return s;
    }
    UUID dueUnenrolled(){return dueUnenrolled(INVOICE);}
    UUID dueUnenrolled(UUID invoice){UUID id=id(fixture.payments.create(PAYER,ACCOUNT,UUID.randomUUID().toString(),List.of(invoice),"CARD").get("id"));
        fixture.payments.submit(PAYER,id);jdbc.update("UPDATE billing.intents SET next_recovery_at=NOW()-INTERVAL '1 second',lease_until=NULL WHERE id=?",id);return id;}
    List<Map<String,Object>> dispatches(){return fixture.db.rows("SELECT * FROM billing.recovery_dispatch ORDER BY attempt");}
    @Test void automaticEnrollmentRecoversOrdinaryTrafficOfTheScopedMerchantAndProvider(){
        var store=auto(1,1);UUID payment=dueUnenrolled();
        assertThat(new RecoveryDispatcher(store,remote).tick()).isTrue();assertThat(remote.starts).isEqualTo(1);
        assertThat(fixture.db.rows("SELECT 1 FROM billing.recovery_campaign_operations WHERE operation_id=?",payment)).hasSize(1);
        var otherProvider=new RecoverySettings(UUID.randomUUID(),VERSION,8,16,100,1,settings.deadline(),true,"STRIPE",MERCHANT,1);
        var stripeStore=new RecoveryDispatchStore(fixture.db,transactions,otherProvider);stripeStore.initialize();
        dueUnenrolled(INVOICE_TWO);assertThat(stripeStore.discover(20)).isZero();
    }
    @Test void aFailedWorkflowGetsALaterAttemptWithItsOwnExecutionAndNoFinanceCase(){
        var store=auto(3,1);UUID payment=dueUnenrolled();var dispatcher=new RecoveryDispatcher(store,remote);
        dispatcher.tick();next();remote.status="FAILED";remote.error="com.invoicepayments.billing.recovery.RecoveryTunnelUnavailableException";dispatcher.tick();
        var rows=dispatches();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("state","SUPERSEDED").containsEntry("remoteError","com.invoicepayments.billing.recovery.RecoveryTunnelUnavailableException");
        assertThat(rows.get(1)).containsEntry("state","PENDING").containsEntry("attempt",2);
        assertThat(rows.get(1).get("executionName")).isNotEqualTo(rows.get(0).get("executionName"));
        assertThat(fixture.db.rows("SELECT 1 FROM billing.cases WHERE intent_id=?",payment)).isEmpty();
        assertThat(fixture.db.one("SELECT next_at>NOW()+INTERVAL '4 minutes' AS later FROM billing.recovery_dispatch WHERE attempt=2")).containsEntry("later",true);
        remote.status="RUNNING";next();dispatcher.tick();
        assertThat(remote.starts).isEqualTo(2);assertThat(fixture.db.one("SELECT starts FROM billing.recovery_campaign WHERE id=?",settings.campaign())).containsEntry("starts",2);
    }
    @Test void aFailureNoLaterWorkflowCanFixGoesStraightToFinance(){
        var store=auto(3,1);UUID payment=dueUnenrolled();var dispatcher=new RecoveryDispatcher(store,remote);
        dispatcher.tick();next();remote.status="FAILED";remote.error="InvalidLabInput";dispatcher.tick();
        assertThat(dispatches()).singleElement().satisfies(row->assertThat(row).containsEntry("state","REVIEW"));
        assertThat(fixture.db.rows("SELECT 1 FROM billing.cases WHERE intent_id=? AND reason='PAYMENT_UNRESOLVED'",payment)).hasSize(1);
    }
    @Test void aCheckoutStillOpenAfterTheLastAttemptClosesWithoutAFinanceCase(){
        var store=auto(1,1);UUID payment=dueUnenrolled();var dispatcher=new RecoveryDispatcher(store,remote);
        dispatcher.tick();jdbc.update("UPDATE billing.intents SET status='REQUIRES_ACTION' WHERE id=?",payment);
        next();remote.status="FAILED";remote.error="LabLoopBudgetExhausted";dispatcher.tick();
        assertThat(dispatches()).singleElement().satisfies(row->assertThat(row).containsEntry("state","DONE").containsEntry("owner","CHECKOUT_NOT_COMPLETED"));
        assertThat(fixture.db.rows("SELECT 1 FROM billing.cases WHERE intent_id=?",payment)).isEmpty();
    }
    @Test void theLastAttemptGoesToFinanceAndAReviewNeverBlocksNewStarts(){
        var store=auto(1,1);UUID first=dueUnenrolled();var dispatcher=new RecoveryDispatcher(store,remote);
        dispatcher.tick();next();remote.status="FAILED";dispatcher.tick();
        assertThat(fixture.db.one("SELECT state FROM billing.recovery_dispatch WHERE operation_id=?",first)).containsEntry("state","REVIEW");
        assertThat(fixture.db.rows("SELECT 1 FROM billing.cases WHERE intent_id=? AND reason='PAYMENT_UNRESOLVED'",first)).hasSize(1);
        remote.status="RUNNING";UUID second=dueUnenrolled(INVOICE_TWO);dispatcher.tick();
        assertThat(remote.starts).isEqualTo(2);
        assertThat(fixture.db.one("SELECT state FROM billing.recovery_dispatch WHERE operation_id=?",second)).containsEntry("state","PENDING");
    }
    @Test void aReviewWhoseMoneyIsLaterSettledNeedsNobody(){
        UUID payment=due();var dispatcher=new RecoveryDispatcher(store,remote);dispatcher.tick();next();remote.status="FAILED";dispatcher.tick();
        assertThat(dispatch()).containsEntry("state","REVIEW");
        fixture.payments.recordResult(payment,null,fixture.provider.simulateResult(payment.toString(),"CAPTURED"));
        dispatcher.tick();
        assertThat(dispatch()).containsEntry("state","DONE").containsEntry("owner","SETTLED_AFTER_REVIEW");
    }
    @Test void anAllocationLimitOnAnAlreadyPaidPaymentOpensNoFinanceCase(){
        UUID payment=due();var dispatcher=new RecoveryDispatcher(store,remote);dispatcher.tick();
        fixture.payments.recordResult(payment,null,fixture.provider.simulateResult(payment.toString(),"CAPTURED"));
        jdbc.update("UPDATE billing.recovery_campaign SET describes=max_describes");next();dispatcher.tick();
        assertThat(dispatch()).containsEntry("state","DONE");
        assertThat(fixture.db.rows("SELECT 1 FROM billing.cases WHERE intent_id=? AND reason='PAYMENT_UNRESOLVED'",payment)).isEmpty();
    }
    @Test void repeatedAwsFailuresAreAlertedAndFailedDescribesDoNotSpendTheBudget(){
        var lines=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();lines.start();
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("operational-alerts");logger.addAppender(lines);
        try {
            due();remote.offline=true;var dispatcher=new RecoveryDispatcher(store,remote);
            for(int i=0;i<3;i++){dispatcher.tick();next();}
            assertThat(fixture.db.one("SELECT describes,submissions FROM billing.recovery_campaign")).containsEntry("describes",0).containsEntry("submissions",0);
            assertThat(lines.list).extracting(e->e.getFormattedMessage()).filteredOn(m->m.startsWith("ALERT code=RECOVERY_DISPATCH_FAILING"))
                .singleElement().asString().contains("errorCode=NETWORK_UNAVAILABLE","consecutiveFailures=3");
            remote.offline=false;dispatcher.tick();
            assertThat(lines.list).extracting(e->e.getFormattedMessage()).contains("ALERT_RESOLVED code=RECOVERY_DISPATCH_FAILING");
        } finally {logger.detachAppender(lines);}
    }
    @Test void truncatingMoneyTablesIsRefusedUnlessMaintenanceIsDeclared(){
        due();
        assertThatThrownBy(()->jdbc.execute("TRUNCATE billing.attempts CASCADE")).hasMessageContaining("cannot be truncated");
        assertThatThrownBy(()->jdbc.execute("TRUNCATE commerce.orders CASCADE")).hasMessageContaining("cannot be truncated");
        assertThat(fixture.db.rows("SELECT 1 FROM billing.attempts")).isNotEmpty();
    }
    final class FakeRemote implements RecoveryRemote {
        int starts;boolean loseAck,offline,wrongVersion;String status="RUNNING",error;Map<String,Execution> executions=new HashMap<>();
        void outside(){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();if(offline)throw new Failure("NETWORK_UNAVAILABLE");}
        public Execution describe(String arn){outside();var e=executions.get(arn);if(e==null)throw new Failure("ExecutionDoesNotExist");return new Execution(arn,wrongVersion?VERSION.replace(":7",":8"):VERSION,e.input(),status,"RUNNING".equals(status)?null:error);}
        public String start(String version,String name,String input){outside();starts++;String arn=settings.executionArn(name);executions.putIfAbsent(arn,new Execution(arn,version,input,"RUNNING"));if(loseAck){loseAck=false;throw new Failure("LOST_START_ACK");}return arn;}
    }
}
