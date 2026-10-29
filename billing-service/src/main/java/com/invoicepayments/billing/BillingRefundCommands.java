package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import org.json.JSONObject;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;

/**
 * Short named refund units. Each locks the payment, then the refund, as every other path does. Provider I/O belongs to
 * BillingRefundService and BillingRecovery; called from an inbox, recovery or case command, these join its transaction.
 */
@Service
@Profile("billing")
@Transactional(transactionManager="transactionManager",propagation=Propagation.REQUIRED,rollbackFor=Exception.class,timeout=10,label="billing")
public class BillingRefundCommands {
    static final Set<String> RECORDABLE=Set.of("REFUNDED","FAILED","PROCESSING","UNKNOWN","REQUIRES_ACTION");
    private final BillingStore db;private final BillingPaymentAccess payments;private final BillingPaymentLocks locks;private final BillingRefundAccess refunds;
    public BillingRefundCommands(BillingStore db,BillingPaymentAccess payments,BillingPaymentLocks locks,BillingRefundAccess refunds) {
        this.db=db;this.payments=payments;this.locks=locks;this.refunds=refunds;
    }
    public Map<String,Object> request(String user,UUID intent,String key,long minor,String reason) {
        BillingExecutionBudget.requireTime();requireTransaction();
        BillingService.validateKey(key);
        if(minor<=0 || reason==null || reason.isBlank() || reason.length()>500) throw BillingProblem.invalid("A positive amount and a reason of at most 500 characters are required");
        var payment=locks.locked(intent); payments.staff(user,refunds.merchant(payment),"FINANCE");
        String fingerprint=hash(minor+":"+reason);
        var existing=db.rows("SELECT * FROM billing.refunds WHERE intent_id=? AND idempotency_key=?",intent,key);
        if(!existing.isEmpty()) {
            if(!fingerprint.equals(existing.get(0).get("requestHash"))) throw BillingProblem.conflict("Refund key belongs to a different request");
            return existing.get(0);
        }
        refunds.requireNoRefundConflict(intent);
        if(!payments.hasCapture(intent) || !"CARD".equals(payment.get("method")) || !"CAPTURED".equals(payment.get("status")))
            throw BillingProblem.conflict("This workflow supports refunds of captured card payments; ACH returns use their own workflow");
        long committed=db.rows("SELECT COALESCE(SUM(amount_minor),0) AS total FROM billing.refunds WHERE intent_id=? AND status NOT IN ('FAILED','REJECTED','RELEASED')",intent)
            .stream().mapToLong(x->amount(x,"total")).sum();
        if(minor>amount(payment,"amountMinor")-committed) throw BillingProblem.conflict("Refund exceeds the amount remaining after existing requests");
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO billing.refunds(id,intent_id,amount_minor,reason,requested_by,status,idempotency_key,request_hash) VALUES(?,?,?,?,?,'REQUESTED',?,?)",id,intent,minor,reason,user,key,fingerprint);
        db.event(intent,"REFUND_REQUESTED","Refund requested and awaits a different authorized approver");
        return refunds.raw(id);
    }
    public void approve(String user,UUID refund) {
        BillingExecutionBudget.requireTime();requireTransaction();
        UUID intent=id(refunds.raw(refund).get("intentId"));
        var payment=locks.locked(intent); payments.staff(user,refunds.merchant(payment),"REFUND_APPROVER");
        var row=lockedRefund(refund);
        if(user.equals(row.get("requestedBy"))) throw new BillingProblem(403,"SEPARATION_OF_DUTIES","The requester cannot approve their own refund");
        if(!"REQUESTED".equals(row.get("status"))) return;
        refunds.requireNoRefundConflict(intent);
        db.update("UPDATE billing.refunds SET status='APPROVED',approved_by=?,approved_at=NOW(),updated_at=NOW() WHERE id=?",user,refund);
        db.event(intent,"REFUND_APPROVED","A different authorized employee approved the refund");
    }
    /**
     * A requested refund can be rejected or withdrawn. An approver can also reject an approved refund
     * that has never been prepared for provider submission. No money moves; rejection stays final.
     */
    public Map<String,Object> reject(String user,UUID refund,String note) {
        BillingExecutionBudget.requireTime();requireTransaction();
        String reason=note==null?"":note.trim();
        if(reason.length()<10 || reason.length()>1000) throw BillingProblem.invalid("Record why the refund is rejected, in 10 to 1000 characters");
        UUID intent=id(refunds.raw(refund).get("intentId"));
        var payment=locks.locked(intent); UUID merchant=refunds.merchant(payment);
        var row=lockedRefund(refund);
        boolean unsentApproval="APPROVED".equals(text(row,"status")) && row.get("originalRequest")==null
            && row.get("submittedAt")==null && row.get("providerId")==null;
        if(unsentApproval) {
            payments.staff(user,merchant,"REFUND_APPROVER");
            if(user.equals(row.get("requestedBy"))) throw new BillingProblem(403,"SEPARATION_OF_DUTIES","A different authorized approver must reject an approved refund");
        } else {
            boolean requester=user.equals(row.get("requestedBy"))
                && db.exists("SELECT 1 FROM billing.staff WHERE user_id=? AND merchant_id=? AND role='FINANCE'",user,merchant);
            if(!requester) payments.staff(user,merchant,"REFUND_APPROVER");
            if("REJECTED".equals(row.get("status"))) return row;
            if(!"REQUESTED".equals(row.get("status"))) throw BillingProblem.conflict("Only a requested refund or an approved refund that was never prepared for sending can be rejected");
        }
        db.update("UPDATE billing.refunds SET status='REJECTED',rejected_by=?,rejected_at=NOW(),rejection_note=?,lease_token=NULL,lease_until=NULL,updated_at=NOW() WHERE id=?",user,reason,refund);
        db.event(intent,"REFUND_REJECTED",unsentApproval?"Approved refund rejected before provider submission; the original approval is retained and its amount is available again"
            :"Refund request rejected before approval; no money moved and its amount is available again");
        return refunds.raw(refund);
    }
    /**
     * Saves the exact request, its provider format and its age before provider I/O, under a fresh lease. A crash or
     * lost response can then replay this request, never a different one. Null when the refund is not APPROVED.
     */
    public Map<String,Object> prepareSubmission(UUID refund,UUID token,String providerName,String requestVersion) {
        BillingExecutionBudget.requireTime();requireTransaction();
        UUID intent=id(refunds.raw(refund).get("intentId"));
        var payment=locks.locked(intent);
        payments.requireProvider(payment,providerName);
        var row=lockedRefund(refund);
        if(!"APPROVED".equals(row.get("status"))) return null;
        refunds.requireNoRefundConflict(intent);
        if(text(payment,"providerId")==null) throw BillingProblem.conflict("The original provider payment reference is missing; reconcile it before refunding");
        RefundCommand command=new RefundCommand(refund.toString(),intent.toString(),text(payment,"providerId"),BillingRefundAccess.operationKey(refund),amount(row,"amountMinor"),text(payment,"currency"));
        db.update("""
            UPDATE billing.refunds SET status='SUBMITTING',lease_token=?,lease_until=NOW()+INTERVAL '120 seconds',
              next_recovery_at=NOW()+INTERVAL '120 seconds',original_request=?::jsonb,request_version=?,submitted_at=NOW(),updated_at=NOW()
            WHERE id=?
            """,token,command.toJson(),requestVersion,refund);
        db.event(intent,"REFUND_SUBMITTING","Refund submission prepared; provider acceptance is not yet known");
        return db.one("SELECT original_request,lease_until FROM billing.refunds WHERE id=?",refund);
    }
    /** A rejected request is not evidence that another worker's refund failed. */
    public void recordInitialRejection(UUID refund,UUID token,ProviderRejectedException rejected) {
        BillingExecutionBudget.requireTime();requireTransaction();
        UUID intent=id(refunds.raw(refund).get("intentId"));
        locks.locked(intent);
        var row=lockedRefund(refund);
        BillingProviderEvidence.record(db,intent,refund,rejected.diagnostics());
        if(!token.toString().equals(text(row,"leaseToken")) || !"SUBMITTING".equals(text(row,"status")) || row.get("providerId")!=null) {
            db.event(intent,"REFUND_REQUEST_REJECTION_IGNORED","An earlier request rejection did not replace the refund's current provider evidence");
            return;
        }
        // Token replacement fences another sender; mere expiry without a takeover does not invalidate this rejection.
        apply(refund,token,new ProviderResult(null,"FAILED",null,"The initial refund request was rejected before an operation was created"),null,false);
    }
    /** Fences out recovery while a release asks the provider to prove there is no refund. Refused while recovery is calling it. */
    public Map<String,Object> leaseForRelease(UUID refund,UUID token) {
        BillingExecutionBudget.requireTime();requireTransaction();
        if(db.update("UPDATE billing.refunds SET lease_token=?,lease_until=NOW()+INTERVAL '120 seconds',next_recovery_at=NOW()+INTERVAL '120 seconds' WHERE id=? AND (lease_until IS NULL OR lease_until<NOW())",token,refund)==0)
            throw BillingProblem.conflict("Recovery is checking this refund with the provider right now. Try the release again in two minutes");
        return db.one("SELECT r.*,i.provider_id AS payment_provider_id FROM billing.refunds r JOIN billing.intents i ON i.id=r.intent_id WHERE r.id=?",refund);
    }
    /**
     * Approved case release, after the provider showed no refund with this ID and replay is no longer possible.
     * The lease taken by leaseForRelease fences the release. If the provider later reports the refund, record()
     * still posts it, or raises a case if capacity was reused.
     * RELEASED, not FAILED: a staff release is not provider evidence that the refund failed.
     */
    public void releaseUnconfirmed(UUID refund,UUID fence,String message) {
        BillingExecutionBudget.requireTime();requireTransaction();
        UUID intent=id(refunds.raw(refund).get("intentId"));
        locks.locked(intent);
        var row=lockedRefund(refund);
        if(!fence.toString().equals(text(row,"leaseToken")))
            throw BillingProblem.conflict("Another process took over this refund during the release check; nothing was released. Try again");
        if(!Set.of("SUBMITTING","UNKNOWN").contains(text(row,"status"))) throw BillingProblem.conflict("This refund is no longer unresolved; refresh the case");
        db.update("UPDATE billing.refunds SET status='RELEASED',lease_token=NULL,lease_until=NULL,updated_at=NOW() WHERE id=?",refund);
        db.event(intent,"REFUND_RELEASED",message);
    }
    public void record(UUID refund,UUID fence,ProviderResult result) {
        BillingExecutionBudget.requireTime();requireTransaction();apply(refund,fence,result,null,false);
    }
    /** The inbox supplies its durable provider/event identity so redelivery cannot reopen a reviewed conflict. */
    public void record(UUID refund,UUID fence,ProviderResult result,String evidenceKey) {
        BillingExecutionBudget.requireTime();requireTransaction();apply(refund,fence,result,evidenceKey,false);
    }
    /** Called only by the approver's fresh provider-reference reconciliation, inside the case transaction. */
    public String reconcileVerifiedFailure(UUID refund,ProviderResult verified) {
        BillingExecutionBudget.requireTime();requireTransaction();
        UUID intent=id(refunds.raw(refund).get("intentId"));
        var payment=locks.locked(intent);
        var row=lockedRefund(refund);
        if(!"FAILED".equals(text(row,"status")) || row.get("approvedBy")==null)
            throw BillingProblem.conflict("The refund changed during reconciliation; refresh the case");
        if(verified.providerId()==null || !verified.providerId().equals(text(row,"providerId")) || !Set.of("REFUNDED","FAILED").contains(verified.state()))
            throw BillingProblem.conflict("The provider has not confirmed the original refund's final outcome");
        if("FAILED".equals(verified.state())) return null;
        if(!payments.hasCapture(intent) || amount(row,"amountMinor")>amount(payment,"amountMinor")-amount(payment,"refundedMinor"))
            return "The provider confirmed the refund, but the recorded capture cannot cover it. Finance reconciliation is required; the evidence and refund hold remain.";
        long committed=amount(db.one("SELECT COALESCE(SUM(amount_minor),0) AS total FROM billing.refunds WHERE intent_id=? AND id<>? AND status NOT IN ('FAILED','REJECTED','RELEASED')",intent,refund),"total");
        if(amount(row,"amountMinor")>amount(payment,"amountMinor")-committed)
            return "The provider confirmed the refund, but other refund requests already use its capacity. Review those requests; no money record was overwritten and the refund hold remains.";
        // The case transaction already saved the diagnostics, including checks that could not yet post money.
        apply(refund,null,new ProviderResult(verified.providerId(),verified.state(),verified.checkoutUrl(),verified.message()),"staff-verified:"+refund,true);
        return null;
    }

