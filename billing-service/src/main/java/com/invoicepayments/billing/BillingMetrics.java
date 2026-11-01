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
    public BillingMetrics(JdbcTemplate jdbc,MeterRegistry registry){this(jdbc,registry,"billing_outbox_cdc");}
    @org.springframework.beans.factory.annotation.Autowired
    public BillingMetrics(JdbcTemplate jdbc,MeterRegistry registry,
            @org.springframework.beans.factory.annotation.Value("${billing.cdc.slot-name:billing_outbox_cdc}") String slot) {
        this.jdbc=jdbc;
        if(!slot.matches("[a-z0-9_]{1,63}"))throw new IllegalArgumentException("Replication slot names are lower-case letters, digits and underscores");
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
        register(registry,"billing.outbox.pending", "SELECT COUNT(*) FROM billing.outbox WHERE state IN ('NEW','IN_FLIGHT','CDC_PENDING')");
        register(registry,"billing.outbox.oldest.seconds", "SELECT COALESCE(EXTRACT(EPOCH FROM NOW()-MIN(created_at)),0) FROM billing.outbox WHERE state IN ('NEW','IN_FLIGHT','CDC_PENDING')");
        register(registry,"billing.recovery.due.oldest.seconds", "SELECT COALESCE(GREATEST(EXTRACT(EPOCH FROM NOW()-MIN(next_recovery_at)),0),0) FROM billing.intents WHERE status IN ('UNKNOWN','SUBMITTING','PROCESSING','REQUIRES_ACTION') AND next_recovery_at<=NOW() AND (lease_until IS NULL OR lease_until<NOW())");
        register(registry,"billing.refunds.due.oldest.seconds", "SELECT COALESCE(GREATEST(EXTRACT(EPOCH FROM NOW()-MIN(next_recovery_at)),0),0) FROM billing.refunds WHERE status IN ('APPROVED','UNKNOWN','SUBMITTING','PROCESSING') AND next_recovery_at<=NOW() AND (lease_until IS NULL OR lease_until<NOW())");
        register(registry,"billing.outbox.cdc.pending", "SELECT COUNT(*) FROM billing.outbox WHERE state='CDC_PENDING'");
        register(registry,"billing.cdc.expected", "SELECT CASE WHEN mode='CDC' THEN 1 ELSE 0 END FROM billing.outbox_relay_control WHERE id");
        register(registry,"billing.cdc.slot.active", "SELECT COALESCE(MAX(CASE WHEN active THEN 1 ELSE 0 END),0) FROM pg_replication_slots WHERE slot_name='"+slot+"'");
        register(registry,"billing.cdc.wal.retained.bytes", "SELECT COALESCE(MAX(pg_wal_lsn_diff(pg_current_wal_lsn(),restart_lsn)),0) FROM pg_replication_slots WHERE slot_name='"+slot+"'");
        // Debezium rewrites this row every few seconds while it reads the outbox, with or without payment traffic.
        register(registry,"billing.cdc.heartbeat.age.seconds", "SELECT COALESCE(EXTRACT(EPOCH FROM NOW()-MAX(touched_at)),0) FROM billing.cdc_heartbeat");
        register(registry,"billing.cases.open", "SELECT COUNT(*) FROM billing.cases WHERE status='OPEN'");
        // For operational alerts: uncertain for 15 minutes and not yet owned by an open finance case (a case already
        // emailed finance, so repeating it every minute helps nobody).
        register(registry,"billing.payments.stuck.unowned", "SELECT COUNT(*) FROM billing.intents i WHERE i.status IN ('UNKNOWN','SUBMITTING') AND i.created_at<NOW()-INTERVAL '15 minutes' AND NOT EXISTS(SELECT 1 FROM billing.cases c WHERE c.intent_id=i.id AND c.refund_id IS NULL AND c.status='OPEN' AND c.reason IN ('PAYMENT_UNRESOLVED','PAYMENT_REPLAY_UNSAFE'))");
        register(registry,"billing.refunds.stuck.unowned", """
            SELECT COUNT(*) FROM billing.refunds r WHERE ((r.status='APPROVED' AND COALESCE(r.approved_at,r.created_at)<NOW()-INTERVAL '15 minutes')
              OR (r.status IN ('SUBMITTING','UNKNOWN') AND COALESCE(r.submitted_at,r.created_at)<NOW()-INTERVAL '15 minutes'))
              AND NOT EXISTS(SELECT 1 FROM billing.cases c WHERE c.refund_id=r.id AND c.status='OPEN' AND c.reason IN ('REFUND_UNRESOLVED','REFUND_REPLAY_UNSAFE'))
            """);
        // Due recovery that nothing owns: no live or scheduled workflow (AWS mode), no local claim, no open case. A workflow
        // waiting for its next attempt counts as owned; one overdue by three minutes does not.
        register(registry,"billing.recovery.unowned.oldest.seconds", """
            SELECT COALESCE(GREATEST(EXTRACT(EPOCH FROM NOW()-MIN(o.next_recovery_at)),0),0) FROM (
              SELECT 'PAYMENT' AS kind,i.id,i.next_recovery_at,i.lease_until FROM billing.intents i WHERE i.status IN ('UNKNOWN','SUBMITTING','PROCESSING','REQUIRES_ACTION')
              UNION ALL
              SELECT 'REFUND',r.id,r.next_recovery_at,r.lease_until FROM billing.refunds r WHERE r.status IN ('APPROVED','UNKNOWN','SUBMITTING','PROCESSING')
            ) o WHERE o.next_recovery_at<=NOW() AND (o.lease_until IS NULL OR o.lease_until<NOW())
              AND NOT EXISTS(SELECT 1 FROM billing.recovery_dispatch d WHERE d.operation_kind=o.kind AND d.operation_id=o.id
                AND (d.state IN ('STARTING','RUNNING','REVIEW') OR (d.state='PENDING' AND d.next_at>NOW()-INTERVAL '3 minutes')))
              AND NOT EXISTS(SELECT 1 FROM billing.cases c WHERE c.status='OPEN' AND c.reason IN ('PAYMENT_UNRESOLVED','PAYMENT_REPLAY_UNSAFE','REFUND_UNRESOLVED','REFUND_REPLAY_UNSAFE')
                AND (c.refund_id=o.id OR (o.kind='PAYMENT' AND c.intent_id=o.id AND c.refund_id IS NULL)))
            """);
        // Emails the mail server keeps refusing (retrying or given up within the last day), and emails dropped because no
        // recipient is configured for the merchant within the last hour.
        register(registry,"billing.notifications.failing", "SELECT COUNT(*) FROM billing.notification_deliveries WHERE (state IN ('PENDING','IN_FLIGHT') AND attempts>=2) OR (state='REVIEW' AND created_at>NOW()-INTERVAL '1 day')");
        register(registry,"billing.notifications.unrouted.recent", "SELECT COUNT(*) FROM billing.notification_deliveries WHERE state='SUPPRESSED' AND last_error='NO_ACTIVE_ROUTE' AND created_at>NOW()-INTERVAL '1 hour'");
        register(registry,"billing.deadletters.open", "SELECT COUNT(*) FROM billing.dead_letters WHERE state<>'REPLAYED'");
        // Bank disputes and inquiries waiting for Carrier finance's answer (or whose result is unclear and needs a look),
        // and those due within 48 hours. Carrier finance decides.
        register(registry,"billing.disputes.needing.response", "SELECT COUNT(*) FROM billing.disputes WHERE status_unclear OR status IN ('needs_response','warning_needs_response')");
        register(registry,"billing.disputes.response.due.soon", "SELECT COUNT(*) FROM billing.disputes WHERE status IN ('needs_response','warning_needs_response') AND evidence_due_by<NOW()+INTERVAL '48 hours'");
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
