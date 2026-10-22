package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.invoicepayments.billing.BillingFinancialDatabaseTest.*;
import static com.invoicepayments.billing.BillingStore.*;
import static org.assertj.core.api.Assertions.*;

/** Staff references must be checked against the local financial operation before any durable effect. */
@EnabledIfEnvironmentVariable(named = "BILLING_TEST_JDBC_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:15432/invoice_payments_test")
class BillingCaseReferenceDatabaseTest {
    private static final String NOTE = "Verified this operation in the provider dashboard";
    private final BillingFinancialDatabaseTest f = new BillingFinancialDatabaseTest();

    @BeforeAll static void migrate() throws Exception { migrateOnlyIsolatedDatabase(); }
    @BeforeEach void seed() { f.cleanAndSeedDedicatedDatabase(); }
    @AfterEach void cleanup() { f.removeOnlyTestFaultTrigger(); }

    @ParameterizedTest
    @ValueSource(strings = {"amount", "intent", "currency", "method"})
    void matchingPaymentKeyAndReferenceCannotOverrideDifferentFinancialEvidence(String mismatch) {
        Unresolved operation = payment();
        switch (mismatch) {
            case "amount" -> f.db.update("UPDATE billing.provider_operations SET amount_minor=1 WHERE provider_id=?", operation.reference());
            case "intent" -> f.db.update("UPDATE billing.provider_operations SET intent_id=? WHERE provider_id=?", UUID.randomUUID(), operation.reference());
            case "currency" -> f.db.update("UPDATE billing.provider_operations SET currency='EUR' WHERE provider_id=?", operation.reference());
            case "method" -> f.db.update("UPDATE billing.provider_operations SET method='ACH' WHERE provider_id=?", operation.reference());
            default -> throw new AssertionError(mismatch);
        }
        // The old key-and-reference lookup accepts this evidence; manual attachment must not.
        assertThat(f.provider.lookup(BillingService.operationKey(operation.intent()), operation.reference()).state()).isEqualTo("CAPTURED");
        Map<String, String> before = snapshot();
        assertProblem(409, () -> verify(cases(f.provider), operation));
        assertUnchangedAndOpen(before, operation);
        f.assertInvoice(INVOICE, 0, 10000);
        assertThat(f.payments.raw(operation.intent()).get("status")).isEqualTo("UNKNOWN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount", "intent", "currency", "refund", "originalPayment"})
    void matchingRefundKeyAndReferenceCannotOverrideDifferentFinancialEvidence(String mismatch) {
        Unresolved operation = refund();
        switch (mismatch) {
            case "amount" -> f.db.update("UPDATE billing.provider_operations SET amount_minor=1 WHERE provider_id=?", operation.reference());
            case "intent" -> f.db.update("UPDATE billing.provider_operations SET intent_id=? WHERE provider_id=?", UUID.randomUUID(), operation.reference());
            case "currency" -> f.db.update("UPDATE billing.provider_operations SET currency='EUR' WHERE provider_id=?", operation.reference());
            case "refund" -> f.db.update("UPDATE billing.provider_operations SET reference_id=? WHERE provider_id=?", UUID.randomUUID().toString(), operation.reference());
            case "originalPayment" -> f.db.update("UPDATE billing.provider_operations SET parent_provider_id='sim_pay_other_payment' WHERE provider_id=?", operation.reference());
            default -> throw new AssertionError(mismatch);
        }
        assertThat(f.provider.lookupRefund(BillingRefundService.operationKey(operation.refund()), operation.reference()).state()).isEqualTo("REFUNDED");
        Map<String, String> before = snapshot();
        assertProblem(409, () -> verify(cases(f.provider), operation));
        assertUnchangedAndOpen(before, operation);
        f.assertInvoice(INVOICE, 10000, 0);
        assertThat(f.refunds.raw(operation.refund()).get("status")).isEqualTo("UNKNOWN");
    }

