package com.invoicepayments.billing;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.concurrent.atomic.AtomicReference;
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
        // The combined count stays for dashboards and checks; alerts use the per-owner counts below, which add up to it.
        register(registry,"billing.payments.stuck", """
            SELECT COUNT(*) FROM billing.intents WHERE
              (status IN ('UNKNOWN','SUBMITTING') AND created_at<NOW()-INTERVAL '15 minutes') OR
              (status='REQUIRES_ACTION' AND created_at<NOW()-INTERVAL '24 hours') OR
              (status='PROCESSING' AND created_at<NOW()-INTERVAL '7 days')
            """);
        // No provider answer yet: our submission or recovery is stuck. Payments engineering.
        register(registry,"billing.payments.stuck.technical", "SELECT COUNT(*) FROM billing.intents WHERE status IN ('UNKNOWN','SUBMITTING') AND created_at<NOW()-INTERVAL '15 minutes'");
        // The paying user has not finished hosted Checkout. Payments operations follows up; nobody is paged.
        register(registry,"billing.payments.action.overdue", "SELECT COUNT(*) FROM billing.intents WHERE status='REQUIRES_ACTION' AND created_at<NOW()-INTERVAL '24 hours'");
        // The provider or bank is still processing a bank debit. Finance and treasury check evidence; age alone is never a failure.
        register(registry,"billing.payments.processing.overdue", "SELECT COUNT(*) FROM billing.intents WHERE status='PROCESSING' AND created_at<NOW()-INTERVAL '7 days'");
        register(registry,"billing.refunds.unresolved", "SELECT COUNT(*) FROM billing.refunds WHERE status IN ('APPROVED','SUBMITTING','PROCESSING','UNKNOWN')");
        // Measured from approval or submission (immutable), never from updated_at, which retries move.
        register(registry,"billing.refunds.stuck", """
            SELECT COUNT(*) FROM billing.refunds WHERE
              (status='APPROVED' AND COALESCE(approved_at,created_at)<NOW()-INTERVAL '15 minutes') OR
              (status IN ('SUBMITTING','UNKNOWN') AND COALESCE(submitted_at,created_at)<NOW()-INTERVAL '15 minutes') OR
              (status='PROCESSING' AND COALESCE(submitted_at,created_at)<NOW()-INTERVAL '7 days')
            """);
        // An approved refund that was never sent, or has no provider answer. Payments engineering.
        register(registry,"billing.refunds.stuck.technical", """
            SELECT COUNT(*) FROM billing.refunds WHERE
              (status='APPROVED' AND COALESCE(approved_at,created_at)<NOW()-INTERVAL '15 minutes') OR
              (status IN ('SUBMITTING','UNKNOWN') AND COALESCE(submitted_at,created_at)<NOW()-INTERVAL '15 minutes')
            """);
        // Pending at the provider or bank. Finance and treasury check evidence; never a reason for a second refund.
        register(registry,"billing.refunds.processing.overdue", "SELECT COUNT(*) FROM billing.refunds WHERE status='PROCESSING' AND COALESCE(submitted_at,created_at)<NOW()-INTERVAL '7 days'");
        register(registry,"billing.cases.awaiting.approval", "SELECT COUNT(*) FROM billing.cases WHERE status='OPEN' AND release_requested_by IS NOT NULL");
        // A verified event that disagrees with our records. Finance review.
        register(registry,"billing.webhooks.unmatched", "SELECT COUNT(*) FROM billing.webhook_inbox WHERE state='UNMATCHED'");
        // Saved events whose latest attempt failed for a technical reason and are retrying. Payments engineering.
        register(registry,"billing.webhooks.retrying", "SELECT COUNT(*) FROM billing.webhook_inbox WHERE state<>'PROCESSED' AND last_error='"+BillingInbox.TEMPORARY_ERROR+"'");
        register(registry,"billing.webhooks.retrying.oldest.seconds", "SELECT COALESCE(EXTRACT(EPOCH FROM NOW()-MIN(received_at)),0) FROM billing.webhook_inbox WHERE state<>'PROCESSED' AND last_error='"+BillingInbox.TEMPORARY_ERROR+"'");
        register(registry,"billing.outbox.pending", "SELECT COUNT(*) FROM billing.outbox WHERE state IN ('NEW','IN_FLIGHT')");
        register(registry,"billing.outbox.oldest.seconds", "SELECT COALESCE(EXTRACT(EPOCH FROM NOW()-MIN(created_at)),0) FROM billing.outbox WHERE state IN ('NEW','IN_FLIGHT')");
        register(registry,"billing.cases.open", "SELECT COUNT(*) FROM billing.cases WHERE status='OPEN'");
        register(registry,"billing.deadletters.open", "SELECT COUNT(*) FROM billing.dead_letters WHERE state<>'REPLAYED'");
        // Counted from capture journals: one immutable journal per captured payment, so a restart or a repeated
        // webhook cannot change the count. Every replica reports the same database total: take max, never sum.
        for(String method:new String[]{"CARD","ACH"}) captured(registry,method);
    }
    private void captured(MeterRegistry registry,String method) {
        // A counter must not go backwards or turn into NaN, so a failed read repeats the last known count.
        var last=new AtomicReference<Double>(0.0);
        FunctionCounter.builder("billing.payments.captured",jdbc,client->{
            try {
                Double n=client.queryForObject("SELECT COUNT(*) FROM billing.journals j JOIN billing.intents i ON i.id=j.intent_id WHERE j.effect_key LIKE 'capture:%' AND i.method=?",Double.class,method);
                if(n!=null && n>last.get()) last.set(n);
            } catch(RuntimeException failure) {}
            return last.get();
        }).tag("method",method).description("Payments with a recorded capture").register(registry);
    }
    private void register(MeterRegistry registry,String name,String query) {
        // Use a concrete JDBC double mapping for floating-point observations;
        // money writers continue to use exact integer minor units.
        // Inject JDBC directly: BillingStore is a Spring repository proxy, whose
        // fields must never be read directly from outside its delegated methods.
        Gauge.builder(name,jdbc,(ToDoubleFunction<JdbcTemplate>)client->{try {Double n=client.queryForObject(query,Double.class);return n==null?Double.NaN:n;}catch(RuntimeException failure){return Double.NaN;}}).register(registry);
    }
}
