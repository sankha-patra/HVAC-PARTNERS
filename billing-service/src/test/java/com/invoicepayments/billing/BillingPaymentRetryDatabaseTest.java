package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import org.assertj.core.api.ThrowableAssert;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import java.util.concurrent.*;

import static com.invoicepayments.billing.BillingStore.*;
import static org.assertj.core.api.Assertions.*;

/**
 * A payer tries again after a payment failed for certain. The retry is a new payment with its own key, Stripe
 * operation and money records, linked to the failed one and shown under the same payment reference.
 * Real PostgreSQL on the allowlisted throwaway database only; no Spring app, Kafka or payment provider.
 */
@EnabledIfEnvironmentVariable(named = "BILLING_TEST_JDBC_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:15432/invoice_payments_test")
class BillingPaymentRetryDatabaseTest {
    static final UUID MERCHANT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final UUID OTHER_MERCHANT = UUID.fromString("10000000-0000-0000-0000-000000000002");
    static final UUID ACCOUNT = UUID.fromString("20000000-0000-0000-0000-000000000001");
    static final UUID SECOND_ACCOUNT = UUID.fromString("20000000-0000-0000-0000-000000000002");
    static final UUID OTHER_ACCOUNT = UUID.fromString("20000000-0000-0000-0000-000000000003");
    static final UUID INVOICE = UUID.fromString("30000000-0000-0000-0000-000000000001");
    static final UUID INVOICE_TWO = UUID.fromString("30000000-0000-0000-0000-000000000002");
    static final UUID SECOND_INVOICE = UUID.fromString("30000000-0000-0000-0000-000000000003");
    static final String PAYER = "payer-one", SECOND_PAYER = "payer-two", OTHER_PAYER = "payer-other";
    static final String VIEWER = "read-admin", FINANCE = "finance-maker";
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager transactions;
    BillingStore db;
    SimulatorBillingProvider provider;
    BillingService payments;
    BillingRecovery recovery;
    BillingPaymentRetry retries;

    @BeforeAll static void migrateOnlyIsolatedDatabase() {
        String url = System.getenv("BILLING_TEST_JDBC_URL");
        assertThat(url).isEqualTo("jdbc:postgresql://127.0.0.1:15432/invoice_payments_test");
        assertThat(System.getenv("BILLING_TEST_DB_USER")).isEqualTo("billing_test");
        var source = new DriverManagerDataSource(url, System.getenv("BILLING_TEST_DB_USER"), System.getenv("BILLING_TEST_DB_PASSWORD"));
        Flyway.configure().dataSource(source).schemas("billing").defaultSchema("billing")
                .locations("classpath:db/billing").cleanDisabled(true).load().migrate();
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
    }

    @BeforeEach void cleanAndSeedDedicatedDatabase() {
        jdbc.execute("BEGIN;SET LOCAL billing.maintenance='truncate';TRUNCATE billing.merchants,billing.provider_operations,billing.outbox,billing.consumer_inbox RESTART IDENTITY CASCADE;COMMIT");
        db = new BillingStore(jdbc);
        provider = com.invoicepayments.billing.BillingTestWiring.simulator(jdbc, transactions);
        payments = BillingTestWiring.payments(db, provider, new MockEnvironment(), true, "http://localhost:4200", MERCHANT.toString());
        var refunds = BillingTestWiring.refunds(db, payments, provider);
        recovery = BillingTestWiring.recovery(db, payments, refunds, provider);
        retries = BillingTestWiring.retry(db, payments);
        db.update("INSERT INTO billing.merchants(id,name) VALUES(?,'Merchant one'),(?,'Merchant other')", MERCHANT, OTHER_MERCHANT);
        account(ACCOUNT, MERCHANT, "customer-one"); account(SECOND_ACCOUNT, MERCHANT, "customer-two");
        account(OTHER_ACCOUNT, OTHER_MERCHANT, "customer-other");
        membership(ACCOUNT, PAYER, "PAYER"); membership(SECOND_ACCOUNT, SECOND_PAYER, "PAYER");
        membership(OTHER_ACCOUNT, OTHER_PAYER, "PAYER"); membership(ACCOUNT, VIEWER, "ACCOUNT_ADMIN");
        db.update("INSERT INTO billing.staff(merchant_id,user_id,role) VALUES(?,?,'FINANCE')", MERCHANT, FINANCE);
        invoice(INVOICE, ACCOUNT, 10000); invoice(INVOICE_TWO, ACCOUNT, 20000);
    }

