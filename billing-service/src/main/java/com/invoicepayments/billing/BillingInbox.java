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
    private final BillingStore db; private final BillingService payments; private final BillingRefundService refunds;
    private final UUID merchant;
    public BillingInbox(BillingStore db,BillingService payments,BillingRefundService refunds,
        @Value("${billing.provider-merchant-id:10000000-0000-0000-0000-000000000001}") String merchant) {
        this.db=db;this.payments=payments;this.refunds=refunds;this.merchant=id(merchant);
    }
    public void receive(String provider,ProviderWebhookEvent event) {
        if(event.eventId()==null || event.eventId().length()>200 || event.providerId()==null || event.providerId().length()>200)
            throw BillingProblem.invalid("Webhook identifiers are missing or too long");
        JSONObject payload=new JSONObject().put("eventId",event.eventId()).put("providerId",event.providerId())
            .put("intentId",Objects.toString(event.intentId(),"")).put("state",event.state())
            .put("amountMinor",event.amountMinor()).put("currency",event.currency()).put("refundId",Objects.toString(event.refundId(),""));
        String evidence=String.join("|",event.providerId(),Objects.toString(event.intentId(),""),event.state(),Long.toString(event.amountMinor()),event.currency(),Objects.toString(event.refundId(),""));
        // Dispute messages saved before their details were kept carry this older fingerprint.
        String older=null;
        ProviderDispute dispute=event.dispute();
        if(dispute!=null) {
            older=hash(evidence);
            List<String> fields=List.of(Objects.toString(dispute.paymentReference(),""),Objects.toString(dispute.chargeReference(),""),
                Objects.toString(dispute.amountMinor(),""),Objects.toString(dispute.currency(),""),Objects.toString(dispute.reason(),""),
                dispute.status(),Objects.toString(dispute.evidenceDueBy(),""),Objects.toString(dispute.occurredAt(),""));
            payload.put("dispute",new JSONObject().put("paymentReference",fields.get(0)).put("chargeReference",fields.get(1))
                .put("amountMinor",fields.get(2)).put("currency",fields.get(3)).put("reason",fields.get(4)).put("status",fields.get(5))
                .put("evidenceDueBy",fields.get(6)).put("occurredAt",fields.get(7)));
            evidence=evidence+"|"+String.join("|",fields);
        }
        String fingerprint=hash(evidence),legacy=older;
        db.tx(() -> {
            db.update("INSERT INTO billing.webhook_inbox(provider,event_id,merchant_id,payload,payload_hash) VALUES(?,?,?,?::jsonb,?) ON CONFLICT(provider,event_id) DO NOTHING",provider,event.eventId(),merchant,payload.toString(),fingerprint);
            var stored=db.one("SELECT payload_hash,state FROM billing.webhook_inbox WHERE provider=? AND event_id=? FOR UPDATE",provider,event.eventId());
            if(fingerprint.equals(stored.get("payloadHash"))) return null;
            // The same dispute message, saved before its details were kept: add them and handle it again.
            // Any other difference is still a reused identity with different evidence.
            if(legacy!=null && legacy.equals(stored.get("payloadHash"))) {
                if(!"PROCESSED".equals(stored.get("state"))) db.update("""
                    UPDATE billing.webhook_inbox SET payload=?::jsonb,payload_hash=?,state='NEW',attempts=0,last_error=NULL,next_attempt_at=NOW()
                    WHERE provider=? AND event_id=?""",payload.toString(),fingerprint,provider,event.eventId());
                return null;
            }
            throw BillingProblem.conflict("Webhook identity was reused with different financial evidence");
        });
    }
    public void drain(int limit) {
        for(var key:db.rows("SELECT provider,event_id FROM billing.webhook_inbox WHERE state<>'PROCESSED' AND next_attempt_at<=NOW() ORDER BY next_attempt_at,received_at LIMIT ?",Math.min(limit,50)))
            process(text(key,"provider"),text(key,"eventId"));
    }
    public void process(String provider,String eventId) {
        try { db.tx(() -> {
            var candidates=db.rows("SELECT payload::text AS payload,state,merchant_id FROM billing.webhook_inbox WHERE provider=? AND event_id=? FOR UPDATE SKIP LOCKED",provider,eventId);
            if(candidates.isEmpty() || "PROCESSED".equals(candidates.get(0).get("state"))) return null;
            JSONObject event=new JSONObject(text(candidates.get(0),"payload"));
            if(event.has("dispute")) {
                recordDispute(provider,eventId,id(candidates.get(0).get("merchantId")),event);
                db.update("UPDATE billing.webhook_inbox SET state='PROCESSED',last_error=NULL WHERE provider=? AND event_id=?",provider,eventId);
                return null;
            }
            UUID intent=id(event.getString("intentId"));
            var payment=payments.locked(intent);
            var account=db.one("SELECT merchant_id FROM billing.accounts WHERE id=?",id(payment.get("accountId")));
            if(!account.get("merchantId").equals(candidates.get(0).get("merchantId"))) throw BillingProblem.conflict("Provider merchant does not match the customer account");
            if(!provider.equals(payment.get("provider")) || !event.getString("currency").equals(payment.get("currency"))) throw BillingProblem.conflict("Provider or currency mismatch");
            String refund=event.optString("refundId",""); String state=event.getString("state");
            if(!refund.isBlank()) {
                UUID refundId=id(refund); var requested=refunds.raw(refundId);
                if(!intent.toString().equals(requested.get("intentId")) || amount(requested,"amountMinor")!=event.getLong("amountMinor")) throw BillingProblem.conflict("Refund evidence does not match the approved operation");
                // Each authenticated event keeps its own evidence key, so two events about one refund stay distinct.
                refunds.record(refundId,null,new ProviderResult(event.getString("providerId"),state,null,"Authenticated provider event"),provider+":"+eventId);
            } else {
                if(amount(payment,"amountMinor")!=event.getLong("amountMinor")) throw BillingProblem.conflict("Payment amount mismatch");
                if(!db.exists("SELECT 1 FROM billing.attempts WHERE intent_id=?",intent)) throw BillingProblem.conflict("No submitted operation matches this event");
                payments.recordResult(intent,null,new ProviderResult(event.getString("providerId"),state,null,"Authenticated provider event"));
            }
            db.update("UPDATE billing.webhook_inbox SET state='PROCESSED',last_error=NULL WHERE provider=? AND event_id=?",provider,eventId);
            // Evidence that applies on a later attempt closes the review case an earlier failure opened.
            db.resolveCases(provider+":"+eventId,"WEBHOOK_REQUIRES_REVIEW","WEBHOOK_REQUIRES_REVIEW","system","PROVIDER_EVIDENCE","AUTO_RESOLVED",
                "Webhook evidence was applied on a later attempt");
            return null;
        }); } catch(RuntimeException failure) {
            Failure outcome;
            try { outcome=db.tx(() -> recordFailure(provider,eventId,failure)); }
            catch(RuntimeException unrecorded) { unrecorded.addSuppressed(failure); throw unrecorded; }
            if(outcome!=null) logFailure(provider,eventId,outcome,failure);
        }
    }
    /**
     * A dispute moves no money here: Carrier finance answers it in the provider's dashboard. It is kept so finance sees
     * the deadline. The provider's event time has one-second resolution and may be missing, so messages are ordered
     * by time and then by stage; when that cannot decide between two different results, the dispute is marked unclear
     * for a person to check in the dashboard. Details only fill gaps, never erase what an earlier message gave.
     */
    private void recordDispute(String provider,String eventId,UUID merchant,JSONObject event) {
        JSONObject dispute=event.getJSONObject("dispute");
        String disputeId=event.getString("providerId"),status=dispute.getString("status"),at=dispute.getString("occurredAt");
        db.update("""
            INSERT INTO billing.disputes(provider,id,merchant_id,status,last_event_id,last_event_at) VALUES(?,?,?,?,?,NULLIF(?,'')::timestamptz)
            ON CONFLICT(provider,id) DO NOTHING""",provider,disputeId,merchant,status,eventId,at);
        var current=db.one("SELECT status,last_event_id,EXTRACT(EPOCH FROM last_event_at)::bigint AS seen FROM billing.disputes WHERE provider=? AND id=? FOR UPDATE",provider,disputeId);
        Long seen=current.get("seen")==null?null:amount(current,"seen"), time=at.isEmpty()?null:java.time.Instant.parse(at).getEpochSecond();
        int stage=disputeStage(status),known=disputeStage(text(current,"status"));
        boolean newest=eventId.equals(current.get("lastEventId"))
            || (time!=null && (seen==null || time>seen || (time.equals(seen) && stage>known)));
        boolean unclear=!newest && !status.equals(current.get("status")) && (time==null || seen==null || (time.equals(seen) && stage==known));
        db.update("""
            UPDATE billing.disputes SET payment_reference=COALESCE(NULLIF(?,''),payment_reference),charge_reference=COALESCE(NULLIF(?,''),charge_reference),
              amount_minor=COALESCE(NULLIF(?,'')::bigint,amount_minor),currency=COALESCE(NULLIF(?,''),currency),reason=COALESCE(NULLIF(?,''),reason),
              status=CASE WHEN ? THEN ? ELSE status END,
              evidence_due_by=CASE WHEN ? THEN COALESCE(NULLIF(?,'')::timestamptz,evidence_due_by) ELSE COALESCE(evidence_due_by,NULLIF(?,'')::timestamptz) END,
              last_event_id=CASE WHEN ? THEN ? ELSE last_event_id END,
              last_event_at=CASE WHEN ? THEN NULLIF(?,'')::timestamptz ELSE last_event_at END,
              status_unclear=CASE WHEN ? THEN FALSE ELSE status_unclear OR ? END,updated_at=NOW()
            WHERE provider=? AND id=?""",
            dispute.getString("paymentReference"),dispute.getString("chargeReference"),dispute.getString("amountMinor"),dispute.getString("currency"),
            dispute.getString("reason"),newest,status,newest,dispute.getString("evidenceDueBy"),dispute.getString("evidenceDueBy"),
            newest,eventId,newest,at,newest,unclear,provider,disputeId);
    }
    /** How far a dispute has gone: waiting for an answer, answered, then decided. Unknown statuses rank lowest. */
    private static int disputeStage(String status) {
        return switch(status) {
            case "needs_response","warning_needs_response" -> 1;
            case "under_review","warning_under_review" -> 2;
            case "won","lost","warning_closed","prevented" -> 3;
            default -> 0;
        };
    }
    /** The tenth failed attempt is logged at WARN again; engineering's alert watches the event's age. */
    static final int TEMPORARY_RETRIES=10;
    /** Temporary failures back off from 10 seconds, doubling to this cap. */
    static final int TEMPORARY_RETRY_CAP_SECONDS=300;
    /** Existing persisted marker for a technical processing failure; BillingMetrics counts these for engineering. */
    static final String TEMPORARY_ERROR="Temporary failure; will retry";
    private record Failure(long attempts,boolean temporary,boolean warn) {}
    /**
     * Database failures and unexpected application errors stay queued for engineering, however often they repeat.
     * Only explicit evidence validation/matching failures become UNMATCHED for finance review.
     * Both keep retrying with backoff until the evidence applies or the underlying problem is corrected.
     */
    private Failure recordFailure(String provider,String eventId,RuntimeException failure) {
        var rows=db.rows("SELECT state,attempts,payload::text AS payload,merchant_id FROM billing.webhook_inbox WHERE provider=? AND event_id=? AND state<>'PROCESSED' FOR UPDATE",provider,eventId);
        if(rows.isEmpty()) return null;
        var row=rows.get(0); long attempts=amount(row,"attempts")+1;
        if(isTransient(failure) || !(failure instanceof BillingProblem problem) || problem.status()>=500) {
            long delay=Math.min(TEMPORARY_RETRY_CAP_SECONDS,10L<<Math.min(attempts-1,5));
            db.update("UPDATE billing.webhook_inbox SET attempts=?,next_attempt_at=NOW()+(? * INTERVAL '1 second'),last_error=? WHERE provider=? AND event_id=?",attempts,delay,TEMPORARY_ERROR,provider,eventId);
            return new Failure(attempts,true,attempts==1 || attempts==TEMPORARY_RETRIES);
        }
        // Back off from 30 seconds, doubling to at most an hour: unmatched evidence waits for people, not for a tighter loop.
        long delay=Math.min(3600,30L<<Math.min(attempts-1,7));
        db.update("UPDATE billing.webhook_inbox SET state='UNMATCHED',attempts=?,next_attempt_at=NOW()+(? * INTERVAL '1 second'),last_error='Evidence requires matching or finance review' WHERE provider=? AND event_id=?",attempts,delay,provider,eventId);
        try { UUID intent=id(new JSONObject(text(row,"payload")).getString("intentId"));
            if(db.exists("SELECT 1 FROM billing.intents i JOIN billing.accounts a ON a.id=i.account_id WHERE i.id=? AND a.merchant_id=?",intent,id(row.get("merchantId")))) db.caseFor(intent,"WEBHOOK_REQUIRES_REVIEW",provider+":"+eventId);
        } catch(BillingProblem ignored) { /* Unknown events remain durably visible in the finance inbox. */ }
        return new Failure(attempts,false,!"UNMATCHED".equals(row.get("state")));
    }
    /**
     * The first report of a failure is a warning, and so is the TEMPORARY_RETRIES-th failed attempt;
     * other repeats on retry are DEBUG. Expected evidence mismatches need no stack trace.
     */
    private static void logFailure(String provider,String eventId,Failure outcome,RuntimeException failure) {
        String reason=failure.getClass().getSimpleName()+": "+failure.getMessage();
        Object[] args=failure instanceof BillingProblem ? new Object[]{provider,eventId,outcome.attempts(),reason} : new Object[]{provider,eventId,outcome.attempts(),reason,failure};
        String message=outcome.temporary() ? "Webhook processing hit a technical failure; will retry with backoff for engineering review provider={} eventId={} attempts={} reason={}"
            : "Webhook event kept for retry and finance review provider={} eventId={} attempts={} reason={}";
        if(outcome.warn()) log.warn(message,args); else log.debug(message,args);
    }
}
