package com.invoicepayments.billing;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Starts the real billing HTTP application against only the explicitly allowlisted PostgreSQL 17
 * test database. Uses unique fixtures and targeted deletion; never truncates shared test tables.
 * Run serially after BillingFinancialDatabaseTest, whose independent fixtures use TRUNCATE.
 * No Kafka, Redis, discovery, background workers, or external payment provider is required.
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
class BillingHttpSecurityDatabaseTest {
    private static final String TEST_URL = "jdbc:postgresql://127.0.0.1:15432/invoice_payments_test";
    private static final String JWT_SECRET = "billing-http-test-only-strong-secret-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String INTERNAL_TOKEN = "billing-http-test-internal-token-only";

    @DynamicPropertySource
    static void isolatedDatabaseOnly(DynamicPropertyRegistry registry) {
        // Assert before Spring can initialize the datasource or run migrations.
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
    private UUID merchant;
    private UUID account;
    private UUID otherAccount;
    private UUID invoice;
    private UUID settlement;
    private String user;
    private String bearer;
    private String financeUser;
    private String reviewerUser;
    private String financeBearer;
    private String reviewerBearer;

    @BeforeEach
    void seedUniqueScope() throws Exception {
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
        merchant = UUID.randomUUID(); account = UUID.randomUUID(); otherAccount = UUID.randomUUID(); invoice = UUID.randomUUID();
        user = "http-security-" + UUID.randomUUID();
        bearer = token(user, "USER");
        financeUser = "finance-maker-" + UUID.randomUUID();
        reviewerUser = "finance-reviewer-" + UUID.randomUUID();
        financeBearer = token(financeUser, "USER");
        reviewerBearer = token(reviewerUser, "USER");
        jdbc.update("INSERT INTO billing.merchants(id,name) VALUES(?,?)", merchant, "HTTP security test merchant");
        jdbc.update("INSERT INTO billing.staff(merchant_id,user_id,role) VALUES(?,?,'FINANCE'),(?,?,'REFUND_APPROVER')",
            merchant, financeUser, merchant, reviewerUser);
        jdbc.update("INSERT INTO billing.accounts(id,merchant_id,name,external_reference) VALUES(?,?,?,?)",
            account, merchant, "Authorized account", "http-account-" + account);
        jdbc.update("INSERT INTO billing.accounts(id,merchant_id,name,external_reference) VALUES(?,?,?,?)",
            otherAccount, merchant, "Other account", "http-account-" + otherAccount);
        jdbc.update("INSERT INTO billing.memberships(account_id,user_id,role) VALUES(?,?,'PAYER')", account, user);
        jdbc.update("INSERT INTO billing.invoices(id,account_id,number,currency,total_minor,due_date) VALUES(?,?,?,'USD',12525,CURRENT_DATE)",
            invoice, account, "HTTP-" + invoice);
    }

    @AfterEach
    void deleteOnlyThisTestsFixtures() {
        // These scenarios deliberately never create a financial operation or immutable journal.
        if (settlement != null) jdbc.update("DELETE FROM billing.settlement_reports WHERE id=?", settlement);
        if (settlement != null) jdbc.update("DELETE FROM billing.reconciliation_imports WHERE id=?", settlement);
        if (invoice != null) jdbc.update("DELETE FROM billing.invoices WHERE id=?", invoice);
        if (merchant != null && financeUser != null) jdbc.update("DELETE FROM billing.staff WHERE merchant_id=? AND user_id=?", merchant, financeUser);
        if (merchant != null && reviewerUser != null) jdbc.update("DELETE FROM billing.staff WHERE merchant_id=? AND user_id=?", merchant, reviewerUser);
        if (account != null) jdbc.update("DELETE FROM billing.memberships WHERE account_id=? AND user_id=?", account, user);
        if (account != null) jdbc.update("DELETE FROM billing.accounts WHERE id=?", account);
        if (otherAccount != null) jdbc.update("DELETE FROM billing.accounts WHERE id=?", otherAccount);
        if (merchant != null) jdbc.update("DELETE FROM billing.merchants WHERE id=?", merchant);
    }

    @Test
    void anonymousBillingAccessReturnsUnauthorized() throws Exception {
        assertThat(send("GET", "/api/v1/billing/accounts", null).statusCode()).isEqualTo(401);
    }

    @Test
    void signedBearerIdentitySeesOnlyItsAccountMembership() throws Exception {
        var response = send("GET", "/api/v1/billing/accounts", null, "Authorization", "Bearer " + bearer);
        assertThat(response.statusCode()).isEqualTo(200);
        JSONArray accounts = new JSONArray(response.body());
        assertThat(accounts.length()).isEqualTo(1);
        assertThat(accounts.getJSONObject(0).getString("id")).isEqualTo(account.toString());
        assertThat(send("GET", "/api/v1/billing/accounts/" + account + "/invoices", null,
            "Authorization", "Bearer " + bearer).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/v1/billing/accounts/" + otherAccount + "/invoices", null,
            "Authorization", "Bearer " + bearer).statusCode()).isEqualTo(404);
    }

