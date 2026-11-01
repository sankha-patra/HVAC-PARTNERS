package com.invoicepayments.billing;

import org.json.JSONObject;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import com.invoicepayments.billing.BillingCaseService.ExternalConfirmation;
import static com.invoicepayments.billing.BillingStore.*;
import static com.invoicepayments.billing.BillingCaseService.*;

/** Merchant-scoped case reads and validation. It never creates a transaction. */
@Component
@Profile("billing")
public class BillingCaseAccess {
    private final BillingStore db;private final BillingPaymentAccess payments;
    public BillingCaseAccess(BillingStore db,BillingPaymentAccess payments){this.db=db;this.payments=payments;}
    public List<Map<String,Object>> list(String user) {
        requireAnyStaff(user);
        var rows=db.rows("""
            SELECT c.id::text,c.intent_id::text,c.refund_id::text,c.merchant_id::text,c.reason,c.status,c.created_at,
              c.resolved_at,c.resolved_by,c.resolution,c.release_requested_by,c.release_note,c.release_requested_at,
              i.status AS payment_status,r.status AS refund_status
            FROM billing.cases c LEFT JOIN billing.intents i ON i.id=c.intent_id LEFT JOIN billing.refunds r ON r.id=c.refund_id
            WHERE EXISTS(SELECT 1 FROM billing.staff s WHERE s.user_id=? AND s.merchant_id=c.merchant_id)
            ORDER BY (c.status='OPEN') DESC,c.created_at DESC LIMIT 200
            """,user);
        for(var row:rows) {
            UUID merchant=id(row.get("merchantId"));
            boolean maker=hasRole(user,merchant,"FINANCE"), checker=hasRole(user,merchant,"REFUND_APPROVER");
            boolean open="OPEN".equals(row.get("status")), unresolved=unresolved(row);
            boolean stuck=PAYMENT_STUCK.contains(text(row,"reason")) || REFUND_STUCK.contains(text(row,"reason"));
            boolean refundConflict=REFUND_CONFLICT.equals(text(row,"reason"));
            boolean pending=row.get("releaseRequestedBy")!=null;
            boolean releasable=open && unresolved && !replayPossible(row);
            boolean awaiting=awaitingProvider(row);
            row.put("canVerifyReference",open && ((stuck && (unresolved || awaiting) && maker) || (refundConflict && checker)));
            row.put("canRequestRelease",releasable && stuck && maker && !pending);
            row.put("canApproveRelease",releasable && pending && checker && !user.equals(row.get("releaseRequestedBy")));
            row.put("canClose",open && !unresolved && !awaiting && maker);
            row.put("canAddNote",true);
            row.put("canRecordExternalConfirmation",releasable && stuck && maker && !pending && externalWaitOver(row));
            if(pending) {
                JSONObject confirmation=pendingConfirmation(id(row.get("id")));
                if(confirmation!=null) row.put("externalConfirmation",confirmation.toMap());
            }
            row.remove("merchantId");
        }
        var evidence=db.rows("""
            SELECT w.provider||':'||w.event_id AS id,w.payload->>'intentId' AS intent_id,'UNMATCHED_WEBHOOK' AS reason,'OPEN' AS status,w.received_at AS created_at
            FROM billing.webhook_inbox w WHERE w.state='UNMATCHED'
            AND EXISTS(SELECT 1 FROM billing.staff s WHERE s.user_id=? AND s.merchant_id=w.merchant_id)
            ORDER BY w.received_at DESC LIMIT 200
            """,user);
        // Bank disputes and inquiries are answered in the provider's dashboard; finance sees each one here with its
        // deadline. Those still needing an answer, or whose result is unclear, come first so old ones never hide them.
        var disputes=db.rows("""
            SELECT 'dispute:'||d.provider||':'||d.id AS id,'' AS intent_id,'DISPUTE' AS reason,
              CASE WHEN NOT d.status_unclear AND d.status IN ('won','lost','warning_closed','prevented') THEN 'RESOLVED' ELSE 'OPEN' END AS status,
              d.created_at,d.status AS dispute_status,d.reason AS dispute_reason,d.status_unclear,d.payment_reference,d.charge_reference,
              d.amount_minor,d.currency,to_char(d.evidence_due_by AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"') AS evidence_due_by
            FROM billing.disputes d WHERE EXISTS(SELECT 1 FROM billing.staff s WHERE s.user_id=? AND s.merchant_id=d.merchant_id)
            ORDER BY (d.status_unclear OR d.status IN ('needs_response','warning_needs_response')) DESC,
              (d.status IN ('under_review','warning_under_review')) DESC,d.evidence_due_by ASC NULLS LAST,d.updated_at DESC
            LIMIT 200
            """,user);
        List<Map<String,Object>> unactionable=new ArrayList<>(evidence); unactionable.addAll(disputes);
        for(var row:unactionable) for(String flag:List.of("canVerifyReference","canRequestRelease","canApproveRelease","canClose","canAddNote","canRecordExternalConfirmation")) row.put(flag,false);
        List<Map<String,Object>> result=new ArrayList<>(unactionable); result.addAll(rows);
        return result;
    }
    public List<Map<String,Object>> history(String user,UUID caseId) {
        visible(user,caseId);
        return db.rows("SELECT action,actor,note,evidence::text AS evidence,created_at FROM billing.case_actions WHERE case_id=? ORDER BY created_at,id",caseId);
    }
    public long conflictEvidenceCount(UUID caseId) {
        return amount(db.one("SELECT COUNT(*) AS total FROM billing.case_actions WHERE case_id=? AND action='CONTRADICTORY_PROVIDER_EVIDENCE'",caseId),"total");
    }
    public static ExternalConfirmation validConfirmation(ExternalConfirmation c) {
        if(c==null) throw BillingProblem.invalid("Record what the payment provider confirmed");
        String source=trim(c.source()), account=trim(c.account()), ticket=trim(c.ticket()), checked=trim(c.checkedAt()), digest=trim(c.evidenceSha256());
        if(!EXTERNAL_SOURCES.contains(source)) throw BillingProblem.invalid("Choose the payment provider's Dashboard or support as the source");
        if(!account.matches("acct_[A-Za-z0-9]{6,64}") && !account.matches("[\\p{L}\\p{N} .,&'()\\-]{3,100}"))
            throw BillingProblem.invalid("Name the original provider account: its account ID or the account name shown in the Dashboard");
        String searched=bounded(c.searched(),"Describe what was searched in Stripe, in 10 to 500 characters");
        String result=bounded(c.result(),"Describe what Stripe showed, in 10 to 500 characters");
        if(source.endsWith("_SUPPORT") && ticket.isEmpty()) throw BillingProblem.invalid("Enter the provider's support ticket reference");
        if(!ticket.isEmpty() && !ticket.matches("[A-Za-z0-9#_\\-]{3,100}")) throw BillingProblem.invalid("Enter the Stripe support ticket reference exactly as Stripe gave it");
        Instant checkedAt;
        try { checkedAt=Instant.parse(checked); } catch(DateTimeParseException ex) { throw BillingProblem.invalid("Enter when Stripe was checked, as a date and time"); }
        if(!digest.matches("[a-fA-F0-9]{64}")) throw BillingProblem.invalid("Provide the SHA-256 digest of the saved evidence file");
        String note=requireNote(c.note());
        requireNoAccountNumbers(account,searched,result,ticket,note);
        return new ExternalConfirmation(source,account,searched,result,ticket.isEmpty()?null:ticket,checkedAt.toString(),digest.toLowerCase(Locale.ROOT),note);
    }
    public static String trim(String value) { return value==null?"":value.trim(); }
    public static String bounded(String value,String message) {
        String text=trim(value);
        if(text.length()<10 || text.length()>500) throw BillingProblem.invalid(message);
        return text;
    }
    public static void requireNoAccountNumbers(String... values) {
        for(String value:values) if(value!=null && ACCOUNT_DIGITS.matcher(value).find())
            throw BillingProblem.invalid("Remove card or bank account numbers; record only what was searched and what was found");
    }
    public Instant databaseNow() { return Instant.parse(text(db.one("SELECT NOW() AS now"),"now")); }
    public Instant externalCheckFrom(Map<String,Object> locked) {
        if(PAYMENT_STUCK.contains(text(locked,"reason"))) {
            var attempt=db.rows("SELECT a.created_at,i.provider FROM billing.attempts a JOIN billing.intents i ON i.id=a.intent_id WHERE a.intent_id=?",id(locked.get("intentId")));
            if(attempt.isEmpty()) throw BillingProblem.conflict("This payment has no recorded submission, so a provider confirmation cannot release it");
            return Instant.parse(text(attempt.get(0),"createdAt")).plus(BillingCaseService.paymentWait(text(attempt.get(0),"provider")));
        }
        var refund=db.one("SELECT r.submitted_at,i.provider FROM billing.refunds r JOIN billing.intents i ON i.id=r.intent_id WHERE r.id=?",id(locked.get("refundId")));
        if(refund.get("submittedAt")==null) throw BillingProblem.conflict("This refund was never sent, so a provider confirmation cannot release it");
        // A Razorpay refund is findable seconds after creation; an hour covers a request still in flight when the app stopped.
        return "RAZORPAY".equals(refund.get("provider")) ? Instant.parse(text(refund,"submittedAt")).plus(BillingCaseService.RAZORPAY_PAYMENT_WAIT)
            : Instant.parse(text(refund,"submittedAt")).minus(CLOCK_TOLERANCE);
    }
    public boolean externalWaitOver(Map<String,Object> row) {
        if(PAYMENT_STUCK.contains(text(row,"reason")))
            return db.exists("SELECT 1 FROM billing.attempts a JOIN billing.intents i ON i.id=a.intent_id WHERE a.intent_id=?::uuid AND a.created_at<=NOW()-CASE WHEN i.provider='RAZORPAY' THEN INTERVAL '1 hour' ELSE INTERVAL '48 hours' END",text(row,"intentId"));
        return REFUND_STUCK.contains(text(row,"reason")) && db.exists("SELECT 1 FROM billing.refunds r JOIN billing.intents i ON i.id=r.intent_id WHERE r.id=?::uuid AND r.submitted_at IS NOT NULL AND (i.provider<>'RAZORPAY' OR r.submitted_at<=NOW()-INTERVAL '1 hour')",text(row,"refundId"));
    }
    public JSONObject pendingConfirmation(UUID caseId) {
        var rows=db.rows("""
            SELECT a.evidence::text AS evidence FROM billing.case_actions a JOIN billing.cases c ON c.id=a.case_id
            WHERE a.case_id=? AND a.action='RECORDED_EXTERNAL_CONFIRMATION' AND c.release_requested_at IS NOT NULL
              AND a.created_at>=c.release_requested_at
            ORDER BY a.created_at DESC,a.id DESC LIMIT 1
            """,caseId);
        return rows.isEmpty()?null:new JSONObject(text(rows.get(0),"evidence"));
    }
    public boolean confirmationExpired(JSONObject confirmation) {
        return Instant.parse(confirmation.getString("checkedAt")).isBefore(databaseNow().minus(EXTERNAL_EVIDENCE_LIFETIME));
    }
    public void requireApprovable(String user,Map<String,Object> locked) {
        requireRole(user,locked,"REFUND_APPROVER"); requireOpen(locked);
        if(locked.get("releaseRequestedBy")==null) throw BillingProblem.conflict("No release request is waiting for approval");
        if(user.equals(locked.get("releaseRequestedBy"))) throw new BillingProblem(403,"SEPARATION_OF_DUTIES","The requester cannot approve their own release");
        requireReleasable(locked);
    }
    public Map<String,Object> visible(String user,UUID caseId) {
        var rows=db.rows("""
            SELECT c.*,i.status AS payment_status,r.status AS refund_status FROM billing.cases c
            LEFT JOIN billing.intents i ON i.id=c.intent_id LEFT JOIN billing.refunds r ON r.id=c.refund_id
            WHERE c.id=? AND EXISTS(SELECT 1 FROM billing.staff s WHERE s.user_id=? AND s.merchant_id=c.merchant_id)
            """,caseId,user);
        if(rows.isEmpty()) throw BillingProblem.missing(); // never reveal cases outside the user's merchant scope
        return rows.get(0);
    }
    public boolean unresolved(Map<String,Object> item) {
        String reason=text(item,"reason");
        if(REFUND_CONFLICT.equals(reason)) return true;
        if(PAYMENT_STUCK.contains(reason)) return Set.of("UNKNOWN","SUBMITTING").contains(text(item,"paymentStatus"));
        if(REFUND_STUCK.contains(reason)) return Set.of("SUBMITTING","UNKNOWN").contains(text(item,"refundStatus"));
        return false;
    }
    /** Still waiting for the provider (an open hosted checkout, a refund in progress): may be verified, never closed or released. */
    public boolean awaitingProvider(Map<String,Object> item) {
        String reason=text(item,"reason");
        if(PAYMENT_STUCK.contains(reason)) return Set.of("REQUIRES_ACTION","PROCESSING").contains(text(item,"paymentStatus"));
        if(REFUND_STUCK.contains(reason)) return "PROCESSING".equals(text(item,"refundStatus"));
        return false;
    }
    public void requireReleasable(Map<String,Object> item) {
        String reason=text(item,"reason");
        if(!PAYMENT_STUCK.contains(reason) && !REFUND_STUCK.contains(reason)) throw BillingProblem.conflict("Only a stuck payment or refund can be released");
        if(!unresolved(item)) throw BillingProblem.conflict("The payment or refund is no longer unresolved; refresh the case");
        if(PAYMENT_STUCK.contains(reason) && payments.hasCapture(id(item.get("intentId")))) throw BillingProblem.conflict("Capture evidence exists; this payment cannot be released");
        if(replayPossible(item)) throw BillingProblem.conflict("Recovery can still settle this refund by replaying its saved request. A release is allowed only once replay is no longer possible.");
    }
    public boolean replayPossible(Map<String,Object> item) {
        return "REFUND_UNRESOLVED".equals(text(item,"reason"))
            && db.exists("SELECT 1 FROM billing.refunds WHERE id=? AND original_request IS NOT NULL",id(item.get("refundId")))
            && !BillingProviderEvidence.replayForbidden(db,id(item.get("intentId")),id(item.get("refundId")));
    }
    public static void requireOpen(Map<String,Object> item) {
        if(!"OPEN".equals(item.get("status"))) throw BillingProblem.conflict("This case is already resolved");
    }
    public void requireRole(String user,Map<String,Object> item,String... roles) {
        if(!hasRole(user,id(item.get("merchantId")),roles)) throw new BillingProblem(403,"FORBIDDEN","This case action requires an assigned merchant permission");
    }
    public boolean hasRole(String user,UUID merchant,String... roles) {
        for(String role:roles) if(db.exists("SELECT 1 FROM billing.staff WHERE user_id=? AND merchant_id=? AND role=?",user,merchant,role)) return true;
        return false;
    }
    public static String requireNote(String note) {
        String value=note==null?"":note.trim();
        if(value.length()<10 || value.length()>1000) throw BillingProblem.invalid("Record what you checked, in 10 to 1000 characters");
        return value;
    }
    public Map<String,Object> view(UUID caseId) {
        return db.one("""
            SELECT id::text,intent_id::text,refund_id::text,reason,status,created_at,resolved_at,resolved_by,resolution,
              release_requested_by,release_note,release_requested_at FROM billing.cases WHERE id=?
            """,caseId);
    }
    public void requireAnyStaff(String user) {
        if(!db.exists("SELECT 1 FROM billing.staff WHERE user_id=?",user))throw new BillingProblem(403,"FORBIDDEN","Merchant finance access is required");
    }
}
