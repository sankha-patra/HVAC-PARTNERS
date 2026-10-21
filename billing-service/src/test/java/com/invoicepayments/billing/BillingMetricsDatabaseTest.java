package com.invoicepayments.billing;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual PostgreSQL/JDBC metric conversion. Unique fixture; no truncation or app data access. */
@EnabledIfEnvironmentVariable(named="BILLING_TEST_JDBC_URL",
    matches="jdbc:postgresql://127\\.0\\.0\\.1:15432/invoice_payments_test")
@SpringBootTest(classes=BillingApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"billing.workers-enabled=false","billing.kafka-enabled=false","billing.demo-enabled=false",
        "billing.erp-simulator-enabled=false","billing.simulator-enabled=true","billing.provider=SIMULATOR",
        "billing.stripe.secret-key=","billing.stripe.webhook-secret=","management.server.port=0",
        "management.tracing.enabled=false","management.health.redis.enabled=false",
        "eureka.client.enabled=false","spring.cloud.discovery.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=4","spring.datasource.hikari.minimum-idle=1",
        "app.jwt.cookie-secure=false",
        "app.jwt.secret=metrics-test-only-strong-secret-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ"})
@ActiveProfiles("billing")
class BillingMetricsDatabaseTest {
    @Autowired DataSource source;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry registry;

    @DynamicPropertySource static void isolatedDatabaseOnly(DynamicPropertyRegistry properties) {
        String url=System.getenv("BILLING_TEST_JDBC_URL");
        assertThat(url).isEqualTo("jdbc:postgresql://127.0.0.1:15432/invoice_payments_test");
        assertThat(System.getenv("BILLING_TEST_DB_USER")).isEqualTo("billing_test");
        assertThat(System.getenv("BILLING_TEST_DB_PASSWORD")).isNotBlank();
        properties.add("spring.datasource.url",()->url);
        properties.add("spring.datasource.username",()->"billing_test");
        properties.add("spring.datasource.password",()->System.getenv("BILLING_TEST_DB_PASSWORD"));
    }
    @Test void springManagedGaugesExposeCountsAndNumericAgesFromPostgres() throws Exception {
        try(var connection=source.getConnection();var statement=connection.createStatement();
            var identity=statement.executeQuery("SELECT current_database(),current_user")) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
            assertThat(identity.next()).isTrue();
            assertThat(identity.getString(1)).isEqualTo("invoice_payments_test");
            assertThat(identity.getString(2)).isEqualTo("billing_test");
        }
        UUID event=UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO billing.outbox(id,aggregate_id,type,payload,created_at) VALUES(?,?,'METRICS_TEST','{}'::jsonb,NOW()-INTERVAL '2 minutes')",event,UUID.randomUUID());
            for(String metric:new String[]{"billing.payments.unresolved","billing.payments.oldest.unresolved.seconds",
                "billing.webhooks.unmatched","billing.outbox.pending","billing.outbox.oldest.seconds",
                "billing.cases.open","billing.deadletters.open","billing.refunds.unresolved","billing.refunds.stuck",
                "billing.cases.awaiting.approval"}) {
                assertThat(Double.isFinite(registry.get(metric).gauge().value())).as(metric).isTrue();
            }
            assertThat(registry.get("billing.outbox.pending").gauge().value()).isGreaterThanOrEqualTo(1);
            assertThat(registry.get("billing.outbox.oldest.seconds").gauge().value()).isGreaterThanOrEqualTo(120);
        } finally {
            jdbc.update("DELETE FROM billing.outbox WHERE id=?",event);
        }
    }
}
