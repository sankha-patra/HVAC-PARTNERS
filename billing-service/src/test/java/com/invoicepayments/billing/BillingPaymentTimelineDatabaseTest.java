package com.invoicepayments.billing;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real signed-JWT HTTP reads against the explicitly allowlisted PostgreSQL 17 test database.
 * Checks the payment response contract and tenant isolation independently of query-plan choice.
 * Run serially with the other database tests; unique fixtures are removed without truncation.
 * No provider operation, immutable journal, Kafka traffic or background worker is started.
 */
@EnabledIfEnvironmentVariable(named = "BILLING_TEST_JDBC_URL",
    matches = "jdbc:postgresql://127\\.0\\.0\\.1:15432/invoice_payments_test")
@SpringBootTest(classes = BillingApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "billing.workers-enabled=false", "billing.kafka-enabled=false", "billing.demo-enabled=false",
        "billing.erp-simulator-enabled=false", "billing.simulator-enabled=true", "billing.provider=SIMULATOR",
        "billing.public-url=http://localhost:4200", "billing.stripe.secret-key=", "billing.stripe.webhook-secret=",
        "management.server.port=0", "management.tracing.enabled=false", "management.health.redis.enabled=false",
        "spring.data.redis.repositories.enabled=false", "spring.cache.type=none",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=4", "spring.datasource.hikari.minimum-idle=1",
        "spring.datasource.hikari.connection-timeout=3000", "spring.flyway.clean-disabled=true",
        "app.jwt.cookie-secure=false",
        "app.jwt.secret=billing-http-test-only-strong-secret-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ",
        "app.internal.token=billing-http-test-internal-token-only"
    })
@ActiveProfiles("billing")
class BillingPaymentTimelineDatabaseTest {
    private static final String TEST_URL = "jdbc:postgresql://127.0.0.1:15432/invoice_payments_test";
    private static final String JWT_SECRET = "billing-http-test-only-strong-secret-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final Instant EVENT_TIME = Instant.parse("2026-01-01T00:00:00Z");