    @Test void validPaymentReferenceUsesLocalFieldsOutsideTransactionAndSettlesOnce() {
        Unresolved operation = payment();
        AtomicInteger calls = new AtomicInteger();
        BillingProvider observed = new ForwardingProvider(f.provider) {
            @Override public ProviderResult verifyPaymentReference(ProviderCommand expected, String reference) {
                assertNoBoundTransaction();
                assertThat(expected.intentId()).isEqualTo(operation.intent().toString());
                assertThat(expected.idempotencyKey()).isEqualTo(BillingService.operationKey(operation.intent()));
                assertThat(expected.amountMinor()).isEqualTo(10000);
                assertThat(expected.currency()).isEqualTo("USD");
                assertThat(expected.method()).isEqualTo("CARD");
                assertThat(reference).isEqualTo(operation.reference());
                calls.incrementAndGet();
                return super.verifyPaymentReference(expected, reference);
            }
            @Override public ProviderResult lookup(String key, String reference) { throw new AssertionError("Manual verification must use expected financial fields"); }
        };
        BillingCaseService service = cases(observed);
        verify(service, operation);
        assertThat(calls).hasValue(1);
        assertThat(f.payments.raw(operation.intent()).get("status")).isEqualTo("CAPTURED");
        f.assertInvoice(INVOICE, 10000, 0);
        assertThat(f.count("billing.journals")).isEqualTo(1);
        assertThat(f.count("billing.ledger_entries")).isEqualTo(2);
        assertThat(f.count("billing.outbox")).isEqualTo(1);
        assertThat(f.db.one("SELECT status FROM billing.cases WHERE id=?", operation.caseId())).containsEntry("status", "RESOLVED");
        assertThat(f.db.exists("SELECT 1 FROM billing.case_actions WHERE case_id=? AND action='VERIFIED_PROVIDER_REFERENCE'", operation.caseId())).isTrue();
        Map<String, String> settled = snapshot();
        assertProblem(409, () -> verify(service, operation));
        assertThat(calls).hasValue(1);
        assertThat(snapshot()).isEqualTo(settled);
    }