    @Test
    void internalServiceTokenCannotActAsACustomer() throws Exception {
        assertThat(send("GET", "/api/v1/billing/accounts", null,
            "X-Internal-Token", INTERNAL_TOKEN).statusCode()).isEqualTo(401);
    }

    @Test
    void cookieAuthenticationRequiresMatchingXsrfCookieAndHeaderForCommands() throws Exception {
        var read = send("GET", "/api/v1/billing/accounts", null, "Cookie", "IP_AUTH=" + bearer);
        assertThat(read.statusCode()).isEqualTo(200);
        String xsrf = read.headers().allValues("Set-Cookie").stream()
            .filter(value -> value.startsWith("XSRF-TOKEN="))
            .map(value -> value.substring("XSRF-TOKEN=".length()).split(";", 2)[0])
            .findFirst().orElseThrow(() -> new AssertionError("GET must issue the browser XSRF cookie"));
        String cookie = "IP_AUTH=" + bearer + "; XSRF-TOKEN=" + xsrf;
        var anotherRead = send("GET", "/api/v1/billing/accounts", null, "Cookie", cookie);
        assertThat(anotherRead.statusCode()).isEqualTo(200);
        assertThat(anotherRead.headers().allValues("Set-Cookie"))
            .noneMatch(value -> value.startsWith("XSRF-TOKEN=") && value.contains("Max-Age=0"));
        String path = "/api/v1/billing/accounts/" + UUID.randomUUID() + "/intents";
        String body = new JSONObject().put("invoiceIds", new JSONArray().put(invoice.toString())).put("method", "CARD").toString();
        String key = UUID.randomUUID().toString();
        assertThat(send("POST", path, body, "Cookie", cookie, "Idempotency-Key", key).statusCode()).isEqualTo(403);
        assertThat(send("POST", path, body, "Cookie", cookie, "X-XSRF-TOKEN", "not-the-cookie", "Idempotency-Key", key).statusCode()).isEqualTo(403);
        // Unknown account gives 404 only after the real CSRF/authentication layers accept the request.
        assertThat(send("POST", path, body, "Cookie", cookie, "X-XSRF-TOKEN", xsrf, "Idempotency-Key", key).statusCode()).isEqualTo(404);
    }

