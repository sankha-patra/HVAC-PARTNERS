package com.invoicepayments.billing;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.UUID;
import static com.invoicepayments.billing.BillingFinancialDatabaseTest.*;
import static com.invoicepayments.billing.BillingStore.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BILLING_TEST_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:15432/invoice_payments_test")
class BillingCaseTransactionDatabaseTest {
    BillingFinancialDatabaseTest fixture;
    @BeforeAll static void migrate() throws Exception{migrateOnlyIsolatedDatabase();}
    @BeforeEach void seed(){fixture=new BillingFinancialDatabaseTest();fixture.cleanAndSeedDedicatedDatabase();}
    @AfterEach void cleanup(){fixture.removeOnlyTestFaultTrigger();}
    @Test void providerVerifiedCaseResolutionAndFinancialEffectsShareOneCommit() throws Exception {
        Class.forName("com.invoicepayments.billing.BillingCaseCommands");
        var stuck=fixture.stuckPayment(true);
        var paid=fixture.provider.simulateResult(stuck.intent().toString(),"CAPTURED");
        UUID caseId=fixture.caseId("PAYMENT_REPLAY_UNSAFE",stuck.intent());
        stuck.cases().verifyProviderReference(FINANCE,caseId,paid.providerId(),"Verified the original provider payment reference");
        long caseTx=amount(fixture.db.one("SELECT xmin::text::bigint AS transaction_id FROM billing.cases WHERE id=? AND status='RESOLVED'",caseId),"transactionId");
        long journalTx=amount(fixture.db.one("SELECT xmin::text::bigint AS transaction_id FROM billing.journals WHERE intent_id=?",stuck.intent()),"transactionId");
        long eventTx=amount(fixture.db.one("SELECT xmin::text::bigint AS transaction_id FROM billing.outbox WHERE aggregate_id=?",stuck.intent()),"transactionId");
        long auditTx=amount(fixture.db.one("SELECT xmin::text::bigint AS transaction_id FROM billing.case_actions WHERE case_id=? AND action='VERIFIED_PROVIDER_REFERENCE'",caseId),"transactionId");
        assertThat(caseTx).isEqualTo(journalTx).isEqualTo(eventTx).isEqualTo(auditTx);
    }
    @Test void failedFinancialRecordingAlsoRollsBackCaseResolutionAndItsAudit() throws Exception {
        Class.forName("com.invoicepayments.billing.BillingCaseCommands");
        var stuck=fixture.stuckPayment(true);var paid=fixture.provider.simulateResult(stuck.intent().toString(),"CAPTURED");
        UUID caseId=fixture.caseId("PAYMENT_REPLAY_UNSAFE",stuck.intent());
        fixture.injectTransientFault("CAPTURED");
        assertThatThrownBy(()->stuck.cases().verifyProviderReference(FINANCE,caseId,paid.providerId(),"Verified the original provider payment reference"))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(fixture.db.one("SELECT status FROM billing.cases WHERE id=?",caseId)).containsEntry("status","OPEN");
        assertThat(fixture.db.rows("SELECT id FROM billing.case_actions WHERE case_id=? AND action='VERIFIED_PROVIDER_REFERENCE'",caseId)).isEmpty();
        assertThat(fixture.db.rows("SELECT id FROM billing.journals WHERE intent_id=?",stuck.intent())).isEmpty();
        fixture.removeTransientFault();
        stuck.cases().verifyProviderReference(FINANCE,caseId,paid.providerId(),"Verified the original provider payment reference");
        assertThat(fixture.db.rows("SELECT id FROM billing.journals WHERE intent_id=?",stuck.intent())).hasSize(1);
    }
}
