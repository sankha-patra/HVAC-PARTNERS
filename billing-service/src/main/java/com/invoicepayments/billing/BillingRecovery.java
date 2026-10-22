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
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(BillingRecovery.class);
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
        if(!provider.name().equals(work.get("provider"))) {db.tx(()->{db.escalate(intent,null,"ORIGINAL_PROVIDER_UNAVAILABLE",intent.toString(),"The original payment provider is still unavailable");return null;});return;}
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
            log.warn("Saved payment cannot be replayed safely; escalating intentId={} reason={}",intent,failure.getMessage());
            db.tx(() -> {db.escalate(intent,null,"PAYMENT_REPLAY_UNSAFE",intent.toString(),"Saved payment request can no longer be replayed safely");return null;});
            result=new ProviderResult(null,"UNKNOWN",null,"Saved payment requires investigation before further submission");
        }
        catch(RuntimeException failure) {
            log.warn("Payment recovery outcome still uncertain intentId={}",intent,failure);
            result=new ProviderResult(text(work,"providerId"),"UNKNOWN",null,"Original provider operation is not yet confirmed");
        }
        try { payments.recordResult(intent,token,result); }
        catch(RuntimeException failure) {
            // A temporary database failure is not evidence: the claim's lease expires and recovery tries again.
            if(isTransient(failure)) log.warn("Payment recovery hit a temporary database failure; will retry intentId={}",intent,failure);
            else {
                log.error("Payment recovery could not record provider evidence intentId={}",intent,failure);
                db.tx(() -> {db.escalate(intent,null,"RECOVERY_EVIDENCE_MISMATCH",intent.toString(),"Recovery again could not record provider evidence");return null;});
            }
        }
        db.tx(() -> {
            // Backoff is per operation; stuck oldest records do not monopolize each batch.
            long seconds=backoffSeconds(amount(work,"recoveryCount"));
            db.update("UPDATE billing.intents SET next_recovery_at=NOW()+(? * INTERVAL '1 second') WHERE id=? AND lease_token IS NULL AND status IN ('SUBMITTING','PROCESSING','UNKNOWN','REQUIRES_ACTION')",seconds,intent);
            // A legitimately processing ACH payment may take days. Escalate uncertainty,
            // rather than treating every asynchronous provider response as a failure.
            if(amount(work,"recoveryCount")>=5 && db.exists("SELECT 1 FROM billing.intents WHERE id=? AND status IN ('UNKNOWN','SUBMITTING')",intent))
                db.escalate(intent,null,"PAYMENT_UNRESOLVED",intent.toString(),"Payment outcome still unconfirmed after repeated recovery");
            return null;
        });
    }
    public List<Map<String,Object>> claimRefunds(int limit) {
        return db.tx(() -> {
            var due=db.rows("SELECT * FROM billing.refunds WHERE status IN ('APPROVED','SUBMITTING','PROCESSING','UNKNOWN') AND next_recovery_at<=NOW() AND (lease_until IS NULL OR lease_until<NOW()) ORDER BY next_recovery_at,id FOR UPDATE SKIP LOCKED LIMIT ?",Math.min(limit,20));
            for(var row:due) {UUID token=UUID.randomUUID();row.put("recoveryToken",token);
                db.update("UPDATE billing.refunds SET lease_token=?,lease_until=NOW()+INTERVAL '45 seconds',next_recovery_at=NOW()+INTERVAL '45 seconds',recovery_count=recovery_count+1 WHERE id=?",token,id(row.get("id")));}
            return due;
        });
    }
    /** Same rules as payment recovery: owned claim, saved request, bounded replay, backoff, escalation. */
    public void recoverRefund(Map<String,Object> claimed) {
        requireNoTransaction();
        UUID refund=id(claimed.get("id")); UUID token=id(claimed.get("recoveryToken"));
        // Refresh ownership and evidence before provider I/O: a stale claim must not call the provider.
        var owned=db.rows("""
            SELECT r.*,i.provider AS payment_provider,i.provider_id AS payment_provider_id
            FROM billing.refunds r JOIN billing.intents i ON i.id=r.intent_id
            WHERE r.id=? AND r.lease_token=? AND r.lease_until>NOW() AND r.status IN ('APPROVED','SUBMITTING','PROCESSING','UNKNOWN')
            """,refund,token);
        if(owned.isEmpty()) return;
        var work=owned.get(0); UUID intent=id(work.get("intentId"));
        if(!provider.name().equals(work.get("paymentProvider"))) {db.tx(()->{db.escalate(intent,refund,"ORIGINAL_PROVIDER_UNAVAILABLE",refund.toString(),"The original payment provider is still unavailable");return null;});return;}
        if("APPROVED".equals(work.get("status"))) {refunds.submitApproved(refund);return;}
        String key=BillingRefundService.operationKey(refund); ProviderResult result;
        try {
            if(work.get("originalRequest")==null || work.get("providerId")!=null) {
                result=provider.lookupRefund(key,text(work,"providerId"));
            } else {
                RefundCommand command=RefundCommand.fromJson(text(work,"originalRequest"));
                if(!refund.toString().equals(command.refundId()) || !intent.toString().equals(command.intentId()) || !key.equals(command.idempotencyKey())
                    || amount(work,"amountMinor")!=command.amountMinor() || !"USD".equals(command.currency())
                    || !Objects.equals(text(work,"paymentProviderId"),command.providerPaymentId()))
                    throw new ProviderReplayUnsafeException("Saved refund does not match the approved refund; reconciliation required");
                result=provider.recoverRefund(command,text(work,"requestVersion"),Instant.parse(text(work,"submittedAt")),null);
            }
        }
        catch(ProviderReplayUnsafeException failure) {
            log.warn("Saved refund cannot be replayed safely; escalating refundId={} intentId={} reason={}",refund,intent,failure.getMessage());
            db.tx(() -> {db.escalate(intent,refund,"REFUND_REPLAY_UNSAFE",refund.toString(),"Saved refund request can no longer be replayed safely");return null;});
            result=new ProviderResult(null,"UNKNOWN",null,"Saved refund requires investigation before further submission");
        }
        catch(RuntimeException failure) {
            log.warn("Refund recovery outcome still uncertain refundId={} intentId={}",refund,intent,failure);
            result=new ProviderResult(text(work,"providerId"),"UNKNOWN",null,"Refund remains uncertain");
        }
        try {refunds.record(refund,token,result);}
        catch(RuntimeException failure) {
            if(isTransient(failure)) log.warn("Refund recovery hit a temporary database failure; will retry refundId={} intentId={}",refund,intent,failure);
            else {
                log.error("Refund recovery could not record provider evidence refundId={} intentId={}",refund,intent,failure);
                db.tx(() -> {db.escalate(intent,refund,"REFUND_UNRESOLVED",refund.toString(),"Refund evidence could not be recorded");return null;});
            }
        }
        long attempts=amount(work,"recoveryCount");
        db.tx(() -> {
            db.update("UPDATE billing.refunds SET next_recovery_at=NOW()+(? * INTERVAL '1 second') WHERE id=? AND lease_token IS NULL AND status IN ('SUBMITTING','PROCESSING','UNKNOWN')",backoffSeconds(attempts),refund);
            // Pending refunds can legitimately take days; only an unknown outcome escalates by attempts.
            if(attempts>=5 && db.exists("SELECT 1 FROM billing.refunds WHERE id=? AND status IN ('UNKNOWN','SUBMITTING')",refund))
                db.escalate(intent,refund,"REFUND_UNRESOLVED",refund.toString(),"Refund outcome still unconfirmed after repeated recovery");
            return null;
        });
    }
    /** Releases reservations held by intents that were created but never submitted. */
    public int expireUnsubmitted(int ttlMinutes,int limit) { return payments.expireUnsubmitted(ttlMinutes,limit); }
    static long backoffSeconds(long attempts) {
        long seconds=Math.min(1800,10L << Math.min(Math.max(attempts,1)-1,7));
        return seconds+java.util.concurrent.ThreadLocalRandom.current().nextLong(1,6);
    }
}
