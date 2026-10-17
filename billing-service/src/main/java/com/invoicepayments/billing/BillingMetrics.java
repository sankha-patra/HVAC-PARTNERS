package com.invoicepayments.billing;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.function.ToDoubleFunction;

/** Aggregate business signals only: never label metrics with customer/payment identifiers. */
@Component
@Profile("billing")
public class BillingMetrics {
    private final JdbcTemplate jdbc;
    public BillingMetrics(JdbcTemplate jdbc,MeterRegistry registry) {
        this.jdbc=jdbc;
        register(registry,"billing.payments.unresolved", "SELECT COUNT(*) FROM billing.intents WHERE status IN ('UNKNOWN','SUBMITTING','PROCESSING','REQUIRES_ACTION')");
        register(registry,"billing.payments.oldest.unresolved.seconds", "SELECT COALESCE(EXTRACT(EPOCH FROM NOW()-MIN(created_at)),0) FROM billing.intents WHERE status IN ('UNKNOWN','SUBMITTING','PROCESSING','REQUIRES_ACTION')");
        // Local escalation policy; retries update updated_at, so measure from
        // creation to avoid hiding a payment behind repeated recovery attempts.
        register(registry,"billing.payments.stuck", """
            SELECT COUNT(*) FROM billing.intents WHERE
              (status IN ('UNKNOWN','SUBMITTING') AND created_at<NOW()-INTERVAL '15 minutes') OR
              (status='REQUIRES_ACTION' AND created_at<NOW()-INTERVAL '24 hours') OR
              (status='PROCESSING' AND created_at<NOW()-INTERVAL '7 days')
            """);
        register(registry,"billing.webhooks.unmatched", "SELECT COUNT(*) FROM billing.webhook_inbox WHERE state='UNMATCHED'");
        register(registry,"billing.outbox.pending", "SELECT COUNT(*) FROM billing.outbox WHERE state IN ('NEW','IN_FLIGHT')");
        register(registry,"billing.outbox.oldest.seconds", "SELECT COALESCE(EXTRACT(EPOCH FROM NOW()-MIN(created_at)),0) FROM billing.outbox WHERE state IN ('NEW','IN_FLIGHT')");
        register(registry,"billing.cases.open", "SELECT COUNT(*) FROM billing.cases WHERE status='OPEN'");
        register(registry,"billing.deadletters.open", "SELECT COUNT(*) FROM billing.dead_letters WHERE state<>'REPLAYED'");
    }
    private void register(MeterRegistry registry,String name,String query) {
        // Use a concrete JDBC double mapping for floating-point observations;
        // money writers continue to use exact integer minor units.
        // Inject JDBC directly: BillingStore is a Spring repository proxy, whose
        // fields must never be read directly from outside its delegated methods.
        Gauge.builder(name,jdbc,(ToDoubleFunction<JdbcTemplate>)client->{try {Double n=client.queryForObject(query,Double.class);return n==null?Double.NaN:n;}catch(RuntimeException failure){return Double.NaN;}}).register(registry);
    }
}
