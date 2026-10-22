package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;

@Service
@Profile("billing")
public class BillingRefundService {
    private final BillingStore db; private final BillingService payments; private final BillingProvider provider;
    public BillingRefundService(BillingStore db,BillingService payments,BillingProvider provider) {this.db=db;this.payments=payments;this.provider=provider;}
    public List<Map<String,Object>> list(String user) {
        requireAnyStaff(user);
        return db.rows("""
            SELECT r.id,r.intent_id,i.provider,r.amount_minor,r.reason,r.requested_by,r.approved_by,r.status,r.created_at,
            (r.status='REQUESTED' AND r.requested_by<>? AND EXISTS(SELECT 1 FROM billing.staff p WHERE p.merchant_id=a.merchant_id AND p.user_id=? AND p.role='REFUND_APPROVER')) AS can_approve
            FROM billing.refunds r JOIN billing.intents i ON i.id=r.intent_id JOIN billing.accounts a ON a.id=i.account_id
            WHERE EXISTS(SELECT 1 FROM billing.staff s WHERE s.merchant_id=a.merchant_id AND s.user_id=? AND s.role IN ('FINANCE','REFUND_APPROVER'))
            ORDER BY r.created_at DESC LIMIT 200
            """,user,user,user);
    }
    public Map<String,Object> request(String user,UUID intent,String key,long minor,String reason) {
        BillingService.validateKey(key);
        if(minor<=0 || reason==null || reason.isBlank() || reason.length()>500) throw BillingProblem.invalid("A positive amount and a reason of at most 500 characters are required");
        return db.tx(() -> {
            var payment=payments.locked(intent); payments.staff(user,merchant(payment),"FINANCE");
            String fingerprint=hash(minor+":"+reason);
            var existing=db.rows("SELECT * FROM billing.refunds WHERE intent_id=? AND idempotency_key=?",intent,key);
            if(!existing.isEmpty()) {
                if(!fingerprint.equals(existing.get(0).get("requestHash"))) throw BillingProblem.conflict("Refund key belongs to a different request");
                return existing.get(0);
            }
            if(!payments.hasCapture(intent) || !"CARD".equals(payment.get("method")) || !"CAPTURED".equals(payment.get("status")))
                throw BillingProblem.conflict("This workflow supports refunds of captured card payments; ACH returns use their own workflow");
            long committed=db.rows("SELECT COALESCE(SUM(amount_minor),0) AS total FROM billing.refunds WHERE intent_id=? AND status NOT IN ('FAILED','REJECTED','RELEASED')",intent)
                .stream().mapToLong(x->amount(x,"total")).sum();
            if(minor>amount(payment,"amountMinor")-committed) throw BillingProblem.conflict("Refund exceeds the amount remaining after existing requests");
            UUID id=UUID.randomUUID();
            db.update("INSERT INTO billing.refunds(id,intent_id,amount_minor,reason,requested_by,status,idempotency_key,request_hash) VALUES(?,?,?,?,?,'REQUESTED',?,?)",id,intent,minor,reason,user,key,fingerprint);
            db.event(intent,"REFUND_REQUESTED","Refund requested and awaits a different authorized approver");
            return raw(id);
        });
    }
    public Map<String,Object> approve(String user,UUID refund) {
        requireNoTransaction();
        UUID intent=id(raw(refund).get("intentId"));
        db.tx(() -> {
            var payment=payments.locked(intent); payments.staff(user,merchant(payment),"REFUND_APPROVER");
            var row=db.one("SELECT * FROM billing.refunds WHERE id=? FOR UPDATE",refund);
            if(user.equals(row.get("requestedBy"))) throw new BillingProblem(403,"SEPARATION_OF_DUTIES","The requester cannot approve their own refund");
            if(!"REQUESTED".equals(row.get("status"))) return null;
            db.update("UPDATE billing.refunds SET status='APPROVED',approved_by=?,approved_at=NOW(),updated_at=NOW() WHERE id=?",user,refund);
            db.event(intent,"REFUND_APPROVED","A different authorized employee approved the refund"); return null;
        });
        submitApproved(refund); return raw(refund);
    }
    public void submitApproved(UUID refund) {
        requireNoTransaction();
        UUID token=UUID.randomUUID(); UUID intent=id(raw(refund).get("intentId"));
        var work=db.tx(() -> {
            var payment=payments.locked(intent);
            payments.requireProvider(payment);
            var row=db.one("SELECT * FROM billing.refunds WHERE id=? FOR UPDATE",refund);
            if(!"APPROVED".equals(row.get("status"))) return null;
            if(text(payment,"providerId")==null) throw BillingProblem.conflict("The original provider payment reference is missing; reconcile it before refunding");
            RefundCommand command=new RefundCommand(refund.toString(),intent.toString(),text(payment,"providerId"),operationKey(refund),amount(row,"amountMinor"),"USD");
            // Save the exact request, its provider format and its age before provider I/O.
            // A crash or lost response can then replay this request, never a different one.
            db.update("""
                UPDATE billing.refunds SET status='SUBMITTING',lease_token=?,lease_until=NOW()+INTERVAL '45 seconds',
                  next_recovery_at=NOW()+INTERVAL '45 seconds',original_request=?::jsonb,request_version=?,submitted_at=NOW(),updated_at=NOW()
                WHERE id=?
                """,token,command.toJson(),provider.refundRequestVersion(),refund);
            db.event(intent,"REFUND_SUBMITTING","Refund submission prepared; provider acceptance is not yet known");
            return db.one("SELECT original_request FROM billing.refunds WHERE id=?",refund);
        });
        if(work==null) return;
        ProviderResult result;
        try { result=provider.refund(RefundCommand.fromJson(text(work,"originalRequest"))); }
        catch(RuntimeException failure) { result=new ProviderResult(null,"UNKNOWN",null,"Refund outcome is uncertain; recovery will reuse the saved request"); }
        record(refund,token,result);
    }
    static String operationKey(UUID refund) { return "refund_"+refund; }
    static final Set<String> RECORDABLE=Set.of("REFUNDED","FAILED","PROCESSING","UNKNOWN","REQUIRES_ACTION");
    /**
     * Approved case release: two employees confirmed the provider never created this refund.
     * Fences out in-flight recovery and frees the refund capacity. If the provider later
     * reports the refund, record() still posts it, or raises a case if capacity was reused.
     * RELEASED, not FAILED: a staff release is not provider evidence that the refund failed.
     */
    void releaseUnconfirmed(UUID refund,String message) {
        UUID intent=id(raw(refund).get("intentId"));
        db.tx(() -> {
            payments.locked(intent);
            var row=db.one("SELECT * FROM billing.refunds WHERE id=? FOR UPDATE",refund);
            if(!Set.of("SUBMITTING","UNKNOWN").contains(text(row,"status"))) throw BillingProblem.conflict("This refund is no longer unresolved; refresh the case");
            db.update("UPDATE billing.refunds SET status='RELEASED',lease_token=NULL,lease_until=NULL,updated_at=NOW() WHERE id=?",refund);
            db.event(intent,"REFUND_RELEASED",message);
            return null;
        });
    }
    public void record(UUID refund,UUID fence,ProviderResult result) {
        UUID intent=id(raw(refund).get("intentId"));
        db.tx(() -> {
            var payment=payments.locked(intent); var row=db.one("SELECT * FROM billing.refunds WHERE id=? FOR UPDATE",refund);
            if(fence!=null && !fence.toString().equals(text(row,"leaseToken"))) return null;
            if(result.providerId()!=null && row.get("providerId")!=null && !result.providerId().equals(row.get("providerId"))) throw BillingProblem.conflict("Refund identity mismatch");
            // REVIEW (contradictory signed evidence) and unrecognised states never change status, money or capacity.
            // Checked before the terminal shortcuts so nothing is silently consumed; the caller's rollback retains the evidence.
            if(result.state()==null || !RECORDABLE.contains(result.state())) throw BillingProblem.conflict("Refund evidence requires finance review");
            if("REFUNDED".equals(row.get("status"))) {
                if("FAILED".equals(result.state())) db.caseFor(intent,refund,"REFUND_LATER_FAILED",refund.toString());
                return null;
            }
            // A provider failure is final, and provider events can arrive out of order: older pending or
            // success evidence never reopens it. A staff release is not provider evidence, so a late success still posts.
            String status=text(row,"status");
            if(Set.of("FAILED","RELEASED").contains(status) && Set.of("PROCESSING","UNKNOWN","REQUIRES_ACTION").contains(result.state())) return null;
            if("FAILED".equals(status) && "REFUNDED".equals(result.state())) {
                db.event(intent,"REFUND_EVIDENCE_IGNORED","Older success evidence arrived after the provider confirmed this refund failed; no money was posted");
                return null;
            }
            if(row.get("approvedBy")==null) throw BillingProblem.conflict("Provider evidence has no independently approved refund");
            if("REFUNDED".equals(result.state())) {
                if(!payments.hasCapture(intent) || row.get("approvedBy")==null) throw BillingProblem.conflict("Refund evidence has no approved capture");
                long minor=amount(row,"amountMinor"); long refunded=amount(payment,"refundedMinor");
                if(minor>amount(payment,"amountMinor")-refunded) throw BillingProblem.conflict("Refund evidence exceeds capture; finance reconciliation required");
                long remaining=minor; boolean allocated=false;
                for(var allocation:payments.lockInvoices(intent)) {
                    if(!"POSTED".equals(allocation.get("state"))) continue;
                    allocated=true;
                    long portion=Math.min(remaining,amount(allocation,"amountMinor")-amount(allocation,"reversedMinor"));
                    if(portion>0) {
                        db.update("UPDATE billing.invoices SET paid_minor=paid_minor-?,version=version+1 WHERE id=?",portion,id(allocation.get("invoiceId")));
                        db.update("UPDATE billing.allocations SET reversed_minor=reversed_minor+? WHERE intent_id=? AND invoice_id=?",portion,intent,id(allocation.get("invoiceId")));
                        remaining-=portion;
                    }
                }
                if(allocated && remaining!=0) throw BillingProblem.conflict("Refund allocation does not balance");
                db.journal(payment,"refund:"+refund,allocated?"CUSTOMER_RECEIVABLES":"UNAPPLIED_CASH","PROVIDER_CLEARING",minor);
                db.update("UPDATE billing.intents SET refunded_minor=refunded_minor+?,status=CASE WHEN refunded_minor+?=amount_minor THEN 'REFUNDED' ELSE status END WHERE id=?",minor,minor,intent);
                db.event(intent,"REFUNDED","Confirmed refund posted; allocated refunds reopen invoice debt and unapplied-cash refunds reverse that liability");
                db.publish(payment,"PAYMENT_REFUNDED",refund,minor);
            }
            String state="REQUIRES_ACTION".equals(result.state()) ? "UNKNOWN" : result.state();
            db.update("UPDATE billing.refunds SET status=?,provider_id=COALESCE(provider_id,?),lease_token=NULL,lease_until=NULL,next_recovery_at=NOW()+INTERVAL '30 seconds',updated_at=NOW() WHERE id=?",state,result.providerId(),refund);
            // Provider evidence that ends the uncertainty also closes the stuck-refund cases it explains.
            if(!"UNKNOWN".equals(state))
                db.resolveCases(refund.toString(),"REFUND_UNRESOLVED","REFUND_REPLAY_UNSAFE","system","PROVIDER_EVIDENCE","AUTO_RESOLVED",
                    "Provider evidence recorded refund status "+state);
            return null;
        });
    }
    Map<String,Object> raw(UUID refund) { return db.one("SELECT * FROM billing.refunds WHERE id=?",refund); }
    UUID merchant(Map<String,Object> intent) { return id(db.one("SELECT merchant_id FROM billing.accounts WHERE id=?",id(intent.get("accountId"))).get("merchantId")); }
    void requireAnyStaff(String user) {
        if(!db.exists("SELECT 1 FROM billing.staff WHERE user_id=?",user)) throw new BillingProblem(403,"FORBIDDEN","Merchant finance access is required");
    }
}
