package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import org.json.JSONObject;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import com.invoicepayments.billing.BillingCaseService.ExternalConfirmation;
import static com.invoicepayments.billing.BillingStore.*;
import static com.invoicepayments.billing.BillingCaseService.*;

/** Case decisions and their financial effects commit as joined local units. No provider I/O. */
@Service
@Profile("billing")
@Transactional(transactionManager="transactionManager",propagation=Propagation.REQUIRED,rollbackFor=Exception.class,timeout=10,label="billing")
public class BillingCaseCommands {
    private final BillingStore db;private final BillingCaseAccess access;private final BillingCaseLocks locks;
    private final BillingPaymentCommands paymentCommands;private final BillingPaymentAccess paymentAccess;private final BillingPaymentLocks paymentLocks;
    private final BillingRefundCommands refunds;
    public BillingCaseCommands(BillingStore db,BillingCaseAccess access,BillingCaseLocks locks,BillingPaymentCommands paymentCommands,
            BillingPaymentAccess paymentAccess,BillingPaymentLocks paymentLocks,BillingRefundCommands refunds){
        this.db=db;this.access=access;this.locks=locks;this.paymentCommands=paymentCommands;this.paymentAccess=paymentAccess;this.paymentLocks=paymentLocks;this.refunds=refunds;
    }
    public record RefundConflictSnapshot(RefundCommand command,String updatedAt,long evidenceCount) {}
    private Map<String,Object> refundRaw(UUID refund){return db.one("SELECT * FROM billing.refunds WHERE id=?",refund);}
    public Map<String,Object> addNote(String user,UUID caseId,String note) {
        BillingExecutionBudget.requireTime();
        requireTransaction();
        String text=access.requireNote(note); access.requireNoAccountNumbers(text);
        var item=access.visible(user,caseId);
        var locked=locks.lock(item); access.requireRole(user,locked,"OPS","FINANCE","REFUND_APPROVER");
        db.caseAction(caseId,"ADDED_NOTE",user,text,null);
        return access.view(caseId);
    }
    public Map<String,Object> recordExternalConfirmation(String user,UUID caseId,ExternalConfirmation confirmation) {
        BillingExecutionBudget.requireTime();
        requireTransaction();
        var item=access.visible(user,caseId);
        access.requireRole(user,item,"FINANCE");
        ExternalConfirmation valid=access.validConfirmation(confirmation);
        Instant checkedAt=Instant.parse(valid.checkedAt());
        var locked=locks.lock(item); access.requireRole(user,locked,"FINANCE"); access.requireOpen(locked); access.requireReleasable(locked);
        if(locked.get("releaseRequestedBy")!=null) throw BillingProblem.conflict("A release request already awaits a different approver");
        // The confirmation must come from the provider that handled this payment.
        String provider=text(db.one("SELECT provider FROM billing.intents WHERE id=?",id(locked.get("intentId"))),"provider");
        // Razorpay payments need a Razorpay check; every other provider (Stripe, and the simulator that stands in for it) a Stripe one.
        if("RAZORPAY".equals(provider)!=valid.source().startsWith("RAZORPAY_")) throw BillingProblem.invalid("Record the confirmation from the provider that handled this payment ("+provider+")");
        Instant now=access.databaseNow(), earliest=access.externalCheckFrom(locked);
        if(now.isBefore(earliest))
            throw BillingProblem.conflict("Wait until the provider's waiting period after the payment was first sent has passed: until then something it created could still appear");
        if(checkedAt.isBefore(earliest)) throw BillingProblem.invalid("Check Stripe again: the check must be made after the waiting period, so it covers everything this payment could have created");
        if(checkedAt.isAfter(now.plus(CLOCK_TOLERANCE))) throw BillingProblem.invalid("The Stripe check time is in the future");
        if(checkedAt.isBefore(now.minus(EXTERNAL_EVIDENCE_LIFETIME))) throw BillingProblem.invalid("The Stripe check is more than 7 days old; check Stripe again");
        JSONObject evidence=new JSONObject().put("source",valid.source()).put("account",valid.account()).put("searched",valid.searched())
            .put("result",valid.result()).put("ticket",valid.ticket()==null?JSONObject.NULL:valid.ticket())
            .put("checkedAt",valid.checkedAt()).put("evidenceSha256",valid.evidenceSha256());
        db.update("UPDATE billing.cases SET release_requested_by=?,release_note=?,release_requested_at=NOW() WHERE id=?",user,valid.note(),caseId);
        db.caseAction(caseId,"RECORDED_EXTERNAL_CONFIRMATION",user,valid.note(),evidence.toString());
        return access.view(caseId);
    }
    public Map<String,Object> requestRelease(String user,UUID caseId,String note) {
        BillingExecutionBudget.requireTime();
        requireTransaction();
        String reason=access.requireNote(note);
        var item=access.visible(user,caseId);
        var locked=locks.lock(item); access.requireRole(user,locked,"FINANCE"); access.requireOpen(locked); access.requireReleasable(locked);
        if(locked.get("releaseRequestedBy")!=null) throw BillingProblem.conflict("A release request already awaits a different approver");
        db.update("UPDATE billing.cases SET release_requested_by=?,release_note=?,release_requested_at=NOW() WHERE id=?",user,reason,caseId);
        db.caseAction(caseId,"REQUESTED_RELEASE",user,reason,null);
        return access.view(caseId);
    }
    public Map<String,Object> rejectRelease(String user,UUID caseId,String note) {
        BillingExecutionBudget.requireTime();
        requireTransaction();
        String reason=access.requireNote(note);
        var item=access.visible(user,caseId);
        var locked=locks.lock(item); access.requireRole(user,locked,"REFUND_APPROVER"); access.requireOpen(locked);
        if(locked.get("releaseRequestedBy")==null) throw BillingProblem.conflict("No release request is waiting for approval");
        if(user.equals(locked.get("releaseRequestedBy"))) throw new BillingProblem(403,"SEPARATION_OF_DUTIES","The requester cannot review their own release");
        db.update("UPDATE billing.cases SET release_requested_by=NULL,release_note=NULL,release_requested_at=NULL WHERE id=?",caseId);
        db.caseAction(caseId,"REJECTED_RELEASE",user,reason,null);
        return access.view(caseId);
    }
    public Map<String,Object> close(String user,UUID caseId,String note) {
        BillingExecutionBudget.requireTime();
        requireTransaction();
        String reason=access.requireNote(note);
        var item=access.visible(user,caseId);
        var locked=locks.lock(item); access.requireRole(user,locked,"FINANCE"); access.requireOpen(locked);
        if(access.unresolved(locked)) throw BillingProblem.conflict("The payment or refund is still unresolved. Verify a provider reference or request an approved release instead.");
        if(access.awaitingProvider(locked)) throw BillingProblem.conflict("The provider may still complete this payment or refund. Verify its provider reference instead of closing the case.");
        db.update("UPDATE billing.cases SET status='RESOLVED',resolved_at=NOW(),resolved_by=?,resolution='CLOSED_AFTER_REVIEW',release_requested_by=NULL,release_note=NULL,release_requested_at=NULL WHERE id=?",user,caseId);
        db.caseAction(caseId,"CLOSED_AFTER_REVIEW",user,reason,null);
        return access.view(caseId);
    }
    public Map<String,Object> recordVerifiedReference(String user,Map<String,Object> item,String reference,String reason,ProviderResult verified) {
        BillingExecutionBudget.requireTime();requireTransaction();
        UUID caseId=id(item.get("id")),intent=id(item.get("intentId"));String kind=text(item,"reason");
        // One commit: the verified evidence, any money effect it implies, the case and its audit entry.
        var locked=locks.lock(item);
        access.requireOpen(locked);
        db.update("UPDATE billing.cases SET status='RESOLVED',resolved_at=NOW(),resolved_by=?,resolution='STAFF_VERIFIED_PROVIDER_REFERENCE',release_requested_by=NULL,release_note=NULL,release_requested_at=NULL WHERE id=?",user,caseId);
        db.caseAction(caseId,"VERIFIED_PROVIDER_REFERENCE",user,reason,
            new JSONObject().put("providerReference",reference).put("providerState",verified.state()).toString());
        if(PAYMENT_STUCK.contains(kind)) paymentCommands.recordResult(intent,null,verified);
        else refunds.record(id(item.get("refundId")),null,verified);
        return access.view(caseId);
    }
    public RefundConflictSnapshot prepareRefundConflict(String user,Map<String,Object> item,String reference,String providerName) {
        BillingExecutionBudget.requireTime();requireTransaction();
        UUID caseId=id(item.get("id")),intent=id(item.get("intentId")),refund=id(item.get("refundId"));
        var locked=locks.lock(item); access.requireOpen(locked); access.requireRole(user,locked,"REFUND_APPROVER");
        var row=refundRaw(refund); var payment=paymentAccess.raw(intent); paymentAccess.requireProvider(payment,providerName);
        if(!intent.toString().equals(text(row,"intentId")) || !"FAILED".equals(text(row,"status")) || row.get("approvedBy")==null)
            throw BillingProblem.conflict("This refund is no longer awaiting failure reconciliation; refresh the case");
        if(!reference.equals(text(row,"providerId")) || text(payment,"providerId")==null)
            throw BillingProblem.conflict("Verify the original refund reference and its original payment; a replacement operation cannot resolve this case");
        return new RefundConflictSnapshot(new RefundCommand(refund.toString(),intent.toString(),text(payment,"providerId"),
            BillingRefundService.operationKey(refund),amount(row,"amountMinor"),text(payment,"currency")),text(row,"updatedAt"),access.conflictEvidenceCount(caseId));
    }
    public String recordRefundConflict(String user,Map<String,Object> item,String reference,String note,RefundConflictSnapshot snapshot,ProviderResult verified,String providerName) {
        BillingExecutionBudget.requireTime();requireTransaction();
        UUID caseId=id(item.get("id")),intent=id(item.get("intentId")),refund=id(item.get("refundId"));
        var locked=locks.lock(item); access.requireOpen(locked); access.requireRole(user,locked,"REFUND_APPROVER");
        var row=refundRaw(refund); var payment=paymentAccess.raw(intent); paymentAccess.requireProvider(payment,providerName);
        if(!Objects.equals(snapshot.updatedAt(),text(row,"updatedAt")) || snapshot.evidenceCount()!=access.conflictEvidenceCount(caseId)
            || !"FAILED".equals(text(row,"status")) || !reference.equals(text(row,"providerId"))
            || !snapshot.command().providerPaymentId().equals(text(payment,"providerId"))
            || snapshot.command().amountMinor()!=amount(row,"amountMinor"))
            throw BillingProblem.conflict("Refund evidence changed while the provider was being checked; refresh and verify again");
        BillingProviderEvidence.record(db,intent,refund,verified.diagnostics());
        String evidence=new JSONObject().put("providerReference",reference).put("providerState",verified.state()).toString();
        db.caseAction(caseId,"VERIFIED_REFUND_CONFLICT",user,note,evidence);
        if(verified.state()==null || !Set.of("REFUNDED","FAILED").contains(verified.state())) {
            String pending="The original refund is not yet finally succeeded or failed. Its verified status was saved; the refund hold remains.";
            db.caseAction(caseId,"RECONCILIATION_PENDING",user,pending,evidence);
            return pending;
        }
        String capacityProblem=refunds.reconcileVerifiedFailure(refund,verified);
        if(capacityProblem!=null) {
            db.caseAction(caseId,"RECONCILIATION_BLOCKED",user,capacityProblem,evidence);
            return capacityProblem;
        }
        db.update("UPDATE billing.cases SET status='RESOLVED',resolved_at=NOW(),resolved_by=?,resolution='VERIFIED_REFUND_RECONCILIATION',release_requested_by=NULL,release_note=NULL,release_requested_at=NULL WHERE id=?",user,caseId);
        db.caseAction(caseId,"RECONCILED_REFUND",user,note,evidence);
        if("REFUNDED".equals(verified.state()))
            db.resolveCases(refund.toString(),"REFUND_FAILED","REFUND_FAILED",user,"VERIFIED_REFUND_RECONCILIATION","AUTO_RESOLVED","The provider verified that the original refund succeeded and its money was posted");
        return null;
    }
    public Map<String,Object> prepareRelease(String user,Map<String,Object> item,UUID token,String providerName) {
        BillingExecutionBudget.requireTime();requireTransaction();
        UUID caseId=id(item.get("id")),intent=id(item.get("intentId"));boolean payment=PAYMENT_STUCK.contains(text(item,"reason"));
        var locked=locks.lock(item); access.requireApprovable(user,locked);
        JSONObject confirmation=access.pendingConfirmation(caseId);
        if(confirmation!=null && access.confirmationExpired(confirmation))
            throw new BillingProblem(409,"STRIPE_CONFIRMATION_EXPIRED","The Stripe confirmation is more than 7 days old. Reject this request so finance can check Stripe again.");
        // Only the payment's original provider can prove there is nothing to release: absence elsewhere proves nothing.
        if(!providerName.equals(text(paymentAccess.raw(intent),"provider")))
            throw BillingProblem.conflict("Only this payment's original provider can confirm a release. Restore it before approving.");
        return payment ? paymentLocks.leaseForRelease(intent,token) : refunds.leaseForRelease(id(locked.get("refundId")),token);
    }
    public Map<String,Object> applyAbsentRelease(String user,Map<String,Object> item,UUID token,ProviderResult releaseProof) {
        BillingExecutionBudget.requireTime();requireTransaction();
        UUID caseId=id(item.get("id")),intent=id(item.get("intentId"));boolean payment=PAYMENT_STUCK.contains(text(item,"reason"));
        var locked=locks.lock(item); access.requireApprovable(user,locked);
        BillingProviderEvidence.record(db,intent,payment?null:id(locked.get("refundId")),releaseProof.diagnostics());
        String note=text(locked,"releaseNote");
        if(payment) {
            paymentCommands.releaseUnresolved(intent,token,
                "Two authorized employees approved the release after the provider confirmed it has no payment and can take none; invoice reservations released.");
            db.resolveCases(intent.toString(),"PAYMENT_UNRESOLVED","PAYMENT_REPLAY_UNSAFE",user,"RELEASED_NO_PROVIDER_OPERATION","APPROVED_RELEASE",note);
        } else {
            refunds.releaseUnconfirmed(id(locked.get("refundId")),token,
                "Two authorized employees approved the release after the provider showed no such refund; refund capacity released.");
            db.resolveCases(text(locked,"refundId"),"REFUND_UNRESOLVED","REFUND_REPLAY_UNSAFE",user,"RELEASED_NO_PROVIDER_OPERATION","APPROVED_RELEASE",note);
        }
        return access.view(caseId);
    }
    public Map<String,Object> applyExternalRelease(String user,Map<String,Object> item,UUID token,ProviderResult releaseProof) {
        BillingExecutionBudget.requireTime();requireTransaction();
        UUID caseId=id(item.get("id")),intent=id(item.get("intentId"));boolean payment=PAYMENT_STUCK.contains(text(item,"reason"));
        var locked=locks.lock(item); access.requireApprovable(user,locked);
        JSONObject confirmation=access.pendingConfirmation(caseId);
        if(confirmation==null || access.confirmationExpired(confirmation)) return null;
        BillingProviderEvidence.record(db,intent,payment?null:id(locked.get("refundId")),releaseProof.diagnostics());
        String note=text(locked,"releaseNote");
        if(payment) {
            paymentCommands.releaseUnresolved(intent,token,
                "Two authorized Carrier employees approved the release on a recorded payment provider confirmation; the provider's own search found nothing. Invoice reservations released.");
            db.resolveCases(intent.toString(),"PAYMENT_UNRESOLVED","PAYMENT_REPLAY_UNSAFE",user,"RELEASED_EXTERNAL_CONFIRMATION","APPROVED_EXTERNAL_RELEASE",note);
        } else {
            refunds.releaseUnconfirmed(id(locked.get("refundId")),token,
                "Two authorized Carrier employees approved the release on a recorded payment provider confirmation; the provider's own search found no such refund. Refund capacity released.");
            db.resolveCases(text(locked,"refundId"),"REFUND_UNRESOLVED","REFUND_REPLAY_UNSAFE",user,"RELEASED_EXTERNAL_CONFIRMATION","APPROVED_EXTERNAL_RELEASE",note);
        }
        return access.view(caseId);
    }
    public void recordReleaseEvidence(Map<String,Object> item,UUID token,ProviderResult answer) {
        BillingExecutionBudget.requireTime();requireTransaction();
        UUID caseId=id(item.get("id")),intent=id(item.get("intentId"));boolean payment=PAYMENT_STUCK.contains(text(item,"reason"));
        if(payment) paymentCommands.recordResult(intent,token,answer); else refunds.record(id(item.get("refundId")),token,answer);
        return;
    }
}
