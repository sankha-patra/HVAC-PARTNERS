package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import org.flywaydb.core.Flyway;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.ResolvableType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.invoicepayments.billing.BillingStore.*;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL transactions only, on the explicitly allowlisted throwaway database. No Spring app, Kafka, or PSP starts. */
@EnabledIfEnvironmentVariable(named = "BILLING_TEST_JDBC_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:15432/invoice_payments_test")
class BillingFinancialDatabaseTest {
    static final UUID MERCHANT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final UUID OTHER_MERCHANT = UUID.fromString("10000000-0000-0000-0000-000000000002");
    static final UUID ACCOUNT = UUID.fromString("20000000-0000-0000-0000-000000000001");
    static final UUID SECOND_ACCOUNT = UUID.fromString("20000000-0000-0000-0000-000000000002");
    static final UUID OTHER_ACCOUNT = UUID.fromString("20000000-0000-0000-0000-000000000003");
    static final UUID INVOICE = UUID.fromString("30000000-0000-0000-0000-000000000001");
    static final UUID INVOICE_TWO = UUID.fromString("30000000-0000-0000-0000-000000000002");
    static final UUID SECOND_INVOICE = UUID.fromString("30000000-0000-0000-0000-000000000003");
    static final UUID OTHER_INVOICE = UUID.fromString("30000000-0000-0000-0000-000000000004");
    static final String PAYER = "payer-one", SECOND_PAYER = "payer-two", OTHER_PAYER = "payer-other";
    static final String FINANCE = "finance-maker", APPROVER = "finance-checker";
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager transactions;
    BillingStore db;
    SimulatorBillingProvider provider;
    BillingService payments;
    BillingRefundService refunds;
    BillingInbox inbox;
    BillingRecovery recovery;
    BillingEvents events;

