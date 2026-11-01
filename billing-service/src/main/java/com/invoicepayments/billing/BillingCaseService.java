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
    private final BillingCaseCommands commands;private final BillingCaseAccess access;
    private final BillingStore db; private final BillingService payments; private final BillingRefundService refunds; private final BillingProvider provider;
    public BillingCaseService(BillingStore db,BillingService payments,BillingRefundService refunds,BillingProvider provider,BillingCaseCommands commands,BillingCaseAccess access) {
        this.commands=commands;this.access=access;this.db=db;this.payments=payments;this.refunds=refunds;this.provider=provider;
    }

    /** Cases and unmatched provider evidence in the user's merchant scope, with the actions the server allows this user. */
    public List<Map<String,Object>> list(String user){return access.list(user);}

    public List<Map<String,Object>> history(String user,UUID caseId){return access.history(user,caseId);}

    /** A provider reference counts only if the provider confirms it belongs to this exact operation. */
    public Map<String,Object> verifyProviderReference(String user,UUID caseId,String reference,String note) {
        requireNoTransaction(); // provider I/O follows; never hold a database transaction across it
        String reason=access.requireNote(note);
        if(reference==null || !reference.matches("[A-Za-z0-9_\\-]{3,255}")) throw BillingProblem.invalid("Enter the provider's reference for this operation");
        var item=access.visible(user,caseId); access.requireOpen(item);
        String kind=text(item,"reason");
        if(REFUND_CONFLICT.equals(kind)) return reconcileRefundConflict(user,item,reference,reason);
        access.requireRole(user,item,"FINANCE");
        UUID intent=id(item.get("intentId"));
        ProviderResult result;
        if(PAYMENT_STUCK.contains(kind)) {
            var payment=payments.raw(intent); payments.requireProvider(payment);
            // Includes a hosted checkout still marked REQUIRES_ACTION/PROCESSING after recovery gave up: the buyer may have paid.
            if(!Set.of("UNKNOWN","SUBMITTING","REQUIRES_ACTION","PROCESSING").contains(text(payment,"status"))) throw BillingProblem.conflict("This payment is no longer unresolved; refresh the case");
            if(payment.get("providerId")!=null && !reference.equals(payment.get("providerId")))
                throw BillingProblem.conflict("This payment already has a different verified provider reference");
            result=provider.verifyPaymentReference(new ProviderCommand(intent.toString(),BillingService.operationKey(intent),
                amount(payment,"amountMinor"),text(payment,"currency"),text(payment,"method"),null),reference);
        } else if(REFUND_STUCK.contains(kind)) {
            UUID refund=id(item.get("refundId"));
            var row=refunds.raw(refund);
            var payment=payments.raw(intent); payments.requireProvider(payment);
            if(!intent.toString().equals(text(row,"intentId"))) throw BillingProblem.conflict("Refund does not belong to this payment");
            if(!Set.of("SUBMITTING","UNKNOWN","PROCESSING").contains(text(row,"status"))) throw BillingProblem.conflict("This refund is no longer unresolved; refresh the case");
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
        return commands.recordVerifiedReference(user,item,reference,reason,verified);
    }

    /** Resolve contradictory refund evidence only against a fresh, financially matched provider lookup. */
    private Map<String,Object> reconcileRefundConflict(String user,Map<String,Object> item,String reference,String note) {
        UUID caseId=id(item.get("id")), intent=id(item.get("intentId")), refund=id(item.get("refundId"));
        BillingCaseCommands.RefundConflictSnapshot snapshot=commands.prepareRefundConflict(user,item,reference,provider.name());
        ProviderResult verified=provider.verifyRefundReference(snapshot.command(),reference);
        if(!reference.equals(verified.providerId()))
            throw new BillingProblem(409,"PROVIDER_REFERENCE_NOT_VERIFIED","The provider did not confirm the original refund reference; the refund hold remains");
        String blocked=commands.recordRefundConflict(user,item,reference,note,snapshot,verified,provider.name());
        // A pending/capacity-blocked check still durably preserves the fresh evidence and leaves the hold open.
        if(blocked!=null) throw new BillingProblem(409,"REFUND_RECONCILIATION_PENDING",blocked);
        return access.view(caseId);
    }

    /** Maker step: one employee asks to release a stuck operation the provider never performed. */
    /** What Carrier finance saw in the Stripe Dashboard or heard from Stripe support. No card or bank details. */
    public record ExternalConfirmation(String source,String account,String searched,String result,String ticket,
        String checkedAt,String evidenceSha256,String note) {}

    /** Any staff member, including the application team, can add a note. A note never changes money or status. */
    public Map<String,Object> addNote(String user,UUID caseId,String note) {
        BillingExecutionBudget.requireTime();return commands.addNote(user,caseId,note);
    }

    /** Maker step when Stripe's own search finds nothing: Carrier finance records what Stripe confirmed. */
    public Map<String,Object> recordExternalConfirmation(String user,UUID caseId,ExternalConfirmation confirmation) {
        BillingExecutionBudget.requireTime();return commands.recordExternalConfirmation(user,caseId,confirmation);
    }

    static final Set<String> EXTERNAL_SOURCES=Set.of("STRIPE_DASHBOARD","STRIPE_SUPPORT","RAZORPAY_DASHBOARD","RAZORPAY_SUPPORT");
    static final Set<String> EXTERNAL_FINDINGS=Set.of(ProviderResult.SEARCH_EMPTY,ProviderResult.CREDENTIALS_CHANGED);
    /** The last allowed resend is at 23 hours, and a Checkout page created then expires 24 hours later. */
    static final Duration EXTERNAL_PAYMENT_WAIT=Duration.ofHours(48);
    /**
     * Razorpay: only the first submitter ever creates the order (recovery never resends), and an order is findable by its
     * receipt seconds after creation. One hour after the request was saved, an empty search covers anything it created.
     */
    static final Duration RAZORPAY_PAYMENT_WAIT=Duration.ofHours(1);
    static Duration paymentWait(String provider){return "RAZORPAY".equals(provider)?RAZORPAY_PAYMENT_WAIT:EXTERNAL_PAYMENT_WAIT;}
    static final Duration EXTERNAL_EVIDENCE_LIFETIME=Duration.ofDays(7);
    static final Duration CLOCK_TOLERANCE=Duration.ofMinutes(5);
    /** 13 to 19 digits, optionally grouped by spaces or dashes: looks like a card or bank account number. */
    static final Pattern ACCOUNT_DIGITS=Pattern.compile("(?:\\d[ -]?){12,18}\\d");

    /** When a Stripe check can first count: 48 hours after a payment was sent, or once a refund was sent. */

    /** The Stripe confirmation behind the release request now waiting, or null for an ordinary request. */

    public Map<String,Object> requestRelease(String user,UUID caseId,String note) {
        BillingExecutionBudget.requireTime();return commands.requestRelease(user,caseId,note);
    }

    /**
     * Checker step: a different authorized approver confirms. Before anything is released, the provider must prove
     * the operation does not exist and cannot appear: an open payment page is expired first. The operation's lease
     * keeps recovery from replaying it during that check, and fences the release itself.
     */
    public Map<String,Object> approveRelease(String user,UUID caseId) {
        requireNoTransaction(); // provider I/O happens between two short transactions
        var item=access.visible(user,caseId);
        boolean payment=PAYMENT_STUCK.contains(text(item,"reason"));
        UUID intent=id(item.get("intentId")), token=UUID.randomUUID();
        var claim=commands.prepareRelease(user,item,token,provider.name());
        ProviderResult proof;
        try(var budget=BillingExecutionBudget.lease(Instant.parse(text(claim,"leaseUntil")))) {
            proof=payment ? provider.closePayment(payments.savedCommand(claim),text(claim,"requestVersion"),payments.preparedAt(claim),text(claim,"providerId"))
                : provider.findRefund(refunds.savedCommand(claim),text(claim,"providerId"));
        } catch(RuntimeException failure) {
            log.warn("Release check got no definite answer from the provider caseId={}",caseId,failure);
            proof=new ProviderResult(null,"UNKNOWN",null,"The provider check gave no definite answer",BillingProviderEvidence.from(failure));
        }
        ProviderResult releaseProof=proof;
        if("ABSENT".equals(proof.state())) return commands.applyAbsentRelease(user,item,token,releaseProof);
        // Stripe's own search found nothing (or could not search the original account). That is never proof by itself,
        // but together with the Stripe confirmation Carrier finance recorded, a different approver may release.
        if("UNKNOWN".equals(proof.state()) && proof.finding()!=null && EXTERNAL_FINDINGS.contains(proof.finding())) {
            Map<String,Object> released=commands.applyExternalRelease(user,item,token,releaseProof);
            if(released!=null) return released;
        }
        // The provider has the operation, or could not prove it does not. Record its answer, which also returns the lease.
        ProviderResult answer=proof;
        commands.recordReleaseEvidence(item,token,answer);
        // Provider-confirmed failure already released the reservation/capacity in the recording transaction.
        if("FAILED".equals(proof.state())) {
            String recorded=payment?text(payments.raw(intent),"status"):text(refunds.raw(id(item.get("refundId"))),"status");
            if("FAILED".equals(recorded)) return access.view(caseId);
        }
        if("UNKNOWN".equals(proof.state()))
            throw new BillingProblem(409,"PROVIDER_ABSENCE_NOT_CONFIRMED","The original outcome is still unconfirmed, so its reservation remains. Review the original request with the provider and finance; do not issue a replacement based on an empty search.");
        throw new BillingProblem(409,"PROVIDER_HAS_OPERATION","The provider has this "+(payment?"payment":"refund")+" (status "+proof.state()+"), so nothing was released. Its status was recorded.");
    }

    public Map<String,Object> rejectRelease(String user,UUID caseId,String note) {
        BillingExecutionBudget.requireTime();return commands.rejectRelease(user,caseId,note);
    }

    /** Closes a case after review. Refused while the payment or refund it describes is still unresolved. */
    public Map<String,Object> close(String user,UUID caseId,String note) {
        BillingExecutionBudget.requireTime();return commands.close(user,caseId,note);
    }

    /** Lock order matches money paths: payment, then refund, then case. */

    /** A saved request blocks release only while recovery is still permitted to resend it. */

}
