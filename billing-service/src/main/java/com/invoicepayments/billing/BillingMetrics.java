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
        register(registry,"billing.webhooks.unmatched", "SELECT COUNT(*) FROM billing.webhook_inbox WHERE state='UNMATCHED'");
        register(registry,"billing.outbox.pending", "SELECT COUNT(*) FROM billing.outbox WHERE state<>'PUBLISHED'");
        register(registry,"billing.outbox.oldest.seconds", "SELECT COALESCE(EXTRACT(EPOCH FROM NOW()-MIN(created_at)),0) FROM billing.outbox WHERE state<>'PUBLISHED'");
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
