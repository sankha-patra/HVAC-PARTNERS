package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;

/**
 * Refund flows. Database units are BillingRefundCommands, reached through their Spring proxy; this facade owns the
 * provider call and opens no transaction of its own. The package-private delegates keep the names other services use.
 */
@Service
@Profile("billing")
public class BillingRefundService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(BillingRefundService.class);
    static final Set<String> RECORDABLE=BillingRefundCommands.RECORDABLE;
    private final BillingProvider provider; private final BillingRefundAccess access; private final BillingRefundCommands commands;
    public BillingRefundService(BillingProvider provider,BillingRefundAccess access,BillingRefundCommands commands) {
        this.provider=provider;this.access=access;this.commands=commands;
    }
    public List<Map<String,Object>> list(String user) { return access.list(user); }
    public Map<String,Object> request(String user,UUID intent,String key,long minor,String reason) {
        BillingExecutionBudget.requireTime();return commands.request(user,intent,key,minor,reason);
    }
    public Map<String,Object> approve(String user,UUID refund) {
        requireNoTransaction();BillingExecutionBudget.requireTime();
        commands.approve(user,refund);
        submitApproved(refund); return raw(refund);
    }
    public Map<String,Object> reject(String user,UUID refund,String note) {
        BillingExecutionBudget.requireTime();return commands.reject(user,refund,note);
    }
    public void submitApproved(UUID refund) {
        requireNoTransaction();BillingExecutionBudget.requireTime();
        UUID token=UUID.randomUUID(); UUID intent=id(raw(refund).get("intentId"));
        var work=commands.prepareSubmission(refund,token,provider.name(),provider.refundRequestVersion());
        if(work==null) return;
        ProviderResult result;String uncertain=null;
        try(var budget=BillingExecutionBudget.lease(Instant.parse(text(work,"leaseUntil")))) {
            result=provider.refund(RefundCommand.fromJson(text(work,"originalRequest")));
        }
        catch(ProviderRejectedException rejected) {
            log.warn("Provider rejected this refund request; checking current ownership before recording it refundId={} intentId={}",refund,intent,rejected);
            commands.recordInitialRejection(refund,token,rejected);
            return;
        }
        catch(RuntimeException failure) {
            log.warn("Refund submission outcome uncertain; recovery will reuse the saved request refundId={} intentId={}",refund,intent,failure);
            uncertain=failure.getClass().getSimpleName();
            result=new ProviderResult(null,"UNKNOWN",null,"Refund outcome is uncertain; recovery will reuse the saved request",BillingProviderEvidence.from(failure));
        }
        try {commands.record(refund,token,result);}
        catch(RuntimeException failure) {
            OperationalAlert.raise("REFUND_OUTCOME_UNKNOWN",OperationalAlert.Severity.WARNING,
                "Provider answer ("+result.state()+") to the refund could not be saved ("+failure.getClass().getSimpleName()+"); refund stays SUBMITTING with its saved request for recovery","refundId",refund,"intentId",intent);
            throw failure;
        }
        if(uncertain!=null)OperationalAlert.raise("REFUND_OUTCOME_UNKNOWN",OperationalAlert.Severity.WARNING,
            "Payment provider did not confirm the refund ("+uncertain+"); kept as UNKNOWN with its saved request for recovery","refundId",refund,"intentId",intent);
    }
    static String operationKey(UUID refund) { return BillingRefundAccess.operationKey(refund); }
    Map<String,Object> leaseForRelease(UUID refund,UUID token) { return commands.leaseForRelease(refund,token); }
    RefundCommand savedCommand(Map<String,Object> claim) { return access.savedCommand(claim); }
    void releaseUnconfirmed(UUID refund,UUID fence,String message) { commands.releaseUnconfirmed(refund,fence,message); }
    public void record(UUID refund,UUID fence,ProviderResult result) { commands.record(refund,fence,result); }
    public void record(UUID refund,UUID fence,ProviderResult result,String evidenceKey) { commands.record(refund,fence,result,evidenceKey); }
    String reconcileVerifiedFailure(UUID refund,ProviderResult verified) { return commands.reconcileVerifiedFailure(refund,verified); }
    boolean hasRefundConflict(UUID intent) { return access.hasRefundConflict(intent); }
    void requireNoRefundConflict(UUID intent) { access.requireNoRefundConflict(intent); }
    Map<String,Object> raw(UUID refund) { return access.raw(refund); }
    UUID merchant(Map<String,Object> intent) { return access.merchant(intent); }
    void requireAnyStaff(String user) { access.requireAnyStaff(user); }
}
