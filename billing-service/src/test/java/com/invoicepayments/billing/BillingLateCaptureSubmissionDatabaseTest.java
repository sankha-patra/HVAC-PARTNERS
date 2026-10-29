package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import java.util.concurrent.*;
import static com.invoicepayments.billing.BillingStore.*;
import static com.invoicepayments.billing.BillingFinancialDatabaseTest.*;
import static org.assertj.core.api.Assertions.*;

/** Deterministic actual-command interleavings. Provider acceptance is controlled, not claimed as a live PSP result. */
@EnabledIfEnvironmentVariable(named="BILLING_TEST_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:15432/invoice_payments_test")
class BillingLateCaptureSubmissionDatabaseTest {
    BillingFinancialDatabaseTest fixture;
    @BeforeAll static void migrate()throws Exception{migrateOnlyIsolatedDatabase();}
    @BeforeEach void seed(){fixture=new BillingFinancialDatabaseTest();fixture.cleanAndSeedDedicatedDatabase();}
    @AfterEach void cleanup(){fixture.removeOnlyTestFaultTrigger();}
    UUID failed(){UUID first=id(fixture.payments.create(PAYER,ACCOUNT,UUID.randomUUID().toString(),List.of(INVOICE),"CARD").get("id"));fixture.payments.submit(PAYER,first);
        fixture.payments.recordResult(first,null,new ProviderResult(text(fixture.db.one("SELECT provider_id FROM billing.intents WHERE id=?",first),"providerId"),"FAILED",null,"Controlled definitive failure"));return first;}
    UUID retry(UUID first){return id(BillingTestWiring.retry(fixture.db,fixture.payments).retry(PAYER,first,UUID.randomUUID().toString(),"CARD").get("id"));}
    void lateCapture(UUID first){fixture.payments.recordResult(first,UUID.randomUUID(),fixture.provider.simulateResult(first.toString(),"CAPTURED"));}
    @Test void committedOldCaptureStopsAnUnsentChildBeforeProviderIo(){
        UUID old=failed(),child=retry(old);lateCapture(old);
        assertThatThrownBy(()->fixture.payments.submit(PAYER,child)).isInstanceOf(BillingProblem.class).hasMessageContaining("earlier attempt");
        assertThat(fixture.db.rows("SELECT * FROM billing.attempts WHERE intent_id=?",child)).isEmpty();
        assertThat(fixture.db.one("SELECT status FROM billing.intents WHERE id=?",child)).containsEntry("status","CREATED");
        assertThat(fixture.db.rows("SELECT * FROM billing.journals WHERE intent_id=?",old)).hasSize(1);
        assertThat(fixture.db.rows("SELECT * FROM billing.cases WHERE intent_id=? AND reason='LATE_CAPTURE_UNAPPLIED'",old)).hasSize(1);
    }
    @Test void oldCaptureDuringAlreadyAdmittedChildIoIsRetainedAsUnappliedCashAndNeverPaysTwice()throws Exception{
        UUID old=failed(),child=retry(old);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var controlled=new DelegateProvider(fixture.provider){@Override public ProviderResult submit(ProviderCommand command){
            assertThat(command.intentId()).isEqualTo(child.toString());assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Test coordinator did not release provider");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return super.submit(command);
        }};
        var childService=BillingTestWiring.payments(fixture.db,controlled,new MockEnvironment(),true,"http://localhost:4200",MERCHANT.toString());
        var executor=Executors.newSingleThreadExecutor();
        try {
            var submitted=executor.submit(()->childService.submit(PAYER,child));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            lateCapture(old);lateCapture(old);release.countDown();assertThat(submitted.get(10,TimeUnit.SECONDS)).containsEntry("status","PROCESSING");
            childService.recordResult(child,null,fixture.provider.simulateResult(child.toString(),"CAPTURED"));
            assertThat(fixture.db.rows("SELECT * FROM billing.journals WHERE intent_id IN (?,?)",old,child)).hasSize(2);
            assertThat(fixture.db.one("SELECT paid_minor,reserved_minor FROM billing.invoices WHERE id=?",INVOICE)).containsEntry("paidMinor",10000L).containsEntry("reservedMinor",0L);
            assertThat(fixture.db.one("SELECT state FROM billing.allocations WHERE intent_id=?",old)).containsEntry("state","RELEASED");
            assertThat(fixture.db.one("SELECT state FROM billing.allocations WHERE intent_id=?",child)).containsEntry("state","POSTED");
            assertThat(fixture.db.rows("SELECT * FROM billing.cases WHERE intent_id=? AND reason='LATE_CAPTURE_UNAPPLIED'",old)).hasSize(1);
            assertThat(fixture.db.rows("SELECT * FROM billing.outbox WHERE aggregate_id=? AND type='PAYMENT_CAPTURED'",old)).hasSize(1);
        }finally{release.countDown();executor.shutdownNow();}
    }
}
