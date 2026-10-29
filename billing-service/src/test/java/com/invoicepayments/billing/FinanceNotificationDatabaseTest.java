package com.invoicepayments.billing;

import com.invoicepayments.billing.notification.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.invoicepayments.billing.BillingFinancialDatabaseTest.*;
import static com.invoicepayments.billing.BillingStore.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BILLING_TEST_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:15432/invoice_payments_test")
class FinanceNotificationDatabaseTest {
    BillingFinancialDatabaseTest fixture;FinanceNotificationStore store;UUID intent,refund;
    @BeforeAll static void migrate() throws Exception{migrateOnlyIsolatedDatabase();}
    @BeforeEach void seed(TestInfo info){
        System.out.println("Finance notification regression: "+info.getDisplayName());
        fixture=new BillingFinancialDatabaseTest();fixture.cleanAndSeedDedicatedDatabase();store=new FinanceNotificationStore(fixture.db,transactions);
        intent=id(fixture.payments.create(PAYER,ACCOUNT,UUID.randomUUID().toString(),List.of(INVOICE),"CARD").get("id"));
        fixture.payments.submit(PAYER,intent);fixture.payments.simulate(PAYER,intent,"CAPTURED");
        jdbc.update("INSERT INTO billing.notification_routes(merchant_id,recipient,enabled) VALUES(?,'finance@example.test',true)",MERCHANT);
    }
    @AfterEach void close(){BillingTestWiring.closeContexts();}
    UUID request(){refund=id(fixture.refunds.request(FINANCE,intent,UUID.randomUUID().toString(),100,"Fictional return for test").get("id"));return refund;}
    Map<String,Object> row(){return fixture.db.one("SELECT * FROM billing.notification_deliveries ORDER BY created_at LIMIT 1");}
    void expire(){jdbc.update("UPDATE billing.notification_deliveries SET lease_until=NOW()-INTERVAL '1 second',next_at=NOW()-INTERVAL '1 second'");}
    @Test void refundCommitAndIdempotentReplayCreateOneApprovalEmail(){
        String key=UUID.randomUUID().toString();fixture.refunds.request(FINANCE,intent,key,100,"Fictional return for test");fixture.refunds.request(FINANCE,intent,key,100,"Fictional return for test");
        assertThat(fixture.db.rows("SELECT * FROM billing.notification_deliveries")).hasSize(1);assertThat(row()).containsEntry("eventType","REFUND_REQUESTED").containsEntry("state","PENDING");
    }
    @Test void businessRollbackAlsoRollsBackItsEmail(){
        new TransactionTemplate(transactions).executeWithoutResult(s->{request();s.setRollbackOnly();});
        assertThat(fixture.db.rows("SELECT * FROM billing.refunds")).isEmpty();assertThat(store.claim()).isNull();
    }
    @Test void partialRefundDisplayUsesTheRefundAmountRatherThanTheCapturedTotal(){
        request();assertThat(store.claim()).containsEntry("displayAmountMinor",100L).containsEntry("displayCurrency","USD");
    }
    @Test void missingRefundContextCannotFallBackToTheFullPaymentAmount(){
        request();jdbc.update("UPDATE billing.notification_deliveries SET operation_id=?",UUID.randomUUID());
        assertThat(store.claim()).containsEntry("displayAmountMinor",null).containsEntry("displayCurrency",null);
    }
    @Test void permanentlyRejectedTemplateNeedsReviewWithoutRetryOrBlockingAnotherDelivery(){
        request();new FinanceNotificationWorker(store,n->{throw new FinanceMailTransport.RejectedNotification();}).tick();
        var rejected=row();assertThat(rejected).containsEntry("state","REVIEW").containsEntry("lastError","TEMPLATE_REJECTED").containsEntry("attempts",1);
        request();assertThat(store.claim().get("id")).isNotEqualTo(rejected.get("id"));
    }
    @Test void noRouteMeansSuppressionEvidenceAndNoLaterBackfill(){jdbc.update("DELETE FROM billing.notification_routes");request();assertThat(store.claim()).isNull();assertThat(row()).containsEntry("state","SUPPRESSED").containsEntry("lastError","NO_ACTIVE_ROUTE");jdbc.update("INSERT INTO billing.notification_routes(merchant_id,recipient,enabled) VALUES(?,'new@example.test',true)",MERCHANT);assertThat(store.claim()).isNull();}
    @Test void anotherMerchantsRouteCannotReceiveThisRefund(){jdbc.update("DELETE FROM billing.notification_routes");jdbc.update("INSERT INTO billing.notification_routes(merchant_id,recipient,enabled) VALUES(?,'other@example.test',true)",OTHER_MERCHANT);request();assertThat(store.claim()).isNull();assertThat(row()).containsEntry("state","SUPPRESSED");}
    @Test void smtpRunsAfterCommitAndAcceptanceMarksOnlyThisDelivery(){
        request();var sent=new AtomicInteger();new FinanceNotificationWorker(store,n->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(n).containsEntry("state","IN_FLIGHT");sent.incrementAndGet();}).tick();
        new FinanceNotificationWorker(store,n->sent.incrementAndGet()).tick();assertThat(sent).hasValue(1);assertThat(row()).containsEntry("state","SENT");
    }
    @Test void failureKeepsRefundAndRetriesAfterBackoff(){
        request();new FinanceNotificationWorker(store,n->{throw new IllegalStateException("Synthetic SMTP unavailable");}).tick();
        assertThat(row()).containsEntry("state","PENDING").containsEntry("lastError","SMTP_NOT_CONFIRMED").containsEntry("attempts",1);assertThat(store.claim()).isNull();
        assertThat(fixture.refunds.raw(refund)).containsEntry("status","REQUESTED");expire();assertThat(store.claim()).containsEntry("attempts",2);
    }
    @Test void expiredSenderCannotAcknowledgeOrFailNewLease(){
        request();var old=store.claim();expire();var next=store.claim();UUID id=id(next.get("id"));
        store.accepted(id,id(old.get("leaseToken")));store.failed(id,id(old.get("leaseToken")));assertThat(row()).containsEntry("state","IN_FLIGHT").containsEntry("leaseToken",next.get("leaseToken"));store.accepted(id,id(next.get("leaseToken")));assertThat(row()).containsEntry("state","SENT");
    }
    @Test void crashAfterSmtpAcceptanceCanRepeatSameNotificationIdentity(){
        request();var before=store.claim(); // Model acceptance followed by process death before accepted().
        expire();var after=store.claim();assertThat(after.get("id")).isEqualTo(before.get("id"));assertThat(after.get("leaseToken")).isNotEqualTo(before.get("leaseToken"));assertThat(after.get("attempts")).isEqualTo(2);
    }
    @Test void eightUncertainAttemptsRequireVisibleReview(){
        request();for(int n=0;n<8;n++){expire();var work=store.claim();assertThat(work).isNotNull();store.failed(id(work.get("id")),id(work.get("leaseToken")));}
        assertThat(row()).containsEntry("state","REVIEW").containsEntry("attempts",8);assertThat(store.claim()).isNull();
    }
    @Test void lastAttemptCrashAlsoBecomesReview(){request();jdbc.update("UPDATE billing.notification_deliveries SET attempts=7");store.claim();expire();assertThat(store.claim()).isNull();assertThat(row()).containsEntry("state","REVIEW");}
    @Test void disabledOrChangedRouteSuppressesPendingMailInsteadOfRedirecting(){request();jdbc.update("UPDATE billing.notification_routes SET recipient='new@example.test'");assertThat(store.claim()).isNull();assertThat(row()).containsEntry("state","SUPPRESSED").containsEntry("recipient","finance@example.test");}
    @Test void routeRevokedAfterClaimIsCheckedBeforeSmtp(){request();var work=store.claim();jdbc.update("UPDATE billing.notification_routes SET enabled=false");assertThat(store.beforeSend(id(work.get("id")),id(work.get("leaseToken")))).isFalse();assertThat(row()).containsEntry("state","SUPPRESSED");}
    @Test void financeRouteAddressRoundTripCannotResurrectPendingMail(){
        request();jdbc.update("UPDATE billing.notification_routes SET recipient='replacement@example.test'");
        jdbc.update("UPDATE billing.notification_routes SET recipient='finance@example.test'");
        assertThat(store.claim()).isNull();assertThat(row()).containsEntry("state","SUPPRESSED");
    }
    @Test void financeRouteDisableAndEnableCannotResurrectClaimedMail(){
        request();var work=store.claim();jdbc.update("UPDATE billing.notification_routes SET enabled=false");
        jdbc.update("UPDATE billing.notification_routes SET enabled=true");
        assertThat(store.beforeSend(id(work.get("id")),id(work.get("leaseToken")))).isFalse();
        assertThat(row()).containsEntry("state","SUPPRESSED");
    }
    @Test void financeRouteDeletionAndRecreationCannotResurrectClaimedMail(){
        request();var work=store.claim();jdbc.update("DELETE FROM billing.notification_routes WHERE merchant_id=?",MERCHANT);
        jdbc.update("INSERT INTO billing.notification_routes(merchant_id,recipient,enabled) VALUES(?,'finance@example.test',true)",MERCHANT);
        assertThat(store.beforeSend(id(work.get("id")),id(work.get("leaseToken")))).isFalse();
        assertThat(row()).containsEntry("state","SUPPRESSED");
    }
    @Test void repeatedStateUpdateDoesNotEnqueueAnotherVersion(){request();jdbc.update("UPDATE billing.refunds SET status='REQUESTED' WHERE id=?",refund);assertThat(fixture.db.rows("SELECT * FROM billing.notification_deliveries")).hasSize(1);assertThat(row()).containsEntry("eventVersion",1L);}
    @Test void parallelSendersClaimDifferentRows() throws Exception {
        request();new TransactionTemplate(transactions).executeWithoutResult(s->fixture.db.caseFor(intent,"PAYMENT_UNRESOLVED",intent.toString()));
        var pool=Executors.newFixedThreadPool(2);try{Callable<Map<String,Object>> task=()->store.claim();var a=pool.submit(task);var b=pool.submit(task);assertThat(a.get(10,TimeUnit.SECONDS).get("id")).isNotEqualTo(b.get(10,TimeUnit.SECONDS).get("id"));}finally{pool.shutdownNow();}
    }
    @Test void independentApprovalAndSuccessProduceDifferentTruthfulEvents(){request();fixture.refunds.approve(APPROVER,refund);assertThat(fixture.db.rows("SELECT event_type FROM billing.notification_deliveries")).extracting(n->n.get("eventType")).containsExactlyInAnyOrder("REFUND_REQUESTED","REFUND_APPROVED","REFUND_REFUNDED");}
    @Test void caseReleaseRequestAndReviewHaveDurableEmails(){new TransactionTemplate(transactions).executeWithoutResult(s->{fixture.db.caseFor(intent,"PAYMENT_UNRESOLVED",intent.toString());UUID c=id(fixture.db.one("SELECT id FROM billing.cases").get("id"));fixture.db.caseAction(c,"REQUESTED_RELEASE",FINANCE,"Synthetic review note",null);});assertThat(fixture.db.rows("SELECT event_type FROM billing.notification_deliveries")).extracting(n->n.get("eventType")).containsExactlyInAnyOrder("FINANCE_REVIEW","CASE_REQUESTED_RELEASE");}
    @Test void queueVisibilityRequiresSameMerchantStaff(){request();var controller=new FinanceNotificationController(fixture.db);var finance=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(FINANCE,"unused");assertThat(controller.list(finance,MERCHANT)).hasSize(1);assertThat(controller.list(finance,MERCHANT).get(0)).doesNotContainKeys("recipient","reason");assertThatThrownBy(()->controller.list(finance,OTHER_MERCHANT)).isInstanceOf(BillingProblem.class);}
}
