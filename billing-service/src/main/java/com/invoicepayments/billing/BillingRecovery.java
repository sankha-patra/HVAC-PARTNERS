package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.util.*;
import java.time.Instant;
import static com.invoicepayments.billing.BillingStore.*;

/**
 * Recovery flows: claim, ask the provider with no transaction or connection held, record, schedule the next check.
 * Each database step is a proxied command (BillingRecoveryCommands, BillingPaymentCommands, BillingRefundCommands).
 */
@Service
@Profile("billing")
public class BillingRecovery {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(BillingRecovery.class);
    private final BillingStore db; private final BillingPaymentCommands payments; private final BillingRefundService refunds;
    private final BillingRefundCommands refundCommands; private final BillingProvider provider; private final BillingRecoveryCommands commands;
    public BillingRecovery(BillingStore db,BillingPaymentCommands payments,BillingRefundService refunds,BillingRefundCommands refundCommands,
            BillingProvider provider,BillingRecoveryCommands commands) {
        this.db=db;this.payments=payments;this.refunds=refunds;this.refundCommands=refundCommands;this.provider=provider;this.commands=commands;
    }
    public List<Map<String,Object>> claimPayments(int limit) {
        BillingExecutionBudget.requireTime();return commands.claimPayments(limit);
    }
    public void releaseUnstarted(Map<String,Object> claimed,boolean refund){
        requireNoTransaction();commands.releaseUnstarted(id(claimed.get("id")),id(claimed.get("recoveryToken")),refund);
    }
    public void recoverPayment(Map<String,Object> claimed) {
        requireNoTransaction();BillingExecutionBudget.requireTime();
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
        if(!provider.name().equals(work.get("provider"))) {commands.escalate(intent,null,"ORIGINAL_PROVIDER_UNAVAILABLE",intent.toString(),"The original payment provider is still unavailable");return;}
        try (var ignored=BillingExecutionBudget.lease(Instant.parse(text(work,"leaseUntil")))) {
            if("RAZORPAY".equals(provider.name()) && work.get("originalRequest")!=null) {
                ProviderCommand command=ProviderCommand.fromJson(text(work,"originalRequest"));
                if(!intent.toString().equals(command.intentId()) || !BillingService.operationKey(intent).equals(command.idempotencyKey())
                    || amount(work,"amountMinor")!=command.amountMinor() || !text(work,"currency").equals(command.currency())
                    || !text(work,"method").equals(command.method()))throw new ProviderReplayUnsafeException();
                result=provider.recoverPayment(command,text(work,"requestVersion"),Instant.parse(text(work,"preparedAt")),text(work,"providerId"));
            } else if(work.get("originalRequest")==null || work.get("providerId")!=null) {
                result=pageDueToClose(intent,work) ? closeUnusedPage(work)
                    : provider.lookup(BillingService.operationKey(intent),text(work,"providerId"));
            } else {
                ProviderCommand command=ProviderCommand.fromJson(text(work,"originalRequest"));
                if(!intent.toString().equals(command.intentId()) || !BillingService.operationKey(intent).equals(command.idempotencyKey())
                    || amount(work,"amountMinor")!=command.amountMinor() || !work.get("currency").equals(command.currency())
                    || !work.get("method").equals(command.method())) throw new ProviderReplayUnsafeException();
                result=BillingProviderEvidence.replayForbidden(db,intent,null)
                    ? provider.discoverPayment(command,text(work,"requestVersion"),Instant.parse(text(work,"preparedAt")))
                    : provider.recoverPayment(command,text(work,"requestVersion"),Instant.parse(text(work,"preparedAt")),null);
            }
        }
        catch(ProviderReplayUnsafeException failure) {
            log.warn("Saved payment cannot be replayed safely; escalating intentId={} reason={}",intent,failure.getMessage());
            commands.escalate(intent,null,"PAYMENT_REPLAY_UNSAFE",intent.toString(),"Saved payment request can no longer be replayed safely");
            result=new ProviderResult(null,"UNKNOWN",null,"Saved payment requires investigation before further submission");
        }
        catch(RuntimeException failure) {
            log.warn("Payment recovery outcome still uncertain intentId={}",intent,failure);
            result=new ProviderResult(text(work,"providerId"),"UNKNOWN",null,"Original payment outcome is not yet confirmed",BillingProviderEvidence.from(failure));
        }
        try { payments.recordResult(intent,token,result); }
        catch(RuntimeException failure) {
            // A temporary database failure is not evidence: the claim's lease expires and recovery tries again.
            // The failed unit has already rolled back; its escalation is a separate unit.
            if(isTransient(failure)) {
                log.warn("Payment recovery hit a temporary database failure; will retry intentId={}",intent,failure);
                if(OperationalAlert.databaseUnavailable(failure))OperationalAlert.raiseAtMostEvery(java.time.Duration.ofMinutes(1),"recovery","DATABASE_UNAVAILABLE",OperationalAlert.Severity.CRITICAL,
                    "Payment recovery could not reach the database; the claim expires and recovery retries","source","recovery","intentId",intent);
            }
            else {
                log.error("Payment recovery could not record provider evidence intentId={}",intent,failure);
                commands.escalate(intent,null,"RECOVERY_EVIDENCE_MISMATCH",intent.toString(),"Recovery again could not record provider evidence");
            }
        }
        ProviderDiagnostics diagnostics=result.diagnostics();
        // A legitimately processing ACH payment may take days. Escalate uncertainty,
        // rather than treating every asynchronous provider response as a failure.
        commands.schedulePaymentCheck(intent,retryDelay(amount(work,"recoveryCount"),diagnostics),PAGE_MINUTES,
            !technicalFailure(diagnostics) && amount(work,"recoveryCount")>=5);
    }
    /** A hosted payment page is closed this long after it opened, so an unused page holds invoices for minutes, not a day. */
    static final int PAGE_MINUTES=30;
    private boolean pageDueToClose(UUID intent,Map<String,Object> work) {
        return "REQUIRES_ACTION".equals(work.get("status")) && work.get("providerId")!=null && work.get("originalRequest")!=null
            && db.exists("SELECT 1 FROM billing.timeline WHERE intent_id=? AND type='REQUIRES_ACTION' HAVING MIN(created_at)<=NOW()-(? * INTERVAL '1 minute')",intent,PAGE_MINUTES);
    }
    /**
     * Stripe's expire step, the same one a release uses. Expired and unpaid proves nothing was charged; a payment made at
     * the last moment is reported instead. Until the close is confirmed the page may still be open, so it keeps waiting.
     */
    private ProviderResult closeUnusedPage(Map<String,Object> work) {
        String page=text(work,"providerId");
        ProviderResult closed;
        try {
            closed=provider.closePayment(ProviderCommand.fromJson(text(work,"originalRequest")),text(work,"requestVersion"),
                Instant.parse(text(work,"preparedAt")),page);
        } catch(ProviderUncertainException notConfirmed) {
            closed=new ProviderResult(page,"UNKNOWN",null,notConfirmed.getMessage(),notConfirmed.diagnostics());
        }
        if("ABSENT".equals(closed.state()))
            return new ProviderResult(page,"FAILED",null,"The payment page closed after "+PAGE_MINUTES+" minutes without a payment; nothing was charged");
        if("UNKNOWN".equals(closed.state()))
            return new ProviderResult(page,"REQUIRES_ACTION",null,"Closing the unused payment page is not confirmed yet; trying again",closed.diagnostics());
        return closed;
    }
    public List<Map<String,Object>> claimRefunds(int limit) {
        BillingExecutionBudget.requireTime();return commands.claimRefunds(limit);
    }
    /** Same rules as payment recovery: owned claim, saved request, bounded replay, backoff, escalation. */
    public void recoverRefund(Map<String,Object> claimed) {
        requireNoTransaction();BillingExecutionBudget.requireTime();
        UUID refund=id(claimed.get("id")); UUID token=id(claimed.get("recoveryToken"));
        // Refresh ownership and evidence before provider I/O: a stale claim must not call the provider.
        var owned=db.rows("""
            SELECT r.*,i.provider AS payment_provider,i.provider_id AS payment_provider_id,i.currency AS payment_currency
            FROM billing.refunds r JOIN billing.intents i ON i.id=r.intent_id
            WHERE r.id=? AND r.lease_token=? AND r.lease_until>NOW() AND r.status IN ('APPROVED','SUBMITTING','PROCESSING','UNKNOWN')
            """,refund,token);
        if(owned.isEmpty()) return;
        var work=owned.get(0); UUID intent=id(work.get("intentId"));
        if(!provider.name().equals(work.get("paymentProvider"))) {commands.escalate(intent,refund,"ORIGINAL_PROVIDER_UNAVAILABLE",refund.toString(),"The original payment provider is still unavailable");return;}
        if("APPROVED".equals(work.get("status"))) {refunds.submitApproved(refund);return;}
        String key=BillingRefundService.operationKey(refund); ProviderResult result;
        try (var ignored=BillingExecutionBudget.lease(Instant.parse(text(work,"leaseUntil")))) {
            if("RAZORPAY".equals(provider.name()) && work.get("originalRequest")!=null) {
                RefundCommand command=RefundCommand.fromJson(text(work,"originalRequest"));
                if(!refund.toString().equals(command.refundId()) || !intent.toString().equals(command.intentId())
                    || !key.equals(command.idempotencyKey()) || amount(work,"amountMinor")!=command.amountMinor()
                    || !text(work,"paymentCurrency").equals(command.currency())
                    || !Objects.equals(text(work,"paymentProviderId"),command.providerPaymentId()))throw new ProviderReplayUnsafeException();
                result=provider.recoverRefund(command,text(work,"requestVersion"),Instant.parse(text(work,"submittedAt")),text(work,"providerId"));
            } else if(work.get("originalRequest")==null || work.get("providerId")!=null) {
                result=provider.lookupRefund(key,text(work,"providerId"));
            } else {
                RefundCommand command=RefundCommand.fromJson(text(work,"originalRequest"));
                if(!refund.toString().equals(command.refundId()) || !intent.toString().equals(command.intentId()) || !key.equals(command.idempotencyKey())
                    || amount(work,"amountMinor")!=command.amountMinor() || !text(work,"paymentCurrency").equals(command.currency())
                    || !Objects.equals(text(work,"paymentProviderId"),command.providerPaymentId()))
                    throw new ProviderReplayUnsafeException("Saved refund does not match the approved refund; reconciliation required");
                if(BillingProviderEvidence.replayForbidden(db,intent,refund)) {
                    if(!Objects.equals(provider.refundRequestVersion(),text(work,"requestVersion")))
                        throw new ProviderReplayUnsafeException("Provider configuration changed; original refund needs verification");
                    result=provider.findRefund(command,null);
                    if("ABSENT".equals(result.state())) result=new ProviderResult(null,"UNKNOWN",null,"No refund was found; the original request is not eligible for automatic replay",result.diagnostics());
                } else result=provider.recoverRefund(command,text(work,"requestVersion"),Instant.parse(text(work,"submittedAt")),null);
            }
        }
        catch(ProviderReplayUnsafeException failure) {
            log.warn("Saved refund cannot be replayed safely; escalating refundId={} intentId={} reason={}",refund,intent,failure.getMessage());
            commands.escalate(intent,refund,"REFUND_REPLAY_UNSAFE",refund.toString(),"Saved refund request can no longer be replayed safely");
            result=new ProviderResult(null,"UNKNOWN",null,"Saved refund requires investigation before further submission");
        }
        catch(RuntimeException failure) {
            log.warn("Refund recovery outcome still uncertain refundId={} intentId={}",refund,intent,failure);
            result=new ProviderResult(text(work,"providerId"),"UNKNOWN",null,"Refund remains uncertain",BillingProviderEvidence.from(failure));
        }
        try {refundCommands.record(refund,token,result);}
        catch(RuntimeException failure) {
            if(isTransient(failure)) {
                log.warn("Refund recovery hit a temporary database failure; will retry refundId={} intentId={}",refund,intent,failure);
                if(OperationalAlert.databaseUnavailable(failure))OperationalAlert.raiseAtMostEvery(java.time.Duration.ofMinutes(1),"recovery","DATABASE_UNAVAILABLE",OperationalAlert.Severity.CRITICAL,
                    "Refund recovery could not reach the database; the claim expires and recovery retries","source","recovery","refundId",refund,"intentId",intent);
            }
            else {
                log.error("Refund recovery could not record provider evidence refundId={} intentId={}",refund,intent,failure);
                commands.escalate(intent,refund,"REFUND_UNRESOLVED",refund.toString(),"Refund evidence could not be recorded");
            }
        }
        long attempts=amount(work,"recoveryCount");
        ProviderDiagnostics diagnostics=result.diagnostics();
        commands.scheduleRefundCheck(refund,intent,retryDelay(attempts,diagnostics),!technicalFailure(diagnostics) && attempts>=5);
    }
    /** Releases reservations held by intents that were created but never submitted. */
    public int expireUnsubmitted(int ttlMinutes,int limit) { BillingExecutionBudget.requireTime();return payments.expireUnsubmitted(ttlMinutes,limit); }
    static long backoffSeconds(long attempts) {
        long seconds=Math.min(1800,10L << Math.min(Math.max(attempts,1)-1,7));
        return seconds+java.util.concurrent.ThreadLocalRandom.current().nextLong(1,6);
    }
    private static long retryDelay(long attempts,ProviderDiagnostics diagnostics) {
        long delay=backoffSeconds(attempts);
        if(diagnostics!=null && diagnostics.retryAfterSeconds()!=null)
            delay=Math.max(delay,Math.min(86400,Math.max(0,diagnostics.retryAfterSeconds())));
        // A persistent credential/request problem needs an engineer, not rapid unchanged requests.
        if(technicalFailure(diagnostics)) delay=Math.max(delay,300);
        return delay;
    }
    private static boolean technicalFailure(ProviderDiagnostics diagnostics) {
        if(diagnostics==null) return false;
        return Objects.equals(diagnostics.httpStatus(),401) || Objects.equals(diagnostics.httpStatus(),403)
            || "invalid_request_error".equals(diagnostics.errorType());
    }
}