    @BeforeAll static void migrateOnlyIsolatedDatabase() throws Exception {
        String url = System.getenv("BILLING_TEST_JDBC_URL");
        assertThat(url).isEqualTo("jdbc:postgresql://127.0.0.1:15432/invoice_payments_test");
        assertThat(System.getenv("BILLING_TEST_DB_USER")).isEqualTo("billing_test");
        DriverManagerDataSource source = new DriverManagerDataSource(url, System.getenv("BILLING_TEST_DB_USER"),
                System.getenv("BILLING_TEST_DB_PASSWORD"));
        try (Connection connection = source.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT current_database(),current_user")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("invoice_payments_test");
                assertThat(result.getString(2)).isEqualTo("billing_test");
            }
        }
        Flyway.configure().dataSource(source).schemas("billing").defaultSchema("billing")
                .locations("classpath:db/billing").cleanDisabled(true).load().migrate();
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
    }

    @BeforeEach void cleanAndSeedDedicatedDatabase() {
        // The hard allowlist above is mandatory: this test must never use an application datasource.
        jdbc.execute("TRUNCATE billing.merchants,billing.provider_operations,billing.outbox,billing.consumer_inbox RESTART IDENTITY CASCADE");
        db = new BillingStore(jdbc, transactions);
        provider = new SimulatorBillingProvider(jdbc, transactions);
        payments = service(provider, MERCHANT);
        refunds = new BillingRefundService(db, payments, provider);
        inbox = new BillingInbox(db, payments, refunds, MERCHANT.toString());
        recovery = new BillingRecovery(db, payments, refunds, provider);
        events = new BillingEvents(db, MERCHANT.toString());
        db.update("INSERT INTO billing.merchants(id,name) VALUES(?,'Merchant one'),(?,'Merchant other')", MERCHANT, OTHER_MERCHANT);
        account(ACCOUNT, MERCHANT, "customer-one"); account(SECOND_ACCOUNT, MERCHANT, "customer-two");
        account(OTHER_ACCOUNT, OTHER_MERCHANT, "customer-other");
        membership(ACCOUNT, PAYER, "PAYER"); membership(SECOND_ACCOUNT, SECOND_PAYER, "PAYER");
        membership(OTHER_ACCOUNT, OTHER_PAYER, "PAYER"); membership(ACCOUNT, "read-admin", "ACCOUNT_ADMIN");
        staff(MERCHANT, FINANCE, "FINANCE"); staff(MERCHANT, FINANCE, "REFUND_APPROVER");
        staff(MERCHANT, APPROVER, "REFUND_APPROVER"); staff(MERCHANT, "second-checker", "REFUND_APPROVER");
        staff(MERCHANT, "operator", "OPS"); staff(OTHER_MERCHANT, "other-finance", "FINANCE");
        staff(OTHER_MERCHANT, "other-checker", "REFUND_APPROVER");
        invoice(INVOICE, ACCOUNT, 10000); invoice(INVOICE_TWO, ACCOUNT, 20000);
        invoice(SECOND_INVOICE, SECOND_ACCOUNT, 7500); invoice(OTHER_INVOICE, OTHER_ACCOUNT, 9000);
    }

    @AfterEach void removeOnlyTestFaultTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS billing_test_reject_outbox ON billing.outbox");
        jdbc.execute("DROP FUNCTION IF EXISTS billing.test_reject_outbox()");
    }

    @Test void sameRequestReplayReturnsSameIntentWithoutDoubleReservation() {
        String key = key();
        var first = payments.create(PAYER, ACCOUNT, key, List.of(INVOICE), "CARD");
        var replay = payments.create(PAYER, ACCOUNT, key, List.of(INVOICE), "CARD");
        assertThat(replay.get("id")).isEqualTo(first.get("id"));
        assertThat(count("billing.intents")).isEqualTo(1);
        assertInvoice(INVOICE, 0, 10000);
        assertThat(count("billing.provider_operations")).isZero();
    }

    @Test void sameKeyDifferentRequestConflictsWithoutTouchingSecondInvoice() {
        String key = key();
        payments.create(PAYER, ACCOUNT, key, List.of(INVOICE), "CARD");
        assertProblem(409, () -> payments.create(PAYER, ACCOUNT, key, List.of(INVOICE_TWO), "CARD"));
        assertInvoice(INVOICE_TWO, 0, 0);
        assertThat(count("billing.intents")).isEqualTo(1);
    }

    @Test void concurrentSameKeyCreatesOneIntentAndOneReservation() throws Exception {
        String key = key();
        List<String> ids = concurrently(8, () -> payments.create(PAYER, ACCOUNT, key, List.of(INVOICE), "CARD").get("id").toString());
        assertThat(ids.stream().distinct()).hasSize(1);
        assertInvoice(INVOICE, 0, 10000);
        assertThat(count("billing.intents")).isEqualTo(1);
    }

    @Test void concurrentDifferentKeysCannotReserveSameInvoiceTwice() throws Exception {
        List<String> outcomes = concurrently(2, () -> {
            try { payments.create(PAYER, ACCOUNT, key(), List.of(INVOICE), "CARD"); return "CREATED"; }
            catch (BillingProblem ex) { assertThat(ex.status()).isEqualTo(409); return "CONFLICT"; }
        });
        assertThat(outcomes).containsExactlyInAnyOrder("CREATED", "CONFLICT");
        assertInvoice(INVOICE, 0, 10000);
        assertThat(count("billing.intents")).isEqualTo(1);
    }

    @Test void concurrentAuthorizedAndUnauthorizedRequestsNeverShareIdempotencyResult() throws Exception {
        String shared = key();
        List<String> outcomes = concurrentlyIndexed(2, index -> {
            try { payments.create(index == 0 ? PAYER : SECOND_PAYER, ACCOUNT, shared, List.of(INVOICE), "CARD"); return "CREATED"; }
            catch (BillingProblem ex) { assertThat(ex.status()).isEqualTo(404); return "DENIED"; }
        });
        assertThat(outcomes).containsExactlyInAnyOrder("CREATED", "DENIED");
        assertThat(count("billing.intents")).isEqualTo(1);
    }

    @Test void oneUserWithTwoMembershipsCanUseSameKeyIndependentlyAcrossAccounts() throws Exception {
        membership(SECOND_ACCOUNT, PAYER, "PAYER");
        String shared = key();
        var results = concurrentlyIndexed(2, index -> payments.create(PAYER, index == 0 ? ACCOUNT : SECOND_ACCOUNT,
                shared, List.of(index == 0 ? INVOICE : SECOND_INVOICE), "CARD"));
        assertThat(results.stream().map(r -> r.get("id")).distinct()).hasSize(2);
        assertInvoice(INVOICE, 0, 10000); assertInvoice(SECOND_INVOICE, 0, 7500);
    }

    @Test void accountAndMerchantBoundariesRejectForeignInvoicesAndPayments() {
        assertProblem(404, () -> payments.create(PAYER, ACCOUNT, key(), List.of(SECOND_INVOICE), "CARD"));
        assertProblem(404, () -> payments.create(OTHER_PAYER, OTHER_ACCOUNT, key(), List.of(OTHER_INVOICE), "CARD"));
        assertProblem(404, () -> payments.create("read-admin", ACCOUNT, key(), List.of(INVOICE), "CARD"));
        assertThat(count("billing.intents")).isZero();
        UUID intent = created("CARD");
        assertProblem(404, () -> payments.get(SECOND_PAYER, intent));
        assertProblem(404, () -> payments.submit(SECOND_PAYER, intent));
        assertThat(count("billing.attempts")).isZero();
    }

    @Test void malformedInvoiceSelectionFailsBeforeReservations() {
        assertProblem(400, () -> payments.create(PAYER, ACCOUNT, key(), null, "CARD"));
        assertProblem(400, () -> payments.create(PAYER, ACCOUNT, key(), Arrays.asList(INVOICE, null), "CARD"));
        assertProblem(400, () -> payments.create(PAYER, ACCOUNT, key(), List.of(INVOICE, INVOICE), "CARD"));
        assertThat(count("billing.intents")).isZero(); assertInvoice(INVOICE, 0, 0);
    }

    @Test void concurrentSubmitCallsOneDurableProviderOperation() throws Exception {
        UUID intent = created("CARD");
        concurrently(6, () -> payments.submit(PAYER, intent));
        assertThat(count("billing.attempts")).isEqualTo(1);
        assertThat(count("billing.provider_operations")).isEqualTo(1);
        assertThat(payments.raw(intent).get("status")).isEqualTo("PROCESSING");
        assertThat(count("billing.journals")).isZero();
        assertInvoice(INVOICE, 0, 10000);
    }

    @Test void lostInitialResponseRecoversOriginalProviderOperationWithoutResubmission() {
        AtomicInteger submitted = new AtomicInteger();
        BillingProvider lossy = new DelegateProvider(provider) {
            @Override public ProviderResult submit(ProviderCommand command) {
                submitted.incrementAndGet();
                ProviderResult accepted = delegate.submit(command);
                delegate.simulateResult(command.intentId(), "CAPTURED");
                throw new ProviderUncertainException("Response lost after acceptance");
            }
        };
        BillingService service = service(lossy, MERCHANT);
        UUID intent = id(service.create(PAYER, ACCOUNT, key(), List.of(INVOICE), "CARD").get("id"));
        service.submit(PAYER, intent);
        assertThat(service.raw(intent).get("status")).isEqualTo("UNKNOWN");
        assertThat(service.raw(intent).get("providerId")).isNull();
        service.submit(PAYER, intent);
        assertThat(submitted).hasValue(1);
        assertInvoice(INVOICE, 0, 10000); assertThat(count("billing.journals")).isZero();
        due(intent);
        var recoveredService = service(provider, MERCHANT);
        var worker = new BillingRecovery(db, recoveredService, new BillingRefundService(db, recoveredService, provider), provider);
        worker.recoverPayment(worker.claimPayments(1).get(0));
        assertThat(recoveredService.raw(intent).get("status")).isEqualTo("CAPTURED");
        assertInvoice(INVOICE, 10000, 0);
        assertThat(count("billing.provider_operations")).isEqualTo(1);
        assertThat(count("billing.journals")).isEqualTo(1);
    }

    @Test void unknownWithoutCanonicalEvidenceKeepsReservationsAndRaisesCase() {
        AtomicInteger submitted = new AtomicInteger();
        BillingProvider absent = new DelegateProvider(provider) {
            @Override public String name() { return "STRIPE"; }
            @Override public ProviderResult submit(ProviderCommand command) { submitted.incrementAndGet(); throw new ProviderUncertainException("Unknown"); }
            @Override public ProviderResult lookup(String key, String id) { return new ProviderResult(null, "UNKNOWN", null, "Reconcile"); }
        };
        BillingService service = service(absent, MERCHANT);
        UUID intent = id(service.create(PAYER, ACCOUNT, key(), List.of(INVOICE), "CARD").get("id"));
        service.submit(PAYER, intent);
        BillingRecovery worker = new BillingRecovery(db, service, new BillingRefundService(db, service, absent), absent);
        for (int i = 0; i < 6; i++) { due(intent); worker.recoverPayment(worker.claimPayments(1).get(0)); }
        service.submit(PAYER, intent);
        assertThat(submitted).hasValue(1);
        assertThat(service.raw(intent).get("status")).isEqualTo("UNKNOWN");
        assertInvoice(INVOICE, 0, 10000);
        assertThat(count("billing.journals")).isZero();
        assertThat(db.exists("SELECT 1 FROM billing.cases WHERE intent_id=? AND reason='PAYMENT_UNRESOLVED'", intent)).isTrue();
    }

    @Test void legitimateAchProcessingDoesNotRaiseUncertaintyCaseAfterSeveralLookups() {
        UUID intent = submitted("ACH");
        for (int i = 0; i < 6; i++) { due(intent); recovery.recoverPayment(recovery.claimPayments(1).get(0)); }
        assertThat(payments.raw(intent).get("status")).isEqualTo("PROCESSING");
        assertThat(db.exists("SELECT 1 FROM billing.cases WHERE intent_id=? AND reason='PAYMENT_UNRESOLVED'", intent)).isFalse();
        assertInvoice(INVOICE, 0, 10000);
        assertThat(count("billing.provider_operations")).isEqualTo(1);
    }

    @Test void staleRecoveryLeaseCannotApplyFinancialResultAfterNewClaim() {
        UUID intent = submitted("CARD");
        due(intent);
        var old = recovery.claimPayments(1).get(0);
        due(intent);
        var current = recovery.claimPayments(1).get(0);
        provider.simulateResult(intent.toString(), "CAPTURED");
        recovery.recoverPayment(old);
        assertThat(count("billing.journals")).isZero();
        assertThat(payments.raw(intent).get("leaseToken")).isEqualTo(current.get("recoveryToken").toString());
        recovery.recoverPayment(current);
        assertInvoice(INVOICE, 10000, 0);
        assertThat(count("billing.journals")).isEqualTo(1);
    }

    @Test void concurrentRecoveryWorkersCannotOwnSameUnexpiredLease() throws Exception {
        UUID intent = submitted("CARD"); due(intent);
        var claims = concurrently(2, () -> recovery.claimPayments(1));
        assertThat(claims.stream().mapToInt(List::size).sum()).isEqualTo(1);
    }

    @Test void changingConfiguredProviderCannotSubmitOrRecoverOldIntentThroughNewAdapter() {
        UUID intent = created("CARD");
        AtomicInteger calls = new AtomicInteger();
        BillingProvider other = new DelegateProvider(provider) {
            @Override public String name() { return "STRIPE"; }
            @Override public ProviderResult submit(ProviderCommand command) { calls.incrementAndGet(); return super.submit(command); }
            @Override public ProviderResult lookup(String key, String id) { calls.incrementAndGet(); return super.lookup(key, id); }
        };
        BillingService changed = service(other, MERCHANT);
        assertProblem(409, () -> changed.submit(PAYER, intent));
        payments.submit(PAYER, intent); due(intent);
        var worker = new BillingRecovery(db, changed, new BillingRefundService(db, changed, other), other);
        worker.recoverPayment(worker.claimPayments(1).get(0));
        assertThat(calls).hasValue(0);
        assertThat(db.exists("SELECT 1 FROM billing.cases WHERE reason='ORIGINAL_PROVIDER_UNAVAILABLE' AND intent_id=?", intent)).isTrue();
    }

    @Test void webhookReceiptCommitsBeforeProcessingAndDuplicatesPostExactlyOnce() {
        UUID intent = submitted("CARD");
        String providerId = providerId(intent);
        ProviderWebhookEvent event = new ProviderWebhookEvent("event-1", providerId, intent.toString(), "CAPTURED", 10000, "USD", null);
        inbox.receive("SIMULATOR", event); inbox.receive("SIMULATOR", event);
        assertThat(count("billing.webhook_inbox")).isEqualTo(1);
        assertThat(count("billing.journals")).isZero();
        inbox.process("SIMULATOR", "event-1");
        inbox.receive("SIMULATOR", new ProviderWebhookEvent("event-2", providerId, intent.toString(), "CAPTURED", 10000, "USD", null));
        inbox.process("SIMULATOR", "event-2");
        assertInvoice(INVOICE, 10000, 0);
        assertThat(count("billing.journals")).isEqualTo(1);
        assertThat(count("billing.outbox")).isEqualTo(1);
        assertThat(db.rows("SELECT state FROM billing.webhook_inbox")).allMatch(r -> r.get("state").equals("PROCESSED"));
    }

    @Test void eventIdentityReuseWithChangedEvidenceRejectsAndPreservesOriginal() {
        UUID intent = submitted("CARD");
        inbox.receive("SIMULATOR", new ProviderWebhookEvent("same-event", providerId(intent), intent.toString(), "CAPTURED", 10000, "USD", null));
        assertProblem(409, () -> inbox.receive("SIMULATOR", new ProviderWebhookEvent("same-event", providerId(intent), intent.toString(), "FAILED", 10000, "USD", null)));
        inbox.process("SIMULATOR", "same-event");
        assertInvoice(INVOICE, 10000, 0);
    }

    @Test void unknownReviewAndWrongAmountEvidenceRemainDurableWithoutFinancialMutation() {
        UUID intent = submitted("CARD");
        inbox.receive("SIMULATOR", new ProviderWebhookEvent("review", "unmatched-operation", null, "REVIEW", 0, "USD", null));
        inbox.receive("SIMULATOR", new ProviderWebhookEvent("amount", providerId(intent), intent.toString(), "CAPTURED", 9999, "USD", null));
        inbox.receive("SIMULATOR", new ProviderWebhookEvent("unknown", "another-operation", UUID.randomUUID().toString(), "CAPTURED", 10000, "USD", null));
        inbox.drain(25);
        assertThat(count("billing.webhook_inbox")).isEqualTo(3);
        assertThat(db.rows("SELECT state FROM billing.webhook_inbox")).allMatch(r -> r.get("state").equals("UNMATCHED"));
        assertThat(count("billing.journals")).isZero(); assertInvoice(INVOICE, 0, 10000);
    }

    @Test void merchantBoundInboxCannotApplyOtherMerchantsPaymentEvidence() {
        BillingService other = service(provider, OTHER_MERCHANT);
        UUID intent = id(other.create(OTHER_PAYER, OTHER_ACCOUNT, key(), List.of(OTHER_INVOICE), "CARD").get("id"));
        other.submit(OTHER_PAYER, intent);
        inbox.receive("SIMULATOR", new ProviderWebhookEvent("foreign", providerId(intent), intent.toString(), "CAPTURED", 9000, "USD", null));
        inbox.process("SIMULATOR", "foreign");
        assertThat(count("billing.journals")).isZero();
        assertInvoice(OTHER_INVOICE, 0, 9000);
        assertThat(db.one("SELECT state FROM billing.webhook_inbox WHERE event_id='foreign'").get("state")).isEqualTo("UNMATCHED");
    }

    @Test void lateCaptureAfterFailureDoesNotStealReplacementReservation() {
        UUID old = submitted("CARD");
        payments.recordResult(old, null, new ProviderResult(providerId(old), "FAILED", null, "Failure evidence"));
        UUID replacement = submitted("CARD");
        payments.simulate(PAYER, old, "CAPTURED");
        assertInvoice(INVOICE, 0, 10000);
        assertThat(payments.raw(replacement).get("status")).isEqualTo("PROCESSING");
        assertThat(db.exists("SELECT 1 FROM billing.ledger_entries e JOIN billing.journals j ON j.id=e.journal_id WHERE j.intent_id=? AND e.account='UNAPPLIED_CASH' AND e.side='CREDIT'", old)).isTrue();
        assertThat(db.exists("SELECT 1 FROM billing.cases WHERE intent_id=? AND reason='LATE_CAPTURE_UNAPPLIED'", old)).isTrue();
        payments.simulate(PAYER, replacement, "CAPTURED");
        assertInvoice(INVOICE, 10000, 0);
        assertThat(count("billing.journals")).isEqualTo(2);
    }

    @Test void staleFailureAndPendingEventsCannotUndoCapture() {
        UUID intent = captured("CARD");
        for (String state : List.of("FAILED", "PROCESSING", "UNKNOWN", "REQUIRES_ACTION", "CAPTURED"))
            payments.recordResult(intent, null, new ProviderResult(providerId(intent), state, null, "Delayed evidence"));
        assertThat(payments.raw(intent).get("status")).isEqualTo("CAPTURED");
        assertInvoice(INVOICE, 10000, 0);
        assertThat(count("billing.journals")).isEqualTo(1); assertThat(count("billing.outbox")).isEqualTo(1);
    }

    @Test void duplicateAchReturnReopensDebtOnceAndRetainsCaptureHistory() {
        UUID intent = captured("ACH");
        payments.simulate(PAYER, intent, "RETURNED");
        payments.recordResult(intent, null, new ProviderResult(providerId(intent), "RETURNED", null, "Replay"));
        payments.recordResult(intent, null, new ProviderResult(providerId(intent), "CAPTURED", null, "Old capture"));
        assertInvoice(INVOICE, 0, 0);
        assertThat(payments.raw(intent).get("status")).isEqualTo("RETURNED");
        assertThat(count("billing.journals")).isEqualTo(2); assertThat(count("billing.outbox")).isEqualTo(2);
    }

    @Test void realDatabaseFailureAfterJournalRollsBackFinancialStateAndRecoveryPostsOnce() {
        UUID intent = submitted("CARD");
        jdbc.execute("""
            CREATE FUNCTION billing.test_reject_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN IF NEW.type='PAYMENT_CAPTURED' THEN RAISE EXCEPTION 'Injected outbox outage'; END IF; RETURN NEW; END $$;
            """);
        jdbc.execute("CREATE TRIGGER billing_test_reject_outbox BEFORE INSERT ON billing.outbox FOR EACH ROW EXECUTE FUNCTION billing.test_reject_outbox()");
        assertThatThrownBy(() -> payments.simulate(PAYER, intent, "CAPTURED")).isInstanceOf(RuntimeException.class);
        assertThat(count("billing.journals")).isZero(); assertThat(count("billing.ledger_entries")).isZero(); assertThat(count("billing.outbox")).isZero();
        assertThat(payments.raw(intent).get("status")).isEqualTo("PROCESSING"); assertInvoice(INVOICE, 0, 10000);
        assertThat(provider.lookup(BillingService.operationKey(intent), providerId(intent)).state()).isEqualTo("CAPTURED");
        removeOnlyTestFaultTrigger(); due(intent);
        recovery.recoverPayment(recovery.claimPayments(1).get(0));
        assertInvoice(INVOICE, 10000, 0); assertThat(count("billing.journals")).isEqualTo(1); assertThat(count("billing.outbox")).isEqualTo(1);
    }

    @Test void requesterCannotApproveOwnRefundEvenWithBothPermissions() {
        UUID intent = captured("CARD"); UUID refund = requestedRefund(intent, 500);
        assertProblem(403, () -> refunds.approve(FINANCE, refund));
        assertProblem(403, () -> refunds.approve("other-checker", refund));
        assertThat(refunds.raw(refund).get("status")).isEqualTo("REQUESTED");
        assertThat(db.rows("SELECT * FROM billing.provider_operations WHERE operation_kind='REFUND'")).isEmpty();
    }

    @Test void concurrentRefundRequestsCannotReserveMoreThanCapturedAmount() throws Exception {
        UUID intent = captured("CARD");
        List<String> outcomes = concurrently(2, () -> {
            try { requestedRefund(intent, 7000); return "REQUESTED"; }
            catch (BillingProblem ex) { assertThat(ex.status()).isEqualTo(409); return "REJECTED"; }
        });
        assertThat(outcomes).containsExactlyInAnyOrder("REQUESTED", "REJECTED");
        assertThat(count("billing.refunds")).isEqualTo(1);
        assertThat(count("billing.journals")).isEqualTo(1);
    }

    @Test void concurrentApprovalSubmitsOneRefundAndPostsOneEffect() throws Exception {
        UUID intent = captured("CARD"); UUID refund = requestedRefund(intent, 500);
        concurrentlyIndexed(2, index -> refunds.approve(index == 0 ? APPROVER : "second-checker", refund));
        assertThat(refunds.raw(refund).get("status")).isEqualTo("REFUNDED");
        assertThat(db.rows("SELECT * FROM billing.provider_operations WHERE operation_kind='REFUND'")).hasSize(1);
        assertThat(count("billing.journals")).isEqualTo(2); assertInvoice(INVOICE, 9500, 0);
    }

    @Test void approvedRefundSurvivesCrashBeforeSubmissionAndWorkerResumesIt() {
        UUID intent = captured("CARD"); UUID refund = requestedRefund(intent, 500);
        // Simulate a process exit after the approval transaction committed, before the provider call.
        db.update("UPDATE billing.refunds SET status='APPROVED',approved_by=?,next_recovery_at=NOW()-INTERVAL '1 second' WHERE id=?", APPROVER, refund);
        var restarted = new BillingRecovery(db, payments, refunds, provider);
        var work = restarted.claimRefunds(1);
        assertThat(work).hasSize(1); assertThat(work.get(0).get("status")).isEqualTo("APPROVED");
        restarted.recoverRefund(work.get(0));
        assertThat(refunds.raw(refund).get("status")).isEqualTo("REFUNDED");
        assertInvoice(INVOICE, 9500, 0); assertThat(count("billing.journals")).isEqualTo(2);
    }

    @Test void twoPartialRefundsEmitExactIndependentAmountsAndErpReplayIsIdempotent() {
        UUID intent = captured("CARD");
        UUID first = requestedRefund(intent, 1500); refunds.approve(APPROVER, first);
        UUID second = requestedRefund(intent, 2500); refunds.approve(APPROVER, second);
        var messages = db.rows("SELECT payload::text AS payload FROM billing.outbox WHERE type='PAYMENT_REFUNDED'");
        assertThat(messages).hasSize(2);
        Set<Long> amounts = new HashSet<>(); Set<String> refundIds = new HashSet<>();
        for (var message : messages) {
            JSONObject payload = new JSONObject(text(message, "payload"));
            amounts.add(payload.getLong("amountMinor")); refundIds.add(payload.getString("refundId"));
            assertThat(payload.getLong("intentAmountMinor")).isEqualTo(10000);
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            events.applyErp(bytes); events.applyErp(bytes);
        }
        assertThat(amounts).containsExactlyInAnyOrder(1500L, 2500L);
        assertThat(refundIds).containsExactlyInAnyOrder(first.toString(), second.toString());
        assertThat(count("billing.erp_postings")).isEqualTo(2);
        assertThat(count("billing.consumer_inbox")).isEqualTo(2);
        assertInvoice(INVOICE, 6000, 0);
        assertThat(amount(payments.raw(intent), "refundedMinor")).isEqualTo(4000);
        assertThat(count("billing.journals")).isEqualTo(3);
    }

    @ParameterizedTest @ValueSource(strings = {"PROCESSING", "UNKNOWN", "REQUIRES_ACTION"})
    void failedRefundCannotRegressAndReserveReplacementAmountAgain(String stale) {
        UUID intent = captured("CARD"); UUID first = requestedRefund(intent, 7000);
        db.update("UPDATE billing.refunds SET status='APPROVED',approved_by=? WHERE id=?", APPROVER, first);
        refunds.record(first, null, new ProviderResult("refund-attempt", "FAILED", null, "Definitive failure"));
        UUID replacement = requestedRefund(intent, 7000);
        refunds.record(first, null, new ProviderResult("refund-attempt", stale, null, "Stale event"));
        assertThat(refunds.raw(first).get("status")).isEqualTo("FAILED");
        assertThat(refunds.raw(replacement).get("status")).isEqualTo("REQUESTED");
        assertThat(amount(db.one("SELECT SUM(amount_minor) AS value FROM billing.refunds WHERE status NOT IN ('FAILED','REJECTED')"), "value")).isEqualTo(7000);
        assertInvoice(INVOICE, 10000, 0);
    }

    @Test void laterRefundFailurePreservesHistoryAndCreatesFinanceCase() {
        UUID intent = captured("CARD"); UUID refund = requestedRefund(intent, 500);
        refunds.approve(APPROVER, refund);
        refunds.record(refund, null, new ProviderResult(text(refunds.raw(refund), "providerId"), "FAILED", null, "Bank later rejected refund"));
        assertThat(refunds.raw(refund).get("status")).isEqualTo("REFUNDED");
        assertThat(count("billing.journals")).isEqualTo(2); assertInvoice(INVOICE, 9500, 0);
        assertThat(db.exists("SELECT 1 FROM billing.cases WHERE reason='REFUND_LATER_FAILED' AND intent_id=?", intent)).isTrue();
    }

    @Test void refundEvidenceWithoutIndependentApprovalCannotMoveMoney() {
        UUID intent = captured("CARD"); UUID refund = requestedRefund(intent, 500);
        inbox.receive("SIMULATOR", new ProviderWebhookEvent("unapproved", "unknown-refund", intent.toString(), "REFUNDED", 500, "USD", refund.toString()));
        inbox.process("SIMULATOR", "unapproved");
        assertThat(refunds.raw(refund).get("status")).isEqualTo("REQUESTED");
        assertThat(count("billing.journals")).isEqualTo(1); assertInvoice(INVOICE, 10000, 0);
        assertThat(db.one("SELECT state FROM billing.webhook_inbox WHERE event_id='unapproved'").get("state")).isEqualTo("UNMATCHED");
    }

    @Test void fabricatedOrAlteredErpMessagesCannotClaimAnAcknowledgement() {
        UUID intent = submitted("CARD");
        JSONObject fake = new JSONObject().put("schemaVersion", 1).put("eventId", UUID.randomUUID().toString())
                .put("intentId", intent.toString()).put("accountId", ACCOUNT.toString()).put("eventType", "PAYMENT_CAPTURED")
                .put("amountMinor", 10000).put("currency", "USD");
        assertThatThrownBy(() -> events.applyErp(fake.toString().getBytes(StandardCharsets.UTF_8))).isInstanceOf(RuntimeException.class);
        payments.simulate(PAYER, intent, "CAPTURED");
        JSONObject original = new JSONObject(text(db.one("SELECT payload::text AS payload FROM billing.outbox WHERE type='PAYMENT_CAPTURED'"), "payload"));
        JSONObject altered = new JSONObject(original.toString()).put("amountMinor", new java.math.BigDecimal("10000.5"));
        assertThatThrownBy(() -> events.applyErp(altered.toString().getBytes(StandardCharsets.UTF_8))).isInstanceOf(RuntimeException.class);
        assertThat(count("billing.erp_postings")).isZero(); assertThat(count("billing.consumer_inbox")).isZero();
        events.applyErp(original.toString().getBytes(StandardCharsets.UTF_8));
        assertThat(count("billing.erp_postings")).isEqualTo(1);
    }

    @Test void outboxAcknowledgementFromExpiredOwnerCannotAcknowledgeNewClaim() {
        captured("CARD");
        ObjectProvider<KafkaTemplate<String, byte[]>> empty = new DefaultListableBeanFactory()
                .getBeanProvider(ResolvableType.forClassWithGenerics(KafkaTemplate.class, String.class, byte[].class));
        BillingWorkers workers = new BillingWorkers(db, recovery, inbox, empty);
        try {
            var old = workers.claimOutbox(1).get(0); UUID event = id(old.get("id"));
            db.update("UPDATE billing.outbox SET lease_until=NOW()-INTERVAL '1 second',next_attempt_at=NOW()-INTERVAL '1 second' WHERE id=?", event);
            var current = workers.claimOutbox(1).get(0);
            workers.markPublished(event, id(old.get("leaseToken")));
            assertThat(db.one("SELECT state FROM billing.outbox WHERE id=?", event).get("state")).isEqualTo("IN_FLIGHT");
            workers.markPublished(event, id(current.get("leaseToken")));
            assertThat(db.one("SELECT state FROM billing.outbox WHERE id=?", event).get("state")).isEqualTo("PUBLISHED");
        } finally { workers.close(); }
    }

    @Test void unbalancedJournalIsRejectedAtCommitAndLeavesNoPartialFinancialRows() {
        UUID intent = created("CARD"); UUID journal = UUID.randomUUID(); AtomicBoolean bodyFinished = new AtomicBoolean();
        assertThatThrownBy(() -> db.tx(() -> {
            db.update("INSERT INTO billing.journals(id,merchant_id,intent_id,effect_key,currency) VALUES(?,?,?,'unbalanced-test','USD')", journal, MERCHANT, intent);
            db.update("INSERT INTO billing.ledger_entries(journal_id,line,account,side,amount_minor) VALUES(?,1,'PROVIDER_CLEARING','DEBIT',100),(?,2,'CUSTOMER_RECEIVABLES','CREDIT',99)", journal, journal);
            bodyFinished.set(true); return null;
        })).isInstanceOf(RuntimeException.class);
        assertThat(bodyFinished).isTrue();
        assertThat(count("billing.journals")).isZero(); assertThat(count("billing.ledger_entries")).isZero();
    }

    @Test void historicalJournalsCannotBeChangedDeletedOrAppendedTo() {
        UUID intent = captured("CARD");
        UUID journal = id(db.one("SELECT id FROM billing.journals WHERE intent_id=?", intent).get("id"));
        assertThatThrownBy(() -> db.update("UPDATE billing.journals SET currency='USD' WHERE id=?", journal)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> db.update("DELETE FROM billing.journals WHERE id=?", journal)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> db.update("UPDATE billing.ledger_entries SET amount_minor=1 WHERE journal_id=?", journal)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> db.update("DELETE FROM billing.ledger_entries WHERE journal_id=?", journal)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> db.update("INSERT INTO billing.ledger_entries(journal_id,line,account,side,amount_minor) VALUES(?,3,'PROVIDER_CLEARING','DEBIT',1)", journal)).isInstanceOf(RuntimeException.class);
        assertThat(count("billing.journals")).isEqualTo(1); assertThat(count("billing.ledger_entries")).isEqualTo(2);
    }

    @Test void databaseRejectsInvoiceOverAllocationEvenForDirectWriter() {
        assertThatThrownBy(() -> db.update("UPDATE billing.invoices SET paid_minor=5000,reserved_minor=5001 WHERE id=?", INVOICE)).isInstanceOf(RuntimeException.class);
        assertInvoice(INVOICE, 0, 0);
    }

    @Test void cancelUnsubmittedIntentReleasesReservationsAndReplayCannotSubmitIt() {
        UUID intent = created("CARD");
        assertThat(payments.cancel(PAYER, intent).get("status")).isEqualTo("CANCELLED");
        long timeline = count("billing.timeline");
        assertThat(payments.cancel(PAYER, intent).get("status")).isEqualTo("CANCELLED");
        assertThat(count("billing.timeline")).isEqualTo(timeline);
        assertInvoice(INVOICE, 0, 0);
        try { payments.submit(PAYER, intent); }
        catch (BillingProblem conflict) { assertThat(conflict.status()).isEqualTo(409); }
        assertThat(payments.raw(intent).get("status")).isEqualTo("CANCELLED");
        assertThat(count("billing.provider_operations")).isZero();
        assertThat(count("billing.journals")).isZero();
        UUID replacement = created("CARD");
        assertThat(replacement).isNotEqualTo(intent); assertInvoice(INVOICE, 0, 10000);
    }

    @Test void cancelRequiresThePayersOwnAccountMembership() {
        UUID intent = created("CARD");
        assertProblem(404, () -> payments.cancel(SECOND_PAYER, intent));
        assertProblem(404, () -> payments.cancel("read-admin", intent));
        assertInvoice(INVOICE, 0, 10000);
        assertThat(payments.raw(intent).get("status")).isEqualTo("CREATED");
    }

    @ParameterizedTest @ValueSource(strings = {"UNKNOWN", "SUBMITTING", "PROCESSING"})
    void cancelCannotReleaseReservationsAfterProviderSubmissionMayHaveStarted(String state) {
        UUID intent = submitted("CARD");
        db.update("UPDATE billing.intents SET status=? WHERE id=?", state, intent);
        assertProblem(409, () -> payments.cancel(PAYER, intent));
        assertInvoice(INVOICE, 0, 10000);
        assertThat(payments.raw(intent).get("status")).isEqualTo(state);
        assertThat(count("billing.provider_operations")).isEqualTo(1);
    }

    @Test void cancelAndSubmitRaceHasOneWinnerWithoutUnreservedProviderPayment() throws Exception {
        UUID intent = created("CARD");
        concurrentlyIndexed(2, index -> {
            try { return (index == 0 ? payments.cancel(PAYER, intent) : payments.submit(PAYER, intent)).get("status"); }
            catch (BillingProblem conflict) { assertThat(conflict.status()).isEqualTo(409); return "CONFLICT"; }
        });
        String state = text(payments.raw(intent), "status");
        assertThat(state).isIn("CANCELLED", "PROCESSING");
        if ("CANCELLED".equals(state)) {
            assertInvoice(INVOICE, 0, 0); assertThat(count("billing.provider_operations")).isZero();
            assertThat(count("billing.attempts")).isZero();
        } else {
            assertInvoice(INVOICE, 0, 10000); assertThat(count("billing.provider_operations")).isEqualTo(1);
            assertThat(count("billing.attempts")).isEqualTo(1);
        }
        assertThat(count("billing.journals")).isZero();
    }

    @Test void settlementReportMatchesCaptureButCannotInventBankMoneyAndReplayMustMatchEvidence() {
        UUID intent = captured("CARD");
        var reconciler = reconciliation();
        var report = report(intent, "provider-report-1", 10000, 125);
        var received = reconciler.receive(FINANCE, report);
        UUID settlement = id(received.get("id"));
        assertThat(received.get("state")).isEqualTo("MATCHED");
        assertThat(db.one("SELECT status FROM billing.settlement_reports WHERE id=?", settlement).get("status")).isEqualTo("REPORTED");
        assertThat(reconciler.receive(FINANCE, report).get("id")).isEqualTo(settlement.toString());
        assertThat(count("billing.reconciliation_imports")).isEqualTo(1);
        assertThat(count("billing.settlement_lines")).isEqualTo(1);
        assertThat(count("billing.journals")).isEqualTo(1);
        assertThat(db.exists("SELECT 1 FROM billing.ledger_entries WHERE account='BANK_CASH'")).isFalse();
        assertProblem(409, () -> reconciler.receive(FINANCE, report(intent, "provider-report-1", 10000, 126)));
        assertThat(count("billing.reconciliation_imports")).isEqualTo(1);
    }

    @Test void settlementUnknownAndMismatchedPaymentsRemainDurableExceptions() {
        UUID intent = captured("CARD");
        var reconciler = reconciliation();
        assertThat(reconciler.receive(FINANCE, report(UUID.randomUUID(), "unknown-payment", 10000, 125)).get("state")).isEqualTo("EXCEPTION");
        assertThat(reconciler.receive(FINANCE, report(intent, "wrong-capture-amount", 9999, 125)).get("state")).isEqualTo("EXCEPTION");
        assertThat(count("billing.reconciliation_imports")).isEqualTo(2);
        assertThat(count("billing.settlement_reports")).isZero();
        assertThat(db.rows("SELECT id FROM billing.cases WHERE reason='SETTLEMENT_REPORT_MISMATCH'")).hasSize(2);
        assertThat(count("billing.journals")).isEqualTo(1);
    }

    @Test void settlementBankEvidenceRequiresDifferentAuthorizedCheckerAndPostsBalancedCashAndFeesOnce() {
        UUID intent = captured("CARD");
        var reconciler = reconciliation();
        var report = report(intent, "checked-bank-report", 10000, 125);
        UUID settlement = id(reconciler.receive(FINANCE, report).get("id"));
        var credit = new BillingReconciliation.BankCredit(report.bankReference(), 9875, "a".repeat(64));
        assertProblem(403, () -> reconciler.confirmBank(FINANCE, settlement, credit));
        assertProblem(403, () -> reconciler.confirmBank("other-checker", settlement, credit));
        assertProblem(403, () -> reconciler.confirmBank(PAYER, settlement, credit));
        assertThat(count("billing.journals")).isEqualTo(1);
        assertThat(reconciler.confirmBank(APPROVER, settlement, credit).get("status")).isEqualTo("MATCHED");
        reconciler.confirmBank(APPROVER, settlement, new BillingReconciliation.BankCredit(report.bankReference(), 9875, "A".repeat(64)));
        UUID journal = id(db.one("SELECT id FROM billing.journals WHERE effect_key=?", "settlement:" + settlement).get("id"));
        assertThat(db.rows("SELECT account,side,amount_minor FROM billing.ledger_entries WHERE journal_id=?", journal)).hasSize(3);
        assertThat(amount(db.one("SELECT SUM(CASE side WHEN 'DEBIT' THEN amount_minor ELSE -amount_minor END) AS balance FROM billing.ledger_entries WHERE journal_id=?", journal), "balance")).isZero();
        assertThat(amount(db.one("SELECT amount_minor FROM billing.ledger_entries WHERE journal_id=? AND account='BANK_CASH'", journal), "amountMinor")).isEqualTo(9875);
        assertThat(amount(db.one("SELECT amount_minor FROM billing.ledger_entries WHERE journal_id=? AND account='PROVIDER_FEES'", journal), "amountMinor")).isEqualTo(125);
        assertThat(amount(db.one("SELECT amount_minor FROM billing.ledger_entries WHERE journal_id=? AND account='PROVIDER_CLEARING'", journal), "amountMinor")).isEqualTo(10000);
        assertThat(count("billing.journals")).isEqualTo(2);
        assertProblem(409, () -> reconciler.confirmBank(APPROVER, settlement, new BillingReconciliation.BankCredit(report.bankReference(), 9875, "b".repeat(64))));
        assertProblem(409, () -> reconciler.confirmBank(APPROVER, settlement, new BillingReconciliation.BankCredit(report.bankReference(), 9874, "a".repeat(64))));
        assertThat(count("billing.journals")).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings = {"AMOUNT", "REFERENCE"})
    void settlementMismatchedBankCreditCreatesExceptionWithoutBankJournal(String mismatch) {
        UUID intent = captured("CARD");
        var reconciler = reconciliation();
        var report = report(intent, "bank-mismatch", 10000, 125);
        UUID settlement = id(reconciler.receive(FINANCE, report).get("id"));
        var wrong = new BillingReconciliation.BankCredit("REFERENCE".equals(mismatch) ? "OTHER-BANK-REF" : report.bankReference(),
                "AMOUNT".equals(mismatch) ? 9874 : 9875, "a".repeat(64));
        assertThat(reconciler.confirmBank(APPROVER, settlement, wrong).get("status")).isEqualTo("EXCEPTION");
        assertThat(db.exists("SELECT 1 FROM billing.cases WHERE reason='BANK_CREDIT_MISMATCH'")).isTrue();
        assertThat(db.exists("SELECT 1 FROM billing.ledger_entries WHERE account='BANK_CASH'")).isFalse();
        assertThat(count("billing.journals")).isEqualTo(1);
    }

    @Test void settlementCannotClearSameCapturedPaymentThroughTwoDifferentReports() {
        UUID intent = captured("CARD");
        var reconciler = reconciliation();
        assertThat(reconciler.receive(FINANCE, report(intent, "first-report", 10000, 125)).get("state")).isEqualTo("MATCHED");
        assertThat(reconciler.receive(FINANCE, report(intent, "duplicate-payment-report", 10000, 125)).get("state")).isEqualTo("EXCEPTION");
        assertThat(count("billing.settlement_reports")).isEqualTo(1);
        assertThat(count("billing.settlement_lines")).isEqualTo(1);
        assertThat(count("billing.journals")).isEqualTo(1);
    }

    @Test void settlementConcurrentBankConfirmationsPostOneJournal() throws Exception {
        UUID intent = captured("CARD");
        var reconciler = reconciliation();
        var report = report(intent, "concurrent-bank", 10000, 125);
        UUID settlement = id(reconciler.receive(FINANCE, report).get("id"));
        var credit = new BillingReconciliation.BankCredit(report.bankReference(), 9875, "a".repeat(64));
        var confirmations = concurrentlyIndexed(2, index -> reconciler.confirmBank(index == 0 ? APPROVER : "second-checker", settlement, credit));
        assertThat(confirmations).allMatch(row -> "MATCHED".equals(row.get("status")));
        assertThat(db.rows("SELECT id FROM billing.journals WHERE effect_key=?", "settlement:" + settlement)).hasSize(1);
        assertThat(count("billing.journals")).isEqualTo(2);
    }

    @Test void settlementOneBankCreditCannotBeReusedForDifferentCapturedPayments() {
        UUID first = captured("CARD");
        db.update("UPDATE billing.invoices SET total_minor=10000 WHERE id=?", INVOICE_TWO);
        UUID second = id(payments.create(PAYER, ACCOUNT, key(), List.of(INVOICE_TWO), "CARD").get("id"));
        payments.submit(PAYER, second); payments.simulate(PAYER, second, "CAPTURED");
        var reconciler = reconciliation();
        var firstReport = new BillingReconciliation.Report(MERCHANT, "bank-credit-first", "SIMULATOR", 10000, 125, 9875,
                "shared-bank-credit", List.of(new BillingReconciliation.Line(first, 10000)));
        var secondReport = new BillingReconciliation.Report(MERCHANT, "bank-credit-second", "SIMULATOR", 10000, 125, 9875,
                "shared-bank-credit", List.of(new BillingReconciliation.Line(second, 10000)));
        UUID firstSettlement = id(reconciler.receive(FINANCE, firstReport).get("id"));
        UUID secondSettlement = id(reconciler.receive(FINANCE, secondReport).get("id"));
        var credit = new BillingReconciliation.BankCredit("shared-bank-credit", 9875, "a".repeat(64));
        assertThat(reconciler.confirmBank(APPROVER, firstSettlement, credit).get("status")).isEqualTo("MATCHED");
        assertThat(reconciler.confirmBank(APPROVER, secondSettlement, credit).get("status")).isEqualTo("EXCEPTION");
        assertThat(db.rows("SELECT id FROM billing.journals WHERE effect_key LIKE 'settlement:%'")).hasSize(1);
        assertThat(db.exists("SELECT 1 FROM billing.cases WHERE reason='DUPLICATE_BANK_CREDIT'")).isTrue();
        assertThat(db.rows("SELECT journal_id FROM billing.ledger_entries WHERE account='BANK_CASH'")).hasSize(1);
    }

    @Test void bankReferencesPreserveCaseAndRejectWhitespaceReformatting() {
        UUID first=captured("CARD");db.update("UPDATE billing.invoices SET total_minor=10000 WHERE id=?",INVOICE_TWO);
        UUID second=id(payments.create(PAYER,ACCOUNT,key(),List.of(INVOICE_TWO),"CARD").get("id"));payments.submit(PAYER,second);payments.simulate(PAYER,second,"CAPTURED");
        var reconciler=reconciliation();
        var firstReport=new BillingReconciliation.Report(MERCHANT,"case-sensitive-a","SIMULATOR",10000,125,9875,
            "Bank Ref/abc-01",List.of(new BillingReconciliation.Line(first,10000)));
        var secondReport=new BillingReconciliation.Report(MERCHANT,"case-sensitive-b","SIMULATOR",10000,125,9875,
            "BANK REF/ABC-01",List.of(new BillingReconciliation.Line(second,10000)));
        UUID firstId=id(reconciler.receive(FINANCE,firstReport).get("id"));UUID secondId=id(reconciler.receive(FINANCE,secondReport).get("id"));
        assertThat(reconciler.confirmBank(APPROVER,firstId,new BillingReconciliation.BankCredit("Bank Ref/abc-01",9875,"a".repeat(64))).get("status")).isEqualTo("MATCHED");
        assertThat(reconciler.confirmBank(APPROVER,secondId,new BillingReconciliation.BankCredit("BANK REF/ABC-01",9875,"b".repeat(64))).get("status")).isEqualTo("MATCHED");
        assertThat(jdbc.queryForList("SELECT bank_reference FROM billing.settlement_reports WHERE id IN (?,?) ORDER BY external_id",String.class,firstId,secondId))
            .containsExactly("Bank Ref/abc-01","BANK REF/ABC-01");
        var padded=new BillingReconciliation.Report(MERCHANT,"bad-reference-format","SIMULATOR",10000,125,9875,
            " padded-ref ",List.of(new BillingReconciliation.Line(first,10000)));
        assertProblem(400,()->reconciler.receive(FINANCE,padded));
        assertThat(db.exists("SELECT 1 FROM billing.cases WHERE reason='DUPLICATE_BANK_CREDIT'")).isFalse();
    }

    BillingReconciliation reconciliation() { return new BillingReconciliation(db, payments, new MockEnvironment(), true); }
    BillingReconciliation.Report report(UUID intent, String external, long gross, long fee) {
        return new BillingReconciliation.Report(MERCHANT, external, "SIMULATOR", gross, fee, gross - fee,
                "BANK-" + external, List.of(new BillingReconciliation.Line(intent, gross)));
    }

    BillingWorkers outboxWorker(KafkaTemplate<String,byte[]> kafka) {return outboxWorker(db,kafka);}
    BillingWorkers outboxWorker(BillingStore store,KafkaTemplate<String,byte[]> kafka) {
        var beans=new DefaultListableBeanFactory();
        if(kafka!=null) beans.registerSingleton("testKafka",kafka);
        // Mockito erases the generic sender type; select this fixture by raw type,
        // as in the real-Kafka integration fixture, rather than resolving to null.
        @SuppressWarnings({"unchecked","rawtypes"})
        ObjectProvider<KafkaTemplate<String,byte[]>> provider=(ObjectProvider)beans.getBeanProvider(KafkaTemplate.class);
        return new BillingWorkers(store,recovery,inbox,provider);
    }
    UUID enqueue(UUID payment) {
        UUID event=UUID.randomUUID();
        db.update("INSERT INTO billing.outbox(id,aggregate_id,type,payload,created_at) VALUES(?,?,'TEST','{}'::jsonb,'2026-01-01'::timestamptz)",event,payment);
        return event;
    }
    void acknowledge(BillingWorkers worker,Map<String,Object> job) {
        worker.markPublished(id(job.get("id")),id(job.get("leaseToken")));
    }

    @Test void samePaymentPublishesInInsertionOrderEvenWhenTimestampsTie() {
        UUID payment=UUID.randomUUID(); UUID first=enqueue(payment),second=enqueue(payment),third=enqueue(payment);
        var worker=outboxWorker(null);
        try {
            for(UUID expected:List.of(first,second,third)) {
                var jobs=worker.claimOutbox(8);
                assertThat(jobs).hasSize(1);
                assertThat(id(jobs.get(0).get("id"))).isEqualTo(expected);
                acknowledge(worker,jobs.get(0));
            }
            assertThat(worker.claimOutbox(8)).isEmpty();
        } finally {worker.close();}
    }

    @Test void eightCompetingPublishersClaimOnlyOneHeadPerPayment() throws Exception {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        UUID firstA=enqueue(a),firstB=enqueue(b);enqueue(a);enqueue(b);
        var worker=outboxWorker(null);
        try {
            var claimed=concurrently(8,()->worker.claimOutbox(8));
            assertThat(claimed.stream().flatMap(List::stream).map(row->id(row.get("id"))).toList())
                .containsExactlyInAnyOrder(firstA,firstB);
        } finally {worker.close();}
    }

    @Test @SuppressWarnings("unchecked") void failedHeadBlocksSuccessorDuringBackoffButNotAnotherPayment() {
        KafkaTemplate<String,byte[]> kafka=org.mockito.Mockito.mock(KafkaTemplate.class);
        org.mockito.Mockito.when(kafka.send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(byte[].class)))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("test broker unavailable")));
        UUID a=UUID.randomUUID();UUID first=enqueue(a);enqueue(a);
        var worker=outboxWorker(kafka);
        try {
            var claim=worker.claimOutbox(8);assertThat(claim).hasSize(1);worker.publishOne(claim.get(0));
            assertThat(db.one("SELECT state,attempts FROM billing.outbox WHERE id=?",first))
                .containsEntry("state","NEW").containsEntry("attempts",1);
            UUID independent=enqueue(UUID.randomUUID());
            var available=worker.claimOutbox(8);assertThat(available).hasSize(1);
            assertThat(id(available.get(0).get("id"))).isEqualTo(independent);
            acknowledge(worker,available.get(0));
            assertThat(worker.claimOutbox(8)).isEmpty();
            db.update("UPDATE billing.outbox SET next_attempt_at=NOW()-INTERVAL '1 second' WHERE id=?",first);
            assertThat(id(worker.claimOutbox(8).get(0).get("id"))).isEqualTo(first);
        } finally {worker.close();}
    }

    @Test @SuppressWarnings("unchecked") void obsoleteLeaseOwnerCannotSendToKafka() {
        KafkaTemplate<String,byte[]> kafka=org.mockito.Mockito.mock(KafkaTemplate.class);
        UUID first=enqueue(UUID.randomUUID());var worker=outboxWorker(kafka);
        try {
            var old=worker.claimOutbox(1).get(0);
            db.update("UPDATE billing.outbox SET lease_until=NOW()-INTERVAL '1 second',next_attempt_at=NOW()-INTERVAL '1 second' WHERE id=?",first);
            var current=worker.claimOutbox(1).get(0);worker.publishOne(old);
            org.mockito.Mockito.verifyNoInteractions(kafka);
            acknowledge(worker,current);
        } finally {worker.close();}
    }

    @Test @SuppressWarnings("unchecked") void eightBrokerWaitsHoldNoPooledConnectionsOrRowLocks() throws Exception {
        KafkaTemplate<String,byte[]> kafka=org.mockito.Mockito.mock(KafkaTemplate.class);
        var brokerAck=new CompletableFuture<org.springframework.kafka.support.SendResult<String,byte[]>>();
        var started=new CountDownLatch(8);var sawTransaction=new AtomicBoolean();
        org.mockito.Mockito.when(kafka.send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(byte[].class)))
            .thenAnswer(call->{
                if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) sawTransaction.set(true);
                started.countDown();return brokerAck;
            });
        try(var pool=new com.zaxxer.hikari.HikariDataSource()) {
            pool.setJdbcUrl(System.getenv("BILLING_TEST_JDBC_URL"));pool.setUsername("billing_test");
            pool.setPassword(System.getenv("BILLING_TEST_DB_PASSWORD"));pool.setMaximumPoolSize(1);
            pool.setMinimumIdle(0);pool.setConnectionTimeout(1000);
            var pooled=new BillingStore(new JdbcTemplate(pool),new DataSourceTransactionManager(pool));
            for(int i=0;i<8;i++) enqueue(UUID.randomUUID());
            var worker=outboxWorker(pooled,kafka);var threads=Executors.newFixedThreadPool(8);
            try {
                var claims=worker.claimOutbox(8);assertThat(claims).hasSize(8);
                var pending=claims.stream().map(job->threads.submit(()->worker.publishOne(job))).toList();
                assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
                assertThat(sawTransaction.get()).isFalse();
                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                // A single-connection pool is still available while all 8 sends wait.
                try(var connection=pool.getConnection();var statement=connection.createStatement()) {
                    connection.setAutoCommit(false);
                    statement.executeQuery("SELECT id FROM billing.outbox FOR UPDATE NOWAIT").close();
                    connection.rollback();
                }
                brokerAck.complete(null);for(var future:pending) future.get(5,TimeUnit.SECONDS);
                assertThat(count("billing.outbox WHERE state='PUBLISHED'")).isEqualTo(8);
            } finally {brokerAck.complete(null);threads.shutdownNow();worker.close();}
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true}) @SuppressWarnings("unchecked")
    void lateCompletionCannotAcknowledgeOrResetNewOwnersClaim(boolean fail) throws Exception {
        KafkaTemplate<String,byte[]> kafka=org.mockito.Mockito.mock(KafkaTemplate.class);
        var ack=new CompletableFuture<org.springframework.kafka.support.SendResult<String,byte[]>>();
        var started=new CountDownLatch(1);
        org.mockito.Mockito.when(kafka.send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(byte[].class)))
            .thenAnswer(call->{started.countDown();return ack;});
        UUID payment=UUID.randomUUID();UUID first=enqueue(payment),second=enqueue(payment);
        var worker=outboxWorker(kafka);var thread=Executors.newSingleThreadExecutor();
        try {
            var old=worker.claimOutbox(1).get(0);var sending=thread.submit(()->worker.publishOne(old));
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            db.update("UPDATE billing.outbox SET lease_until=NOW()-INTERVAL '1 second',next_attempt_at=NOW()-INTERVAL '1 second' WHERE id=?",first);
            var current=worker.claimOutbox(1).get(0);
            if(fail) ack.completeExceptionally(new org.apache.kafka.common.errors.RecordTooLargeException("test only"));else ack.complete(null);
            sending.get(5,TimeUnit.SECONDS);
            assertThat(db.one("SELECT state,lease_token FROM billing.outbox WHERE id=?",first))
                .containsEntry("state","IN_FLIGHT").containsEntry("leaseToken",text(current,"leaseToken"));
            assertThat(worker.claimOutbox(8)).isEmpty();assertThat(count("billing.dead_letters")).isZero();
            acknowledge(worker,current);
            assertThat(id(worker.claimOutbox(8).get(0).get("id"))).isEqualTo(second);
        } finally {ack.complete(null);thread.shutdownNow();worker.close();}
    }

    @Test @SuppressWarnings("unchecked") void permanentFailureIsAuditedReleasesSuccessorAndSupportsReplay() {
        KafkaTemplate<String,byte[]> kafka=org.mockito.Mockito.mock(KafkaTemplate.class);
        org.mockito.Mockito.when(kafka.send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(byte[].class)))
            .thenReturn(CompletableFuture.failedFuture(new org.apache.kafka.common.errors.RecordTooLargeException("test only")));
        UUID payment=captured("CARD");var original=db.one("SELECT id,payload::text AS payload FROM billing.outbox WHERE aggregate_id=?",payment);
        UUID first=id(original.get("id")),second=enqueue(payment);var worker=outboxWorker(kafka);
        try {
            worker.publishOne(worker.claimOutbox(1).get(0));
            assertThat(db.one("SELECT state,lease_token,lease_until FROM billing.outbox WHERE id=?",first))
                .containsEntry("state","DEAD_LETTER").containsEntry("leaseToken",null).containsEntry("leaseUntil",null);
            var audit=db.one("SELECT * FROM billing.dead_letters WHERE source_outbox_id=?",first);
            assertThat(audit).containsEntry("state","OPEN").containsEntry("merchantId",MERCHANT.toString())
                .containsEntry("failureReason","RecordTooLargeException");
            assertThat((byte[])audit.get("rawPayload")).containsExactly(text(original,"payload").getBytes(StandardCharsets.UTF_8));
            var next=worker.claimOutbox(8);assertThat(next).hasSize(1);assertThat(id(next.get(0).get("id"))).isEqualTo(second);
            acknowledge(worker,next.get(0));
            var registry=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            try {
                new BillingMetrics(jdbc,registry);
                assertThat(registry.get("billing.outbox.pending").gauge().value()).isZero();
                assertThat(registry.get("billing.outbox.oldest.seconds").gauge().value()).isZero();
                assertThat(registry.get("billing.deadletters.open").gauge().value()).isEqualTo(1);
            } finally {registry.close();}
            UUID auditId=id(audit.get("id"));events.replay("operator",auditId);
            // If replay also fails, reopen the same audit record without multiplying cases.
            worker.publishOne(worker.claimOutbox(8).get(0));
            assertThat(count("billing.dead_letters")).isEqualTo(1);
            assertThat(db.one("SELECT state FROM billing.dead_letters WHERE id=?",auditId).get("state")).isEqualTo("OPEN");
            events.replay("operator",auditId);
            org.mockito.Mockito.when(kafka.send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture(null));
            var replay=worker.claimOutbox(8).get(0);
            assertThat(text(replay,"payload")).isEqualTo(text(original,"payload"));worker.publishOne(replay);
            assertThat(db.one("SELECT state FROM billing.dead_letters WHERE id=?",auditId).get("state")).isEqualTo("REPLAYED");
            assertThat(count("billing.journals WHERE intent_id='"+payment+"'")).isEqualTo(1);
        } finally {worker.close();}
    }

    @Test @SuppressWarnings("unchecked") void transientFailureIsQuarantinedAtAttemptLimit() {
        KafkaTemplate<String,byte[]> kafka=org.mockito.Mockito.mock(KafkaTemplate.class);
        org.mockito.Mockito.when(kafka.send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(byte[].class)))
            .thenReturn(CompletableFuture.failedFuture(new TimeoutException("test outage")));
        UUID payment=created("CARD"),first=enqueue(payment),second=enqueue(payment);
        db.update("UPDATE billing.outbox SET attempts=? WHERE id=?",BillingWorkers.MAX_OUTBOX_ATTEMPTS-1,first);
        var worker=outboxWorker(kafka);
        try {
            var claim=worker.claimOutbox(1).get(0);worker.publishOne(claim);worker.publishOne(claim);
            assertThat(db.one("SELECT state,attempts FROM billing.outbox WHERE id=?",first))
                .containsEntry("state","DEAD_LETTER").containsEntry("attempts",BillingWorkers.MAX_OUTBOX_ATTEMPTS);
            assertThat(count("billing.dead_letters")).isEqualTo(1);
            assertThat(id(worker.claimOutbox(8).get(0).get("id"))).isEqualTo(second);
        } finally {worker.close();}
    }

    @Test @SuppressWarnings("unchecked") void expiredFinalClaimIsQuarantinedWithoutAnotherSend() {
        KafkaTemplate<String,byte[]> kafka=org.mockito.Mockito.mock(KafkaTemplate.class);
        UUID payment=created("CARD"),first=enqueue(payment),second=enqueue(payment);
        db.update("UPDATE billing.outbox SET attempts=?,state='IN_FLIGHT',lease_token=?,lease_until=NOW()-INTERVAL '1 second' WHERE id=?",BillingWorkers.MAX_OUTBOX_ATTEMPTS,UUID.randomUUID(),first);
        var worker=outboxWorker(kafka);
        try {
            assertThat(worker.claimOutbox(8)).isEmpty();
            assertThat(db.one("SELECT state FROM billing.outbox WHERE id=?",first).get("state")).isEqualTo("DEAD_LETTER");
            assertThat(id(worker.claimOutbox(8).get(0).get("id"))).isEqualTo(second);
            org.mockito.Mockito.verifyNoInteractions(kafka);
        } finally {worker.close();}
    }

    @Test @SuppressWarnings("unchecked") void failedAuditRollsBackQuarantineAndKeepsSuccessorBlocked() {
        KafkaTemplate<String,byte[]> kafka=org.mockito.Mockito.mock(KafkaTemplate.class);
        org.mockito.Mockito.when(kafka.send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(byte[].class)))
            .thenReturn(CompletableFuture.failedFuture(new org.apache.kafka.common.errors.RecordTooLargeException("test only")));
        UUID payment=created("CARD"),first=enqueue(payment);enqueue(payment);var worker=outboxWorker(kafka);
        jdbc.execute("CREATE FUNCTION billing.test_reject_deadletter() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected deadletter audit failure'; END $$");
        jdbc.execute("CREATE TRIGGER billing_test_reject_deadletter BEFORE INSERT ON billing.dead_letters FOR EACH ROW EXECUTE FUNCTION billing.test_reject_deadletter()");
        try {
            var claim=worker.claimOutbox(1).get(0);
            assertThatThrownBy(()->worker.publishOne(claim)).isInstanceOf(RuntimeException.class);
            assertThat(db.one("SELECT state FROM billing.outbox WHERE id=?",first).get("state")).isEqualTo("IN_FLIGHT");
            assertThat(count("billing.dead_letters")).isZero();assertThat(worker.claimOutbox(8)).isEmpty();
        } finally {
            jdbc.execute("DROP TRIGGER billing_test_reject_deadletter ON billing.dead_letters");
            jdbc.execute("DROP FUNCTION billing.test_reject_deadletter()");worker.close();
        }
    }

    @Test void concurrentInsertCannotExposeLaterEventBeforeEarlierTransactionCommits() throws Exception {
        UUID payment=UUID.randomUUID();var inserted=new CountDownLatch(1);var commit=new CountDownLatch(1);
        var threads=Executors.newFixedThreadPool(2);var worker=outboxWorker(null);
        try {
            var first=threads.submit(()->db.tx(()->{
                UUID event=enqueue(payment);inserted.countDown();
                try {if(!commit.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test commit timed out");}
                catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
                return event;
            }));
            assertThat(inserted.await(5,TimeUnit.SECONDS)).isTrue();
            var second=threads.submit(()->enqueue(payment));
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(()->
                jdbc.queryForObject("SELECT COUNT(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted",Long.class)>0);
            assertThat(worker.claimOutbox(8)).isEmpty();assertThat(second.isDone()).isFalse();
            commit.countDown();UUID firstId=first.get(5,TimeUnit.SECONDS),secondId=second.get(5,TimeUnit.SECONDS);
            var head=worker.claimOutbox(8).get(0);assertThat(id(head.get("id"))).isEqualTo(firstId);
            acknowledge(worker,head);
            assertThat(id(worker.claimOutbox(8).get(0).get("id"))).isEqualTo(secondId);
        } finally {commit.countDown();threads.shutdownNow();worker.close();}
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({
        "UNKNOWN,16,true","UNKNOWN,14,false","SUBMITTING,16,true","SUBMITTING,14,false",
        "REQUIRES_ACTION,1441,true","REQUIRES_ACTION,1439,false",
        "PROCESSING,10081,true","PROCESSING,10079,false",
        "CREATED,20000,false","CAPTURED,20000,false","FAILED,20000,false","RETURNED,20000,false","REFUNDED,20000,false"})
    void stuckGaugeUsesStateSpecificDeadlines(String status,int ageMinutes,boolean overdue) {
        UUID payment=created("CARD");
        db.update("UPDATE billing.intents SET status=?,created_at=NOW()-(? * INTERVAL '1 minute'),updated_at=NOW() WHERE id=?",status,ageMinutes,payment);
        var registry=new io.micrometer.core.instrument.simple.SimpleMeterRegistry(); try {
            new BillingMetrics(jdbc,registry);
            assertThat(registry.get("billing.payments.stuck").gauge().value()).isEqualTo(overdue?1.0:0.0);
        } finally {registry.close();}
    }

    BillingService service(BillingProvider adapter, UUID merchant) {
        return new BillingService(db, adapter, new MockEnvironment(), true, "http://localhost:4200", merchant.toString());
    }
    void account(UUID account, UUID merchant, String name) {
        db.update("INSERT INTO billing.accounts(id,merchant_id,name,external_reference) VALUES(?,?,?,?)", account, merchant, name, name);
    }
    void membership(UUID account, String user, String role) { db.update("INSERT INTO billing.memberships(account_id,user_id,role) VALUES(?,?,?)", account, user, role); }
    void staff(UUID merchant, String user, String role) { db.update("INSERT INTO billing.staff(merchant_id,user_id,role) VALUES(?,?,?)", merchant, user, role); }
    void invoice(UUID invoice, UUID account, long amount) {
        db.update("INSERT INTO billing.invoices(id,account_id,number,currency,total_minor,due_date) VALUES(?,?,?,'USD',?,CURRENT_DATE)", invoice, account, invoice.toString(), amount);
    }
    UUID created(String method) { return id(payments.create(PAYER, ACCOUNT, key(), List.of(INVOICE), method).get("id")); }
    UUID submitted(String method) { UUID intent = created(method); payments.submit(PAYER, intent); return intent; }
    UUID captured(String method) { UUID intent = submitted(method); payments.simulate(PAYER, intent, "CAPTURED"); return intent; }
    UUID requestedRefund(UUID intent, long minor) { return id(refunds.request(FINANCE, intent, key(), minor, "Customer requested adjustment").get("id")); }
    String providerId(UUID intent) { return text(payments.raw(intent), "providerId"); }
    void due(UUID intent) { db.update("UPDATE billing.intents SET lease_until=NOW()-INTERVAL '1 second',next_recovery_at=NOW()-INTERVAL '1 second' WHERE id=?", intent); }
    static String key() { return UUID.randomUUID().toString(); }
    long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    void assertInvoice(UUID invoice, long paid, long reserved) {
        var row = db.one("SELECT paid_minor,reserved_minor FROM billing.invoices WHERE id=?", invoice);
        assertThat(amount(row, "paidMinor")).isEqualTo(paid); assertThat(amount(row, "reservedMinor")).isEqualTo(reserved);
    }
    static void assertProblem(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BillingProblem.class, ex -> assertThat(ex.status()).isEqualTo(status));
    }
    static <T> List<T> concurrently(int count, Callable<T> action) throws Exception { return concurrentlyIndexed(count, i -> action.call()); }
    static <T> List<T> concurrentlyIndexed(int count, Indexed<T> action) throws Exception {
        var pool = Executors.newFixedThreadPool(count); var gate = new CyclicBarrier(count);
        try {
            List<Future<T>> pending = new ArrayList<>();
            for (int i = 0; i < count; i++) { final int index = i; pending.add(pool.submit(() -> { gate.await(10, TimeUnit.SECONDS); return action.call(index); })); }
            List<T> results = new ArrayList<>(); for (Future<T> future : pending) results.add(future.get(30, TimeUnit.SECONDS)); return results;
        } finally { pool.shutdownNow(); }
    }
    @FunctionalInterface interface Indexed<T> { T call(int index) throws Exception; }
    static class DelegateProvider implements BillingProvider {
        final SimulatorBillingProvider delegate;
        DelegateProvider(SimulatorBillingProvider delegate) { this.delegate = delegate; }
        @Override public String name() { return delegate.name(); }
        @Override public ProviderResult submit(ProviderCommand command) { return delegate.submit(command); }
        @Override public ProviderResult lookup(String key, String id) { return delegate.lookup(key, id); }
        @Override public ProviderResult refund(RefundCommand command) { return delegate.refund(command); }
        @Override public ProviderResult lookupRefund(String key, String id) { return delegate.lookupRefund(key, id); }
    }
}
