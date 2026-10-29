package com.invoicepayments.billing;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;

/** Recovery's database units: fenced claims, escalations and the next check time. Provider I/O stays in BillingRecovery. */
@Service
@Profile("billing")
@Transactional(transactionManager="transactionManager",propagation=Propagation.REQUIRED,rollbackFor=Exception.class,timeout=10,label="billing")
public class BillingRecoveryCommands {
    private final BillingStore db;
    public BillingRecoveryCommands(BillingStore db) {this.db=db;}
    public List<Map<String,Object>> claimPayments(int limit) {
        BillingExecutionBudget.requireTime();requireTransaction();
        var due=db.rows("""
            SELECT * FROM billing.intents WHERE status IN ('SUBMITTING','PROCESSING','UNKNOWN','REQUIRES_ACTION')
             AND next_recovery_at<=NOW() AND (lease_until IS NULL OR lease_until<NOW())
            ORDER BY next_recovery_at,id FOR UPDATE SKIP LOCKED LIMIT ?
            """,Math.min(limit,20));
        for(var row:due) {
            UUID token=UUID.randomUUID(); row.put("recoveryToken",token);
            db.update("UPDATE billing.intents SET lease_token=?,lease_until=NOW()+INTERVAL '120 seconds',next_recovery_at=NOW()+INTERVAL '120 seconds',recovery_count=recovery_count+1 WHERE id=?",token,id(row.get("id")));
        }
        return due;
    }
    public List<Map<String,Object>> claimRefunds(int limit) {
        BillingExecutionBudget.requireTime();requireTransaction();
        var due=db.rows("SELECT * FROM billing.refunds WHERE status IN ('APPROVED','SUBMITTING','PROCESSING','UNKNOWN') AND next_recovery_at<=NOW() AND (lease_until IS NULL OR lease_until<NOW()) ORDER BY next_recovery_at,id FOR UPDATE SKIP LOCKED LIMIT ?",Math.min(limit,20));
        for(var row:due) {UUID token=UUID.randomUUID();row.put("recoveryToken",token);
            db.update("UPDATE billing.refunds SET lease_token=?,lease_until=NOW()+INTERVAL '120 seconds',next_recovery_at=NOW()+INTERVAL '120 seconds',recovery_count=recovery_count+1 WHERE id=?",token,id(row.get("id")));}
        return due;
    }
    public void escalate(UUID intent,UUID refund,String reason,String reference,String note) {
        BillingExecutionBudget.requireTime();requireTransaction();
        db.escalate(intent,refund,reason,reference,note);
    }
    /** Only a task that never started may return this claim early; an old token cannot release a newer owner. */
    public void releaseUnstarted(UUID operation,UUID token,boolean refund){
        requireTransaction();BillingExecutionBudget.requireTime();
        String table=refund?"billing.refunds":"billing.intents";
        db.update("UPDATE "+table+" SET lease_token=NULL,lease_until=NULL,next_recovery_at=NOW() WHERE id=? AND lease_token=?",operation,token);
    }
    /**
     * Backoff is per operation, so stuck oldest records do not monopolize each batch. A waiting payment page is
     * checked again when it is due to close, however far the backoff has grown. A legitimately processing ACH payment
     * may take days, so only an outcome that is still unknown escalates by attempts.
     */
    public void schedulePaymentCheck(UUID intent,long delaySeconds,int pageMinutes,boolean mayEscalate) {
        BillingExecutionBudget.requireTime();requireTransaction();
        db.update("""
            UPDATE billing.intents i SET next_recovery_at=CASE
                WHEN i.status='REQUIRES_ACTION' AND p.closes_at>NOW() THEN LEAST(NOW()+(? * INTERVAL '1 second'),p.closes_at)
                ELSE NOW()+(? * INTERVAL '1 second') END
            FROM (SELECT MIN(created_at)+(? * INTERVAL '1 minute')+INTERVAL '1 second' AS closes_at
                  FROM billing.timeline WHERE intent_id=? AND type='REQUIRES_ACTION') p
            WHERE i.id=? AND i.lease_token IS NULL AND i.status IN ('SUBMITTING','PROCESSING','UNKNOWN','REQUIRES_ACTION')
            """,delaySeconds,delaySeconds,pageMinutes,intent,intent);
        if(mayEscalate && db.exists("SELECT 1 FROM billing.intents WHERE id=? AND status IN ('UNKNOWN','SUBMITTING')",intent))
            db.escalate(intent,null,"PAYMENT_UNRESOLVED",intent.toString(),"Payment outcome still unconfirmed after repeated recovery");
    }
    /** Pending refunds can legitimately take days; only an unknown outcome escalates by attempts. */
    public void scheduleRefundCheck(UUID refund,UUID intent,long delaySeconds,boolean mayEscalate) {
        BillingExecutionBudget.requireTime();requireTransaction();
        db.update("UPDATE billing.refunds SET next_recovery_at=NOW()+(? * INTERVAL '1 second') WHERE id=? AND lease_token IS NULL AND status IN ('SUBMITTING','PROCESSING','UNKNOWN')",delaySeconds,refund);
        if(mayEscalate && db.exists("SELECT 1 FROM billing.refunds WHERE id=? AND status IN ('UNKNOWN','SUBMITTING')",refund))
            db.escalate(intent,refund,"REFUND_UNRESOLVED",refund.toString(),"Refund outcome still unconfirmed after repeated recovery");
    }
}