    // The payer's card is declined and Stripe confirms the failure. Trying again makes a new payment for the same
    // invoices and amount, with its own key and Stripe operation, shown under the payment reference the payer
    // already has. The failed payment and its records are left exactly as they were.
    @Test void aRetryAfterAConfirmedFailureKeepsThePaymentReference() {
        UUID first = failed("CARD");
        var before = db.one("SELECT status,amount_minor,idempotency_key,provider_id,updated_at FROM billing.intents WHERE id=?", first);
        var firstAttempt = db.one("SELECT operation_key,status FROM billing.attempts WHERE intent_id=?", first);

        var retry = retries.retry(PAYER, first, key(), "CARD");
        UUID second = id(retry.get("id"));
        assertThat(second).isNotEqualTo(first);
        assertThat(retry.get("paymentReference")).isEqualTo(first.toString());
        assertThat(retry.get("retryOf")).isEqualTo(first.toString());
        assertThat(retry.get("status")).as("the payer still presses Pay").isEqualTo("CREATED");
        assertThat(count("SELECT COUNT(*) FROM billing.attempts WHERE intent_id=?", second)).as("nothing sent").isZero();
        assertThat(retry.get("method")).isEqualTo("CARD");
        assertThat(amount(retry, "amountMinor")).isEqualTo(10000);
        assertInvoice(INVOICE, 0, 10000);
        assertThat(allocations(second)).containsExactly(INVOICE);

        assertThat(db.one("SELECT status,amount_minor,idempotency_key,provider_id,updated_at FROM billing.intents WHERE id=?", first))
            .as("the failed payment is unchanged").isEqualTo(before);
        assertThat(db.one("SELECT operation_key,status FROM billing.attempts WHERE intent_id=?", first)).isEqualTo(firstAttempt);
        assertThat(reference(first)).isEqualTo(first);

        payments.submit(PAYER, second);
        var secondAttempt = db.one("SELECT operation_key FROM billing.attempts WHERE intent_id=?", second);
        assertThat(secondAttempt.get("operationKey")).as("its own Stripe operation").isNotEqualTo(firstAttempt.get("operationKey"));
        assertThat(count("SELECT COUNT(*) FROM billing.journals")).isZero();
    }

    @Test void everyNewPaymentStartsAsItsOwnReference() {
        UUID pay = id(payments.create(PAYER, ACCOUNT, key(), List.of(INVOICE), "CARD").get("id"));
        assertThat(reference(pay)).isEqualTo(pay);
        assertThat(db.one("SELECT retry_of FROM billing.intents WHERE id=?", pay).get("retryOf")).isNull();
        assertThat(count("SELECT COUNT(*) FROM billing.intents WHERE payment_reference IS NULL")).isZero();
    }

    @Test void theSameRetryKeyReturnsTheSameRetry() {
        UUID first = failed("CARD");
        String key = key();
        var once = retries.retry(PAYER, first, key, "CARD");
        var again = retries.retry(PAYER, first, key, "CARD");
        assertThat(again.get("id")).isEqualTo(once.get("id"));
        assertThat(count("SELECT COUNT(*) FROM billing.intents")).isEqualTo(2);
        assertInvoice(INVOICE, 0, 10000);
        assertProblem(409, "IDEMPOTENCY_CONFLICT", () -> retries.retry(PAYER, first, key, "ACH"));
    }

