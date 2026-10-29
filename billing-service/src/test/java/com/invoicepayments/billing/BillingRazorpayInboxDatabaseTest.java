package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import static com.invoicepayments.billing.BillingFinancialDatabaseTest.*;
import static com.invoicepayments.billing.BillingStore.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual PostgreSQL inbox, commands and ledger; provider calls are controlled fixture results. */
@EnabledIfEnvironmentVariable(named="BILLING_TEST_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:15432/invoice_payments_test")
class BillingRazorpayInboxDatabaseTest {
    BillingFinancialDatabaseTest fixture;BillingProvider provider;BillingService payments;BillingInbox inbox;BillingRefundService refunds;
    org.springframework.context.annotation.AnnotationConfigApplicationContext inboxContext;
    @BeforeAll static void migrate()throws Exception{migrateOnlyIsolatedDatabase();}
    @BeforeEach void seed(){
        fixture=new BillingFinancialDatabaseTest();fixture.cleanAndSeedDedicatedDatabase();fixture.db.update("UPDATE billing.invoices SET currency='INR' WHERE id=?",INVOICE);
        provider=mock(BillingProvider.class);when(provider.name()).thenReturn("RAZORPAY");when(provider.paymentRequestVersion()).thenReturn("razorpay-fixture-v1");
        when(provider.refundRequestVersion()).thenReturn("razorpay-refund-fixture-v1");when(provider.admitNewPayment()).thenReturn(ProviderPermit.NONE);
        when(provider.submitSaved(any(),any(),any())).thenReturn(new ProviderResult("order_original","REQUIRES_ACTION",null,"Fixture order saved"));
        payments=BillingTestWiring.payments(fixture.db,provider,new MockEnvironment(),false,"http://localhost:4200",MERCHANT.toString());
        refunds=BillingTestWiring.refunds(fixture.db,payments,provider);
        inboxContext=BillingTestWiring.context(fixture.db,provider,new MockEnvironment(),false,"http://localhost:4200",MERCHANT.toString());
        inboxContext.registerBean(BillingService.class,()->payments);inboxContext.registerBean(BillingRefundService.class,()->refunds);
        inboxContext.registerBean(BillingInbox.class);inboxContext.refresh();inbox=inboxContext.getBean(BillingInbox.class);
    }
    @AfterEach void close(){if(inboxContext!=null)inboxContext.close();fixture.removeOnlyTestFaultTrigger();}
    UUID create(){return id(payments.create(PAYER,ACCOUNT,UUID.randomUUID().toString(),List.of(INVOICE),"CARD").get("id"));}
    ProviderWebhookEvent event(String key,String state,long minor){return new ProviderWebhookEvent(key,"order_original",null,state,minor,"INR",null,null,"pay_original");}
    @Test void webhookBeforeLocalOrderMappingIsRetainedAndLaterPostsExactlyOnce(){
        UUID intent=create();inbox.receive("RAZORPAY",event("early_capture","CAPTURED",10000));inbox.process("RAZORPAY","early_capture");
        assertThat(fixture.db.one("SELECT state FROM billing.webhook_inbox WHERE event_id='early_capture'")).containsEntry("state","UNMATCHED");
        assertThat(payments.hasCapture(intent)).isFalse();payments.submit(PAYER,intent);inbox.process("RAZORPAY","early_capture");inbox.process("RAZORPAY","early_capture");
        assertThat(fixture.db.one("SELECT state FROM billing.webhook_inbox WHERE event_id='early_capture'")).containsEntry("state","PROCESSED");
        assertThat(fixture.db.rows("SELECT id FROM billing.journals WHERE intent_id=?",intent)).hasSize(1);
        assertThat(fixture.db.rows("SELECT id FROM billing.outbox WHERE aggregate_id=? AND type='PAYMENT_CAPTURED'",intent)).hasSize(1);
        assertThat(fixture.db.one("SELECT paid_minor,reserved_minor FROM billing.invoices WHERE id=?",INVOICE)).containsEntry("paidMinor",10000L).containsEntry("reservedMinor",0L);
    }
    @Test void failedAttemptKeepsReservationAndSameOrderCanThenCapture(){
        UUID intent=create();payments.submit(PAYER,intent);inbox.receive("RAZORPAY",event("first_attempt_failed","REQUIRES_ACTION",10000));inbox.process("RAZORPAY","first_attempt_failed");
        assertThat(payments.get(PAYER,intent)).containsEntry("status","REQUIRES_ACTION");
        assertThat(fixture.db.one("SELECT reserved_minor FROM billing.invoices WHERE id=?",INVOICE)).containsEntry("reservedMinor",10000L);
        inbox.receive("RAZORPAY",event("second_attempt_paid","CAPTURED",10000));inbox.process("RAZORPAY","second_attempt_paid");
        inbox.process("RAZORPAY","first_attempt_failed");assertThat(payments.get(PAYER,intent)).containsEntry("status","CAPTURED");
        verify(provider,times(1)).submitSaved(any(),any(),any());
    }
    @Test void changedEvidenceUnderSameEventIdRollsBackReceiptAndCannotPostTwice(){
        UUID intent=create();payments.submit(PAYER,intent);inbox.receive("RAZORPAY",event("same_event","CAPTURED",10000));
        assertThatThrownBy(()->inbox.receive("RAZORPAY",event("same_event","CAPTURED",9999))).isInstanceOf(BillingProblem.class);
        inbox.process("RAZORPAY","same_event");assertThat(fixture.db.rows("SELECT id FROM billing.journals WHERE intent_id=?",intent)).hasSize(1);
    }
    @Test void mismatchedSignedAmountRemainsDurableWithoutPosting(){
        UUID intent=create();payments.submit(PAYER,intent);inbox.receive("RAZORPAY",event("wrong_amount","CAPTURED",9999));inbox.process("RAZORPAY","wrong_amount");
        assertThat(fixture.db.one("SELECT state FROM billing.webhook_inbox WHERE event_id='wrong_amount'")).containsEntry("state","UNMATCHED");assertThat(payments.hasCapture(intent)).isFalse();
    }
    @Test void staleFailureAfterCaptureNeverReopensCapturedOrder(){
        UUID intent=create();payments.submit(PAYER,intent);inbox.receive("RAZORPAY",event("paid_event","CAPTURED",10000));inbox.process("RAZORPAY","paid_event");
        inbox.receive("RAZORPAY",event("late_attempt_failure","REQUIRES_ACTION",10000));inbox.process("RAZORPAY","late_attempt_failure");
        assertThat(payments.get(PAYER,intent)).containsEntry("status","CAPTURED");assertThat(fixture.db.rows("SELECT id FROM billing.journals WHERE intent_id=?",intent)).hasSize(1);
    }
    @Test void partialRefundUsesOriginalInrCurrencyAndIndependentApproval(){
        UUID intent=create();payments.submit(PAYER,intent);inbox.receive("RAZORPAY",event("capture_for_refund","CAPTURED",10000));inbox.process("RAZORPAY","capture_for_refund");
        when(provider.refund(any())).thenAnswer(call->{RefundCommand saved=call.getArgument(0);assertThat(saved.currency()).isEqualTo("INR");assertThat(saved.providerPaymentId()).isEqualTo("order_original");return new ProviderResult("rfnd_partial","REFUNDED",null,"Controlled refund");});
        UUID refund=id(refunds.request(FINANCE,intent,UUID.randomUUID().toString(),500,"Part of order returned").get("id"));
        assertThatThrownBy(()->refunds.approve(FINANCE,refund)).isInstanceOf(BillingProblem.class);refunds.approve(APPROVER,refund);
        assertThat(payments.get(PAYER,intent)).containsEntry("refundedMinor",500L);
        assertThat(fixture.db.one("SELECT currency FROM billing.journals WHERE effect_key=?","refund:"+refund)).containsEntry("currency","INR");
    }
    @Test void browserReconcileChecksProviderButDoesNotClearAnotherLiveLeaseForAnUncertainResult(){
        UUID intent=create();payments.submit(PAYER,intent);UUID lease=UUID.randomUUID();fixture.db.update("UPDATE billing.intents SET lease_token=?,lease_until=NOW()+INTERVAL '120 seconds' WHERE id=?",lease,intent);
        when(provider.recoverPayment(any(),any(),any(),any())).thenReturn(new ProviderResult("order_original","UNKNOWN",null,"Still uncertain"));
        payments.reconcile(PAYER,intent);assertThat(fixture.db.one("SELECT lease_token FROM billing.intents WHERE id=?",intent)).containsEntry("leaseToken",lease.toString());
        verify(provider,never()).submit(any());
    }
    @Test void twoDistinctAuthenticatedCapturesAreBothRecordedButInvoiceIsPaidOnlyOnce(){
        UUID intent=create();payments.submit(PAYER,intent);
        var first=new ProviderWebhookEvent("first_money","order_original",null,"CAPTURED",10000,"INR",null,null,"pay_first");
        var second=new ProviderWebhookEvent("extra_money","order_original",null,"CAPTURED",10000,"INR",null,null,"pay_second");
        inbox.receive("RAZORPAY",first);inbox.process("RAZORPAY","first_money");inbox.receive("RAZORPAY",second);inbox.process("RAZORPAY","extra_money");
        inbox.receive("RAZORPAY",new ProviderWebhookEvent("redelivered_primary","order_original",null,"CAPTURED",10000,"INR",null,null,"pay_first"));
        inbox.process("RAZORPAY","redelivered_primary");inbox.process("RAZORPAY","extra_money");
        assertThat(fixture.db.rows("SELECT id FROM billing.journals WHERE intent_id=?",intent)).hasSize(2);
        assertThat(fixture.db.rows("SELECT id FROM billing.outbox WHERE aggregate_id=? AND type='PAYMENT_CAPTURED'",intent)).hasSize(1);
        assertThat(fixture.db.one("SELECT paid_minor FROM billing.invoices WHERE id=?",INVOICE)).containsEntry("paidMinor",10000L);
        assertThat(fixture.db.rows("SELECT id FROM billing.cases WHERE intent_id=? AND reason='ADDITIONAL_CAPTURE_REQUIRES_RECONCILIATION'",intent)).hasSize(1);
    }
    @Test void signedRefundIsAppliedOnlyAfterReadOnlyParentVerificationAndCommitsWithInboxReceipt(){
        UUID intent=create();payments.submit(PAYER,intent);inbox.receive("RAZORPAY",event("capture_before_refund","CAPTURED",10000));inbox.process("RAZORPAY","capture_before_refund");
        when(provider.refund(any())).thenReturn(new ProviderResult("rfnd_pending","PROCESSING",null,"Controlled pending refund"));
        UUID refund=id(refunds.request(FINANCE,intent,UUID.randomUUID().toString(),500,"Partial return requiring verification").get("id"));refunds.approve(APPROVER,refund);
        when(provider.verifyRefundReference(any(),eq("rfnd_pending"))).thenAnswer(call->{
            requireNoTransaction();RefundCommand saved=call.getArgument(0);assertThat(saved.currency()).isEqualTo("INR");
            assertThat(saved.providerPaymentId()).isEqualTo("order_original");return new ProviderResult("rfnd_pending","REFUNDED",null,"Read-only verified parent");});
        inbox.receive("RAZORPAY",new ProviderWebhookEvent("refund_signed","rfnd_pending",intent.toString(),"REVIEW",500,"INR",refund.toString()));
        inbox.process("RAZORPAY","refund_signed");inbox.process("RAZORPAY","refund_signed");
        assertThat(fixture.db.one("SELECT state FROM billing.webhook_inbox WHERE event_id='refund_signed'")).containsEntry("state","PROCESSED");
        assertThat(fixture.db.one("SELECT status FROM billing.refunds WHERE id=?",refund)).containsEntry("status","REFUNDED");
        assertThat(fixture.db.rows("SELECT id FROM billing.journals WHERE effect_key=?","refund:"+refund)).hasSize(1);
        verify(provider,times(1)).verifyRefundReference(any(),eq("rfnd_pending"));
    }
    @Test void providerLookupThenWebhookForSamePaymentProducesOneCaptureJournalAndOneIdentity(){
        UUID intent=create();payments.submit(PAYER,intent);
        when(provider.recoverPayment(any(),any(),any(),any())).thenReturn(new ProviderResult("order_original","CAPTURED",null,"Verified lookup",null,null,"pay_original"));
        payments.reconcile(PAYER,intent);inbox.receive("RAZORPAY",event("lookup_then_webhook","CAPTURED",10000));inbox.process("RAZORPAY","lookup_then_webhook");payments.reconcile(PAYER,intent);
        assertThat(fixture.db.rows("SELECT id FROM billing.journals WHERE intent_id=?",intent)).hasSize(1);
        assertThat(fixture.db.rows("SELECT payment_id FROM billing.provider_payment_evidence WHERE intent_id=?",intent)).hasSize(1);
        assertThat(fixture.db.rows("SELECT id FROM billing.cases WHERE intent_id=? AND reason='ADDITIONAL_CAPTURE_REQUIRES_RECONCILIATION'",intent)).isEmpty();
    }
}
