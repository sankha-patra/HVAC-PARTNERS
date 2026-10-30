package com.invoicepayments.billing.recovery;

import com.invoicepayments.billing.BillingStore;
import com.invoicepayments.billing.OperationalAlert;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static com.invoicepayments.billing.BillingStore.*;

/** Short, committed database units. No AWS or provider calls are made here. */
public final class RecoveryDispatchStore {
    private final BillingStore db;
    private final TransactionTemplate tx;
    private final RecoverySettings settings;
    public RecoveryDispatchStore(BillingStore db,PlatformTransactionManager manager,RecoverySettings settings) {
        this.db=db;this.settings=settings;tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    /** Explicit test-cohort admission. Unrelated existing app operations are never discovered by this campaign. */
    public void enroll(String kind,UUID operation) {
        requireNoTransaction();String table=switch(kind){case "PAYMENT"->"billing.intents";case "REFUND"->"billing.refunds";default->throw new IllegalArgumentException("Unsupported recovery kind");};
        tx.executeWithoutResult(s->{
            var c=campaign();if(!Boolean.TRUE.equals(c.get("live")))throw new IllegalStateException("Campaign deadline passed");
            if(!db.exists("SELECT 1 FROM "+table+" WHERE id=?",operation))throw new IllegalArgumentException("Saved operation is required");
            if(db.exists("SELECT 1 FROM billing.recovery_campaign_operations WHERE campaign_id=? AND operation_kind=? AND operation_id=?",settings.campaign(),kind,operation))return;
            if(amount(db.one("SELECT COUNT(*) AS n FROM billing.recovery_campaign_operations WHERE campaign_id=?",settings.campaign()),"n")>=settings.maxStarts())throw new IllegalStateException("Campaign cohort allocation exhausted");
            db.update("INSERT INTO billing.recovery_campaign_operations(campaign_id,operation_kind,operation_id) VALUES(?,?,?)",settings.campaign(),kind,operation);
        });
    }
    public void initialize() {
        tx.executeWithoutResult(s->{
            db.update("""
                INSERT INTO billing.recovery_campaign(id,workflow_version,max_starts,max_submissions,max_describes,max_inflight,deadline)
                VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING
                """,settings.campaign(),settings.versionArn(),settings.maxStarts(),settings.maxSubmissions(),settings.maxDescribes(),settings.maxInFlight(),Timestamp.from(settings.deadline()));
            var c=campaign();
            if(!settings.versionArn().equals(c.get("workflowVersion"))||number(c,"maxStarts")!=settings.maxStarts()
                ||number(c,"maxSubmissions")!=settings.maxSubmissions()||number(c,"maxDescribes")!=settings.maxDescribes()
                ||number(c,"maxInflight")!=settings.maxInFlight()
                ||!java.time.Instant.parse(text(c,"deadline")).equals(settings.deadline()))
                throw new IllegalStateException("Campaign settings are immutable; existing counters cannot be reset");
        });
    }
    /** Repairs the committed-operation to workflow-start gap, including after the app was offline. */
    public int discover(int limit) {
        if(limit<1||limit>20)throw new IllegalArgumentException("Bounded recovery discovery required");
        return Objects.requireNonNull(tx.execute(s->{
            var c=campaign();if(!Boolean.TRUE.equals(c.get("live")))return 0;
            var due=db.rows("""
                SELECT kind,operation_id FROM (
                    SELECT 'PAYMENT' AS kind,id AS operation_id,next_recovery_at FROM billing.intents
                    WHERE status IN ('SUBMITTING','PROCESSING','UNKNOWN','REQUIRES_ACTION')
                    UNION ALL
                    SELECT 'REFUND',id,next_recovery_at FROM billing.refunds
                    WHERE status IN ('APPROVED','SUBMITTING','PROCESSING','UNKNOWN')
                ) o WHERE next_recovery_at<=NOW()
                  AND EXISTS(SELECT 1 FROM billing.recovery_campaign_operations a WHERE a.campaign_id=? AND a.operation_kind=o.kind AND a.operation_id=o.operation_id)
                  AND NOT EXISTS(SELECT 1 FROM billing.recovery_dispatch d WHERE d.operation_kind=o.kind AND d.operation_id=o.operation_id
                    AND (d.campaign_id=? OR d.state IN ('PENDING','STARTING','RUNNING','REVIEW')))
                ORDER BY next_recovery_at,operation_id LIMIT ?
                """,settings.campaign(),settings.campaign(),limit);
            int inserted=0;
            for(var row:due) {
                if(number(c,"starts")+inserted>=settings.maxStarts())break;
                String kind=text(row,"kind");UUID operation=id(row.get("operationId"));
                String name="recovery-"+digest(settings.campaign()+":"+kind+":"+operation);
                String input="{\"kind\":\""+kind+"\",\"operationId\":\""+operation+"\"}";
                UUID dispatch=UUID.randomUUID();
                int n=db.update("""
                    INSERT INTO billing.recovery_dispatch(id,campaign_id,operation_kind,operation_id,workflow_version,execution_name,execution_arn,input_text)
                    VALUES(?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                    """,dispatch,settings.campaign(),kind,operation,settings.versionArn(),name,settings.executionArn(name),input);
                if(n==1){inserted++;audit(dispatch,"ENQUEUED",null);}
            }
            db.update("UPDATE billing.recovery_campaign SET starts=starts+? WHERE id=?",inserted,settings.campaign());
            return inserted;
        }));
    }
    public Map<String,Object> claim() {
        return tx.execute(s->{
            var c=campaign();
            if(!Boolean.TRUE.equals(c.get("live"))) {
                var ended=db.rows("UPDATE billing.recovery_dispatch SET state='REVIEW',owner='DEADLINE_REVIEW',last_error='CAMPAIGN_DEADLINE' WHERE campaign_id=? AND state IN ('PENDING','STARTING','RUNNING') RETURNING *",settings.campaign());
                for(var endedJob:ended){financeReview(endedJob);reviewAlert(endedJob,"CAMPAIGN_DEADLINE");}
                return null;
            }
            var rows=db.rows("""
                SELECT * FROM billing.recovery_dispatch WHERE campaign_id=? AND state IN ('PENDING','STARTING','RUNNING')
                AND next_at<=NOW() AND (claim_until IS NULL OR claim_until<NOW())
                ORDER BY CASE WHEN state='RUNNING' THEN 0 WHEN submissions>0 THEN 1 ELSE 2 END,next_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                """,settings.campaign());
            if(rows.isEmpty())return null;
            var job=rows.get(0);UUID jobId=id(job.get("id"));
            if(number(job,"submissions")==0) {
                long active=((Number)db.one("SELECT COUNT(*) AS n FROM billing.recovery_dispatch WHERE campaign_id=? AND id<>? AND (state IN ('STARTING','RUNNING','REVIEW') OR submissions>0 AND state<>'DONE')",settings.campaign(),jobId).get("n")).longValue();
                if(active>=settings.maxInFlight())return null;
            }
            UUID token=UUID.randomUUID();
            db.update("UPDATE billing.recovery_dispatch SET state='STARTING',token=?,claim_until=NOW()+INTERVAL '45 seconds',updated_at=NOW() WHERE id=?",token,jobId);
            job.put("token",token);return job;
        });
    }
    public boolean reserve(Map<String,Object> job,boolean submit) {
        return Boolean.TRUE.equals(tx.execute(s->{
            var c=campaign();var row=owned(job);if(row==null)return false;
            String column=submit?"submissions":"describes";int maximum=submit?settings.maxSubmissions():settings.maxDescribes();
            if(!Boolean.TRUE.equals(c.get("live"))||number(c,column)>=maximum) {review(job,"ALLOCATION_LIMIT");return false;}
            db.update("UPDATE billing.recovery_campaign SET "+column+"="+column+"+1 WHERE id=?",settings.campaign());
            if(submit)db.update("UPDATE billing.recovery_dispatch SET submissions=submissions+1 WHERE id=?",id(job.get("id")));
            audit(id(job.get("id")),submit?"SUBMIT_RESERVED":"DESCRIBE_RESERVED",null);return true;
        }));
    }
    public void record(Map<String,Object> job,RecoveryRemote.Execution e) {
        tx.executeWithoutResult(s->{
            if(owned(job)==null)return;
            if(!job.get("executionArn").equals(e.arn())||!job.get("workflowVersion").equals(e.versionArn())||!job.get("inputText").equals(e.input())) {
                review(job,"REMOTE_IDENTITY_MISMATCH");return;
            }
            Set<String> terminal=Set.of("SUCCEEDED","FAILED","TIMED_OUT","ABORTED");
            if(!"RUNNING".equals(e.status())&&!terminal.contains(e.status())){review(job,"UNKNOWN_REMOTE_STATUS");return;}
            boolean done=financiallyFinished(job),closed=terminal.contains(e.status());
            // A closed workflow is not financial success. Unresolved work gets an explicit human owner.
            String state=closed?(done?"DONE":"REVIEW"):"RUNNING";
            String owner=closed?(done?"FINANCIAL_DONE":"TERMINAL_REVIEW"):"WORKFLOW";
            db.update("""
                UPDATE billing.recovery_dispatch SET state=?,owner=?,remote_status=?,token=NULL,claim_until=NULL,
                next_at=NOW()+INTERVAL '15 seconds',last_error=NULL,updated_at=NOW() WHERE id=?
                """,state,owner,e.status(),id(job.get("id")));
            audit(id(job.get("id")),closed?"TERMINAL_OBSERVED":"RUNNING_OBSERVED",e.status());
            if("REVIEW".equals(state)){financeReview(job);reviewAlert(job,"WORKFLOW_"+e.status());}
        });
    }
    public void uncertain(Map<String,Object> job,String code) {
        tx.executeWithoutResult(s->{if(owned(job)==null)return;
            String safe=code!=null&&code.matches("[A-Za-z0-9_.-]{1,100}")?code:"REMOTE_UNCERTAIN";
            db.update("UPDATE billing.recovery_dispatch SET state='PENDING',owner='DISPATCH',token=NULL,claim_until=NULL,next_at=NOW()+INTERVAL '15 seconds',last_error=?,updated_at=NOW() WHERE id=?",safe,id(job.get("id")));
            audit(id(job.get("id")),"REMOTE_UNCERTAIN",safe);
        });
    }
    private Map<String,Object> campaign(){return db.one("SELECT *,deadline>NOW() AS live FROM billing.recovery_campaign WHERE id=? FOR UPDATE",settings.campaign());}
    private Map<String,Object> owned(Map<String,Object> job) {
        var r=db.rows("SELECT * FROM billing.recovery_dispatch WHERE id=? AND token=? AND state='STARTING' AND claim_until>NOW() FOR UPDATE",id(job.get("id")),id(job.get("token")));
        return r.isEmpty()?null:r.get(0);
    }
    private boolean financiallyFinished(Map<String,Object> job) {
        String table="PAYMENT".equals(job.get("operationKind"))?"billing.intents":"billing.refunds";
        var rows=db.rows("SELECT status FROM "+table+" WHERE id=?",id(job.get("operationId")));
        if(rows.isEmpty())return false;
        return !Set.of("SUBMITTING","PROCESSING","UNKNOWN","REQUIRES_ACTION","APPROVED").contains(text(rows.get(0),"status"));
    }
    private void review(Map<String,Object> job,String reason) {
        db.update("UPDATE billing.recovery_dispatch SET state='REVIEW',owner='MANUAL_REVIEW',last_error=?,token=NULL,claim_until=NULL,updated_at=NOW() WHERE id=?",reason,id(job.get("id")));
        audit(id(job.get("id")),"REVIEW_REQUIRED",reason);
        financeReview(job);
        reviewAlert(job,reason);
    }
    private static void reviewAlert(Map<String,Object> job,String reason) {
        OperationalAlert.raise("RECOVERY_NEEDS_REVIEW",OperationalAlert.Severity.WARNING,
            "Step Functions recovery ended without a money result; a person must decide",
            "operationKind",job.get("operationKind"),"operationId",job.get("operationId"),"cause",reason);
    }
    private void financeReview(Map<String,Object> job){
        UUID operation=id(job.get("operationId"));
        if("PAYMENT".equals(job.get("operationKind")))db.caseFor(operation,"PAYMENT_UNRESOLVED",operation.toString());
        else{
            UUID intent=id(db.one("SELECT intent_id FROM billing.refunds WHERE id=?",operation).get("intentId"));
            db.escalate(intent,operation,"REFUND_UNRESOLVED",operation.toString(),"Managed recovery ended unresolved; merchant finance review is required");
        }
    }
    private void audit(UUID job,String kind,String detail){db.update("INSERT INTO billing.recovery_dispatch_audit(dispatch_id,kind,detail) VALUES(?,?,?)",job,kind,detail);}
    private static int number(Map<String,Object> row,String key){return ((Number)row.get(key)).intValue();}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
