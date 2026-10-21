package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.util.*;
import java.time.Instant;
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
    public void recoverPayment(Map<String,Object> claimed) {
        requireNoTransaction();
        UUID intent=id(claimed.get("id")); UUID token=id(claimed.get("recoveryToken")); ProviderResult result;
        // Refresh persisted evidence and ownership before any provider call. A queued
        // claim may have expired or a webhook may already have completed the payment.
        var owned=db.rows("""
            SELECT i.*,a.original_request,a.request_version,a.created_at AS prepared_at
            FROM billing.intents i LEFT JOIN billing.attempts a ON a.intent_id=i.id
            WHERE i.id=? AND i.lease_token=? AND i.lease_until>NOW()
              AND i.status IN ('SUBMITTING','PROCESSING','UNKNOWN','REQUIRES_ACTION')
            """,intent,token);
        if(owned.isEmpty()) return;
        var work=owned.get(0);
        if(!provider.name().equals(work.get("provider"))) {db.tx(()->{db.caseFor(intent,"ORIGINAL_PROVIDER_UNAVAILABLE",intent.toString());return null;});return;}
        try {
            if(work.get("originalRequest")==null || work.get("providerId")!=null) {
                result=provider.lookup(BillingService.operationKey(intent),text(work,"providerId"));
            } else {
                ProviderCommand command=ProviderCommand.fromJson(text(work,"originalRequest"));
                if(!intent.toString().equals(command.intentId()) || !BillingService.operationKey(intent).equals(command.idempotencyKey())
                    || amount(work,"amountMinor")!=command.amountMinor() || !work.get("currency").equals(command.currency())
                    || !work.get("method").equals(command.method())) throw new ProviderReplayUnsafeException();
                result=provider.recoverPayment(command,text(work,"requestVersion"),Instant.parse(text(work,"preparedAt")),null);
            }
        }
        catch(ProviderReplayUnsafeException failure) {
            db.tx(() -> {db.caseFor(intent,"PAYMENT_REPLAY_UNSAFE",intent.toString());return null;});
            result=new ProviderResult(null,"UNKNOWN",null,"Saved payment requires investigation before further submission");
        }
        catch(RuntimeException failure) {result=new ProviderResult(text(work,"providerId"),"UNKNOWN",null,"Original provider operation is not yet confirmed");}
        try { payments.recordResult(intent,token,result); }
        catch(RuntimeException failure) {db.tx(() -> {db.caseFor(intent,"RECOVERY_EVIDENCE_MISMATCH",intent.toString());return null;});}
        db.tx(() -> {
            // Backoff is per operation; stuck oldest records do not monopolize each batch.
            long seconds=Math.min(1800,10L << Math.min(amount(work,"recoveryCount")-1,7));
            seconds+=java.util.concurrent.ThreadLocalRandom.current().nextLong(1,6);
            db.update("UPDATE billing.intents SET next_recovery_at=NOW()+(? * INTERVAL '1 second') WHERE id=? AND lease_token IS NULL AND status IN ('SUBMITTING','PROCESSING','UNKNOWN','REQUIRES_ACTION')",seconds,intent);
            // A legitimately processing ACH payment may take days. Escalate uncertainty,
            // rather than treating every asynchronous provider response as a failure.
            if(amount(work,"recoveryCount")>=5 && db.exists("SELECT 1 FROM billing.intents WHERE id=? AND status IN ('UNKNOWN','SUBMITTING')",intent))
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
        requireNoTransaction();
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