    @Test
    void caseActionsAreAuthenticatedMerchantScopedAndCsrfProtected() throws Exception {
        String path = "/api/v1/billing/finance/cases/" + UUID.randomUUID() + "/close";
        String body = new JSONObject().put("note", "Reviewed the provider dashboard").toString();
        assertThat(send("POST", path, body).statusCode()).isEqualTo(401);
        // Unknown and out-of-scope cases are indistinguishable: both are 404, for staff and customers.
        assertThat(send("POST", path, body, "Authorization", "Bearer " + financeBearer).statusCode()).isEqualTo(404);
        assertThat(send("POST", path, body, "Authorization", "Bearer " + bearer).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/api/v1/billing/finance/cases", null, "Authorization", "Bearer " + bearer).statusCode()).isEqualTo(403);
        var read = send("GET", "/api/v1/billing/finance/cases", null, "Cookie", "IP_AUTH=" + financeBearer);
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(send("POST", path, body, "Cookie", "IP_AUTH=" + financeBearer).statusCode()).isEqualTo(403);
    }

    @Test
    void refusedFinanceAccessIsAuditedEvenThoughTheActionRolledBack() throws Exception {
        assertThat(send("GET", "/api/v1/billing/finance/cases", null, "Authorization", "Bearer " + bearer).statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForList("SELECT method,path,code FROM billing.denied_actions WHERE actor=?", user)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("method", "GET").containsEntry("path", "/api/v1/billing/finance/cases")
                .containsEntry("code", "FORBIDDEN"));
        assertThatThrownBy(() -> jdbc.update("DELETE FROM billing.denied_actions WHERE actor=?", user))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void hostileBrowserOriginIsRejectedDespiteAValidUserToken() throws Exception {
        assertThat(send("GET", "/api/v1/billing/accounts", null, "Authorization", "Bearer " + bearer,
            "Origin", "https://evil.example").statusCode()).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = { "1.5", "\"100\"" })
    void rawJsonCannotTruncateFractionalOrCoerceStringMinorUnits(String rawAmount) throws Exception {
        var response = send("POST", "/api/v1/billing/finance/intents/" + UUID.randomUUID() + "/refunds",
            "{\"amountMinor\":" + rawAmount + ",\"reason\":\"Reject noninteger money\"}",
            "Authorization", "Bearer " + bearer, "Idempotency-Key", UUID.randomUUID().toString());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(new JSONObject(response.body()).getString("code")).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void oversizedBillingBodyIsRejectedBeforeBusinessBinding() throws Exception {
        var response = send("POST", "/api/v1/billing/accounts/" + account + "/intents",
            "{\"padding\":\"" + "x".repeat(262145) + "\"}",
            "Authorization", "Bearer " + bearer, "Idempotency-Key", UUID.randomUUID().toString());
        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    void billingApplicationDoesNotExposeTheLegacyPaymentRoutes() throws Exception {
        assertThat(send("GET", "/api/v1/payments/not-a-real-provider-order/status", null,
            "Authorization", "Bearer " + bearer).statusCode()).isEqualTo(403);
    }

    @Test
    void financeSettlementReadIsMerchantScopedAndEnforcesSeparateBankReviewer() throws Exception {
        var financeMerchants = send("GET", "/api/v1/billing/finance/merchants", null,
            "Authorization", "Bearer " + financeBearer);
        assertThat(financeMerchants.statusCode()).isEqualTo(200);
        JSONArray financeScopes = new JSONArray(financeMerchants.body());
        assertThat(financeScopes.length()).isEqualTo(1);
        assertThat(financeScopes.getJSONObject(0).getString("id")).isEqualTo(merchant.toString());
        assertThat(financeScopes.getJSONObject(0).getBoolean("canImport")).isTrue();
        assertThat(financeScopes.getJSONObject(0).getBoolean("canConfirm")).isFalse();

        var reviewerMerchants = send("GET", "/api/v1/billing/finance/merchants", null,
            "Authorization", "Bearer " + reviewerBearer);
        assertThat(reviewerMerchants.statusCode()).isEqualTo(200);
        JSONObject reviewerScope = new JSONArray(reviewerMerchants.body()).getJSONObject(0);
        assertThat(reviewerScope.getBoolean("canImport")).isFalse();
        assertThat(reviewerScope.getBoolean("canConfirm")).isTrue();

        settlement = UUID.randomUUID();
        String evidence = new JSONObject().put("grossMinor", 10000).put("feeMinor", 300).put("netMinor", 9700)
            .put("bankReference", "ACH/Ref-Case-01").toString();
        jdbc.update("INSERT INTO billing.reconciliation_imports(id,merchant_id,external_id,source,evidence,evidence_hash,created_by,state) " +
            "VALUES(?,?,?,'PROVIDER',?::jsonb,?,?,'EXCEPTION')", settlement, merchant, "HTTP-report-" + settlement,
            evidence, "a".repeat(64), financeUser);
        jdbc.update("INSERT INTO billing.settlement_reports(id,merchant_id,external_id,source,gross_minor,fee_minor,net_minor,bank_reference,created_by,status) " +
            "VALUES(?,?,?,'PROVIDER',10000,300,9700,'ACH/Ref-Case-01',?,'EXCEPTION')",
            settlement, merchant, "HTTP-report-" + settlement, financeUser);

        var financeReports = send("GET", "/api/v1/billing/finance/settlements", null,
            "Authorization", "Bearer " + financeBearer);
        assertThat(financeReports.statusCode()).isEqualTo(200);
        JSONArray financeRows = new JSONArray(financeReports.body());
        assertThat(financeRows.length()).isEqualTo(1);
        JSONObject report = financeRows.getJSONObject(0);
        assertThat(report.getString("merchantId")).isEqualTo(merchant.toString());
        assertThat(report.getString("externalId")).isEqualTo("HTTP-report-" + settlement);
        assertThat(report.getLong("grossMinor")).isEqualTo(10000);
        assertThat(report.getLong("feeMinor")).isEqualTo(300);
        assertThat(report.getLong("netMinor")).isEqualTo(9700);
        assertThat(report.getString("bankReference")).isEqualTo("ACH/Ref-Case-01");
        assertThat(report.getJSONArray("paymentIds").length()).isZero();
        assertThat(report.getBoolean("canConfirm")).isFalse();

        var reviewerReports = send("GET", "/api/v1/billing/finance/settlements", null,
            "Authorization", "Bearer " + reviewerBearer);
        assertThat(reviewerReports.statusCode()).isEqualTo(200);
        assertThat(new JSONArray(reviewerReports.body()).getJSONObject(0).getBoolean("canConfirm")).isTrue();
        assertThat(send("GET", "/api/v1/billing/finance/settlements", null,
            "Authorization", "Bearer " + bearer).statusCode()).isEqualTo(403);
    }

    @Test
    void tenantHeadersAndLegacyAdminJwtCannotCreateBillingPrivileges() throws Exception {
        String administratorToken = token(user, "ADMIN");
        assertThat(send("GET", "/api/v1/billing/accounts/" + otherAccount + "/invoices", null,
            "Authorization", "Bearer " + administratorToken, "X-Tenant-ID", otherAccount.toString()).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/api/v1/billing/finance/refunds", null,
            "Authorization", "Bearer " + administratorToken, "X-Tenant-ID", merchant.toString()).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/api/v1/billing/accounts/" + otherAccount + "/intents",
            new JSONObject().put("invoiceIds", new JSONArray().put(invoice.toString())).put("method", "CARD").toString(),
            "Authorization", "Bearer " + administratorToken, "X-Tenant-ID", otherAccount.toString(),
            "Idempotency-Key", UUID.randomUUID().toString()).statusCode()).isEqualTo(404);
    }

    private String token(String subject, String role) {
        return Jwts.builder().subject(subject).claim("role", role)
            .issuedAt(Date.from(Instant.now().minusSeconds(5))).expiration(Date.from(Instant.now().plusSeconds(300)))
            .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10)).header("Accept", "application/json");
        for (int index = 0; index < headers.length; index += 2) request.header(headers[index], headers[index + 1]);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
