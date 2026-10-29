package com.invoicepayments.billing;

import org.springframework.context.annotation.Profile;
import com.invoicepayments.commerce.CommercePaymentGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;

/**
 * Prepares a payer's retry: the checks, the new payment (BillingPaymentCommands.create joins this transaction), and the
 * link to the failed payment commit together. If any check after creation refuses, the new payment and its
 * reservations roll back with it.
 */
@Service
@Profile("billing")
@Transactional(transactionManager="transactionManager",propagation=Propagation.REQUIRED,rollbackFor=Exception.class,timeout=10,label="billing")
public class BillingRetryCommands {
    /** Released by Carrier finance without Stripe confirming a failure: a late capture is still possible. */
    static final List<String> RELEASES=List.of("RELEASED_NO_PROVIDER_OPERATION","RELEASED_EXTERNAL_CONFIRMATION");
    private final BillingStore db;private final BillingPaymentAccess access;private final BillingPaymentLocks locks;private final BillingPaymentCommands payments;
    public BillingRetryCommands(BillingStore db,BillingPaymentAccess access,BillingPaymentLocks locks,BillingPaymentCommands payments) {
        this.db=db;this.access=access;this.locks=locks;this.payments=payments;
    }
    /** Returns the new (or, for the same key, the earlier) payment's id. */
    public UUID prepareRetry(String user,UUID failed,UUID account,String key,String method,String providerName) {
        BillingExecutionBudget.requireTime();requireTransaction();
        // Account, then payment, then (inside create) invoices: the order every other path uses.
        db.one("SELECT id FROM billing.accounts WHERE id=? FOR UPDATE",account);
        CommercePaymentGuard.requirePayableOrder(db,failed);
        var payment=locks.locked(failed);
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
        var created=CommercePaymentGuard.withOrderBillCreation(() -> payments.create(user,account,key,invoices,method,providerName));
        UUID next=id(created.get("id"));
        if(amount(created,"amountMinor")!=amount(payment,"amountMinor"))
            throw new BillingProblem(409,"AMOUNT_CHANGED","The amount due on these invoices has changed since this payment. Please start a new payment.");
        db.update("UPDATE billing.intents SET payment_reference=?,retry_of=? WHERE id=?",reference,failed,next);
        db.event(next,"RETRY","Payer tried again after payment "+failed+" failed; payment reference "+reference+"; not yet sent");
        return next;
    }
    /** Only a failure Stripe confirmed, or a request it refused before anything was created, is retried. */
    private void requireFailedForCertain(Map<String,Object> payment) {
        UUID id=id(payment.get("id"));
        String status=text(payment,"status");
        if(Set.of("CAPTURED","RETURNED","REFUNDED").contains(status) || access.hasCapture(id))
            throw notAllowed("This payment was received, so it can't be paid again.");
        if("CANCELLED".equals(status)) throw notAllowed("This payment was cancelled before it was sent. Please start a new payment.");
        if(!"FAILED".equals(status)) throw notAllowed("This payment is still being checked, so it can't be tried again yet.");
        if(db.exists("SELECT 1 FROM billing.cases WHERE (intent_id=? OR reference=?) AND resolution IN ('"+String.join("','",RELEASES)+"')",id,id.toString()))
            throw notAllowed("This payment was closed by Carrier finance without Stripe confirming it failed. Please start a new payment.");
    }
    private static BillingProblem notAllowed(String message) { return new BillingProblem(409,"RETRY_NOT_ALLOWED",message); }
}
