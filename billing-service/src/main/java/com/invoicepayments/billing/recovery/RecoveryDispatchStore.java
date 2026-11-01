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
    private static final String RECOVERABLE_PAYMENT="'SUBMITTING','PROCESSING','UNKNOWN','REQUIRES_ACTION'",RECOVERABLE_REFUND="'APPROVED','SUBMITTING','PROCESSING','UNKNOWN'";
    /** Wait before the next workflow of the same operation: 5 min, 15 min, 1 h, 3 h, then 6 h. */
    private static final int[] RETRY_MINUTES={5,15,60,180,360};
    /** Failures a later workflow can get past: timeouts, the loop budget, database/tunnel outages, Lambda service errors. */
    private static final Set<String> RETRYABLE_ERRORS=Set.of("States.Timeout","LabLoopBudgetExhausted","Sandbox.Timedout","Runtime.ExitError",
        "Lambda.ServiceException","Lambda.AWSLambdaException","Lambda.SdkClientException","Lambda.TooManyRequestsException",
        "com.invoicepayments.billing.recovery.RecoveryConnectionUnavailableException","com.invoicepayments.billing.recovery.RecoveryTunnelUnavailableException");
    private final BillingStore db;
    private final TransactionTemplate tx;
    private final RecoverySettings settings;
    public RecoveryDispatchStore(BillingStore db,PlatformTransactionManager manager,RecoverySettings settings) {
        this.db=db;this.settings=settings;tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    /** Explicit admission, for campaigns without automatic enrollment (test cohorts). */
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
    /**
     * Repairs the committed-operation to workflow-start gap, including after the app was offline. With automatic
     * enrollment, any due operation of the scoped merchant and provider joins the campaign here.
     */
    public int discover(int limit) {
        if(limit<1||limit>20)throw new IllegalArgumentException("Bounded recovery discovery required");
        return Objects.requireNonNull(tx.execute(s->{
            var c=campaign();if(!Boolean.TRUE.equals(c.get("live")))return 0;
            String admitted=settings.autoEnroll()
                ? """
                  AND EXISTS(SELECT 1 FROM billing.intents i JOIN billing.accounts a ON a.id=i.account_id
                    WHERE i.id=COALESCE((SELECT r.intent_id FROM billing.refunds r WHERE o.kind='REFUND' AND r.id=o.operation_id),o.operation_id)
                    AND a.merchant_id=? AND i.provider=?)
                  """
                : "AND EXISTS(SELECT 1 FROM billing.recovery_campaign_operations a WHERE a.campaign_id=? AND a.operation_kind=o.kind AND a.operation_id=o.operation_id)\n";
            Object[] scope=settings.autoEnroll()?new Object[]{settings.merchant(),settings.provider()}:new Object[]{settings.campaign()};
            var arguments=new ArrayList<Object>(Arrays.asList(scope));arguments.add(settings.campaign());arguments.add(limit);
            var due=db.rows("""
                SELECT kind,operation_id FROM (
                    SELECT 'PAYMENT' AS kind,id AS operation_id,next_recovery_at FROM billing.intents
                    WHERE status IN (%s)
                    UNION ALL
                    SELECT 'REFUND',id,next_recovery_at FROM billing.refunds
                    WHERE status IN (%s)
                ) o WHERE next_recovery_at<=NOW()
                  %s
                  AND NOT EXISTS(SELECT 1 FROM billing.recovery_dispatch d WHERE d.operation_kind=o.kind AND d.operation_id=o.operation_id
                    AND (d.campaign_id=? OR d.state IN ('PENDING','STARTING','RUNNING','REVIEW')))
                ORDER BY next_recovery_at,operation_id LIMIT ?
                """.formatted(RECOVERABLE_PAYMENT,RECOVERABLE_REFUND,admitted),arguments.toArray());
            int inserted=0;
            for(var row:due) {
                if(number(c,"starts")+inserted>=settings.maxStarts())break;
                String kind=text(row,"kind");UUID operation=id(row.get("operationId"));
                if(settings.autoEnroll())db.update("INSERT INTO billing.recovery_campaign_operations(campaign_id,operation_kind,operation_id) VALUES(?,?,?) ON CONFLICT DO NOTHING",settings.campaign(),kind,operation);
                UUID dispatch=insertAttempt(kind,operation,1,0);
                if(dispatch!=null){inserted++;audit(dispatch,"ENQUEUED",null);}
            }
            db.update("UPDATE billing.recovery_campaign SET starts=starts+? WHERE id=?",inserted,settings.campaign());
            return inserted;
        }));
    }
    public Map<String,Object> claim() {
        return tx.execute(s->{
            var c=campaign();
            // A review whose money was later settled (buyer refresh, finance, a later webhook) no longer needs anyone.
            for(var settled:db.rows("""
                UPDATE billing.recovery_dispatch d SET state='DONE',owner='SETTLED_AFTER_REVIEW',updated_at=NOW()
                WHERE d.state='REVIEW' AND (
                  (d.operation_kind='PAYMENT' AND EXISTS(SELECT 1 FROM billing.intents i WHERE i.id=d.operation_id AND i.status NOT IN (%s)))
                  OR (d.operation_kind='REFUND' AND EXISTS(SELECT 1 FROM billing.refunds r WHERE r.id=d.operation_id AND r.status NOT IN (%s))))
                RETURNING d.id
                """.formatted(RECOVERABLE_PAYMENT,RECOVERABLE_REFUND)))audit(id(settled.get("id")),"SETTLED_AFTER_REVIEW",null);
            if(!Boolean.TRUE.equals(c.get("live"))) {
                var ended=db.rows("UPDATE billing.recovery_dispatch SET state='REVIEW',owner='DEADLINE_REVIEW',last_error='CAMPAIGN_DEADLINE' WHERE campaign_id=? AND state IN ('PENDING','STARTING','RUNNING') RETURNING *",settings.campaign());
                for(var endedJob:ended)ownerFor(endedJob,"CAMPAIGN_DEADLINE");
                return null;
            }
            var rows=db.rows("""
                SELECT * FROM billing.recovery_dispatch WHERE campaign_id=? AND state IN ('PENDING','STARTING','RUNNING')
                AND next_at<=NOW() AND (claim_until IS NULL OR claim_until<NOW())
                ORDER BY CASE WHEN state='RUNNING' THEN 0 WHEN submissions>0 THEN 1 ELSE 2 END,
                  -- A payment whose outcome is unknown goes before a buyer who is still on the checkout page.
                  COALESCE((SELECT CASE WHEN i.status='REQUIRES_ACTION' THEN 1 ELSE 0 END FROM billing.intents i WHERE operation_kind='PAYMENT' AND i.id=operation_id),0),
                  next_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                """,settings.campaign());
            if(rows.isEmpty())return null;
            var job=rows.get(0);UUID jobId=id(job.get("id"));
            if(number(job,"submissions")==0) {
                // Reviews belong to finance, not to the workflow budget: they never block new starts.
                long active=((Number)db.one("SELECT COUNT(*) AS n FROM billing.recovery_dispatch WHERE campaign_id=? AND id<>? AND (state IN ('STARTING','RUNNING') OR submissions>0 AND state='PENDING')",settings.campaign(),jobId).get("n")).longValue();
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
            String error=safe(e.error());
            // A closed workflow is not financial success. Unresolved work gets a later workflow (an infrastructure failure
            // such as the database, tunnel or a sleeping laptop) or, after the last attempt or an abort, a human owner.
            boolean retryable=closed&&!done&&("TIMED_OUT".equals(e.status())||"SUCCEEDED".equals(e.status())
                ||"FAILED".equals(e.status())&&(error==null||RETRYABLE_ERRORS.contains(error)));
            boolean retry=retryable&&number(job,"attempt")<settings.maxAttempts();
            // Every attempt saw the buyer still on the checkout page and nothing paid: an abandoned checkout, not a money
            // problem. Shop expiry releases its stock; a payment made later opens ORDER_PAID_AFTER_EXPIRY for finance.
            boolean abandoned=retryable&&!retry&&settings.autoEnroll()&&checkoutStillOpen(job);
            String state=closed?(done||abandoned?"DONE":retry?"SUPERSEDED":"REVIEW"):"RUNNING";
            String owner=closed?(done?"FINANCIAL_DONE":abandoned?"CHECKOUT_NOT_COMPLETED":retry?"NEXT_ATTEMPT":"TERMINAL_REVIEW"):"WORKFLOW";
            db.update("""
                UPDATE billing.recovery_dispatch SET state=?,owner=?,remote_status=?,remote_error=?,token=NULL,claim_until=NULL,
                next_at=NOW()+INTERVAL '15 seconds',last_error=NULL,updated_at=NOW() WHERE id=?
                """,state,owner,e.status(),error,id(job.get("id")));
            audit(id(job.get("id")),closed?"TERMINAL_OBSERVED":"RUNNING_OBSERVED",error==null?e.status():e.status()+":"+error);
            String cause="WORKFLOW_"+e.status()+(error==null?"":":"+error);
            if(retry)retryLater(job,cause);
            else if(abandoned)audit(id(job.get("id")),"CHECKOUT_NOT_COMPLETED",cause);
            else if("REVIEW".equals(state)){financeReview(job);reviewAlert(job,cause);}
        });
    }
    /** A remote call failed. A failed describe sent nothing that changes AWS state, so it does not use the budget. */
    public void uncertain(Map<String,Object> job,String code,boolean describeFailed) {
        tx.executeWithoutResult(s->{if(owned(job)==null)return;
            String safe=code!=null&&code.matches("[A-Za-z0-9_.-]{1,100}")?code:"REMOTE_UNCERTAIN";
            db.update("UPDATE billing.recovery_dispatch SET state='PENDING',owner='DISPATCH',token=NULL,claim_until=NULL,next_at=NOW()+INTERVAL '15 seconds',last_error=?,updated_at=NOW() WHERE id=?",safe,id(job.get("id")));
            if(describeFailed)db.update("UPDATE billing.recovery_campaign SET describes=GREATEST(describes-1,0) WHERE id=?",settings.campaign());
            audit(id(job.get("id")),"REMOTE_UNCERTAIN",safe);
        });
    }
    public void uncertain(Map<String,Object> job,String code){uncertain(job,code,false);}
    /** Campaign headroom, for the expiry alert. */
    public Map<String,Object> health() {
        return tx.execute(s->db.one("SELECT starts,max_starts,deadline,EXTRACT(EPOCH FROM deadline-NOW())::bigint AS seconds_left FROM billing.recovery_campaign WHERE id=?",settings.campaign()));
    }
    private void retryLater(Map<String,Object> job,String cause) {
        var c=campaign();int attempt=number(job,"attempt")+1;
        if(number(c,"starts")>=settings.maxStarts()){ownerFor(job,"ALLOCATION_LIMIT");return;}
        UUID next=insertAttempt(text(job,"operationKind"),id(job.get("operationId")),attempt,RETRY_MINUTES[Math.min(attempt-2,RETRY_MINUTES.length-1)]);
        if(next==null){ownerFor(job,"ATTEMPT_CONFLICT");return;}
        db.update("UPDATE billing.recovery_campaign SET starts=starts+1 WHERE id=?",settings.campaign());
        audit(next,"RETRY_SCHEDULED",cause);
    }
    private UUID insertAttempt(String kind,UUID operation,int attempt,int delayMinutes) {
        String identity=settings.campaign()+":"+kind+":"+operation+(attempt==1?"":":"+attempt);
        String name="recovery-"+digest(identity);
        String input="{\"kind\":\""+kind+"\",\"operationId\":\""+operation+"\"}";
        UUID dispatch=UUID.randomUUID();
        int n=db.update("""
            INSERT INTO billing.recovery_dispatch(id,campaign_id,operation_kind,operation_id,workflow_version,execution_name,execution_arn,input_text,attempt,next_at)
            VALUES(?,?,?,?,?,?,?,?,?,NOW()+(? * INTERVAL '1 minute')) ON CONFLICT DO NOTHING
            """,dispatch,settings.campaign(),kind,operation,settings.versionArn(),name,settings.executionArn(name),input,attempt,delayMinutes);
        return n==1?dispatch:null;
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
    private boolean checkoutStillOpen(Map<String,Object> job) {
        return "PAYMENT".equals(job.get("operationKind"))&&db.exists("SELECT 1 FROM billing.intents WHERE id=? AND status='REQUIRES_ACTION'",id(job.get("operationId")));
    }
    private void review(Map<String,Object> job,String reason) {
        db.update("UPDATE billing.recovery_dispatch SET state='REVIEW',owner='MANUAL_REVIEW',last_error=?,token=NULL,claim_until=NULL,updated_at=NOW() WHERE id=?",reason,id(job.get("id")));
        audit(id(job.get("id")),"REVIEW_REQUIRED",reason);
        ownerFor(job,reason);
    }
    /** A row already moved to REVIEW: money that is settled needs nobody; otherwise finance owns it. */
    private void ownerFor(Map<String,Object> job,String reason) {
        if(financiallyFinished(job)) {
            db.update("UPDATE billing.recovery_dispatch SET state='DONE',owner='FINANCIAL_DONE',updated_at=NOW() WHERE id=?",id(job.get("id")));
            audit(id(job.get("id")),"SETTLED_AFTER_REVIEW",reason);return;
        }
        db.update("UPDATE billing.recovery_dispatch SET state='REVIEW',owner=CASE WHEN state='REVIEW' THEN owner ELSE 'MANUAL_REVIEW' END,last_error=COALESCE(last_error,?),token=NULL,claim_until=NULL,updated_at=NOW() WHERE id=?",reason,id(job.get("id")));
        financeReview(job);
        reviewAlert(job,reason);
    }
    private static void reviewAlert(Map<String,Object> job,String reason) {
        OperationalAlert.raise("RECOVERY_NEEDS_REVIEW",OperationalAlert.Severity.WARNING,
            "Step Functions recovery ended without a money result; a person must decide",
            "operationKind",job.get("operationKind"),"operationId",job.get("operationId"),"attempt",job.get("attempt"),"cause",reason);
    }
    /** escalate() reopens a case staff closed (with its alert); a case the Lambda already opened was alerted from the Lambda log. */
    private void financeReview(Map<String,Object> job){
        UUID operation=id(job.get("operationId"));
        if("PAYMENT".equals(job.get("operationKind")))db.escalate(operation,null,"PAYMENT_UNRESOLVED",operation.toString(),"Managed recovery ended unresolved; finance review is required");
        else{
            UUID intent=id(db.one("SELECT intent_id FROM billing.refunds WHERE id=?",operation).get("intentId"));
            db.escalate(intent,operation,"REFUND_UNRESOLVED",operation.toString(),"Managed recovery ended unresolved; merchant finance review is required");
        }
    }
    private void audit(UUID job,String kind,String detail){db.update("INSERT INTO billing.recovery_dispatch_audit(dispatch_id,kind,detail) VALUES(?,?,?)",job,kind,detail);}
    private static String safe(String error){return error!=null&&error.matches("[A-Za-z0-9_.:-]{1,120}")?error:error==null?null:"UNREADABLE_ERROR";}
    private static int number(Map<String,Object> row,String key){return ((Number)row.get(key)).intValue();}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
