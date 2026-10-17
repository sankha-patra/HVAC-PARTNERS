package com.invoicepayments.billing.provider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Explicit isolated PostgreSQL 17 only; no Spring application startup, service containers,
 * schema reset, or access to an application's configured datasource.
 */
@EnabledIfEnvironmentVariable(named = "BILLING_TEST_JDBC_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:15432/invoice_payments_test")
class SimulatorBillingProviderDatabaseTest {
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager transactions;
    SimulatorBillingProvider provider;
    String intentId;
    String key;

    @BeforeAll static void isolatedDatabaseOnly() throws Exception {
        String url = System.getenv("BILLING_TEST_JDBC_URL");
        assertThat(url).isEqualTo("jdbc:postgresql://127.0.0.1:15432/invoice_payments_test");
        assertThat(System.getenv("BILLING_TEST_DB_USER")).isEqualTo("billing_test");
        DriverManagerDataSource source = new DriverManagerDataSource(url, System.getenv("BILLING_TEST_DB_USER"),
                System.getenv("BILLING_TEST_DB_PASSWORD"));
        try (Connection connection = source.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
        }
        Flyway.configure().dataSource(source).schemas("billing").defaultSchema("billing")
                .locations("classpath:db/billing").cleanDisabled(true).load().migrate();
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
    }

    @BeforeEach void uniqueFixture() {
        provider = new SimulatorBillingProvider(jdbc, transactions);
        intentId = UUID.randomUUID().toString();
        key = "provider-test:" + intentId;
    }

    @AfterEach void deleteOnlyThisTestOperation() {
        jdbc.update("DELETE FROM billing.provider_operations WHERE intent_id=?::uuid", intentId);
    }