    @Test void validRefundReferenceUsesLocalFieldsOutsideTransactionAndSettlesOnce() {
        Unresolved operation = refund();
        String originalPayment = f.providerId(operation.intent());
        AtomicInteger calls = new AtomicInteger();
        BillingProvider observed = new ForwardingProvider(f.provider) {
            @Override public ProviderResult verifyRefundReference(RefundCommand expected, String reference) {
                assertNoBoundTransaction();
                assertThat(expected.intentId()).isEqualTo(operation.intent().toString());
                assertThat(expected.refundId()).isEqualTo(operation.refund().toString());
                assertThat(expected.idempotencyKey()).isEqualTo(BillingRefundService.operationKey(operation.refund()));
                assertThat(expected.providerPaymentId()).isEqualTo(originalPayment);
                assertThat(expected.amountMinor()).isEqualTo(500);
                assertThat(expected.currency()).isEqualTo("USD");
                assertThat(reference).isEqualTo(operation.reference());
                calls.incrementAndGet();
                return super.verifyRefundReference(expected, reference);
            }
            @Override public ProviderResult lookupRefund(String key, String reference) { throw new AssertionError("Manual verification must use expected financial fields"); }
        };
        BillingCaseService service = cases(observed);
        verify(service, operation);
        assertThat(calls).hasValue(1);
        assertThat(f.refunds.raw(operation.refund()).get("status")).isEqualTo("REFUNDED");
        f.assertInvoice(INVOICE, 9500, 0);
        assertThat(f.count("billing.journals")).isEqualTo(2);
        assertThat(f.count("billing.ledger_entries")).isEqualTo(4);
        assertThat(f.count("billing.outbox")).isEqualTo(2);
        assertThat(f.db.one("SELECT status FROM billing.cases WHERE id=?", operation.caseId())).containsEntry("status", "RESOLVED");
        assertThat(f.db.exists("SELECT 1 FROM billing.case_actions WHERE case_id=? AND action='VERIFIED_PROVIDER_REFERENCE'", operation.caseId())).isTrue();
        Map<String, String> settled = snapshot();
        assertProblem(409, () -> verify(service, operation));
        assertThat(calls).hasValue(1);
        assertThat(snapshot()).isEqualTo(settled);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void manualVerificationRejectsCallerTransactionBeforeCallingProvider(boolean isRefund) {
        Unresolved operation = isRefund ? refund() : payment();
        AtomicInteger calls = new AtomicInteger();
        BillingProvider observed = new ForwardingProvider(f.provider) {
            @Override public ProviderResult verifyPaymentReference(ProviderCommand expected, String reference) {
                calls.incrementAndGet(); return super.verifyPaymentReference(expected, reference);
            }
            @Override public ProviderResult verifyRefundReference(RefundCommand expected, String reference) {
                calls.incrementAndGet(); return super.verifyRefundReference(expected, reference);
            }
        };
        Map<String, String> before = snapshot();
        assertThatThrownBy(() -> f.db.tx(() -> verify(cases(observed), operation)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(calls).hasValue(0);
        assertUnchangedAndOpen(before, operation);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void providerWithoutExplicitReferenceVerificationCannotSettleManualReference(boolean isRefund) {
        Unresolved operation = isRefund ? refund() : payment();
        // Deliberately inherit the interface defaults, even though ordinary lookup succeeds.
        BillingProvider unsupported = new BillingProvider() {
            @Override public String name() { return f.provider.name(); }
            @Override public ProviderResult submit(ProviderCommand command) { return f.provider.submit(command); }
            @Override public ProviderResult lookup(String key, String reference) { return f.provider.lookup(key, reference); }
            @Override public ProviderResult refund(RefundCommand command) { return f.provider.refund(command); }
            @Override public ProviderResult lookupRefund(String key, String reference) { return f.provider.lookupRefund(key, reference); }
        };
        Map<String, String> before = snapshot();
        assertProblem(409, () -> verify(cases(unsupported), operation));
        assertUnchangedAndOpen(before, operation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "REVIEW", "UNRECOGNIZED_STATE"})
    void unresolvedOrUnsupportedPaymentEvidenceLeavesCaseAndMoneyUnchanged(String state) {
        Unresolved operation = payment();
        BillingProvider unresolved = new ForwardingProvider(f.provider) {
            @Override public ProviderResult verifyPaymentReference(ProviderCommand expected, String reference) {
                return new ProviderResult(reference, state, null, "Provider evidence needs investigation");
            }
        };
        Map<String, String> before = snapshot();
        assertProblem(409, () -> verify(cases(unresolved), operation));
        assertUnchangedAndOpen(before, operation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "REVIEW", "REQUIRES_ACTION", "UNRECOGNIZED_STATE"})
    void unresolvedOrUnsupportedRefundEvidenceLeavesCaseAndMoneyUnchanged(String state) {
        Unresolved operation = refund();
        BillingProvider unresolved = new ForwardingProvider(f.provider) {
            @Override public ProviderResult verifyRefundReference(RefundCommand expected, String reference) {
                return new ProviderResult(reference, state, null, "Provider evidence needs investigation");
            }
        };
        Map<String, String> before = snapshot();
        assertProblem(409, () -> verify(cases(unresolved), operation));
        assertUnchangedAndOpen(before, operation);
    }

    private Unresolved payment() {
        var stuck = f.stuckPayment(true);
        f.provider.simulateResult(stuck.intent().toString(), "CAPTURED");
        String reference = text(f.db.one("SELECT provider_id FROM billing.provider_operations WHERE idempotency_key=?",
                BillingService.operationKey(stuck.intent())), "providerId");
        return new Unresolved(stuck.intent(), null, f.caseId("PAYMENT_REPLAY_UNSAFE", stuck.intent()), reference);
    }

    private Unresolved refund() {
        UUID intent = f.captured("CARD");
        UUID refund = f.requestedRefund(intent, 500);
        BillingProvider silent = f.refundNeverSent(true);
        BillingService payments = f.service(silent, MERCHANT);
        BillingRefundService refunds = new BillingRefundService(f.db, payments, silent);
        refunds.approve(APPROVER, refund);
        BillingRecovery recovery = new BillingRecovery(f.db, payments, refunds, silent);
        f.dueRefund(refund);
        recovery.recoverRefund(recovery.claimRefunds(1).get(0));
        ProviderResult accepted = f.provider.refund(new RefundCommand(refund.toString(), intent.toString(), f.providerId(intent),
                BillingRefundService.operationKey(refund), 500, "USD"));
        assertThat(f.refunds.raw(refund).get("status")).isEqualTo("UNKNOWN");
        UUID caseId = id(f.db.one("SELECT id FROM billing.cases WHERE reason='REFUND_REPLAY_UNSAFE' AND refund_id=?", refund).get("id"));
        return new Unresolved(intent, refund, caseId, accepted.providerId());
    }

    private BillingCaseService cases(BillingProvider provider) {
        BillingService payments = f.service(provider, MERCHANT);
        return new BillingCaseService(f.db, payments, new BillingRefundService(f.db, payments, provider), provider);
    }

    private Map<String, Object> verify(BillingCaseService service, Unresolved operation) {
        return service.verifyProviderReference("operator", operation.caseId(), operation.reference(), NOTE);
    }

    private void assertUnchangedAndOpen(Map<String, String> before, Unresolved operation) {
        assertThat(snapshot()).isEqualTo(before);
        assertThat(f.db.one("SELECT status FROM billing.cases WHERE id=?", operation.caseId())).containsEntry("status", "OPEN");
    }

    /** Full rows detect status, money, reservations, audit, timestamps, and event changes, not merely counts. */
    private Map<String, String> snapshot() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : List.of("intents", "refunds", "invoices", "allocations", "cases", "case_actions",
                "journals", "ledger_entries", "outbox", "timeline", "attempts", "provider_operations")) {
            result.put(table, jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM billing." + table + " t", String.class));
        }
        return result;
    }

    private static void assertNoBoundTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(jdbc.getDataSource())).isFalse();
    }

    private record Unresolved(UUID intent, UUID refund, UUID caseId, String reference) {}

    private static class ForwardingProvider extends DelegateProvider {
        ForwardingProvider(SimulatorBillingProvider delegate) { super(delegate); }
        @Override public ProviderResult verifyPaymentReference(ProviderCommand expected, String reference) {
            return delegate.verifyPaymentReference(expected, reference);
        }
        @Override public ProviderResult verifyRefundReference(RefundCommand expected, String reference) {
            return delegate.verifyRefundReference(expected, reference);
        }
    }
}
