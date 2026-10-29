package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import com.invoicepayments.commerce.CommercePaymentGuard;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;
import static com.invoicepayments.billing.BillingService.*;

/** Short named database units; Stripe I/O belongs to BillingService. */
@Service
@Profile("billing")
@Transactional(transactionManager="transactionManager",propagation=Propagation.REQUIRED,rollbackFor=Exception.class,timeout=10,label="billing")
public class BillingPaymentCommands {
    private final BillingStore db;private final BillingPaymentAccess access;private final BillingPaymentLocks locks;
    public BillingPaymentCommands(BillingStore db,BillingPaymentAccess access,BillingPaymentLocks locks) {this.db=db;this.access=access;this.locks=locks;}
    public Map<String,Object> create(String user,UUID account,String key,List<UUID> requested,String method,String providerName) {
        BillingExecutionBudget.requireTime();
        requireTransaction();
        access.authorizePayer(user,account); validateKey(key);
        if(requested==null || requested.isEmpty() || requested.size()>50 || requested.stream().anyMatch(Objects::isNull)
            || new HashSet<>(requested).size()!=requested.size()) throw BillingProblem.invalid("Select between one and fifty distinct invoices");
        if(!Set.of("CARD","ACH").contains(Objects.toString(method,""))) throw BillingProblem.invalid("Select CARD or ACH");
        CommercePaymentGuard.rejectDirectInvoiceCreation(db,requested);
        List<UUID> ids=requested.stream().sorted().toList();
        String fingerprint=hash(method+":"+ids);
        // Lock in PostgreSQL's uuid order (unsigned, the same as the text form), as every other path does.
        // UUID.compareTo is signed, so its order can invert and deadlock. The fingerprint keeps its original order.
        List<UUID> lockOrder=requested.stream().sorted(Comparator.comparing(UUID::toString)).toList();
        // A committed replay needs no account lock. An uncommitted repeat must not wait behind its first save.
        var replay=db.rows("SELECT * FROM billing.intents WHERE account_id=? AND idempotency_key=?",account,key);
        if(!replay.isEmpty()) {
            if(!fingerprint.equals(replay.get(0).get("requestHash"))) throw new BillingProblem(409,"IDEMPOTENCY_CONFLICT","Idempotency key already belongs to a different request");
            return access.view(replay.get(0));
        }
        db.requireCommandSlot("create:"+account+":"+key);
        // Serialize different creation commands for the same account, not provider IO.
        db.one("SELECT id FROM billing.accounts WHERE id=? FOR UPDATE",account);
        var prior=db.rows("SELECT * FROM billing.intents WHERE account_id=? AND idempotency_key=?",account,key);
        if(!prior.isEmpty()) {
            if(!fingerprint.equals(prior.get(0).get("requestHash"))) throw new BillingProblem(409,"IDEMPOTENCY_CONFLICT","Idempotency key already belongs to a different request");
            return access.view(prior.get(0));
        }
        List<Map<String,Object>> selected=new ArrayList<>(); long total=0; String currency=null;
        for(UUID invoice:lockOrder) {
            var row=db.one("SELECT * FROM billing.invoices WHERE id=? AND account_id=? FOR UPDATE",invoice,account);
            String invoiceCurrency=text(row,"currency");
            if(currency==null) currency=invoiceCurrency;
            else if(!currency.equals(invoiceCurrency)) throw BillingProblem.invalid("One payment cannot combine different invoice currencies");
            long available=amount(row,"totalMinor")-amount(row,"paidMinor")-amount(row,"reservedMinor");
            if(available<=0) throw new BillingProblem(409,"INVOICE_UNAVAILABLE","An invoice is already paid or reserved by another payment; no intent was created for this key");
            total=Math.addExact(total,available); row.put("available",available); selected.add(row);
        }
        if(total>99_999_999L || ("STRIPE".equals(providerName) && total<50))
            throw BillingProblem.invalid("This provider supports payments up to 99999999 cents; Stripe requires at least 50 cents");
        if("RAZORPAY".equals(providerName) && (!"INR".equals(currency) || total<100 || !"CARD".equals(method)))
            throw BillingProblem.invalid("The Razorpay test integration supports INR card payments of at least 100 paise");
        UUID intent=UUID.randomUUID();
        db.update("INSERT INTO billing.intents(id,account_id,idempotency_key,request_hash,amount_minor,currency,method,status,provider) VALUES(?,?,?,?,?,?,?,'CREATED',?)",
            intent,account,key,fingerprint,total,currency,method,providerName);
        db.update("INSERT INTO billing.payment_notification_owners(intent_id,account_id,user_id) VALUES(?,?,?)",intent,account,user);
        for(var invoice:selected) {
            UUID invoiceId=id(invoice.get("id")); long available=amount(invoice,"available");
            db.update("UPDATE billing.invoices SET reserved_minor=reserved_minor+?,version=version+1 WHERE id=?",available,invoiceId);
            db.update("INSERT INTO billing.allocations(intent_id,invoice_id,amount_minor,state) VALUES(?,?,?,'RESERVED')",intent,invoiceId,available);
        }
        db.event(intent,"CREATED","Payment created and invoice amounts reserved; funds have not been collected");
        return access.view(access.raw(intent));
    }
    public Map<String,Object> cancel(String user,UUID intent) {
        BillingExecutionBudget.requireTime();
        requireTransaction();
        access.authorizePayer(user,id(access.raw(intent).get("accountId")));
        CommercePaymentGuard.requireOrderCancellation(db,intent);
        var payment=locks.locked(intent);
        if("CANCELLED".equals(payment.get("status"))) return access.view(payment);
        if(!"CREATED".equals(payment.get("status"))) throw BillingProblem.conflict("A submitted or uncertain payment cannot be cancelled locally");
        for(var allocation:locks.lockInvoices(intent)) if("RESERVED".equals(allocation.get("state")))
            db.update("UPDATE billing.invoices SET reserved_minor=reserved_minor-?,version=version+1 WHERE id=?",amount(allocation,"amountMinor"),id(allocation.get("invoiceId")));
        db.update("UPDATE billing.allocations SET state='RELEASED' WHERE intent_id=?",intent);
        db.update("UPDATE billing.intents SET status='CANCELLED',updated_at=NOW() WHERE id=?",intent);
        db.event(intent,"CANCELLED","Unsubmitted payment cancelled and invoice reservations released; no provider operation was issued");
        return access.view(access.raw(intent));
    }
    public Map<String,Object> prepareSubmission(UUID intent,UUID token,BillingPaymentAdmission admission,
            java.util.concurrent.atomic.AtomicReference<ProviderPermit> slot,String providerName,String requestVersion,String returnUrl) {
        BillingExecutionBudget.requireTime();requireTransaction();
        db.requireCommandSlot("submit:"+intent);
        CommercePaymentGuard.requirePayableOrder(db,intent);
        var row=locks.locked(intent);
        access.requireProvider(row,providerName);
        if(!"CREATED".equals(row.get("status"))) return null;
        // Existing money evidence remains recordable; a known older capture stops a new retry from being sent.
        if(db.exists("SELECT 1 FROM billing.intents i JOIN billing.journals j ON j.effect_key='capture:'||i.id WHERE i.payment_reference=? AND i.id<>?",
                id(row.get("paymentReference")),intent))
            throw new BillingProblem(409,"ALREADY_PAID","An earlier attempt for this payment was received. This attempt was not sent. Check your payment history.");
        // Asked only while this payment is locked and still new. Another click on it waits for the lock and is then
        // shown the payment on its way, so "busy, nothing was charged" is only ever said of an unsent payment.
        slot.set(admission.admit(intent));
        db.update("UPDATE billing.intents SET status='SUBMITTING',lease_token=?,lease_until=NOW()+INTERVAL '120 seconds',next_recovery_at=NOW()+INTERVAL '120 seconds',updated_at=NOW() WHERE id=?",token,intent);
        ProviderCommand command=new ProviderCommand(intent.toString(),operationKey(intent),amount(row,"amountMinor"),
            text(row,"currency"),text(row,"method"),returnUrl);
        db.update("INSERT INTO billing.attempts(id,intent_id,provider,operation_key,status,original_request,request_version) VALUES(?,?,?,?,'SUBMITTING',?::jsonb,?)",
            UUID.randomUUID(),intent,providerName,operationKey(intent),command.toJson(),requestVersion);
        db.event(intent,"SUBMITTING","Payment submission prepared; provider acceptance is not yet known");
        return db.one("SELECT a.original_request,a.request_version,a.created_at,i.lease_until FROM billing.attempts a JOIN billing.intents i ON i.id=a.intent_id WHERE a.intent_id=?",intent);

    }
    public void recordInitialRejection(UUID intent,UUID token,ProviderRejectedException rejected) {
        BillingExecutionBudget.requireTime();
        requireTransaction();
        var row=locks.locked(intent);
        BillingProviderEvidence.record(db,intent,null,rejected.diagnostics());
        if(!token.toString().equals(text(row,"leaseToken")) || !"SUBMITTING".equals(row.get("status"))
            || row.get("providerId")!=null) {
            db.event(intent,"STALE_REQUEST_REJECTION","A superseded submission was rejected; the current payment outcome was preserved");
            return;
        }
        // Expiry alone does not invalidate this proof: no replacement owner claimed the row and
        // no provider operation has been recorded. The row lock prevents a simultaneous claim.
        applyResult(intent,token,new ProviderResult(null,"FAILED",null,"The initial request was rejected before a payment was created"));
        return;
    }
    public void recordResult(UUID intent,UUID fence,ProviderResult result) {
        BillingExecutionBudget.requireTime();requireTransaction();applyResult(intent,fence,result);
    }

