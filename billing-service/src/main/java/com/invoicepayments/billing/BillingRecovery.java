package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;

@Service
@Profile("billing")
public class BillingRecovery {
    private final BillingStore db; private final BillingService payments; private final BillingRefundService refunds; private final BillingProvider provider;
    public BillingRecovery(BillingStore db,BillingService payments,BillingRefundService refunds,BillingProvider provider) {this.db=db;this.payments=payments;this.refunds=refunds;this.provider=provider;}
    public List<Map<String,Object>> claimPayments(int limit) {
        return db.tx(() -> {
            var due=db.rows("""
                SELECT * FROM billing.intents WHERE status IN ('SUBMITTING','PROCESSING','UNKNOWN','REQUIRES_ACTION')
                 AND next_recovery_at<=NOW() AND (lease_until IS NULL OR lease_until<NOW())
                ORDER BY next_recovery_at,id FOR UPDATE SKIP LOCKED LIMIT ?
                """,Math.min(limit,20));
            for(var row:due) {
                UUID token=UUID.randomUUID(); row.put("recoveryToken",token);
                db.update("UPDATE billing.intents SET lease_token=?,lease_until=NOW()+INTERVAL '45 seconds',next_recovery_at=NOW()+INTERVAL '45 seconds',recovery_count=recovery_count+1 WHERE id=?",token,id(row.get("id")));
            }
            return due;
        });
    }
    public void recoverPayment(Map<String,Object> work) {
        UUID intent=id(work.get("id")); UUID token=id(work.get("recoveryToken")); ProviderResult result;
        if(!provider.name().equals(work.get("provider"))) {db.tx(()->{db.caseFor(intent,"ORIGINAL_PROVIDER_UNAVAILABLE",intent.toString());return null;});return;}
        try { result=provider.lookup(BillingService.operationKey(intent),text(work,"providerId")); }
        catch(RuntimeException failure) {result=new ProviderResult(text(work,"providerId"),"UNKNOWN",null,"Original provider operation is not yet confirmed");}
        try { payments.recordResult(intent,token,result); }
        catch(RuntimeException failure) {db.tx(() -> {db.caseFor(intent,"RECOVERY_EVIDENCE_MISMATCH",intent.toString());return null;});}
        db.tx(() -> {
            // Backoff is per operation; stuck oldest records do not monopolize each batch.
            long seconds=Math.min(1800,10L << Math.min(amount(work,"recoveryCount"),7));
            seconds+=java.util.concurrent.ThreadLocalRandom.current().nextLong(1,6);
            db.update("UPDATE billing.intents SET next_recovery_at=NOW()+(? * INTERVAL '1 second') WHERE id=? AND lease_token IS NULL AND status IN ('SUBMITTING','PROCESSING','UNKNOWN','REQUIRES_ACTION')",seconds,intent);
            // A legitimately processing ACH payment may take days. Escalate uncertainty,
            // rather than treating every asynchronous provider response as a failure.
            if(amount(work,"recoveryCount")>=4 && db.exists("SELECT 1 FROM billing.intents WHERE id=? AND status IN ('UNKNOWN','SUBMITTING')",intent))
                db.caseFor(intent,"PAYMENT_UNRESOLVED",intent.toString());
            return null;
        });
    }
    public List<Map<String,Object>> claimRefunds(int limit) {
        return db.tx(() -> {
            var due=db.rows("SELECT * FROM billing.refunds WHERE status IN ('APPROVED','SUBMITTING','PROCESSING','UNKNOWN') AND next_recovery_at<=NOW() AND (lease_until IS NULL OR lease_until<NOW()) ORDER BY next_recovery_at,id FOR UPDATE SKIP LOCKED LIMIT ?",Math.min(limit,20));
            for(var row:due) {UUID token=UUID.randomUUID();row.put("recoveryToken",token);
                db.update("UPDATE billing.refunds SET lease_token=?,lease_until=NOW()+INTERVAL '45 seconds',next_recovery_at=NOW()+INTERVAL '45 seconds' WHERE id=?",token,id(row.get("id")));}
            return due;
        });
    }
    public void recoverRefund(Map<String,Object> work) {
        UUID refund=id(work.get("id")); ProviderResult result;
        var payment=payments.raw(id(work.get("intentId")));
        if(!provider.name().equals(payment.get("provider"))) {db.tx(()->{db.caseFor(id(work.get("intentId")),"ORIGINAL_PROVIDER_UNAVAILABLE",refund.toString());return null;});return;}
        if("APPROVED".equals(work.get("status"))) {refunds.submitApproved(refund);return;}
        try {result=provider.lookupRefund("refund_"+refund,text(work,"providerId"));}
        catch(RuntimeException failure) {result=new ProviderResult(text(work,"providerId"),"UNKNOWN",null,"Refund remains uncertain");}
        try {refunds.record(refund,id(work.get("recoveryToken")),result);}
        catch(RuntimeException failure) {db.tx(() -> {db.caseFor(id(work.get("intentId")),"REFUND_UNRESOLVED",refund.toString());return null;});}
    }
}