    @Test void concurrentSubmitCreatesExactlyOneDurableOperation() throws Exception {
        List<ProviderResult> results = concurrently(8, () -> provider.submit(command("CARD")));
        assertThat(results).hasSize(8).allMatch(r -> r.state().equals("PROCESSING"));
        assertThat(results.stream().map(ProviderResult::providerId).distinct()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM billing.provider_operations WHERE intent_id=?::uuid", Long.class, intentId)).isEqualTo(1L);
        var restarted = new SimulatorBillingProvider(jdbc, transactions);
        assertThat(restarted.lookup(key, results.get(0).providerId())).isEqualTo(results.get(0));
    }

    @Test void changedAmountCannotReuseAcceptedOperationKey() {
        provider.submit(command("CARD"));
        assertThatThrownBy(() -> provider.submit(new ProviderCommand(intentId, key, 12346, "USD", "CARD", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different operation");
        assertThat(jdbc.queryForObject("SELECT amount_minor FROM billing.provider_operations WHERE idempotency_key=?", Long.class, key))
                .isEqualTo(12345L);
    }

    @Test void providerAcceptanceSurvivesCallerTransactionRollback() {
        new TransactionTemplate(transactions).execute(tx -> {
            provider.submit(command("CARD"));
            tx.setRollbackOnly();
            return null;
        });
        assertThat(new SimulatorBillingProvider(jdbc, transactions).lookup(key, null).state()).isEqualTo("PROCESSING");
    }

    @Test void timeoutAfterAcceptancePersistsCaptureBeforeThrowingAndRestartRecovers() {
        ProviderResult submitted = provider.submit(command("CARD"));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(tx ->
                provider.simulateResult(intentId, "TIMEOUT_AFTER_ACCEPT"))).isInstanceOf(ProviderUncertainException.class);
        ProviderResult recovered = new SimulatorBillingProvider(jdbc, transactions).lookup(key, submitted.providerId());
        assertThat(recovered.state()).isEqualTo("CAPTURED");
        assertThat(provider.submit(command("CARD")).providerId()).isEqualTo(submitted.providerId());
        assertThat(provider.submit(command("CARD")).state()).isEqualTo("CAPTURED");
    }

    @Test void staleFailureCannotEraseCapturedPaymentAndOnlyAchCanReturn() {
        provider.submit(command("CARD"));
        provider.simulateResult(intentId, "CAPTURED");
        assertThatThrownBy(() -> provider.simulateResult(intentId, "FAILED")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.simulateResult(intentId, "RETURNED")).isInstanceOf(IllegalArgumentException.class);
        assertThat(provider.lookup(key, null).state()).isEqualTo("CAPTURED");
    }

    @Test void achReturnIsDurableAndCannotBeRecapturedByReplay() {
        provider.submit(command("ACH"));
        provider.simulateResult(intentId, "CAPTURED");
        provider.simulateResult(intentId, "RETURNED");
        assertThat(new SimulatorBillingProvider(jdbc, transactions).lookup(key, null).state()).isEqualTo("RETURNED");
        assertThat(provider.submit(command("ACH")).state()).isEqualTo("RETURNED");
        assertThatThrownBy(() -> provider.simulateResult(intentId, "CAPTURED")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void refundReplayIsStableAcrossRestartAndRejectsChangedFingerprint() {
        ProviderResult payment = captured();
        String refundId = UUID.randomUUID().toString();
        String refundKey = key + ":refund";
        RefundCommand command = new RefundCommand(refundId, intentId, payment.providerId(), refundKey, 500, "USD");
        ProviderResult result = provider.refund(command);
        assertThat(result.state()).isEqualTo("REFUNDED");
        var restarted = new SimulatorBillingProvider(jdbc, transactions);
        assertThat(restarted.refund(command)).isEqualTo(result);
        assertThat(restarted.lookupRefund(refundKey, result.providerId())).isEqualTo(result);
        assertThatThrownBy(() -> provider.refund(new RefundCommand(refundId, intentId, payment.providerId(), refundKey, 501, "USD")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(provider.lookup(refundKey, result.providerId()).state()).isEqualTo("UNKNOWN");
    }

    @Test void concurrentRefundsCannotExceedCapturedAmount() throws Exception {
        ProviderResult payment = captured();
        List<String> outcomes = concurrently(2, () -> {
            String refundId = UUID.randomUUID().toString();
            try {
                return provider.refund(new RefundCommand(refundId, intentId, payment.providerId(), key + ":" + refundId, 7000, "USD")).state();
            } catch (IllegalArgumentException ex) {
                return "REJECTED";
            }
        });
        assertThat(outcomes).containsExactlyInAnyOrder("REFUNDED", "REJECTED");
        assertThat(jdbc.queryForObject("SELECT SUM(amount_minor) FROM billing.provider_operations WHERE intent_id=?::uuid AND operation_kind='REFUND'",
                Long.class, intentId)).isEqualTo(7000L);
    }

    @Test void refundDoesNotConfusePaymentSimulationLookup() {
        ProviderResult payment = captured();
        String refundId = UUID.randomUUID().toString();
        provider.refund(new RefundCommand(refundId, intentId, payment.providerId(), key + ":refund", 500, "USD"));
        assertThat(provider.simulateResult(intentId, "CAPTURED").providerId()).isEqualTo(payment.providerId());
    }

    @Test void unknownOrContradictoryLookupNeverCreatesOperation() {
        ProviderResult payment = provider.submit(command("CARD"));
        assertThat(provider.lookup(key, "sim_pay_unrelated").state()).isEqualTo("UNKNOWN");
        assertThat(provider.lookup(key + ":missing", null).state()).isEqualTo("UNKNOWN");
        assertThat(provider.lookupRefund(key, payment.providerId()).state()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM billing.provider_operations WHERE intent_id=?::uuid", Long.class, intentId)).isEqualTo(1L);
    }

    ProviderResult captured() {
        provider.submit(command("CARD"));
        return provider.simulateResult(intentId, "CAPTURED");
    }
    ProviderCommand command(String method) { return new ProviderCommand(intentId, key, 12345, "USD", method, null); }

    static <T> List<T> concurrently(int count, Callable<T> action) throws Exception {
        var pool = Executors.newFixedThreadPool(count);
        var gate = new CyclicBarrier(count);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) futures.add(pool.submit(() -> { gate.await(10, TimeUnit.SECONDS); return action.call(); }));
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
