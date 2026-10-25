package com.invoicepayments.billing;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.util.*;

import static com.invoicepayments.billing.BillingStore.*;

/**
 * A payer's retry after a payment failed for certain. The retry is a new, unsubmitted payment for the same invoices
 * and amount, with its own key, Stripe operation and money records, linked to the failed one and shown under the
 * same payment reference. The payer still presses Pay. Nothing about the failed payment changes, so any late
 * evidence for it is still booked against it.
 *
 * Not to be confused with a network retry: repeating the same request with the same Idempotency-Key returns the
 * same answer and never makes a second payment.
 */
@Service
@Profile("billing")
public class BillingPaymentRetry {
    /** Released by Carrier finance without Stripe confirming a failure: a late capture is still possible. */
    static final List<String> RELEASES=List.of("RELEASED_NO_PROVIDER_OPERATION","RELEASED_EXTERNAL_CONFIRMATION");
    private final BillingStore db;
    private final BillingService payments;
    public BillingPaymentRetry(BillingStore db,BillingService payments) { this.db=db; this.payments=payments; }

    public Map<String,Object> retry(String user,UUID failed,String key,String method) {
        requireNoTransaction();
        BillingService.validateKey(key);
        // Card or bank is the payer's explicit choice for every attempt; nothing is assumed from the failed one.
        if(!Set.of("CARD","ACH").contains(Objects.toString(method,""))) throw BillingProblem.invalid("Select CARD or ACH");
        UUID account=id(payments.raw(failed).get("accountId"));
        // The same account, role and configured-merchant rule applies to creation AND returning a saved retry.
        payments.authorizePayer(user,account);
        UUID retry=db.tx(() -> {
            // Account, then payment, then (inside create) invoices: the order every other path uses.
            db.one("SELECT id FROM billing.accounts WHERE id=? FOR UPDATE",account);
            var payment=payments.locked(failed);
            // The same key again is a network retry of this request: the same answer, or a conflict if it differs.
            var sameKey=db.rows("SELECT id,retry_of,method FROM billing.intents WHERE account_id=? AND idempotency_key=?",account,key);
            if(!sameKey.isEmpty()) {
                var earlier=sameKey.get(0);
                if(failed.toString().equals(text(earlier,"retryOf")) && method.equals(text(earlier,"method"))) return id(earlier.get("id"));
                throw new BillingProblem(409,"IDEMPOTENCY_CONFLICT","Idempotency key already belongs to a different request");
            }
            // A different request after this payment was already tried again: never a second new payment.
            if(db.exists("SELECT 1 FROM billing.intents WHERE retry_of=?",failed))
                throw new BillingProblem(409,"ALREADY_RETRIED","This payment was already tried again. Check your recent payments.");
            requireFailedForCertain(payment);
            UUID reference=id(payment.get("paymentReference"));
            if(db.exists("SELECT 1 FROM billing.intents WHERE payment_reference=? AND status<>'FAILED'",reference)
                || db.exists("SELECT 1 FROM billing.intents i JOIN billing.journals j ON j.effect_key='capture:'||i.id WHERE i.payment_reference=?",reference))
                throw notAllowed("Another try of this payment was received or is on its way.");
            List<UUID> invoices=db.rows("SELECT invoice_id FROM billing.allocations WHERE intent_id=? ORDER BY invoice_id",failed)
                .stream().map(a -> id(a.get("invoiceId"))).toList();
            // Joins this transaction: if anything below refuses, the new payment and its reservations are undone.
            var created=payments.create(user,account,key,invoices,method);
            UUID next=id(created.get("id"));
            if(amount(created,"amountMinor")!=amount(payment,"amountMinor"))
                throw new BillingProblem(409,"AMOUNT_CHANGED","The amount due on these invoices has changed since this payment. Please start a new payment.");
            db.update("UPDATE billing.intents SET payment_reference=?,retry_of=? WHERE id=?",reference,failed,next);
            db.event(next,"RETRY","Payer tried again after payment "+failed+" failed; payment reference "+reference+"; not yet sent");
            return next;
        });
        return payments.get(user,retry);
    }

    /** Only a failure Stripe confirmed, or a request it refused before anything was created, is retried. */
    private void requireFailedForCertain(Map<String,Object> payment) {
        UUID id=id(payment.get("id"));
        String status=text(payment,"status");
        if(Set.of("CAPTURED","RETURNED","REFUNDED").contains(status) || payments.hasCapture(id))
            throw notAllowed("This payment was received, so it can't be paid again.");
        if("CANCELLED".equals(status)) throw notAllowed("This payment was cancelled before it was sent. Please start a new payment.");
        if(!"FAILED".equals(status)) throw notAllowed("This payment is still being checked, so it can't be tried again yet.");
        if(db.exists("SELECT 1 FROM billing.cases WHERE (intent_id=? OR reference=?) AND resolution IN ('"+String.join("','",RELEASES)+"')",id,id.toString()))
            throw notAllowed("This payment was closed by Carrier finance without Stripe confirming it failed. Please start a new payment.");
    }
    private static BillingProblem notAllowed(String message) { return new BillingProblem(409,"RETRY_NOT_ALLOWED",message); }
}
