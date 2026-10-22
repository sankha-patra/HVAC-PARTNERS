package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import org.json.JSONObject;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;

/**
 * Staff resolution of exception cases. Nothing here can mark a payment paid. A stuck payment or
 * refund is resolved only by (1) a provider reference that the provider itself verifies, or
 * (2) a release requested by one employee and approved by a different one. Informational cases
 * close with a review note. Every action is written to an append-only audit table.
 */
@Service
@Profile("billing")
public class BillingCaseService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(BillingCaseService.class);
    static final Set<String> PAYMENT_STUCK=Set.of("PAYMENT_UNRESOLVED","PAYMENT_REPLAY_UNSAFE");
    static final Set<String> REFUND_STUCK=Set.of("REFUND_UNRESOLVED","REFUND_REPLAY_UNSAFE");
    private final BillingStore db; private final BillingService payments; private final BillingRefundService refunds; private final BillingProvider provider;
    public BillingCaseService(BillingStore db,BillingService payments,BillingRefundService refunds,BillingProvider provider) {
        this.db=db;this.payments=payments;this.refunds=refunds;this.provider=provider;
    }

    /** Cases and unmatched provider evidence in the user's merchant scope, with the actions the server allows this user. */
    public List<Map<String,Object>> list(String user) {
        refunds.requireAnyStaff(user);
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
            boolean maker=hasRole(user,merchant,"OPS","FINANCE"), checker=hasRole(user,merchant,"REFUND_APPROVER");
            boolean open="OPEN".equals(row.get("status")), unresolved=unresolved(row);
            boolean stuck=PAYMENT_STUCK.contains(text(row,"reason")) || REFUND_STUCK.contains(text(row,"reason"));
            boolean pending=row.get("releaseRequestedBy")!=null;
            boolean releasable=open && unresolved && !replayPossible(row);
            row.put("canVerifyReference",open && stuck && unresolved && maker);
            row.put("canRequestRelease",releasable && stuck && maker && !pending);
            row.put("canApproveRelease",releasable && pending && checker && !user.equals(row.get("releaseRequestedBy")));
            row.put("canClose",open && !unresolved && maker);
            row.remove("merchantId");
        }
        var evidence=db.rows("""
            SELECT w.provider||':'||w.event_id AS id,w.payload->>'intentId' AS intent_id,'UNMATCHED_WEBHOOK' AS reason,'OPEN' AS status,w.received_at AS created_at
            FROM billing.webhook_inbox w WHERE w.state='UNMATCHED'
            AND EXISTS(SELECT 1 FROM billing.staff s WHERE s.user_id=? AND s.merchant_id=w.merchant_id)
            ORDER BY w.received_at DESC LIMIT 200
            """,user);
        for(var row:evidence) for(String flag:List.of("canVerifyReference","canRequestRelease","canApproveRelease","canClose")) row.put(flag,false);
        List<Map<String,Object>> result=new ArrayList<>(evidence); result.addAll(rows);
        return result;
    }

    public List<Map<String,Object>> history(String user,UUID caseId) {
        visible(user,caseId);
        return db.rows("SELECT action,actor,note,evidence::text AS evidence,created_at FROM billing.case_actions WHERE case_id=? ORDER BY created_at,id",caseId);
    }

    /** A provider reference counts only if the provider confirms it belongs to this exact operation. */
    public Map<String,Object> verifyProviderReference(String user,UUID caseId,String reference,String note) {
        requireNoTransaction(); // provider I/O follows; never hold a database transaction across it
        String reason=requireNote(note);
        if(reference==null || !reference.matches("[A-Za-z0-9_\\-]{3,255}")) throw BillingProblem.invalid("Enter the provider's reference for this operation");
        var item=visible(user,caseId); requireRole(user,item,"OPS","FINANCE"); requireOpen(item);
        String kind=text(item,"reason");
        UUID intent=id(item.get("intentId"));
        ProviderResult result;
        if(PAYMENT_STUCK.contains(kind)) {
            var payment=payments.raw(intent); payments.requireProvider(payment);
            if(!Set.of("UNKNOWN","SUBMITTING").contains(text(payment,"status"))) throw BillingProblem.conflict("This payment is no longer unresolved; refresh the case");
            if(payment.get("providerId")!=null && !reference.equals(payment.get("providerId")))
                throw BillingProblem.conflict("This payment already has a different verified provider reference");
            result=provider.verifyPaymentReference(new ProviderCommand(intent.toString(),BillingService.operationKey(intent),
                amount(payment,"amountMinor"),text(payment,"currency"),text(payment,"method"),null),reference);
        } else if(REFUND_STUCK.contains(kind)) {
            UUID refund=id(item.get("refundId"));
            var row=refunds.raw(refund);
            var payment=payments.raw(intent); payments.requireProvider(payment);
            if(!intent.toString().equals(text(row,"intentId"))) throw BillingProblem.conflict("Refund does not belong to this payment");
            if(!Set.of("SUBMITTING","UNKNOWN").contains(text(row,"status"))) throw BillingProblem.conflict("This refund is no longer unresolved; refresh the case");
            if(row.get("providerId")!=null && !reference.equals(row.get("providerId")))
                throw BillingProblem.conflict("This refund already has a different verified provider reference");
            if(text(payment,"providerId")==null) throw BillingProblem.conflict("The original verified payment reference is required");
            result=provider.verifyRefundReference(new RefundCommand(refund.toString(),intent.toString(),text(payment,"providerId"),
                BillingRefundService.operationKey(refund),amount(row,"amountMinor"),text(payment,"currency")),reference);
        } else throw BillingProblem.conflict("This case is closed after review, not by attaching a provider reference");
        Set<String> accepted=PAYMENT_STUCK.contains(kind) ? Set.of("CAPTURED","FAILED","PROCESSING","REQUIRES_ACTION")
            : Set.of("REFUNDED","FAILED","PROCESSING");
        if(!reference.equals(result.providerId()) || !accepted.contains(result.state()))
            throw new BillingProblem(409,"PROVIDER_REFERENCE_NOT_VERIFIED","The provider did not confirm that this reference belongs to this operation; nothing was changed");
        ProviderResult verified=result;
        return db.tx(() -> {
            // One commit: the verified evidence, any money effect it implies, the case and its audit entry.
            var locked=lock(item);
            requireOpen(locked);
            db.update("UPDATE billing.cases SET status='RESOLVED',resolved_at=NOW(),resolved_by=?,resolution='STAFF_VERIFIED_PROVIDER_REFERENCE',release_requested_by=NULL,release_note=NULL,release_requested_at=NULL WHERE id=?",user,caseId);
            db.caseAction(caseId,"VERIFIED_PROVIDER_REFERENCE",user,reason,
                new JSONObject().put("providerReference",reference).put("providerState",verified.state()).toString());
            if(PAYMENT_STUCK.contains(kind)) payments.recordResult(intent,null,verified);
            else refunds.record(id(item.get("refundId")),null,verified);
            return view(caseId);
        });
    }

    /** Maker step: one employee asks to release a stuck operation the provider never performed. */
    public Map<String,Object> requestRelease(String user,UUID caseId,String note) {
        String reason=requireNote(note);
        var item=visible(user,caseId);
        return db.tx(() -> {
            var locked=lock(item); requireRole(user,locked,"OPS","FINANCE"); requireOpen(locked); requireReleasable(locked);
            if(locked.get("releaseRequestedBy")!=null) throw BillingProblem.conflict("A release request already awaits a different approver");
            db.update("UPDATE billing.cases SET release_requested_by=?,release_note=?,release_requested_at=NOW() WHERE id=?",user,reason,caseId);
            db.caseAction(caseId,"REQUESTED_RELEASE",user,reason,null);
            return view(caseId);
        });
    }

    /**
     * Checker step: a different authorized approver confirms. Before anything is released, the provider must prove
     * the operation does not exist and cannot appear: an open payment page is expired first. The operation's lease
     * keeps recovery from replaying it during that check, and fences the release itself.
     */
    public Map<String,Object> approveRelease(String user,UUID caseId) {
        requireNoTransaction(); // provider I/O happens between two short transactions
        var item=visible(user,caseId);
        boolean payment=PAYMENT_STUCK.contains(text(item,"reason"));
        UUID intent=id(item.get("intentId")), token=UUID.randomUUID();
        var claim=db.tx(() -> {
            var locked=lock(item); requireApprovable(user,locked);
            // Only the payment's original provider can prove there is nothing to release: absence elsewhere proves nothing.
            if(!provider.name().equals(text(payments.raw(intent),"provider")))
                throw BillingProblem.conflict("Only this payment's original provider can confirm a release. Restore it before approving.");
            return payment ? payments.leaseForRelease(intent,token) : refunds.leaseForRelease(id(locked.get("refundId")),token);
        });
        ProviderResult proof;
        try {
            proof=payment ? provider.closePayment(payments.savedCommand(claim),text(claim,"requestVersion"),payments.preparedAt(claim),text(claim,"providerId"))
                : provider.findRefund(refunds.savedCommand(claim),text(claim,"providerId"));
        } catch(RuntimeException failure) {
            log.warn("Release check got no definite answer from the provider caseId={}",caseId,failure);
            proof=new ProviderResult(null,"UNKNOWN",null,"The provider check gave no definite answer");
        }
        if("ABSENT".equals(proof.state())) return db.tx(() -> {
            var locked=lock(item); requireApprovable(user,locked);
            String note=text(locked,"releaseNote");
            if(payment) {
                payments.releaseUnresolved(intent,token,
                    "Two authorized employees approved the release after the provider confirmed it has no payment and can take none; invoice reservations released.");
                db.resolveCases(intent.toString(),"PAYMENT_UNRESOLVED","PAYMENT_REPLAY_UNSAFE",user,"RELEASED_NO_PROVIDER_OPERATION","APPROVED_RELEASE",note);
            } else {
                refunds.releaseUnconfirmed(id(locked.get("refundId")),token,
                    "Two authorized employees approved the release after the provider showed no such refund; refund capacity released.");
                db.resolveCases(text(locked,"refundId"),"REFUND_UNRESOLVED","REFUND_REPLAY_UNSAFE",user,"RELEASED_NO_PROVIDER_OPERATION","APPROVED_RELEASE",note);
            }
            return view(caseId);
        });
        // The provider has the operation, or could not prove it does not. Record its answer, which also returns the lease.
        ProviderResult answer=proof;
        db.tx(() -> {
            if(payment) payments.recordResult(intent,token,answer); else refunds.record(id(item.get("refundId")),token,answer);
            return null;
        });
        if("UNKNOWN".equals(proof.state()))
            throw new BillingProblem(409,"PROVIDER_ABSENCE_NOT_CONFIRMED","The provider could not confirm that nothing exists, so nothing was released. Try again later or attach the provider reference.");
        throw new BillingProblem(409,"PROVIDER_HAS_OPERATION","The provider has this "+(payment?"payment":"refund")+" (status "+proof.state()+"), so nothing was released. Its status was recorded.");
    }
    private void requireApprovable(String user,Map<String,Object> locked) {
        requireRole(user,locked,"REFUND_APPROVER"); requireOpen(locked);
        if(locked.get("releaseRequestedBy")==null) throw BillingProblem.conflict("No release request is waiting for approval");
        if(user.equals(locked.get("releaseRequestedBy"))) throw new BillingProblem(403,"SEPARATION_OF_DUTIES","The requester cannot approve their own release");
        requireReleasable(locked);
    }

    public Map<String,Object> rejectRelease(String user,UUID caseId,String note) {
        String reason=requireNote(note);
        var item=visible(user,caseId);
        return db.tx(() -> {
            var locked=lock(item); requireRole(user,locked,"REFUND_APPROVER"); requireOpen(locked);
            if(locked.get("releaseRequestedBy")==null) throw BillingProblem.conflict("No release request is waiting for approval");
            if(user.equals(locked.get("releaseRequestedBy"))) throw new BillingProblem(403,"SEPARATION_OF_DUTIES","The requester cannot review their own release");
            db.update("UPDATE billing.cases SET release_requested_by=NULL,release_note=NULL,release_requested_at=NULL WHERE id=?",caseId);
            db.caseAction(caseId,"REJECTED_RELEASE",user,reason,null);
            return view(caseId);
        });
    }

    /** Closes a case after review. Refused while the payment or refund it describes is still unresolved. */
    public Map<String,Object> close(String user,UUID caseId,String note) {
        String reason=requireNote(note);
        var item=visible(user,caseId);
        return db.tx(() -> {
            var locked=lock(item); requireRole(user,locked,"OPS","FINANCE"); requireOpen(locked);
            if(unresolved(locked)) throw BillingProblem.conflict("The payment or refund is still unresolved. Verify a provider reference or request an approved release instead.");
            db.update("UPDATE billing.cases SET status='RESOLVED',resolved_at=NOW(),resolved_by=?,resolution='CLOSED_AFTER_REVIEW',release_requested_by=NULL,release_note=NULL,release_requested_at=NULL WHERE id=?",user,caseId);
            db.caseAction(caseId,"CLOSED_AFTER_REVIEW",user,reason,null);
            return view(caseId);
        });
    }

    private Map<String,Object> visible(String user,UUID caseId) {
        var rows=db.rows("""
            SELECT c.*,i.status AS payment_status,r.status AS refund_status FROM billing.cases c
            LEFT JOIN billing.intents i ON i.id=c.intent_id LEFT JOIN billing.refunds r ON r.id=c.refund_id
            WHERE c.id=? AND EXISTS(SELECT 1 FROM billing.staff s WHERE s.user_id=? AND s.merchant_id=c.merchant_id)
            """,caseId,user);
        if(rows.isEmpty()) throw BillingProblem.missing(); // never reveal cases outside the user's merchant scope
        return rows.get(0);
    }
    /** Lock order matches money paths: payment, then refund, then case. */
    private Map<String,Object> lock(Map<String,Object> item) {
        UUID caseId=id(item.get("id"));
        if(item.get("intentId")!=null) payments.locked(id(item.get("intentId")));
        if(item.get("refundId")!=null) db.one("SELECT id FROM billing.refunds WHERE id=? FOR UPDATE",id(item.get("refundId")));
        db.one("SELECT id FROM billing.cases WHERE id=? FOR UPDATE",caseId);
        return db.one("""
            SELECT c.*,i.status AS payment_status,r.status AS refund_status FROM billing.cases c
            LEFT JOIN billing.intents i ON i.id=c.intent_id LEFT JOIN billing.refunds r ON r.id=c.refund_id WHERE c.id=?
            """,caseId);
    }
    private boolean unresolved(Map<String,Object> item) {
        String reason=text(item,"reason");
        if(PAYMENT_STUCK.contains(reason)) return Set.of("UNKNOWN","SUBMITTING").contains(text(item,"paymentStatus"));
        if(REFUND_STUCK.contains(reason)) return Set.of("SUBMITTING","UNKNOWN").contains(text(item,"refundStatus"));
        return false;
    }
    private void requireReleasable(Map<String,Object> item) {
        String reason=text(item,"reason");
        if(!PAYMENT_STUCK.contains(reason) && !REFUND_STUCK.contains(reason)) throw BillingProblem.conflict("Only a stuck payment or refund can be released");
        if(!unresolved(item)) throw BillingProblem.conflict("The payment or refund is no longer unresolved; refresh the case");
        if(PAYMENT_STUCK.contains(reason) && payments.hasCapture(id(item.get("intentId")))) throw BillingProblem.conflict("Capture evidence exists; this payment cannot be released");
        if(replayPossible(item)) throw BillingProblem.conflict("Recovery can still settle this refund by replaying its saved request. A release is allowed only once replay is no longer possible.");
    }
    /** A stuck refund whose saved request recovery can still replay must not be released: the replay may still create it. */
    private boolean replayPossible(Map<String,Object> item) {
        return "REFUND_UNRESOLVED".equals(text(item,"reason"))
            && db.exists("SELECT 1 FROM billing.refunds WHERE id=? AND original_request IS NOT NULL",id(item.get("refundId")));
    }
    private static void requireOpen(Map<String,Object> item) {
        if(!"OPEN".equals(item.get("status"))) throw BillingProblem.conflict("This case is already resolved");
    }
    private void requireRole(String user,Map<String,Object> item,String... roles) {
        if(!hasRole(user,id(item.get("merchantId")),roles)) throw new BillingProblem(403,"FORBIDDEN","This case action requires an assigned merchant permission");
    }
    private boolean hasRole(String user,UUID merchant,String... roles) {
        for(String role:roles) if(db.exists("SELECT 1 FROM billing.staff WHERE user_id=? AND merchant_id=? AND role=?",user,merchant,role)) return true;
        return false;
    }
    private static String requireNote(String note) {
        String value=note==null?"":note.trim();
        if(value.length()<10 || value.length()>1000) throw BillingProblem.invalid("Record what you checked, in 10 to 1000 characters");
        return value;
    }
    private Map<String,Object> view(UUID caseId) {
        return db.one("""
            SELECT id::text,intent_id::text,refund_id::text,reason,status,created_at,resolved_at,resolved_by,resolution,
              release_requested_by,release_note,release_requested_at FROM billing.cases WHERE id=?
            """,caseId);
    }
}
