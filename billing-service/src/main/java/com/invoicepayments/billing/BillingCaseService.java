package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import org.json.JSONObject;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;
import static com.invoicepayments.billing.BillingStore.*;

/**
 * Staff resolution of exception cases. Nothing here can mark a payment paid. A stuck payment or
 * refund is resolved only by (1) a provider reference that the provider itself verifies, or
 * (2) a release requested by one employee and approved by a different one. Informational cases
 * close with a review note. Every action is written to an append-only audit table.
 *
 * Who decides: FINANCE and REFUND_APPROVER are the client's (Carrier's) finance staff and make every
 * decision. OPS is the application team, a service provider: it can read cases and evidence and add
 * notes, but it never verifies, requests, approves, rejects or closes anything.
 */
@Service
@Profile("billing")
public class BillingCaseService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(BillingCaseService.class);
    static final Set<String> PAYMENT_STUCK=Set.of("PAYMENT_UNRESOLVED","PAYMENT_REPLAY_UNSAFE");
    static final Set<String> REFUND_STUCK=Set.of("REFUND_UNRESOLVED","REFUND_REPLAY_UNSAFE");
    static final String REFUND_CONFLICT="REFUND_EVIDENCE_CONFLICT";
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
            boolean maker=hasRole(user,merchant,"FINANCE"), checker=hasRole(user,merchant,"REFUND_APPROVER");
            boolean open="OPEN".equals(row.get("status")), unresolved=unresolved(row);
            boolean stuck=PAYMENT_STUCK.contains(text(row,"reason")) || REFUND_STUCK.contains(text(row,"reason"));
            boolean refundConflict=REFUND_CONFLICT.equals(text(row,"reason"));
            boolean pending=row.get("releaseRequestedBy")!=null;
            boolean releasable=open && unresolved && !replayPossible(row);
            row.put("canVerifyReference",open && ((stuck && unresolved && maker) || (refundConflict && checker)));
            row.put("canRequestRelease",releasable && stuck && maker && !pending);
            row.put("canApproveRelease",releasable && pending && checker && !user.equals(row.get("releaseRequestedBy")));
            row.put("canClose",open && !unresolved && maker);
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
        for(var row:evidence) for(String flag:List.of("canVerifyReference","canRequestRelease","canApproveRelease","canClose","canAddNote","canRecordExternalConfirmation")) row.put(flag,false);
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
        var item=visible(user,caseId); requireOpen(item);
        String kind=text(item,"reason");
        if(REFUND_CONFLICT.equals(kind)) return reconcileRefundConflict(user,item,reference,reason);
        requireRole(user,item,"FINANCE");
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

    /** Resolve contradictory refund evidence only against a fresh, financially matched provider lookup. */
    private Map<String,Object> reconcileRefundConflict(String user,Map<String,Object> item,String reference,String note) {
        UUID caseId=id(item.get("id")), intent=id(item.get("intentId")), refund=id(item.get("refundId"));
        RefundConflictSnapshot snapshot=db.tx(() -> {
            var locked=lock(item); requireOpen(locked); requireRole(user,locked,"REFUND_APPROVER");
            var row=refunds.raw(refund); var payment=payments.raw(intent); payments.requireProvider(payment);
            if(!intent.toString().equals(text(row,"intentId")) || !"FAILED".equals(text(row,"status")) || row.get("approvedBy")==null)
                throw BillingProblem.conflict("This refund is no longer awaiting failure reconciliation; refresh the case");
            if(!reference.equals(text(row,"providerId")) || text(payment,"providerId")==null)
                throw BillingProblem.conflict("Verify the original refund reference and its original payment; a replacement operation cannot resolve this case");
            return new RefundConflictSnapshot(new RefundCommand(refund.toString(),intent.toString(),text(payment,"providerId"),
                BillingRefundService.operationKey(refund),amount(row,"amountMinor"),text(payment,"currency")),text(row,"updatedAt"),conflictEvidenceCount(caseId));
        });
        ProviderResult verified=provider.verifyRefundReference(snapshot.command(),reference);
        if(!reference.equals(verified.providerId()))
            throw new BillingProblem(409,"PROVIDER_REFERENCE_NOT_VERIFIED","The provider did not confirm the original refund reference; the refund hold remains");
        String blocked=db.tx(() -> {
            var locked=lock(item); requireOpen(locked); requireRole(user,locked,"REFUND_APPROVER");
            var row=refunds.raw(refund); var payment=payments.raw(intent); payments.requireProvider(payment);
            if(!Objects.equals(snapshot.updatedAt(),text(row,"updatedAt")) || snapshot.evidenceCount()!=conflictEvidenceCount(caseId)
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
        });
        // A pending/capacity-blocked check still durably preserves the fresh evidence and leaves the hold open.
        if(blocked!=null) throw new BillingProblem(409,"REFUND_RECONCILIATION_PENDING",blocked);
        return view(caseId);
    }
    private long conflictEvidenceCount(UUID caseId) {
        return amount(db.one("SELECT COUNT(*) AS total FROM billing.case_actions WHERE case_id=? AND action='CONTRADICTORY_PROVIDER_EVIDENCE'",caseId),"total");
    }
    private record RefundConflictSnapshot(RefundCommand command,String updatedAt,long evidenceCount) {}

    /** Maker step: one employee asks to release a stuck operation the provider never performed. */
    /** What Carrier finance saw in the Stripe Dashboard or heard from Stripe support. No card or bank details. */
    public record ExternalConfirmation(String source,String account,String searched,String result,String ticket,
        String checkedAt,String evidenceSha256,String note) {}

    /** Any staff member, including the application team, can add a note. A note never changes money or status. */
    public Map<String,Object> addNote(String user,UUID caseId,String note) {
        String text=requireNote(note); requireNoAccountNumbers(text);
        var item=visible(user,caseId);
        return db.tx(() -> {
            var locked=lock(item); requireRole(user,locked,"OPS","FINANCE","REFUND_APPROVER");
            db.caseAction(caseId,"ADDED_NOTE",user,text,null);
            return view(caseId);
        });
    }

    /** Maker step when Stripe's own search finds nothing: Carrier finance records what Stripe confirmed. */
    public Map<String,Object> recordExternalConfirmation(String user,UUID caseId,ExternalConfirmation confirmation) {
        var item=visible(user,caseId);
        requireRole(user,item,"FINANCE");
        ExternalConfirmation valid=validConfirmation(confirmation);
        Instant checkedAt=Instant.parse(valid.checkedAt());
        return db.tx(() -> {
            var locked=lock(item); requireRole(user,locked,"FINANCE"); requireOpen(locked); requireReleasable(locked);
            if(locked.get("releaseRequestedBy")!=null) throw BillingProblem.conflict("A release request already awaits a different approver");
            Instant now=databaseNow(), earliest=externalCheckFrom(locked);
            if(now.isBefore(earliest))
                throw BillingProblem.conflict("Wait until 48 hours after the payment was first sent: until then a payment page created for it could still be paid");
            if(checkedAt.isBefore(earliest)) throw BillingProblem.invalid("Check Stripe again: the check must be made after the waiting period, so it covers everything this payment could have created");
            if(checkedAt.isAfter(now.plus(CLOCK_TOLERANCE))) throw BillingProblem.invalid("The Stripe check time is in the future");
            if(checkedAt.isBefore(now.minus(EXTERNAL_EVIDENCE_LIFETIME))) throw BillingProblem.invalid("The Stripe check is more than 7 days old; check Stripe again");
            JSONObject evidence=new JSONObject().put("source",valid.source()).put("account",valid.account()).put("searched",valid.searched())
                .put("result",valid.result()).put("ticket",valid.ticket()==null?JSONObject.NULL:valid.ticket())
                .put("checkedAt",valid.checkedAt()).put("evidenceSha256",valid.evidenceSha256());
            db.update("UPDATE billing.cases SET release_requested_by=?,release_note=?,release_requested_at=NOW() WHERE id=?",user,valid.note(),caseId);
            db.caseAction(caseId,"RECORDED_EXTERNAL_CONFIRMATION",user,valid.note(),evidence.toString());
            return view(caseId);
        });
    }

    static final Set<String> EXTERNAL_SOURCES=Set.of("STRIPE_DASHBOARD","STRIPE_SUPPORT");
    static final Set<String> EXTERNAL_FINDINGS=Set.of(ProviderResult.SEARCH_EMPTY,ProviderResult.CREDENTIALS_CHANGED);
    /** The last allowed resend is at 23 hours, and a Checkout page created then expires 24 hours later. */
    static final Duration EXTERNAL_PAYMENT_WAIT=Duration.ofHours(48);
    static final Duration EXTERNAL_EVIDENCE_LIFETIME=Duration.ofDays(7);
    private static final Duration CLOCK_TOLERANCE=Duration.ofMinutes(5);
    /** 13 to 19 digits, optionally grouped by spaces or dashes: looks like a card or bank account number. */
    private static final Pattern ACCOUNT_DIGITS=Pattern.compile("(?:\\d[ -]?){12,18}\\d");

    private static ExternalConfirmation validConfirmation(ExternalConfirmation c) {
        if(c==null) throw BillingProblem.invalid("Record what Stripe confirmed");
        String source=trim(c.source()), account=trim(c.account()), ticket=trim(c.ticket()), checked=trim(c.checkedAt()), digest=trim(c.evidenceSha256());
        if(!EXTERNAL_SOURCES.contains(source)) throw BillingProblem.invalid("Choose Stripe Dashboard or Stripe support as the source");
        if(!account.matches("acct_[A-Za-z0-9]{6,64}") && !account.matches("[\\p{L}\\p{N} .,&'()\\-]{3,100}"))
            throw BillingProblem.invalid("Name the original Stripe account: its acct_ ID or the account name shown in the Dashboard");
        String searched=bounded(c.searched(),"Describe what was searched in Stripe, in 10 to 500 characters");
        String result=bounded(c.result(),"Describe what Stripe showed, in 10 to 500 characters");
        if("STRIPE_SUPPORT".equals(source) && ticket.isEmpty()) throw BillingProblem.invalid("Enter the Stripe support ticket reference");
        if(!ticket.isEmpty() && !ticket.matches("[A-Za-z0-9#_\\-]{3,100}")) throw BillingProblem.invalid("Enter the Stripe support ticket reference exactly as Stripe gave it");
        Instant checkedAt;
        try { checkedAt=Instant.parse(checked); } catch(DateTimeParseException ex) { throw BillingProblem.invalid("Enter when Stripe was checked, as a date and time"); }
        if(!digest.matches("[a-fA-F0-9]{64}")) throw BillingProblem.invalid("Provide the SHA-256 digest of the saved evidence file");
        String note=requireNote(c.note());
        requireNoAccountNumbers(account,searched,result,ticket,note);
        return new ExternalConfirmation(source,account,searched,result,ticket.isEmpty()?null:ticket,checkedAt.toString(),digest.toLowerCase(Locale.ROOT),note);
    }
    private static String trim(String value) { return value==null?"":value.trim(); }
    private static String bounded(String value,String message) {
        String text=trim(value);
        if(text.length()<10 || text.length()>500) throw BillingProblem.invalid(message);
        return text;
    }
    private static void requireNoAccountNumbers(String... values) {
        for(String value:values) if(value!=null && ACCOUNT_DIGITS.matcher(value).find())
            throw BillingProblem.invalid("Remove card or bank account numbers; record only what was searched and what was found");
    }
    private Instant databaseNow() { return Instant.parse(text(db.one("SELECT NOW() AS now"),"now")); }
    /** When a Stripe check can first count: 48 hours after a payment was sent, or once a refund was sent. */
    private Instant externalCheckFrom(Map<String,Object> locked) {
        if(PAYMENT_STUCK.contains(text(locked,"reason"))) {
            var attempt=db.rows("SELECT created_at FROM billing.attempts WHERE intent_id=?",id(locked.get("intentId")));
            if(attempt.isEmpty()) throw BillingProblem.conflict("This payment has no recorded submission, so a Stripe confirmation cannot release it");
            return Instant.parse(text(attempt.get(0),"createdAt")).plus(EXTERNAL_PAYMENT_WAIT);
        }
        var refund=db.one("SELECT submitted_at FROM billing.refunds WHERE id=?",id(locked.get("refundId")));
        if(refund.get("submittedAt")==null) throw BillingProblem.conflict("This refund was never sent, so a Stripe confirmation cannot release it");
        return Instant.parse(text(refund,"submittedAt")).minus(CLOCK_TOLERANCE);
    }
    private boolean externalWaitOver(Map<String,Object> row) {
        if(PAYMENT_STUCK.contains(text(row,"reason")))
            return db.exists("SELECT 1 FROM billing.attempts WHERE intent_id=?::uuid AND created_at<=NOW()-INTERVAL '48 hours'",text(row,"intentId"));
        return REFUND_STUCK.contains(text(row,"reason")) && db.exists("SELECT 1 FROM billing.refunds WHERE id=?::uuid AND submitted_at IS NOT NULL",text(row,"refundId"));
    }
    /** The Stripe confirmation behind the release request now waiting, or null for an ordinary request. */
    private JSONObject pendingConfirmation(UUID caseId) {
        var rows=db.rows("""
            SELECT a.evidence::text AS evidence FROM billing.case_actions a JOIN billing.cases c ON c.id=a.case_id
            WHERE a.case_id=? AND a.action='RECORDED_EXTERNAL_CONFIRMATION' AND c.release_requested_at IS NOT NULL
              AND a.created_at>=c.release_requested_at
            ORDER BY a.created_at DESC,a.id DESC LIMIT 1
            """,caseId);
        return rows.isEmpty()?null:new JSONObject(text(rows.get(0),"evidence"));
    }
    private boolean confirmationExpired(JSONObject confirmation) {
        return Instant.parse(confirmation.getString("checkedAt")).isBefore(databaseNow().minus(EXTERNAL_EVIDENCE_LIFETIME));
    }

    public Map<String,Object> requestRelease(String user,UUID caseId,String note) {
        String reason=requireNote(note);
        var item=visible(user,caseId);
        return db.tx(() -> {
            var locked=lock(item); requireRole(user,locked,"FINANCE"); requireOpen(locked); requireReleasable(locked);
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
            JSONObject confirmation=pendingConfirmation(caseId);
            if(confirmation!=null && confirmationExpired(confirmation))
                throw new BillingProblem(409,"STRIPE_CONFIRMATION_EXPIRED","The Stripe confirmation is more than 7 days old. Reject this request so finance can check Stripe again.");
            // Only the payment's original provider can prove there is nothing to release: absence elsewhere proves nothing.
            if(!provider.name().equals(text(payments.raw(intent),"provider")))
                throw BillingProblem.conflict("Only this payment's original provider can confirm a release. Restore it before approving.");
            return payment ? payments.leaseForRelease(intent,token) : refunds.leaseForRelease(id(locked.get("refundId")),token);
        });
        ProviderResult proof;
        try(var budget=BillingExecutionBudget.lease(Instant.parse(text(claim,"leaseUntil")))) {
            proof=payment ? provider.closePayment(payments.savedCommand(claim),text(claim,"requestVersion"),payments.preparedAt(claim),text(claim,"providerId"))
                : provider.findRefund(refunds.savedCommand(claim),text(claim,"providerId"));
        } catch(RuntimeException failure) {
            log.warn("Release check got no definite answer from the provider caseId={}",caseId,failure);
            proof=new ProviderResult(null,"UNKNOWN",null,"The provider check gave no definite answer",BillingProviderEvidence.from(failure));
        }
        ProviderResult releaseProof=proof;
        if("ABSENT".equals(proof.state())) return db.tx(() -> {
            var locked=lock(item); requireApprovable(user,locked);
            BillingProviderEvidence.record(db,intent,payment?null:id(locked.get("refundId")),releaseProof.diagnostics());
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
        // Stripe's own search found nothing (or could not search the original account). That is never proof by itself,
        // but together with the Stripe confirmation Carrier finance recorded, a different approver may release.
        if("UNKNOWN".equals(proof.state()) && proof.finding()!=null && EXTERNAL_FINDINGS.contains(proof.finding())) {
            Map<String,Object> released=db.tx(() -> {
                var locked=lock(item); requireApprovable(user,locked);
                JSONObject confirmation=pendingConfirmation(caseId);
                if(confirmation==null || confirmationExpired(confirmation)) return null;
                BillingProviderEvidence.record(db,intent,payment?null:id(locked.get("refundId")),releaseProof.diagnostics());
                String note=text(locked,"releaseNote");
                if(payment) {
                    payments.releaseUnresolved(intent,token,
                        "Two authorized Carrier employees approved the release on a recorded Stripe confirmation; Stripe's own search found nothing. Invoice reservations released.");
                    db.resolveCases(intent.toString(),"PAYMENT_UNRESOLVED","PAYMENT_REPLAY_UNSAFE",user,"RELEASED_EXTERNAL_CONFIRMATION","APPROVED_EXTERNAL_RELEASE",note);
                } else {
                    refunds.releaseUnconfirmed(id(locked.get("refundId")),token,
                        "Two authorized Carrier employees approved the release on a recorded Stripe confirmation; Stripe's own search found no such refund. Refund capacity released.");
                    db.resolveCases(text(locked,"refundId"),"REFUND_UNRESOLVED","REFUND_REPLAY_UNSAFE",user,"RELEASED_EXTERNAL_CONFIRMATION","APPROVED_EXTERNAL_RELEASE",note);
                }
                return view(caseId);
            });
            if(released!=null) return released;
        }
        // The provider has the operation, or could not prove it does not. Record its answer, which also returns the lease.
        ProviderResult answer=proof;
        db.tx(() -> {
            if(payment) payments.recordResult(intent,token,answer); else refunds.record(id(item.get("refundId")),token,answer);
            return null;
        });
        // Provider-confirmed failure already released the reservation/capacity in the recording transaction.
        if("FAILED".equals(proof.state())) {
            String recorded=payment?text(payments.raw(intent),"status"):text(refunds.raw(id(item.get("refundId"))),"status");
            if("FAILED".equals(recorded)) return view(caseId);
        }
        if("UNKNOWN".equals(proof.state()))
            throw new BillingProblem(409,"PROVIDER_ABSENCE_NOT_CONFIRMED","The original outcome is still unconfirmed, so its reservation remains. Review the original request with the provider and finance; do not issue a replacement based on an empty search.");
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
            var locked=lock(item); requireRole(user,locked,"FINANCE"); requireOpen(locked);
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
        if(REFUND_CONFLICT.equals(reason)) return true;
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
    /** A saved request blocks release only while recovery is still permitted to resend it. */
    private boolean replayPossible(Map<String,Object> item) {
        return "REFUND_UNRESOLVED".equals(text(item,"reason"))
            && db.exists("SELECT 1 FROM billing.refunds WHERE id=? AND original_request IS NOT NULL",id(item.get("refundId")))
            && !BillingProviderEvidence.replayForbidden(db,id(item.get("intentId")),id(item.get("refundId")));
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