    private void applyResult(UUID intent,UUID fence,ProviderResult result) {
        BillingExecutionBudget.requireTime();
        requireTransaction();
        var row=locks.locked(intent);
        BillingProviderEvidence.record(db,intent,null,result.diagnostics());
        boolean owner=fence==null || fence.toString().equals(text(row,"leaseToken"));
        // A superseded worker's uncertain answer is dropped. A definitive one is always booked: capture, failure and
        // return are idempotent, and a capture after a release becomes unapplied cash with its own case.
        if(!owner && !DEFINITIVE.contains(result.state())) return;
        String existing=text(row,"providerId");
        if(result.providerId()!=null && existing!=null && !existing.equals(result.providerId()))
            throw BillingProblem.conflict("Provider operation identity does not match the original payment");
        if(result.providerId()!=null) {
            db.update("UPDATE billing.intents SET provider_id=COALESCE(provider_id,?),checkout_url=COALESCE(?,checkout_url) WHERE id=?",result.providerId(),safeCheckout(result.checkoutUrl()),intent);
            db.update("UPDATE billing.attempts SET provider_id=COALESCE(provider_id,?),updated_at=NOW() WHERE intent_id=?",result.providerId(),intent);
        }
        switch(result.state()) {
            case "CAPTURED" -> {
                if(!"RAZORPAY".equals(row.get("provider")) || BillingCapturedPaymentEvidence.primary(db,row,result.providerId(),result.sourcePaymentId()))capture(row);
            }
            case "FAILED" -> fail(row,"Provider confirmed failure; invoice reservations released");
            case "RETURNED" -> returned(row);
            case "UNKNOWN","PROCESSING","REQUIRES_ACTION" -> {
                if(!access.hasCapture(intent) && !Set.of("FAILED","RETURNED","REFUNDED").contains(text(row,"status"))) {
                    db.update("UPDATE billing.intents SET status=? WHERE id=?",result.state(),intent);
                    db.event(intent,result.state(),"Payment awaits a definitive provider outcome");
                }
            }
            default -> throw BillingProblem.invalid("Unsupported provider result");
        }
        // Only the lease owner returns the lease; a superseded worker leaves the current owner's lease alone.
        if(owner) db.update("UPDATE billing.intents SET lease_token=NULL,lease_until=NULL,next_recovery_at=NOW()+INTERVAL '30 seconds',updated_at=NOW() WHERE id=?",intent);
        else db.update("UPDATE billing.intents SET updated_at=NOW() WHERE id=?",intent);
        db.update("UPDATE billing.attempts SET status=(SELECT status FROM billing.intents WHERE id=?),updated_at=NOW() WHERE intent_id=?",intent,intent);
        // Evidence that ends the uncertainty also closes the stuck-payment cases it explains.
        String current=text(db.one("SELECT status FROM billing.intents WHERE id=?",intent),"status");
        if(!Set.of("UNKNOWN","SUBMITTING").contains(current))
            db.resolveCases(intent.toString(),"PAYMENT_UNRESOLVED","PAYMENT_REPLAY_UNSAFE","system","PROVIDER_EVIDENCE","AUTO_RESOLVED",
                "Provider evidence recorded payment status "+current);
        return;
    }
    public void releaseUnresolved(UUID intent,UUID fence,String message) {
        BillingExecutionBudget.requireTime();
        requireTransaction();
        var row=locks.locked(intent);
        if(!fence.toString().equals(text(row,"leaseToken")))
            throw BillingProblem.conflict("Another process took over this payment during the release check; nothing was released. Try again");
        if(!Set.of("UNKNOWN","SUBMITTING").contains(text(row,"status")) || access.hasCapture(intent))
            throw BillingProblem.conflict("This payment is no longer unresolved; refresh the case");
        db.update("UPDATE billing.intents SET lease_token=NULL,lease_until=NULL,updated_at=NOW() WHERE id=?",intent);
        fail(row,message);
        db.update("UPDATE billing.attempts SET status='FAILED',updated_at=NOW() WHERE intent_id=?",intent);
        return;
    }
    public int expireUnsubmitted(int ttlMinutes,int limit) {
        BillingExecutionBudget.requireTime();
        requireTransaction();
        if(ttlMinutes<5) throw new IllegalArgumentException("Unsubmitted intents must stay payable for at least five minutes");
        var due=db.rows("""
            SELECT i.id FROM billing.intents i WHERE i.status='CREATED' AND i.created_at<NOW()-(? * INTERVAL '1 minute')
            AND NOT EXISTS (SELECT 1 FROM commerce.order_bills b WHERE b.payment_reference=i.payment_reference)
            ORDER BY i.created_at,i.id FOR UPDATE OF i SKIP LOCKED LIMIT ?
            """,ttlMinutes,Math.max(1,Math.min(limit,50)));
        for(var item:due) {
            UUID intent=id(item.get("id"));
            for(var allocation:locks.lockInvoices(intent)) if("RESERVED".equals(allocation.get("state")))
                db.update("UPDATE billing.invoices SET reserved_minor=reserved_minor-?,version=version+1 WHERE id=?",amount(allocation,"amountMinor"),id(allocation.get("invoiceId")));
            db.update("UPDATE billing.allocations SET state='RELEASED' WHERE intent_id=? AND state='RESERVED'",intent);
            db.update("UPDATE billing.intents SET status='CANCELLED',updated_at=NOW() WHERE id=?",intent);
            db.event(intent,"EXPIRED","Unsubmitted payment expired after "+ttlMinutes+" minutes; invoice reservations released; no provider operation was issued");
        }
        return due.size();
    }
    private void capture(Map<String,Object> intent) {
        UUID id=id(intent.get("id")); if(access.hasCapture(id)) return;
        var allocations=locks.lockInvoices(id);
        boolean reserved=!allocations.isEmpty() && allocations.stream().mapToLong(a->amount(a,"amountMinor")).sum()==amount(intent,"amountMinor")
            && allocations.stream().allMatch(a -> "RESERVED".equals(a.get("state")));
        if(reserved) {
            for(var allocation:allocations) db.update("UPDATE billing.invoices SET reserved_minor=reserved_minor-?,paid_minor=paid_minor+?,version=version+1 WHERE id=?",
                amount(allocation,"amountMinor"),amount(allocation,"amountMinor"),id(allocation.get("invoiceId")));
            db.update("UPDATE billing.allocations SET state='POSTED' WHERE intent_id=?",id);
        } else {
            for(var allocation:allocations) if("RESERVED".equals(allocation.get("state")))
                db.update("UPDATE billing.invoices SET reserved_minor=reserved_minor-?,version=version+1 WHERE id=?",amount(allocation,"amountMinor"),id(allocation.get("invoiceId")));
            db.update("UPDATE billing.allocations SET state='RELEASED' WHERE intent_id=? AND state='RESERVED'",id);
            db.caseFor(id,"LATE_CAPTURE_UNAPPLIED",id.toString());
        }
        db.journal(intent,"capture:"+id,"PROVIDER_CLEARING",reserved?"CUSTOMER_RECEIVABLES":"UNAPPLIED_CASH",amount(intent,"amountMinor"));
        db.update("UPDATE billing.intents SET status='CAPTURED' WHERE id=?",id);
        db.event(id,"CAPTURED",reserved?"Capture confirmed and receipt allocated; bank settlement is separate":"Late capture recorded as unapplied cash for finance review");
        db.publish(intent,"PAYMENT_CAPTURED");
    }
    private void fail(Map<String,Object> intent,String message) {
        UUID id=id(intent.get("id")); if(access.hasCapture(id) || "FAILED".equals(intent.get("status"))) return;
        for(var allocation:locks.lockInvoices(id)) if("RESERVED".equals(allocation.get("state")))
            db.update("UPDATE billing.invoices SET reserved_minor=reserved_minor-?,version=version+1 WHERE id=?",amount(allocation,"amountMinor"),id(allocation.get("invoiceId")));
        db.update("UPDATE billing.allocations SET state='RELEASED' WHERE intent_id=? AND state='RESERVED'",id);
        db.update("UPDATE billing.intents SET status='FAILED' WHERE id=?",id);
        db.event(id,"FAILED",message); db.publish(intent,"PAYMENT_FAILED");
    }
    private void returned(Map<String,Object> intent) {
        UUID id=id(intent.get("id"));
        if(!"ACH".equals(intent.get("method"))) {db.escalate(id,null,"DISPUTE_REQUIRES_REVIEW",id.toString(),"New dispute evidence arrived for this card payment"); return;}
        if(!access.hasCapture(id)) throw BillingProblem.conflict("A return cannot precede recorded payment evidence");
        if(db.exists("SELECT 1 FROM billing.journals WHERE effect_key=?","return:"+id)) return;
        boolean allocated=false;
        for(var allocation:locks.lockInvoices(id)) if("POSTED".equals(allocation.get("state"))) {
            allocated=true;
            db.update("UPDATE billing.invoices SET paid_minor=paid_minor-?,version=version+1 WHERE id=?",amount(allocation,"amountMinor"),id(allocation.get("invoiceId")));
        }
        db.update("UPDATE billing.allocations SET state='REVERSED',reversed_minor=amount_minor WHERE intent_id=? AND state='POSTED'",id);
        db.journal(intent,"return:"+id,allocated?"CUSTOMER_RECEIVABLES":"UNAPPLIED_CASH","PROVIDER_CLEARING",amount(intent,"amountMinor"));
        db.update("UPDATE billing.intents SET status='RETURNED' WHERE id=?",id);
        db.event(id,"RETURNED","ACH return recorded; prior receipt retained and invoice balances reopened");
        db.caseFor(id,"ACH_RETURN",id.toString()); db.publish(intent,"PAYMENT_RETURNED");
    }
}