    @DynamicPropertySource
    static void isolatedDatabaseOnly(DynamicPropertyRegistry registry) {
        // Check before Spring creates a datasource or runs migrations.
        assertThat(System.getenv("BILLING_TEST_JDBC_URL")).isEqualTo(TEST_URL);
        assertThat(System.getenv("BILLING_TEST_DB_USER")).isEqualTo("billing_test");
        assertThat(System.getenv("BILLING_TEST_DB_PASSWORD")).isNotBlank();
        registry.add("spring.datasource.url", () -> TEST_URL);
        registry.add("spring.datasource.username", () -> "billing_test");
        registry.add("spring.datasource.password", () -> System.getenv("BILLING_TEST_DB_PASSWORD"));
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private Fixture authorized;
    private Fixture foreign;
    private String bearer;

    @BeforeEach
    void seedTwoTenantsWithInterleavedHistory() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT current_database(),current_user")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("invoice_payments_test");
                assertThat(result.getString(2)).isEqualTo("billing_test");
            }
        }
        authorized = fixture(12525L);
        foreign = fixture(8875L);
        seed(authorized);
        seed(foreign);
        bearer = token(authorized.user());
        for (int number = 1; number <= 120; number++) {
            // Identical timestamps make ID ordering, rather than timestamp ordering, observable.
            append(authorized.intent(), "authorized-event-" + number);
            append(foreign.intent(), "foreign-event-" + number);
        }
    }

    @AfterEach
    void deleteOnlyOwnedFixtures() {
        cleanup(authorized);
        cleanup(foreign);
    }

    @Test
    void applicationStatementsOutsideTransactionsHaveAQueryTimeout() {
        // Transactions carry their own deadline; this bounds every other statement of the configured app.
        assertThat(jdbc.getQueryTimeout()).isEqualTo(15);
    }

    @Test
    void statusReadKeepsLatestHundredEventsTenantScopedAndFinancialStateUnchanged() throws Exception {
        Map<String, List<String>> before = snapshot();
        assertThat(before.get("journals")).isEmpty();
        assertThat(before.get("providerOperations")).isEmpty();

        var response = get(authorized.intent(), bearer);
        assertThat(response.statusCode()).isEqualTo(200);
        JSONObject payment = new JSONObject(response.body());
        assertThat(payment.getString("id")).isEqualTo(authorized.intent().toString());
        assertThat(payment.getString("accountId")).isEqualTo(authorized.account().toString());
        assertThat(payment.getLong("amountMinor")).isEqualTo(authorized.amount());
        assertThat(payment.getLong("refundedMinor")).isZero();
        assertThat(payment.getString("currency")).isEqualTo("USD");
        assertThat(payment.getString("method")).isEqualTo("CARD");
        assertThat(payment.getString("status")).isEqualTo("CREATED");
        assertThat(payment.getString("provider")).isEqualTo("SIMULATOR");
        assertThat(payment.isNull("checkoutUrl")).isTrue();
        assertThat(payment.getBoolean("simulatorEnabled")).isTrue();

        JSONArray allocations = payment.getJSONArray("allocations");
        assertThat(allocations.length()).isEqualTo(1);
        JSONObject allocation = allocations.getJSONObject(0);
        assertThat(allocation.getString("invoiceId")).isEqualTo(authorized.invoice().toString());
        assertThat(allocation.getLong("amountMinor")).isEqualTo(authorized.amount());
        assertThat(allocation.getLong("reversedMinor")).isZero();
        assertThat(allocation.getString("state")).isEqualTo("RESERVED");

        JSONArray timeline = payment.getJSONArray("timeline");
        assertThat(timeline.length()).isEqualTo(100);
        for (int index = 0; index < 100; index++) {
            JSONObject event = timeline.getJSONObject(index);
            assertThat(event.getString("message")).isEqualTo("authorized-event-" + (120 - index));
            assertThat(event.getString("type")).isEqualTo("STATUS_RECORDED");
            assertThat(Instant.parse(event.getString("at"))).isEqualTo(EVENT_TIME);
        }

        var repeated = get(authorized.intent(), bearer);
        assertThat(repeated.statusCode()).isEqualTo(200);
        assertThat(new JSONObject(repeated.body()).toMap()).isEqualTo(payment.toMap());
        assertThat(get(foreign.intent(), bearer).statusCode()).isEqualTo(404);
        assertThat(get(authorized.intent(), null).statusCode()).isEqualTo(401);
        assertThat(snapshot()).isEqualTo(before);
    }

    private Fixture fixture(long amount) {
        return new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "timeline-reader-" + UUID.randomUUID(), amount);
    }

    private void seed(Fixture fixture) {
        jdbc.update("INSERT INTO billing.merchants(id,name) VALUES(?,?)", fixture.merchant(), "Timeline test merchant");
        jdbc.update("INSERT INTO billing.accounts(id,merchant_id,name,external_reference) VALUES(?,?,?,?)",
            fixture.account(), fixture.merchant(), "Timeline test account", "timeline-account-" + fixture.account());
        jdbc.update("INSERT INTO billing.memberships(account_id,user_id,role) VALUES(?,?,'PAYER')",
            fixture.account(), fixture.user());
        jdbc.update("INSERT INTO billing.invoices(id,account_id,number,currency,total_minor,reserved_minor,due_date) " +
            "VALUES(?,?,?,'USD',?,?,CURRENT_DATE)", fixture.invoice(), fixture.account(), "TIMELINE-" + fixture.invoice(),
            fixture.amount(), fixture.amount());
        jdbc.update("INSERT INTO billing.intents(id,account_id,idempotency_key,request_hash,amount_minor,currency,method,status,provider) " +
            "VALUES(?,?,?,?,?,'USD','CARD','CREATED','SIMULATOR')", fixture.intent(), fixture.account(),
            UUID.randomUUID().toString(), BillingStore.hash("CARD:" + List.of(fixture.invoice())), fixture.amount());
        jdbc.update("INSERT INTO billing.allocations(intent_id,invoice_id,amount_minor,state) VALUES(?,?,?,'RESERVED')",
            fixture.intent(), fixture.invoice(), fixture.amount());
    }

    private void append(UUID intent, String message) {
        jdbc.update("INSERT INTO billing.timeline(intent_id,type,message,created_at) VALUES(?,'STATUS_RECORDED',?,?)",
            intent, message, Timestamp.from(EVENT_TIME));
    }

    private Map<String, List<String>> snapshot() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("intents", rows("SELECT to_jsonb(r)::text FROM billing.intents r WHERE id IN (?,?) ORDER BY id"));
        result.put("allocations", rows("SELECT to_jsonb(r)::text FROM billing.allocations r WHERE intent_id IN (?,?) ORDER BY intent_id,invoice_id"));
        result.put("timeline", rows("SELECT to_jsonb(r)::text FROM billing.timeline r WHERE intent_id IN (?,?) ORDER BY id"));
        result.put("invoices", jdbc.queryForList("SELECT to_jsonb(r)::text FROM billing.invoices r WHERE id IN (?,?) ORDER BY id",
            String.class, authorized.invoice(), foreign.invoice()));
        result.put("attempts", rows("SELECT to_jsonb(r)::text FROM billing.attempts r WHERE intent_id IN (?,?) ORDER BY id"));
        result.put("providerOperations", rows("SELECT to_jsonb(r)::text FROM billing.provider_operations r WHERE intent_id IN (?,?) ORDER BY idempotency_key"));
        result.put("journals", rows("SELECT to_jsonb(r)::text FROM billing.journals r WHERE intent_id IN (?,?) ORDER BY id"));
        result.put("ledgerEntries", rows("SELECT to_jsonb(r)::text FROM billing.ledger_entries r JOIN billing.journals j ON j.id=r.journal_id " +
            "WHERE j.intent_id IN (?,?) ORDER BY r.journal_id,r.line"));
        result.put("outbox", rows("SELECT to_jsonb(r)::text FROM billing.outbox r WHERE aggregate_id IN (?,?) ORDER BY id"));
        result.put("cases", rows("SELECT to_jsonb(r)::text FROM billing.cases r WHERE intent_id IN (?,?) ORDER BY id"));
        result.put("refunds", rows("SELECT to_jsonb(r)::text FROM billing.refunds r WHERE intent_id IN (?,?) ORDER BY id"));
        result.put("erpPostings", rows("SELECT to_jsonb(r)::text FROM billing.erp_postings r WHERE intent_id IN (?,?) ORDER BY intent_id,event_id"));
        return result;
    }

    private List<String> rows(String sql) {
        return jdbc.queryForList(sql, String.class, authorized.intent(), foreign.intent());
    }

    private void cleanup(Fixture fixture) {
        if (fixture == null) return;
        // Never delete immutable journals to conceal an unexpected financial side effect.
        jdbc.update("DELETE FROM billing.timeline WHERE intent_id=?", fixture.intent());
        jdbc.update("DELETE FROM billing.allocations WHERE intent_id=?", fixture.intent());
        jdbc.update("DELETE FROM billing.intents WHERE id=?", fixture.intent());
        jdbc.update("DELETE FROM billing.invoices WHERE id=?", fixture.invoice());
        jdbc.update("DELETE FROM billing.memberships WHERE account_id=?", fixture.account());
        jdbc.update("DELETE FROM billing.accounts WHERE id=?", fixture.account());
        jdbc.update("DELETE FROM billing.merchants WHERE id=?", fixture.merchant());
    }

    private String token(String subject) {
        return Jwts.builder().subject(subject).claim("role", "USER")
            .issuedAt(Date.from(Instant.now().minusSeconds(5))).expiration(Date.from(Instant.now().plusSeconds(300)))
            .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private HttpResponse<String> get(UUID intent, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/billing/intents/" + intent))
            .timeout(Duration.ofSeconds(10)).header("Accept", "application/json").GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private record Fixture(UUID merchant, UUID account, UUID invoice, UUID intent, String user, long amount) {}
}
