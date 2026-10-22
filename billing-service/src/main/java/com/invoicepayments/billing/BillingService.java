package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import java.util.*;
import java.time.Instant;
import static com.invoicepayments.billing.BillingStore.*;

/** Financial effects occur only inside database transactions; provider IO occurs outside them. */
@Service
@Profile("billing")
public class BillingService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(BillingService.class);
    private final BillingStore db;
    private final BillingProvider provider;
    private final boolean simulator;
    private final String publicUrl;
    private final UUID providerMerchant;
    public BillingService(BillingStore db,BillingProvider provider,Environment env,
        @Value("${billing.simulator-enabled:false}") boolean simulator,
        @Value("${billing.public-url:http://localhost:4200}") String publicUrl,
        @Value("${billing.provider-merchant-id:10000000-0000-0000-0000-000000000001}") String merchant) {
        this.db=db; this.provider=provider;
        this.simulator=simulator && !env.acceptsProfiles(Profiles.of("prod")); this.publicUrl=publicUrl;
        this.providerMerchant=id(merchant);
    }
    public List<Map<String,Object>> accounts(String user) {
        return db.rows("SELECT a.id,a.name,a.merchant_id,m.role FROM billing.accounts a JOIN billing.memberships m ON m.account_id=a.id WHERE m.user_id=? ORDER BY a.name",user);
    }
    public void authorize(String user,UUID account) {
        if(!db.exists("SELECT 1 FROM billing.memberships WHERE user_id=? AND account_id=?",user,account)) throw BillingProblem.missing();
    }
    private void authorizePayer(String user,UUID account) {
        if(!db.exists("SELECT 1 FROM billing.memberships m JOIN billing.accounts a ON a.id=m.account_id WHERE m.user_id=? AND m.account_id=? AND m.role='PAYER' AND a.merchant_id=?",user,account,providerMerchant)) throw BillingProblem.missing();
    }
    public void staff(String user,UUID merchant,String role) {
        if(!db.exists("SELECT 1 FROM billing.staff WHERE user_id=? AND merchant_id=? AND role=?",user,merchant,role))
            throw new BillingProblem(403,"FORBIDDEN","This operation requires an assigned merchant permission");
    }
    public List<Map<String,Object>> invoices(String user,UUID account) {
        authorize(user,account);
        return db.rows("""
            SELECT id,number,currency,total_minor,paid_minor,reserved_minor,due_date,version,
             CASE WHEN paid_minor=total_minor THEN 'PAID' WHEN reserved_minor>0 THEN 'PAYMENT_PENDING' ELSE 'OPEN' END status
            FROM billing.invoices WHERE account_id=? ORDER BY due_date,id LIMIT 200
            """,account);
    }
    public Map<String,Object> create(String user,UUID account,String key,List<UUID> requested,String method) {
        authorizePayer(user,account); validateKey(key);
        if(requested==null || requested.isEmpty() || requested.size()>50 || requested.stream().anyMatch(Objects::isNull)
            || new HashSet<>(requested).size()!=requested.size()) throw BillingProblem.invalid("Select between one and fifty distinct invoices");
        if(!Set.of("CARD","ACH").contains(Objects.toString(method,""))) throw BillingProblem.invalid("Select CARD or ACH");
        List<UUID> ids=requested.stream().sorted().toList();
        String fingerprint=hash(method+":"+ids);
        // Lock in PostgreSQL's uuid order (unsigned, the same as the text form), as every other path does.
        // UUID.compareTo is signed, so its order can invert and deadlock. The fingerprint keeps its original order.
        List<UUID> lockOrder=requested.stream().sorted(Comparator.comparing(UUID::toString)).toList();
        return db.tx(() -> {
            // Serialize this customer's creation commands and the uniqueness check, not provider IO.
            db.one("SELECT id FROM billing.accounts WHERE id=? FOR UPDATE",account);
            var prior=db.rows("SELECT * FROM billing.intents WHERE account_id=? AND idempotency_key=?",account,key);
            if(!prior.isEmpty()) {
                if(!fingerprint.equals(prior.get(0).get("requestHash"))) throw new BillingProblem(409,"IDEMPOTENCY_CONFLICT","Idempotency key already belongs to a different request");
                return view(prior.get(0));
            }
            List<Map<String,Object>> selected=new ArrayList<>(); long total=0;
            for(UUID invoice:lockOrder) {
                var row=db.one("SELECT * FROM billing.invoices WHERE id=? AND account_id=? FOR UPDATE",invoice,account);
                long available=amount(row,"totalMinor")-amount(row,"paidMinor")-amount(row,"reservedMinor");
                if(available<=0) throw new BillingProblem(409,"INVOICE_UNAVAILABLE","An invoice is already paid or reserved by another payment; no intent was created for this key");
                total=Math.addExact(total,available); row.put("available",available); selected.add(row);
            }
            if(total>99_999_999L || ("STRIPE".equals(provider.name()) && total<50))
                throw BillingProblem.invalid("This provider supports payments up to 99999999 cents; Stripe requires at least 50 cents");
            UUID intent=UUID.randomUUID();
            db.update("INSERT INTO billing.intents(id,account_id,idempotency_key,request_hash,amount_minor,currency,method,status,provider) VALUES(?,?,?,?,?,'USD',?,'CREATED',?)",
                intent,account,key,fingerprint,total,method,provider.name());
            for(var invoice:selected) {
                UUID invoiceId=id(invoice.get("id")); long available=amount(invoice,"available");
                db.update("UPDATE billing.invoices SET reserved_minor=reserved_minor+?,version=version+1 WHERE id=?",available,invoiceId);
                db.update("INSERT INTO billing.allocations(intent_id,invoice_id,amount_minor,state) VALUES(?,?,?,'RESERVED')",intent,invoiceId,available);
            }
            db.event(intent,"CREATED","Payment created and invoice amounts reserved; funds have not been collected");
            return view(raw(intent));
        });
    }
    public Map<String,Object> get(String user,UUID intent) {
        var row=raw(intent); authorize(user,id(row.get("accountId"))); return view(row);
    }
    public List<Map<String,Object>> history(String user,UUID account) {
        authorize(user,account);
        var rows=db.rows("SELECT id,account_id,amount_minor,currency,method,status,provider,created_at,updated_at,refunded_minor FROM billing.intents WHERE account_id=? ORDER BY created_at DESC,id DESC LIMIT 100",account);
        for(var row:rows) row.put("simulatorEnabled",simulator && "SIMULATOR".equals(row.get("provider")));
        return rows;
    }
    public Map<String,Object> cancel(String user,UUID intent) {
        authorizePayer(user,id(raw(intent).get("accountId")));
        return db.tx(()->{
            var payment=locked(intent);
            if("CANCELLED".equals(payment.get("status"))) return view(payment);
            if(!"CREATED".equals(payment.get("status"))) throw BillingProblem.conflict("A submitted or uncertain payment cannot be cancelled locally");
            for(var allocation:lockInvoices(intent)) if("RESERVED".equals(allocation.get("state")))
                db.update("UPDATE billing.invoices SET reserved_minor=reserved_minor-?,version=version+1 WHERE id=?",amount(allocation,"amountMinor"),id(allocation.get("invoiceId")));
            db.update("UPDATE billing.allocations SET state='RELEASED' WHERE intent_id=?",intent);
            db.update("UPDATE billing.intents SET status='CANCELLED',updated_at=NOW() WHERE id=?",intent);
            db.event(intent,"CANCELLED","Unsubmitted payment cancelled and invoice reservations released; no provider operation was issued");
            return view(raw(intent));
        });
    }
    public Map<String,Object> submit(String user,UUID intent) {
        requireNoTransaction();
        authorizePayer(user,id(raw(intent).get("accountId")));
        UUID token=UUID.randomUUID();
        var work=db.tx(() -> {
            var row=locked(intent);
            requireProvider(row);
            if(!"CREATED".equals(row.get("status"))) return null;
            db.update("UPDATE billing.intents SET status='SUBMITTING',lease_token=?,lease_until=NOW()+INTERVAL '120 seconds',next_recovery_at=NOW()+INTERVAL '120 seconds',updated_at=NOW() WHERE id=?",token,intent);
            ProviderCommand command=new ProviderCommand(intent.toString(),operationKey(intent),amount(row,"amountMinor"),
                text(row,"currency"),text(row,"method"),publicUrl+"/invoices?intentId="+intent);
            db.update("INSERT INTO billing.attempts(id,intent_id,provider,operation_key,status,original_request,request_version) VALUES(?,?,?,?,'SUBMITTING',?::jsonb,?)",
                UUID.randomUUID(),intent,provider.name(),operationKey(intent),command.toJson(),provider.paymentRequestVersion());
            db.event(intent,"SUBMITTING","Payment submission prepared; provider acceptance is not yet known");
            return db.one("SELECT original_request,request_version,created_at FROM billing.attempts WHERE intent_id=?",intent);
        });
        if(work==null) return get(user,intent);
        ProviderResult result;
        try {
            result=provider.submitSaved(ProviderCommand.fromJson(text(work,"originalRequest")),
                text(work,"requestVersion"),Instant.parse(text(work,"createdAt")));
        } catch(ProviderRejectedException rejected) {
            // TX-A made this the first attempt with this key, and the provider refused it before acting.
            log.warn("Provider rejected the payment before acting; releasing invoices intentId={} reason={}",intent,rejected.getMessage());
            result=new ProviderResult(null,"FAILED",null,"Provider rejected the request before creating a payment");
        } catch(RuntimeException failure) {
            // Identifiers and the exception only: provider exceptions carry no credential or response body.
            log.warn("Payment submission outcome uncertain; recovery will reconcile intentId={}",intent,failure);
            result=new ProviderResult(null,"UNKNOWN",null,"Provider outcome is uncertain; recovery will query the original operation");
        }
        recordResult(intent,token,result);
        return get(user,intent);
    }
    public Map<String,Object> simulate(String user,UUID intent,String outcome) {
        requireNoTransaction();
        authorizePayer(user,id(raw(intent).get("accountId")));
        if(!simulator || !(provider instanceof SimulatorBillingProvider simulated))
            throw new BillingProblem(403,"SIMULATOR_DISABLED","Simulation is disabled in this environment");
        var before=raw(intent);
        if("CREATED".equals(before.get("status"))) throw BillingProblem.conflict("Submit the original operation before simulating an outcome");
        try { recordResult(intent,null,simulated.simulateResult(intent.toString(),outcome)); }
        catch(ProviderUncertainException ex) { recordResult(intent,null,new ProviderResult(null,"UNKNOWN",null,"Response lost; query the original provider operation")); }
        return get(user,intent);
    }
    /** States the provider never takes back: a superseded worker's answer of this kind is still booked. */
    static final Set<String> DEFINITIVE=Set.of("CAPTURED","FAILED","RETURNED");
    public void recordResult(UUID intent,UUID fence,ProviderResult result) {
        db.tx(() -> {
            var row=locked(intent);
            boolean owner=fence==null || fence.toString().equals(text(row,"leaseToken"));
            // A superseded worker's uncertain answer is dropped. A definitive one is always booked: capture, failure and
            // return are idempotent, and a capture after a release becomes unapplied cash with its own case.
            if(!owner && !DEFINITIVE.contains(result.state())) return null;
            String existing=text(row,"providerId");
            if(result.providerId()!=null && existing!=null && !existing.equals(result.providerId()))
                throw BillingProblem.conflict("Provider operation identity does not match the original payment");
            if(result.providerId()!=null) {
                db.update("UPDATE billing.intents SET provider_id=COALESCE(provider_id,?),checkout_url=COALESCE(?,checkout_url) WHERE id=?",result.providerId(),safeCheckout(result.checkoutUrl()),intent);
                db.update("UPDATE billing.attempts SET provider_id=COALESCE(provider_id,?),updated_at=NOW() WHERE intent_id=?",result.providerId(),intent);
            }
            switch(result.state()) {
                case "CAPTURED" -> capture(row);
                case "FAILED" -> fail(row,"Provider confirmed failure; invoice reservations released");
                case "RETURNED" -> returned(row);
                case "UNKNOWN","PROCESSING","REQUIRES_ACTION" -> {
                    if(!hasCapture(intent) && !Set.of("FAILED","RETURNED","REFUNDED").contains(text(row,"status"))) {
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
            return null;
        });
    }
    /**
     * Fences out submission and recovery while a release asks the provider to prove there is no payment.
     * Refused while one of them is talking to the provider. Returns what the provider check needs.
     */
    Map<String,Object> leaseForRelease(UUID intent,UUID token) {
        locked(intent);
        if(db.update("UPDATE billing.intents SET lease_token=?,lease_until=NOW()+INTERVAL '120 seconds',next_recovery_at=NOW()+INTERVAL '120 seconds' WHERE id=? AND (lease_until IS NULL OR lease_until<NOW())",token,intent)==0)
            throw BillingProblem.conflict("Recovery is checking this payment with the provider right now. Try the release again in two minutes");
        return db.one("""
            SELECT i.id AS intent_id,i.amount_minor,i.currency,i.method,i.provider_id,i.created_at,a.original_request,a.request_version,a.created_at AS prepared_at
            FROM billing.intents i LEFT JOIN billing.attempts a ON a.intent_id=i.id WHERE i.id=?
            """,intent);
    }
    /** The exact request that was saved before the first provider call, or, for legacy attempts, its identity. */
    ProviderCommand savedCommand(Map<String,Object> claim) {
        if(claim.get("originalRequest")!=null) return ProviderCommand.fromJson(text(claim,"originalRequest"));
        UUID intent=id(claim.get("intentId"));
        return new ProviderCommand(intent.toString(),operationKey(intent),amount(claim,"amountMinor"),text(claim,"currency"),text(claim,"method"),null);
    }
    Instant preparedAt(Map<String,Object> claim) { return Instant.parse(text(claim,claim.get("preparedAt")!=null?"preparedAt":"createdAt")); }
    /**
     * Approved case release, after the provider proved it has no payment and can no longer take one. The lease
     * taken by leaseForRelease fences the release. Late provider evidence is still recorded: a later capture
     * becomes unapplied cash with its own case, never another invoice's money.
     */
    void releaseUnresolved(UUID intent,UUID fence,String message) {
        db.tx(() -> {
            var row=locked(intent);
            if(!fence.toString().equals(text(row,"leaseToken")))
                throw BillingProblem.conflict("Another process took over this payment during the release check; nothing was released. Try again");
            if(!Set.of("UNKNOWN","SUBMITTING").contains(text(row,"status")) || hasCapture(intent))
                throw BillingProblem.conflict("This payment is no longer unresolved; refresh the case");
            db.update("UPDATE billing.intents SET lease_token=NULL,lease_until=NULL,updated_at=NOW() WHERE id=?",intent);
            fail(row,message);
            db.update("UPDATE billing.attempts SET status='FAILED',updated_at=NOW() WHERE intent_id=?",intent);
            return null;
        });
    }
    /**
     * Releases invoices held by intents the customer created but never submitted. Row locks make
     * this race-safe with submit and cancel: whichever locks the intent first decides, and the
     * other sees the new status. No provider operation exists for a CREATED intent.
     */
    public int expireUnsubmitted(int ttlMinutes,int limit) {
        if(ttlMinutes<5) throw new IllegalArgumentException("Unsubmitted intents must stay payable for at least five minutes");
        return db.tx(() -> {
            var due=db.rows("""
                SELECT id FROM billing.intents WHERE status='CREATED' AND created_at<NOW()-(? * INTERVAL '1 minute')
                ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT ?
                """,ttlMinutes,Math.max(1,Math.min(limit,50)));
            for(var item:due) {
                UUID intent=id(item.get("id"));
                for(var allocation:lockInvoices(intent)) if("RESERVED".equals(allocation.get("state")))
                    db.update("UPDATE billing.invoices SET reserved_minor=reserved_minor-?,version=version+1 WHERE id=?",amount(allocation,"amountMinor"),id(allocation.get("invoiceId")));
                db.update("UPDATE billing.allocations SET state='RELEASED' WHERE intent_id=? AND state='RESERVED'",intent);
                db.update("UPDATE billing.intents SET status='CANCELLED',updated_at=NOW() WHERE id=?",intent);
                db.event(intent,"EXPIRED","Unsubmitted payment expired after "+ttlMinutes+" minutes; invoice reservations released; no provider operation was issued");
            }
            return due.size();
        });
    }
    private void capture(Map<String,Object> intent) {
        UUID id=id(intent.get("id")); if(hasCapture(id)) return;
        var allocations=lockInvoices(id);
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
        UUID id=id(intent.get("id")); if(hasCapture(id) || "FAILED".equals(intent.get("status"))) return;
        for(var allocation:lockInvoices(id)) if("RESERVED".equals(allocation.get("state")))
            db.update("UPDATE billing.invoices SET reserved_minor=reserved_minor-?,version=version+1 WHERE id=?",amount(allocation,"amountMinor"),id(allocation.get("invoiceId")));
        db.update("UPDATE billing.allocations SET state='RELEASED' WHERE intent_id=? AND state='RESERVED'",id);
        db.update("UPDATE billing.intents SET status='FAILED' WHERE id=?",id);
        db.event(id,"FAILED",message); db.publish(intent,"PAYMENT_FAILED");
    }
    private void returned(Map<String,Object> intent) {
        UUID id=id(intent.get("id"));
        if(!"ACH".equals(intent.get("method"))) {db.escalate(id,null,"DISPUTE_REQUIRES_REVIEW",id.toString(),"New dispute evidence arrived for this card payment"); return;}
        if(!hasCapture(id)) throw BillingProblem.conflict("A return cannot precede recorded payment evidence");
        if(db.exists("SELECT 1 FROM billing.journals WHERE effect_key=?","return:"+id)) return;
        boolean allocated=false;
        for(var allocation:lockInvoices(id)) if("POSTED".equals(allocation.get("state"))) {
            allocated=true;
            db.update("UPDATE billing.invoices SET paid_minor=paid_minor-?,version=version+1 WHERE id=?",amount(allocation,"amountMinor"),id(allocation.get("invoiceId")));
        }
        db.update("UPDATE billing.allocations SET state='REVERSED',reversed_minor=amount_minor WHERE intent_id=? AND state='POSTED'",id);
        db.journal(intent,"return:"+id,allocated?"CUSTOMER_RECEIVABLES":"UNAPPLIED_CASH","PROVIDER_CLEARING",amount(intent,"amountMinor"));
        db.update("UPDATE billing.intents SET status='RETURNED' WHERE id=?",id);
        db.event(id,"RETURNED","ACH return recorded; prior receipt retained and invoice balances reopened");
        db.caseFor(id,"ACH_RETURN",id.toString()); db.publish(intent,"PAYMENT_RETURNED");
    }
    List<Map<String,Object>> lockInvoices(UUID intent) {
        return db.rows("SELECT a.* FROM billing.allocations a JOIN billing.invoices v ON v.id=a.invoice_id WHERE a.intent_id=? ORDER BY v.id FOR UPDATE OF v,a",intent);
    }
    public boolean hasCapture(UUID intent) { return db.exists("SELECT 1 FROM billing.journals WHERE effect_key=?","capture:"+intent); }
    void requireProvider(Map<String,Object> payment) {
        if(!provider.name().equals(payment.get("provider"))) throw BillingProblem.conflict("The original payment provider must be restored before this operation can continue");
    }
    public Map<String,Object> raw(UUID intent) { return db.one("SELECT * FROM billing.intents WHERE id=?",intent); }
    Map<String,Object> locked(UUID intent) { return db.one("SELECT * FROM billing.intents WHERE id=? FOR UPDATE",intent); }
    Map<String,Object> view(Map<String,Object> row) {
        UUID intent=id(row.get("id"));
        Map<String,Object> result=new LinkedHashMap<>();
        for(String key:List.of("id","accountId","amountMinor","currency","method","status","provider","checkoutUrl","createdAt","updatedAt","refundedMinor")) result.put(key,row.get(key));
        result.put("simulatorEnabled",simulator && "SIMULATOR".equals(row.get("provider")));
        result.put("message",switch(text(row,"status")) {
            case "CAPTURED" -> "Payment captured. Bank settlement is tracked separately.";
            case "UNKNOWN","SUBMITTING" -> "Payment outcome is uncertain. Refresh this operation; do not start another payment.";
            case "PROCESSING","REQUIRES_ACTION" -> "Awaiting provider confirmation.";
            case "CREATED" -> "Invoice amounts reserved; ready to submit.";
            default -> "See the payment timeline for the recorded outcome.";
        });
        result.put("allocations",db.rows("SELECT invoice_id,amount_minor,reversed_minor,state FROM billing.allocations WHERE intent_id=? ORDER BY invoice_id",intent));
        result.put("timeline",db.rows("SELECT type,created_at AS at,message FROM billing.timeline WHERE intent_id=? ORDER BY id DESC LIMIT 100",intent));
        return result;
    }
    static void validateKey(String key) {
        if(key==null || !key.matches("[A-Za-z0-9_\\-]{16,128}")) throw BillingProblem.invalid("A 16–128 character Idempotency-Key is required");
    }
    static String operationKey(UUID intent) { return "billing_"+intent; }
    static String safeCheckout(String url) {
        if(url==null) return null;
        var uri=java.net.URI.create(url);
        if(!"https".equals(uri.getScheme()) || !"checkout.stripe.com".equals(uri.getHost()) || uri.getUserInfo()!=null || (uri.getPort()!=-1 && uri.getPort()!=443))
            throw BillingProblem.conflict("Untrusted checkout destination");
        return url;
    }
}
