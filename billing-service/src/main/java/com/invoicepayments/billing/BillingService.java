package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;

/** Financial effects occur only inside database transactions; provider IO occurs outside them. */
@Service
@Profile("billing")
public class BillingService {
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
        return db.tx(() -> {
            // Serialize this customer's creation commands and the uniqueness check, not provider IO.
            db.one("SELECT id FROM billing.accounts WHERE id=? FOR UPDATE",account);
            var prior=db.rows("SELECT * FROM billing.intents WHERE account_id=? AND idempotency_key=?",account,key);
            if(!prior.isEmpty()) {
                if(!fingerprint.equals(prior.get(0).get("requestHash"))) throw new BillingProblem(409,"IDEMPOTENCY_CONFLICT","Idempotency key already belongs to a different request");
                return view(prior.get(0));
            }
            List<Map<String,Object>> selected=new ArrayList<>(); long total=0;
            for(UUID invoice:ids) {
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
        authorizePayer(user,id(raw(intent).get("accountId")));
        UUID token=UUID.randomUUID();
        var work=db.tx(() -> {
            var row=locked(intent);
            requireProvider(row);
            if(!"CREATED".equals(row.get("status"))) return null;
            db.update("UPDATE billing.intents SET status='SUBMITTING',lease_token=?,lease_until=NOW()+INTERVAL '45 seconds',next_recovery_at=NOW()+INTERVAL '45 seconds',updated_at=NOW() WHERE id=?",token,intent);
            db.update("INSERT INTO billing.attempts(id,intent_id,provider,operation_key,status) VALUES(?,?,?,?,'SUBMITTING')",UUID.randomUUID(),intent,provider.name(),operationKey(intent));
            db.event(intent,"SUBMITTING","Original payment operation submitted; retries will reuse this operation");
            return row;
        });
        if(work==null) return get(user,intent);
        ProviderResult result;
        try {
            result=provider.submit(new ProviderCommand(intent.toString(),operationKey(intent),amount(work,"amountMinor"),"USD",text(work,"method"),publicUrl+"/invoices?intentId="+intent));
        } catch(RuntimeException failure) { result=new ProviderResult(null,"UNKNOWN",null,"Provider outcome is uncertain; recovery will query the original operation"); }
        recordResult(intent,token,result);
        return get(user,intent);
    }
    public Map<String,Object> simulate(String user,UUID intent,String outcome) {
        authorizePayer(user,id(raw(intent).get("accountId")));
        if(!simulator || !(provider instanceof SimulatorBillingProvider simulated))
            throw new BillingProblem(403,"SIMULATOR_DISABLED","Simulation is disabled in this environment");
        var before=raw(intent);
        if("CREATED".equals(before.get("status"))) throw BillingProblem.conflict("Submit the original operation before simulating an outcome");
        try { recordResult(intent,null,simulated.simulateResult(intent.toString(),outcome)); }
        catch(ProviderUncertainException ex) { recordResult(intent,null,new ProviderResult(null,"UNKNOWN",null,"Response lost; query the original provider operation")); }
        return get(user,intent);
    }
    public void recordResult(UUID intent,UUID fence,ProviderResult result) {
        db.tx(() -> {
            var row=locked(intent);
            if(fence!=null && !fence.toString().equals(text(row,"leaseToken"))) return null;
            String existing=text(row,"providerId");
            if(result.providerId()!=null && existing!=null && !existing.equals(result.providerId()))
                throw BillingProblem.conflict("Provider operation identity does not match the original payment");
            if(result.providerId()!=null) {
                db.update("UPDATE billing.intents SET provider_id=COALESCE(provider_id,?),checkout_url=COALESCE(?,checkout_url) WHERE id=?",result.providerId(),safeCheckout(result.checkoutUrl()),intent);
                db.update("UPDATE billing.attempts SET provider_id=COALESCE(provider_id,?),updated_at=NOW() WHERE intent_id=?",result.providerId(),intent);
            }
            switch(result.state()) {
                case "CAPTURED" -> capture(row);
                case "FAILED" -> fail(row);
                case "RETURNED" -> returned(row);
                case "UNKNOWN","PROCESSING","REQUIRES_ACTION" -> {
                    if(!hasCapture(intent) && !Set.of("FAILED","RETURNED","REFUNDED").contains(text(row,"status"))) {
                        db.update("UPDATE billing.intents SET status=? WHERE id=?",result.state(),intent);
                        db.event(intent,result.state(),"Payment awaits a definitive provider outcome");
                    }
                }
                default -> throw BillingProblem.invalid("Unsupported provider result");
            }
            db.update("UPDATE billing.intents SET lease_token=NULL,lease_until=NULL,next_recovery_at=NOW()+INTERVAL '30 seconds',updated_at=NOW() WHERE id=?",intent);
            db.update("UPDATE billing.attempts SET status=(SELECT status FROM billing.intents WHERE id=?),updated_at=NOW() WHERE intent_id=?",intent,intent);
            return null;
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
    private void fail(Map<String,Object> intent) {
        UUID id=id(intent.get("id")); if(hasCapture(id) || "FAILED".equals(intent.get("status"))) return;
        for(var allocation:lockInvoices(id)) if("RESERVED".equals(allocation.get("state")))
            db.update("UPDATE billing.invoices SET reserved_minor=reserved_minor-?,version=version+1 WHERE id=?",amount(allocation,"amountMinor"),id(allocation.get("invoiceId")));
        db.update("UPDATE billing.allocations SET state='RELEASED' WHERE intent_id=? AND state='RESERVED'",id);
        db.update("UPDATE billing.intents SET status='FAILED' WHERE id=?",id);
        db.event(id,"FAILED","Provider confirmed failure; invoice reservations released"); db.publish(intent,"PAYMENT_FAILED");
    }
    private void returned(Map<String,Object> intent) {
        UUID id=id(intent.get("id"));
        if(!"ACH".equals(intent.get("method"))) {db.caseFor(id,"DISPUTE_REQUIRES_REVIEW",id.toString()); return;}
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
