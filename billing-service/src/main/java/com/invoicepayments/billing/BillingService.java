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
    private final BillingPaymentCommands commands;
    private final BillingPaymentLocks locks;
    private final BillingProvider provider;
    private final boolean simulator;
    private final String publicUrl;
    private final UUID providerMerchant;
    public BillingService(BillingStore db,BillingProvider provider,Environment env,
        @Value("${billing.simulator-enabled:false}") boolean simulator,
        @Value("${billing.public-url:http://localhost:4200}") String publicUrl,
        @Value("${billing.provider-merchant-id:10000000-0000-0000-0000-000000000001}") String merchant,BillingPaymentCommands commands,BillingPaymentLocks locks) {
        this.db=db; this.provider=provider;this.commands=commands;this.locks=locks;
        this.simulator=simulator && !env.acceptsProfiles(Profiles.of("prod")); this.publicUrl=publicUrl;
        this.providerMerchant=id(merchant);
    }
    BillingProvider configuredProvider(){return provider;}
    String configuredMerchant(){return providerMerchant.toString();}
    String configuredPublicUrl(){return publicUrl;}
    boolean simulationEnabled(){return simulator;}
    public List<Map<String,Object>> accounts(String user) {
        return db.rows("SELECT a.id,a.name,a.merchant_id,m.role FROM billing.accounts a JOIN billing.memberships m ON m.account_id=a.id WHERE m.user_id=? ORDER BY a.name",user);
    }
    public void authorize(String user,UUID account) {
        if(!db.exists("SELECT 1 FROM billing.memberships WHERE user_id=? AND account_id=?",user,account)) throw BillingProblem.missing();
    }
    void authorizePayer(String user,UUID account) {
        if(!db.exists("SELECT 1 FROM billing.memberships m JOIN billing.accounts a ON a.id=m.account_id WHERE m.user_id=? AND m.account_id=? AND m.role='PAYER' AND a.merchant_id=?",user,account,providerMerchant)) throw BillingProblem.missing();
    }
    public void staff(String user,UUID merchant,String role) {
        if(!db.exists("SELECT 1 FROM billing.staff WHERE user_id=? AND merchant_id=? AND role=?",user,merchant,role))
            throw new BillingProblem(403,"FORBIDDEN","This operation requires an assigned merchant permission");
    }
    public List<Map<String,Object>> invoices(String user,UUID account) {
        authorize(user,account);
        return db.rows("""
            SELECT v.id,v.number,v.currency,v.total_minor,v.paid_minor,v.reserved_minor,v.due_date,v.version,
             CASE WHEN v.paid_minor=v.total_minor THEN 'PAID' WHEN v.reserved_minor>0 THEN 'PAYMENT_PENDING' ELSE 'OPEN' END status
            FROM billing.invoices v WHERE v.account_id=?
             AND NOT EXISTS (SELECT 1 FROM commerce.order_bills b WHERE b.invoice_id=v.id)
            ORDER BY v.due_date,v.id LIMIT 200
            """,account);
    }
    public Map<String,Object> create(String user,UUID account,String key,List<UUID> requested,String method) {
        BillingExecutionBudget.requireTime();return commands.create(user,account,key,requested,method,provider.name());
    }
    public Map<String,Object> get(String user,UUID intent) {
        var row=raw(intent); authorize(user,id(row.get("accountId"))); return view(row);
    }
    public List<Map<String,Object>> history(String user,UUID account) {
        authorize(user,account);
        var rows=db.rows("SELECT id,account_id,amount_minor,currency,method,status,provider,created_at,updated_at,refunded_minor,payment_reference,retry_of FROM billing.intents WHERE account_id=? ORDER BY created_at DESC,id DESC LIMIT 100",account);
        for(var row:rows) row.put("simulatorEnabled",simulator && "SIMULATOR".equals(row.get("provider")));
        return rows;
    }
    public Map<String,Object> cancel(String user,UUID intent) {
        BillingExecutionBudget.requireTime();return commands.cancel(user,intent);
    }
    public Map<String,Object> submit(String user,UUID intent) {
        requireNoTransaction();
        var current=raw(intent);
        authorizePayer(user,id(current.get("accountId")));
        // Only a new payment needs room at the provider. A payment already on its way is never refused.
        if(!"CREATED".equals(current.get("status"))) return get(user,intent);
        return submitNew(user,intent);
    }
    /** Browser callbacks only request this read-only provider verification; they are never capture evidence. */
    public Map<String,Object> reconcile(String user,UUID intent) {
        requireNoTransaction();BillingExecutionBudget.requireTime();
        var row=raw(intent);authorizePayer(user,id(row.get("accountId")));requireProvider(row);
        if(!"RAZORPAY".equals(provider.name()))throw BillingProblem.conflict("This verification route is for Razorpay test orders");
        var attempts=db.rows("SELECT original_request,request_version,created_at FROM billing.attempts WHERE intent_id=?",intent);
        if(attempts.isEmpty() || attempts.get(0).get("originalRequest")==null)throw BillingProblem.conflict("No submitted operation is available for verification");
        var attempt=attempts.get(0);var saved=ProviderCommand.fromJson(text(attempt,"originalRequest"));
        if(!intent.toString().equals(saved.intentId()) || !operationKey(intent).equals(saved.idempotencyKey())
                || amount(row,"amountMinor")!=saved.amountMinor() || !text(row,"currency").equals(saved.currency()))
            throw BillingProblem.conflict("The saved request does not match the payment");
        ProviderResult result;
        try { result=provider.recoverPayment(saved,text(attempt,"requestVersion"),Instant.parse(text(attempt,"createdAt")),text(row,"providerId")); }
        catch(ProviderUncertainException uncertain) { return get(user,intent); }
        // Only a definitive capture is recorded here. Recording it without a fence takes the lease, which is safe: the capture
        // is idempotent, and a recovery worker that still holds an older claim finds nothing left to do.
        if("CAPTURED".equals(result.state()))recordResult(intent,null,result);
        return get(user,intent);
    }
    /** Refused before anything is saved, so the payer can be told for certain that nothing was charged. */
    private ProviderPermit admitNewPayment(UUID intent) {
        try { return provider.admitNewPayment(); }
        catch(ProviderBusyException busy) {
            log.warn("New payment refused before anything was saved or sent intentId={} reason={}",intent,busy.getMessage());
            OperationalAlert.raiseAtMostEvery(java.time.Duration.ofMinutes(15),"admission","PROVIDER_PAUSED",OperationalAlert.Severity.WARNING,
                "New payments are being refused before anything is saved or sent (provider paused after repeated failures, or all call slots busy); nothing was charged","intentId",intent);
            throw new BillingProblem(503,"PROVIDER_BUSY","Payments are very busy right now. Nothing was charged. Please try again in a minute.");
        }
    }
    private Map<String,Object> submitNew(String user,UUID intent) {
        UUID token=UUID.randomUUID();
        var slot=new java.util.concurrent.atomic.AtomicReference<ProviderPermit>();
        Map<String,Object> work;
        try {
            boolean shopOrder=db.exists("SELECT 1 FROM commerce.orders o JOIN billing.intents i ON i.payment_reference=o.payment_reference WHERE i.id=?",intent);
            String returnUrl=publicUrl+(shopOrder?"/orders":"/invoices?intentId="+intent);
            work=commands.prepareSubmission(intent,token,this::admitNewPayment,slot,provider.name(),provider.paymentRequestVersion(),returnUrl);
        } catch(RuntimeException|Error failure) {
            // The proxy did not return successfully: release admission. Commit may be uncertain; recovery rereads saved state.
            if(slot.get()!=null) slot.get().close();
            throw failure;
        }
        if(work==null) return get(user,intent);
        ProviderResult result;String uncertain=null;
        // The slot is handed back as soon as Stripe answers, before the answer is saved.
        try (var permit=slot.get(); var ignored=BillingExecutionBudget.lease(Instant.parse(text(work,"leaseUntil")))) {
            result=provider.submitSaved(ProviderCommand.fromJson(text(work,"originalRequest")),
                text(work,"requestVersion"),Instant.parse(text(work,"createdAt")));
        } catch(ProviderRejectedException rejected) {
            log.warn("Initial payment request rejected; checking current ownership before recording intentId={} reason={}",intent,rejected.getMessage());
            recordInitialRejection(intent,token,rejected);
            return get(user,intent);
        } catch(RuntimeException failure) {
            // Identifiers and the exception only: provider exceptions carry no credential or response body.
            log.warn("Payment submission outcome uncertain; recovery will reconcile intentId={}",intent,failure);
            uncertain=failure.getClass().getSimpleName();
            result=new ProviderResult(null,"UNKNOWN",null,"Payment outcome is unconfirmed; recovery will check the original operation",BillingProviderEvidence.from(failure));
        }
        try {recordResult(intent,token,result);}
        catch(RuntimeException failure) {
            // The provider answered (or not) but the answer was not saved: the payment stays SUBMITTING with its saved
            // request, and recovery asks the provider again.
            OperationalAlert.raise("PAYMENT_OUTCOME_UNKNOWN",OperationalAlert.Severity.WARNING,
                "Provider answer ("+result.state()+") could not be saved ("+failure.getClass().getSimpleName()+"); payment stays SUBMITTING with its saved request for recovery","intentId",intent);
            throw failure;
        }
        if(uncertain!=null)OperationalAlert.raise("PAYMENT_OUTCOME_UNKNOWN",OperationalAlert.Severity.WARNING,
            "Payment provider did not confirm the request ("+uncertain+"); kept as UNKNOWN with its saved request for recovery","intentId",intent);
        return get(user,intent);
    }
    /** Rejection describes this request, not a provider operation another worker may have created. */
    private void recordInitialRejection(UUID intent,UUID token,ProviderRejectedException rejected) {
        BillingExecutionBudget.requireTime();commands.recordInitialRejection(intent,token,rejected);
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
        BillingExecutionBudget.requireTime();commands.recordResult(intent,fence,result);
    }
    /**
     * Fences out submission and recovery while a release asks the provider to prove there is no payment.
     * Refused while one of them is talking to the provider. Returns what the provider check needs.
     */
    Map<String,Object> leaseForRelease(UUID intent,UUID token) {return locks.leaseForRelease(intent,token);}
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
        BillingExecutionBudget.requireTime();commands.releaseUnresolved(intent,fence,message);
    }
    /**
     * Releases invoices held by intents the customer created but never submitted. Row locks make
     * this race-safe with submit and cancel: whichever locks the intent first decides, and the
     * other sees the new status. No provider operation exists for a CREATED intent.
     */
    public int expireUnsubmitted(int ttlMinutes,int limit) {
        BillingExecutionBudget.requireTime();return commands.expireUnsubmitted(ttlMinutes,limit);
    }

    List<Map<String,Object>> lockInvoices(UUID intent) {return locks.lockInvoices(intent);}
    public boolean hasCapture(UUID intent) { return db.exists("SELECT 1 FROM billing.journals WHERE effect_key=?","capture:"+intent); }
    void requireProvider(Map<String,Object> payment) {
        if(!provider.name().equals(payment.get("provider"))) throw BillingProblem.conflict("The original payment provider must be restored before this operation can continue");
    }
    public Map<String,Object> raw(UUID intent) { return db.one("SELECT * FROM billing.intents WHERE id=?",intent); }
    Map<String,Object> locked(UUID intent) {return locks.locked(intent);}
    Map<String,Object> view(Map<String,Object> row) {
        UUID intent=id(row.get("id"));
        Map<String,Object> result=new LinkedHashMap<>();
        for(String key:List.of("id","accountId","amountMinor","currency","method","status","provider","checkoutUrl","createdAt","updatedAt","refundedMinor","paymentReference","retryOf")) result.put(key,row.get(key));
        result.put("simulatorEnabled",simulator && "SIMULATOR".equals(row.get("provider")));
        if(provider instanceof RazorpayBillingProvider razorpay && "RAZORPAY".equals(row.get("provider"))
                && text(row,"providerId")!=null && "REQUIRES_ACTION".equals(row.get("status")))
            result.put("checkout",Map.of("provider","RAZORPAY","keyId",razorpay.publicKeyId(),"orderId",text(row,"providerId"),
                "amountMinor",amount(row,"amountMinor"),"currency",text(row,"currency")));
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