    // Card or bank is always the payer's explicit choice; nothing is assumed from the failed payment.
    @Test void thePaymentMethodMustBeChosen() {
        UUID first = failed("CARD");
        assertProblem(400, "INVALID_REQUEST", () -> retries.retry(PAYER, first, key(), null));
        assertThat(count("SELECT COUNT(*) FROM billing.intents")).isEqualTo(1);
        assertInvoice(INVOICE, 0, 0);
    }

    @Test void aKeyAlreadyUsedForAnotherPaymentIsRefused() {
        UUID first = failed("CARD");
        String used = key();
        payments.create(PAYER, ACCOUNT, used, List.of(INVOICE_TWO), "CARD");
        assertProblem(409, "IDEMPOTENCY_CONFLICT", () -> retries.retry(PAYER, first, used, "CARD"));
        assertThat(count("SELECT COUNT(*) FROM billing.intents")).isEqualTo(2);
        assertInvoice(INVOICE, 0, 0);
    }

    // Two "Try again" requests at once with different keys, as from two browser tabs: one new payment. The other is
    // told the payment was already tried again, never answered with a payment it did not ask for.
    @Test void twoRetriesAtOnceMakeOneNewPayment() throws Exception {
        UUID first = failed("CARD");
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CyclicBarrier(2);
        try {
            List<Future<Object>> clicks = new ArrayList<>();
            for (int i = 0; i < 2; i++) clicks.add(pool.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                try { return retries.retry(PAYER, first, key(), "CARD").get("status"); }
                catch (BillingProblem refused) { return refused.code(); }
            }));
            List<Object> shown = new ArrayList<>();
            for (var click : clicks) shown.add(click.get(20, TimeUnit.SECONDS));
            assertThat(shown).containsExactlyInAnyOrder("CREATED", "ALREADY_RETRIED");
        } finally { pool.shutdownNow(); }
        assertThat(count("SELECT COUNT(*) FROM billing.intents WHERE retry_of IS NOT NULL")).isEqualTo(1);
        assertInvoice(INVOICE, 0, 10000);
    }

    // Only a failure is retried. A payment not yet sent, still being checked, or received is refused, and nothing
    // is created or reserved.
    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "SUBMITTING", "REQUIRES_ACTION", "PROCESSING", "UNKNOWN", "CANCELLED", "CAPTURED", "RETURNED", "REFUNDED"})
    void onlyAFailedPaymentCanBeRetried(String status) {
        UUID pay = id(payments.create(PAYER, ACCOUNT, key(), List.of(INVOICE), "CARD").get("id"));
        db.update("UPDATE billing.intents SET status=? WHERE id=?", status, pay);
        assertProblem(409, "RETRY_NOT_ALLOWED", () -> retries.retry(PAYER, pay, key(), "CARD"));
        assertThat(count("SELECT COUNT(*) FROM billing.intents")).isEqualTo(1);
    }

    @Test void aCapturedPaymentIsNeverRetried() {
        UUID pay = id(payments.create(PAYER, ACCOUNT, key(), List.of(INVOICE), "CARD").get("id"));
        payments.submit(PAYER, pay);
        provider.simulateResult(pay.toString(), "CAPTURED");
        due(pay); recovery.recoverPayment(recovery.claimPayments(1).get(0));
        assertThat(status(pay)).isEqualTo("CAPTURED");
        assertProblem(409, "RETRY_NOT_ALLOWED", () -> retries.retry(PAYER, pay, key(), "CARD"));
        assertInvoice(INVOICE, 10000, 0);
    }

    // Carrier finance released this payment because Stripe's search found nothing, not because Stripe confirmed a
    // failure. A late capture is still possible, so it is not retried under the same reference.
    @ParameterizedTest
    @ValueSource(strings = {"RELEASED_NO_PROVIDER_OPERATION", "RELEASED_EXTERNAL_CONFIRMATION"})
    void aPaymentReleasedWithoutStripeConfirmationIsNotRetried(String resolution) {
        UUID pay = failed("CARD");
        db.update("""
            INSERT INTO billing.cases(id,merchant_id,intent_id,reason,reference,status,resolution,resolved_at,resolved_by)
            VALUES(?,?,?,'PAYMENT_UNRESOLVED',?,'RESOLVED',?,NOW(),'finance-checker')
            """, UUID.randomUUID(), MERCHANT, pay, pay.toString(), resolution);
        assertProblem(409, "RETRY_NOT_ALLOWED", () -> retries.retry(PAYER, pay, key(), "CARD"));
        assertThat(count("SELECT COUNT(*) FROM billing.intents")).isEqualTo(1);
        assertInvoice(INVOICE, 0, 0);
    }

    // The invoice is now reserved by another payment, or part of it was paid another way: the retry would not be
    // the same payment, so nothing is created and the payer starts a new one.
    @Test void aRetryIsRefusedWhenTheInvoicesNoLongerMatch() {
        UUID first = failed("CARD");
        db.update("UPDATE billing.invoices SET paid_minor=2500 WHERE id=?", INVOICE);
        assertProblem(409, "AMOUNT_CHANGED", () -> retries.retry(PAYER, first, key(), "CARD"));
        assertThat(count("SELECT COUNT(*) FROM billing.intents")).isEqualTo(1);
        assertInvoice(INVOICE, 2500, 0);

        db.update("UPDATE billing.invoices SET paid_minor=0 WHERE id=?", INVOICE);
        payments.create(PAYER, ACCOUNT, key(), List.of(INVOICE), "CARD");
        assertProblem(409, "INVOICE_UNAVAILABLE", () -> retries.retry(PAYER, first, key(), "CARD"));
        assertThat(count("SELECT COUNT(*) FROM billing.intents")).isEqualTo(2);
        assertInvoice(INVOICE, 0, 10000);
    }

    // Only the newest attempt can be retried; the reference stays the first payment's.
    @Test void retriesFormOneChainUnderTheFirstReference() {
        UUID first = failed("CARD");
        UUID second = id(retries.retry(PAYER, first, key(), "CARD").get("id"));
        failSubmitted(second);
        assertProblem(409, "ALREADY_RETRIED", () -> retries.retry(PAYER, first, key(), "CARD"));
        assertThat(count("SELECT COUNT(*) FROM billing.intents")).isEqualTo(2);

        var third = retries.retry(PAYER, second, key(), "CARD");
        assertThat(third.get("paymentReference")).isEqualTo(first.toString());
        assertThat(third.get("retryOf")).isEqualTo(second.toString());
        assertThat(count("SELECT COUNT(*) FROM billing.intents WHERE payment_reference=?", first)).isEqualTo(3);
    }

    @Test void theRetryMayUseTheOtherPaymentMethod() {
        UUID first = failed("CARD");
        var retry = retries.retry(PAYER, first, key(), "ACH");
        assertThat(retry.get("method")).isEqualTo("ACH");
        assertThat(retry.get("paymentReference")).isEqualTo(first.toString());
        assertProblem(400, "INVALID_REQUEST", () -> retries.retry(PAYER, failedOn(INVOICE_TWO), key(), "CHEQUE"));
    }

    // Only that account's payer may try again. Viewers, finance staff, other customers and another company's payer
    // are told the payment does not exist, before anything about it is revealed.
    @Test void retryReplayStillRequiresTheConfiguredMerchantsPayer() {
        UUID foreignInvoice=UUID.randomUUID();
        invoice(foreignInvoice,OTHER_ACCOUNT,10000);
        var otherPayments=BillingTestWiring.payments(db,provider,new MockEnvironment(),true,"http://localhost:4200",OTHER_MERCHANT.toString());
        var otherRetries=BillingTestWiring.retry(db,otherPayments);
        UUID first=id(otherPayments.create(OTHER_PAYER,OTHER_ACCOUNT,key(),List.of(foreignInvoice),"CARD").get("id"));
        otherPayments.submit(OTHER_PAYER,first);
        otherPayments.simulate(OTHER_PAYER,first,"FAILED");
        String retryKey=key();
        otherRetries.retry(OTHER_PAYER,first,retryKey,"CARD");
        assertProblem(404,"NOT_FOUND",()->retries.retry(OTHER_PAYER,first,retryKey,"CARD"));
    }

    @Test void onlyThePayerOfThatAccountCanRetry() {
        UUID first = failed("CARD");
        for (String user : List.of(VIEWER, FINANCE, SECOND_PAYER, OTHER_PAYER))
            assertProblem(404, "NOT_FOUND", () -> retries.retry(user, first, key(), "CARD"));
        db.update("UPDATE billing.intents SET status='PROCESSING' WHERE id=?", first);
        assertProblem(404, "NOT_FOUND", () -> retries.retry(VIEWER, first, key(), "CARD"));
        assertProblem(404, "NOT_FOUND", () -> retries.retry(PAYER, UUID.randomUUID(), key(), "CARD"));
        assertThat(count("SELECT COUNT(*) FROM billing.intents")).isEqualTo(1);
    }

    // Stripe should never collect on a payment it confirmed failed, but if it ever reports a capture for it after the
    // retry, that money is booked on the old payment as unapplied cash with its own case. The retry and the invoice
    // it holds are not touched.
    @Test void aLateCaptureOnTheFailedPaymentLeavesTheRetryAlone() {
        UUID first = failed("CARD");
        UUID second = id(retries.retry(PAYER, first, key(), "CARD").get("id"));
        String providerId = text(db.one("SELECT provider_id FROM billing.intents WHERE id=?", first), "providerId");
        payments.recordResult(first, null, new ProviderResult(providerId, "CAPTURED", null, "Stripe reported a capture"));
        assertThat(status(first)).isEqualTo("CAPTURED");
        assertThat(count("SELECT COUNT(*) FROM billing.cases WHERE reason='LATE_CAPTURE_UNAPPLIED' AND intent_id=?", first)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM billing.journals WHERE intent_id=?", second)).isZero();
        assertThat(status(second)).isEqualTo("CREATED");
        assertInvoice(INVOICE, 0, 10000);
        assertProblem(409, "ALREADY_RETRIED", () -> retries.retry(PAYER, first, key(), "CARD"));
        assertThat(count("SELECT COUNT(*) FROM billing.intents")).isEqualTo(2);
    }

    // Once an earlier attempt is known to be paid, the retry waiting for Pay is refused before anything is saved or
    // sent. Needs the submit check in BillingService (handed to Codex).
    @Test void aRetryIsNotSentOnceAnEarlierAttemptWasPaid() {
        UUID first = failed("CARD");
        UUID second = id(retries.retry(PAYER, first, key(), "CARD").get("id"));
        String providerId = text(db.one("SELECT provider_id FROM billing.intents WHERE id=?", first), "providerId");
        payments.recordResult(first, null, new ProviderResult(providerId, "CAPTURED", null, "Stripe reported a capture"));
        assertProblem(409, "ALREADY_PAID", () -> payments.submit(PAYER, second));
        assertThat(status(second)).isEqualTo("CREATED");
        assertThat(count("SELECT COUNT(*) FROM billing.attempts WHERE intent_id=?", second)).isZero();
    }

    // The payment view and history carry the reference and the link, and keep every attempt as its own row.
    // Needs the view/history lines in BillingService (handed to Codex).
    @Test void thePaymentViewAndHistoryShowTheReferenceAndEveryAttempt() {
        UUID first = failed("CARD");
        UUID second = id(retries.retry(PAYER, first, key(), "CARD").get("id"));
        var view = payments.get(PAYER, second);
        assertThat(view.get("paymentReference")).isEqualTo(first.toString());
        assertThat(view.get("retryOf")).isEqualTo(first.toString());
        var history = payments.history(PAYER, ACCOUNT);
        assertThat(history).extracting(row -> row.get("id")).containsExactlyInAnyOrder(first.toString(), second.toString());
        assertThat(history).extracting(row -> row.get("paymentReference")).containsOnly(first.toString());
    }

    // The database itself refuses a reference or link to another customer's payment, whatever the application does.
    @Test void aReferenceCannotPointAtAnotherAccountsPayment() {
        UUID mine = failed("CARD");
        invoice(SECOND_INVOICE, SECOND_ACCOUNT, 7500);
        UUID theirs = id(payments.create(SECOND_PAYER, SECOND_ACCOUNT, key(), List.of(SECOND_INVOICE), "CARD").get("id"));
        assertThatThrownBy(() -> db.update("UPDATE billing.intents SET retry_of=? WHERE id=?", mine, theirs))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("UPDATE billing.intents SET payment_reference=? WHERE id=?", mine, theirs))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    UUID failed(String method) { return failedOn(INVOICE, method); }
    UUID failedOn(UUID invoice) { return failedOn(invoice, "CARD"); }
    UUID failedOn(UUID invoice, String method) {
        UUID pay = id(payments.create(PAYER, ACCOUNT, key(), List.of(invoice), method).get("id"));
        payments.submit(PAYER, pay);
        failSubmitted(pay);
        return pay;
    }
    void failSubmitted(UUID pay) {
        if ("CREATED".equals(status(pay))) payments.submit(PAYER, pay);
        provider.simulateResult(pay.toString(), "FAILED");
        due(pay); recovery.recoverPayment(recovery.claimPayments(1).get(0));
        assertThat(status(pay)).isEqualTo("FAILED");
    }
    void due(UUID intent) { db.update("UPDATE billing.intents SET lease_until=NOW()-INTERVAL '1 second',next_recovery_at=NOW()-INTERVAL '1 second' WHERE id=?", intent); }
    String status(UUID intent) { return text(db.one("SELECT status FROM billing.intents WHERE id=?", intent), "status"); }
    UUID reference(UUID intent) { return id(db.one("SELECT payment_reference FROM billing.intents WHERE id=?", intent).get("paymentReference")); }
    List<UUID> allocations(UUID intent) {
        return db.rows("SELECT invoice_id FROM billing.allocations WHERE intent_id=? ORDER BY invoice_id", intent).stream().map(a -> id(a.get("invoiceId"))).toList();
    }
    long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    void assertInvoice(UUID invoice, long paid, long reserved) {
        var row = db.one("SELECT paid_minor,reserved_minor FROM billing.invoices WHERE id=?", invoice);
        assertThat(amount(row, "paidMinor")).isEqualTo(paid); assertThat(amount(row, "reservedMinor")).isEqualTo(reserved);
    }
    static void assertProblem(int status, String code, ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BillingProblem.class, problem -> {
            assertThat(problem.status()).isEqualTo(status);
            assertThat(problem.code()).isEqualTo(code);
        });
    }
    void account(UUID account, UUID merchant, String name) {
        db.update("INSERT INTO billing.accounts(id,merchant_id,name,external_reference) VALUES(?,?,?,?)", account, merchant, name, name);
    }
    void membership(UUID account, String user, String role) { db.update("INSERT INTO billing.memberships(account_id,user_id,role) VALUES(?,?,?)", account, user, role); }
    void invoice(UUID invoice, UUID account, long amount) {
        db.update("INSERT INTO billing.invoices(id,account_id,number,currency,total_minor,due_date) VALUES(?,?,?,'USD',?,CURRENT_DATE)", invoice, account, invoice.toString(), amount);
    }
    static String key() { return UUID.randomUUID().toString(); }
}
