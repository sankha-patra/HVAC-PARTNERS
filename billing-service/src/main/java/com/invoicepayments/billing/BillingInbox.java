package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import org.json.JSONObject;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;

/** Receipt commits before HTTP acknowledgement. Retry and financial effects use separate transactions. */
@Service
@Profile("billing")
public class BillingInbox {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(BillingInbox.class);
    private final BillingStore db;private final BillingInboxCommands commands;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private BillingProvider provider;
    public BillingInbox(BillingStore db,BillingInboxCommands commands){this.db=db;this.commands=commands;}
    public void receive(String provider,ProviderWebhookEvent event) {
        requireNoTransaction();BillingExecutionBudget.requireTime();commands.receive(provider,event);
    }
    public void drain(int limit) {
        for(var key:db.rows("SELECT provider,event_id FROM billing.webhook_inbox WHERE state<>'PROCESSED' AND next_attempt_at<=NOW() ORDER BY next_attempt_at,received_at LIMIT ?",Math.min(limit,50)))
            process(text(key,"provider"),text(key,"eventId"));
    }
    public void process(String provider,String eventId) {
        requireNoTransaction();BillingExecutionBudget.requireTime();
        try {
            if(!verifyRazorpayRefund(provider,eventId))commands.process(provider,eventId);
        }
        catch(RuntimeException failure) {
            BillingInboxCommands.Failure outcome;
            try {outcome=commands.recordFailure(provider,eventId,failure);}
            catch(RuntimeException unrecorded){unrecorded.addSuppressed(failure);throw unrecorded;}
            if(outcome!=null)logFailure(provider,eventId,outcome,failure);
        }
    }
    /** Receipt is already durable. Only the approved saved request supplies lookup expectations; no SQL transaction spans HTTP. */
    private boolean verifyRazorpayRefund(String source,String eventId) {
        if(!"RAZORPAY".equals(source) || provider==null || !"RAZORPAY".equals(provider.name()))return false;
        var rows=db.rows("SELECT payload::text AS payload,state FROM billing.webhook_inbox WHERE provider=? AND event_id=?",source,eventId);
        if(rows.isEmpty() || "PROCESSED".equals(rows.get(0).get("state")))return false;
        JSONObject event=new JSONObject(text(rows.get(0),"payload"));String refund=event.optString("refundId","");
        if(refund.isBlank())return false;
        var approved=db.one("SELECT original_request FROM billing.refunds WHERE id=? AND approved_by IS NOT NULL",id(refund));
        if(approved.get("originalRequest")==null)throw BillingProblem.conflict("The refund has not been prepared for submission");
        RefundCommand saved=RefundCommand.fromJson(text(approved,"originalRequest"));
        if(!saved.refundId().equals(refund) || !saved.intentId().equals(event.optString("intentId"))
            || saved.amountMinor()!=event.getLong("amountMinor") || !saved.currency().equals(event.getString("currency")))
            throw BillingProblem.conflict("Signed refund receipt differs from the approved request");
        ProviderResult verified=provider.verifyRefundReference(saved,event.getString("providerId"));
        commands.processVerifiedRazorpayRefund(eventId,saved,verified);return true;
    }
    /**
     * A dispute moves no money here: Carrier finance answers it in the provider's dashboard. It is kept so finance sees
     * the deadline. The provider's event time has one-second resolution and may be missing, so messages are ordered
     * by time and then by stage; when that cannot decide between two different results, the dispute is marked unclear
     * for a person to check in the dashboard. Details only fill gaps, never erase what an earlier message gave.
     */

    /** How far a dispute has gone: waiting for an answer, answered, then decided. Unknown statuses rank lowest. */

    /** The tenth failed attempt is logged at WARN again; engineering's alert watches the event's age. */
    static final int TEMPORARY_RETRIES=10;
    /** Temporary failures back off from 10 seconds, doubling to this cap. */
    static final int TEMPORARY_RETRY_CAP_SECONDS=300;
    /** Existing persisted marker for a technical processing failure; BillingMetrics counts these for engineering. */
    static final String TEMPORARY_ERROR="Temporary failure; will retry";
    /**
     * Database failures and unexpected application errors stay queued for engineering, however often they repeat.
     * Only explicit evidence validation/matching failures become UNMATCHED for finance review.
     * Both keep retrying with backoff until the evidence applies or the underlying problem is corrected.
     */

    /**
     * The first report of a failure is a warning, and so is the TEMPORARY_RETRIES-th failed attempt;
     * other repeats on retry are DEBUG. Expected evidence mismatches need no stack trace.
     */
    private static void logFailure(String provider,String eventId,BillingInboxCommands.Failure outcome,RuntimeException failure) {
        String reason=failure.getClass().getSimpleName()+": "+failure.getMessage();
        Object[] args=failure instanceof BillingProblem ? new Object[]{provider,eventId,outcome.attempts(),reason} : new Object[]{provider,eventId,outcome.attempts(),reason,failure};
        String message=outcome.temporary() ? "Webhook processing hit a technical failure; will retry with backoff for engineering review provider={} eventId={} attempts={} reason={}"
            : "Webhook event kept for retry and finance review provider={} eventId={} attempts={} reason={}";
        if(outcome.warn()) log.warn(message,args); else log.debug(message,args);
    }
}