    private Map<String,Object> lockedRefund(UUID refund) { return db.one("SELECT * FROM billing.refunds WHERE id=? FOR UPDATE",refund); }
    private void apply(UUID refund,UUID fence,ProviderResult result,String evidenceKey,boolean reconciledFailure) {
        UUID intent=id(refunds.raw(refund).get("intentId"));
        var payment=locks.locked(intent); var row=lockedRefund(refund);
        BillingProviderEvidence.record(db,intent,refund,result.diagnostics());
        boolean owner=fence==null || fence.toString().equals(text(row,"leaseToken"));
        // A superseded worker's uncertain answer is dropped; a refund the provider made or definitively failed is still recorded.
        if(!owner && !Set.of("REFUNDED","FAILED").contains(result.state())) return;
        if(result.providerId()!=null && row.get("providerId")!=null && !result.providerId().equals(row.get("providerId"))) throw BillingProblem.conflict("Refund identity mismatch");
        // REVIEW (contradictory signed evidence) and unrecognised states never change status, money or capacity.
        // Checked before the terminal shortcuts so nothing is silently consumed; the caller's rollback retains the evidence.
        if(result.state()==null || !RECORDABLE.contains(result.state())) throw BillingProblem.conflict("Refund evidence requires finance review");
        if("REFUNDED".equals(row.get("status"))) {
            if("FAILED".equals(result.state())) db.escalate(intent,refund,"REFUND_LATER_FAILED",refund.toString(),"The provider again reported that this refund failed");
            return;
        }
        // Arrival order does not establish which contradictory terminal result is current.
        // Preserve the evidence and hold further refunds until a fresh provider verification resolves it.
        String status=text(row,"status");
        if(Set.of("FAILED","RELEASED").contains(status) && Set.of("PROCESSING","UNKNOWN","REQUIRES_ACTION").contains(result.state())) return;
        if("FAILED".equals(status) && "REFUNDED".equals(result.state()) && !reconciledFailure) {
            if(row.get("approvedBy")==null) throw BillingProblem.conflict("Provider evidence has no independently approved refund");
            preserveConflict(intent,refund,row,result,fence,evidenceKey,owner);
            return;
        }
        if(row.get("approvedBy")==null) throw BillingProblem.conflict("Provider evidence has no independently approved refund");
        if("REFUNDED".equals(result.state())) {
            if(!payments.hasCapture(intent) || row.get("approvedBy")==null) throw BillingProblem.conflict("Refund evidence has no approved capture");
            long minor=amount(row,"amountMinor"); long refunded=amount(payment,"refundedMinor");
            if(minor>amount(payment,"amountMinor")-refunded) throw BillingProblem.conflict("Refund evidence exceeds capture; finance reconciliation required");
            long remaining=minor; boolean allocated=false;
            for(var allocation:locks.lockInvoices(intent)) {
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
        // Only the lease owner returns the lease; a superseded worker leaves the current owner's lease alone.
        if(owner) db.update("UPDATE billing.refunds SET status=?,provider_id=COALESCE(provider_id,?),lease_token=NULL,lease_until=NULL,next_recovery_at=NOW()+INTERVAL '30 seconds',updated_at=NOW() WHERE id=?",state,result.providerId(),refund);
        else db.update("UPDATE billing.refunds SET status=?,provider_id=COALESCE(provider_id,?),updated_at=NOW() WHERE id=?",state,result.providerId(),refund);
        // A failed provider object needs finance follow-up. Initial request/config rejection has no provider object.
        if("FAILED".equals(state) && result.providerId()!=null && (!"FAILED".equals(status) || row.get("providerId")==null)) {
            db.escalate(intent,refund,"REFUND_FAILED",refund.toString(),"The provider confirmed this refund failed; review how the customer will receive their refund");
            db.event(intent,"REFUND_FAILED","The provider confirmed this refund failed; finance review is required");
        }
        // Provider evidence that ends the uncertainty also closes the stuck-refund cases it explains.
        if(!"UNKNOWN".equals(state))
            db.resolveCases(refund.toString(),"REFUND_UNRESOLVED","REFUND_REPLAY_UNSAFE","system","PROVIDER_EVIDENCE","AUTO_RESOLVED",
                "Provider evidence recorded refund status "+state);
    }
    private void preserveConflict(UUID intent,UUID refund,Map<String,Object> row,ProviderResult result,UUID fence,String evidenceKey,boolean owner) {
        String reference=result.providerId()!=null?result.providerId():text(row,"providerId");
        if(reference==null) throw BillingProblem.conflict("Conflicting refund evidence has no provider reference");
        String identity=hash(evidenceKey!=null?evidenceKey:"provider-result:"+reference+":"+result.state()+":"+Objects.toString(fence,"direct"));
        if(db.exists("""
            SELECT 1 FROM billing.case_actions a JOIN billing.cases c ON c.id=a.case_id
            WHERE c.refund_id=? AND c.reason='REFUND_EVIDENCE_CONFLICT'
              AND a.action='CONTRADICTORY_PROVIDER_EVIDENCE' AND a.evidence->>'evidenceKeyHash'=?
            """,refund,identity)) return;
        db.escalate(intent,refund,"REFUND_EVIDENCE_CONFLICT",refund.toString(),"Refund success conflicts with the recorded failure; further refunds are held until verified reconciliation");
        UUID caseId=id(db.one("SELECT id FROM billing.cases WHERE reason='REFUND_EVIDENCE_CONFLICT' AND reference=?",refund.toString()).get("id"));
        db.caseAction(caseId,"CONTRADICTORY_PROVIDER_EVIDENCE","system","Success evidence retained without assuming it is newer than the recorded failure",
            new JSONObject().put("providerReference",reference).put("providerState",result.state()).put("evidenceKeyHash",identity).toString());
        db.update("UPDATE billing.refunds SET provider_id=COALESCE(provider_id,?),updated_at=NOW() WHERE id=?",reference,refund);
        if(owner) db.update("UPDATE billing.refunds SET lease_token=NULL,lease_until=NULL WHERE id=?",refund);
        db.event(intent,"REFUND_EVIDENCE_CONFLICT","Conflicting refund results require verified finance reconciliation; further refunds are held");
    }
}
