package com.invoicepayments.billing.recovery;

import com.invoicepayments.billing.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;

/** The same guarded business recovery, addressed by one saved operation rather than a batch. */
public final class RecoveryTask {
    private final BillingStore db;private final BillingRecovery recovery;private final TransactionTemplate tx;
    private final UUID campaign,merchant;private final String provider;
    public RecoveryTask(BillingStore db,BillingRecovery recovery,PlatformTransactionManager manager){this(db,recovery,manager,null,null,null);}
    public RecoveryTask(BillingStore db,BillingRecovery recovery,PlatformTransactionManager manager,UUID campaign,UUID merchant,String provider){
        this.db=db;this.recovery=recovery;this.campaign=campaign;this.merchant=merchant;this.provider=provider;
        if(campaign!=null&&(merchant==null||provider==null))throw new IllegalArgumentException("A campaign requires merchant and provider scope");
        tx=new TransactionTemplate(manager);tx.setTimeout(10);
    }
    public Map<String,Object> run(String kind,UUID operation) {
        requireNoTransaction();String table=table(kind);UUID token=UUID.randomUUID();
        String statuses="PAYMENT".equals(kind)?"'SUBMITTING','PROCESSING','UNKNOWN','REQUIRES_ACTION'":"'APPROVED','SUBMITTING','PROCESSING','UNKNOWN'";
        var claim=tx.execute(s->{requireEnrolled(kind,operation);var rows=db.rows("UPDATE "+table+" SET lease_token=?,lease_until=NOW()+INTERVAL '120 seconds',next_recovery_at=NOW()+INTERVAL '120 seconds',recovery_count=recovery_count+1 WHERE id=? AND status IN ("+statuses+") AND next_recovery_at<=NOW() AND (lease_until IS NULL OR lease_until<NOW()) RETURNING *",token,operation);
            if(rows.isEmpty())return null;var row=rows.get(0);row.put("recoveryToken",token.toString());return row;});
        if(claim!=null){if("PAYMENT".equals(kind))recovery.recoverPayment(claim);else recovery.recoverRefund(claim);}
        // Admission is checked before I/O. Already-received financial evidence remains recorded after a deadline.
        return observed(kind,operation,claim!=null);
    }
    public Map<String,Object> state(String kind,UUID operation,boolean ran) {
        requireEnrolled(kind,operation);return observed(kind,operation,ran);
    }
    private Map<String,Object> observed(String kind,UUID operation,boolean ran) {
        var row=db.one("SELECT status,GREATEST(1,CEIL(EXTRACT(EPOCH FROM (GREATEST(next_recovery_at,COALESCE(lease_until,next_recovery_at))-NOW()))))::int AS wait_seconds FROM "+table(kind)+" WHERE id=?",operation);
        String status=text(row,"status");return Map.of("kind",kind,"operationId",operation.toString(),"ran",ran,"status",status,
            "recoverable",Set.of("APPROVED","SUBMITTING","PROCESSING","UNKNOWN","REQUIRES_ACTION").contains(status),"waitSeconds",Math.min(30,((Number)row.get("waitSeconds")).intValue()));
    }
    private void requireEnrolled(String kind,UUID operation){
        table(kind);if(campaign==null)return;
        String operationMatch="PAYMENT".equals(kind)?"i.id=?":"EXISTS(SELECT 1 FROM billing.refunds r WHERE r.id=? AND r.intent_id=i.id)";
        if(!db.exists("SELECT 1 FROM billing.recovery_campaign_operations e JOIN billing.recovery_campaign c ON c.id=e.campaign_id WHERE e.campaign_id=? AND e.operation_kind=? AND e.operation_id=? AND c.deadline>NOW() AND EXISTS(SELECT 1 FROM billing.intents i JOIN billing.accounts a ON a.id=i.account_id WHERE "+operationMatch+" AND a.merchant_id=? AND i.provider=?)",campaign,kind,operation,operation,merchant,provider))
            throw new IllegalArgumentException("Operation is outside the live enrolled recovery campaign");
    }
    private static String table(String kind){return switch(kind){case "PAYMENT"->"billing.intents";case "REFUND"->"billing.refunds";default->throw new IllegalArgumentException("Unsupported recovery operation kind");};}
}
