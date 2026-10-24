package com.invoicepayments.billing;

import org.json.JSONObject;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.IllegalTransactionStateException;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Supplier;

@Repository
@Profile("billing")
public class BillingStore {
    public final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    // Every transaction is bounded. Spring cancels statements past the deadline; PostgreSQL stops waiting
    // for a row lock, a slow statement, or a session that went idle inside a transaction while holding locks.
    static final int TRANSACTION_TIMEOUT_SECONDS=10;
    static final String LOCK_TIMEOUT="3s", STATEMENT_TIMEOUT="10s", IDLE_IN_TRANSACTION_TIMEOUT="20s";
    public BillingStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.transaction=new TransactionTemplate(manager);
        transaction.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
    }
    public <T> T tx(Supplier<T> action) {
        BillingExecutionBudget.requireTime();
        boolean outermost=!TransactionSynchronizationManager.isActualTransactionActive();
        java.time.Duration remaining=BillingExecutionBudget.remaining();
        TransactionTemplate bounded=transaction;
        if(outermost && remaining!=null) {
            bounded=new TransactionTemplate(java.util.Objects.requireNonNull(transaction.getTransactionManager()));
            bounded.setTimeout((int)Math.max(1,Math.min(TRANSACTION_TIMEOUT_SECONDS,remaining.toSeconds())));
        }
        return bounded.execute(s -> {
            // is_local=true works like SET LOCAL: the limits end with this transaction and never stay on a pooled connection.
            if(outermost) {
                BillingExecutionBudget.requireTime();
                java.time.Duration left=BillingExecutionBudget.remaining();
                long millis=left==null ? 10000 : Math.max(1,Math.min(10000,left.toMillis()));
                jdbc.queryForList("SELECT set_config('lock_timeout',?,true),set_config('statement_timeout',?,true),set_config('idle_in_transaction_session_timeout',?,true)",
                    Math.min(3000,millis)+"ms",millis+"ms",IDLE_IN_TRANSACTION_TIMEOUT);
            }
            return action.get();
        });
    }
    /** Money, event and audit writes must commit with the change they describe, like MANDATORY propagation. */
    static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException("Financial, event and audit writes must run inside a billing transaction");
        }
    }
    public static void requireNoTransaction() {
        // REQUIRED blocks below must commit before provider IO. Suspending an outer
        // transaction would still leave its connection borrowed while the provider waits.
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalTransactionStateException("Payment orchestration must run outside an existing transaction");
        }
    }
    /**
     * True when retrying later can succeed: a lock, statement or transaction timeout, a deadlock or
     * serialization failure, or a lost or refused connection. Such a failure is not evidence of anything.
     */
    public static boolean isTransient(Throwable failure) {
        int depth=0;
        for(Throwable cause=failure; cause!=null && depth++<20; cause=cause.getCause()) {
            if(cause instanceof org.springframework.dao.TransientDataAccessException
                || cause instanceof org.springframework.dao.RecoverableDataAccessException
                || cause instanceof org.springframework.jdbc.CannotGetJdbcConnectionException
                || cause instanceof org.springframework.transaction.TransactionTimedOutException
                || cause instanceof org.springframework.transaction.CannotCreateTransactionException) return true;
            if(cause instanceof java.sql.SQLException sql && sql.getSQLState()!=null) {
                String state=sql.getSQLState();
                if(state.startsWith("08") || TRANSIENT_STATES.contains(state)) return true;
            }
        }
        return false;
    }
    // Serialization failure, deadlock, lock timeout, statement cancelled or timed out, server shutdown, too many connections.
    private static final Set<String> TRANSIENT_STATES=Set.of("40001","40P01","55P03","57014","57P01","53300");
    public int update(String sql,Object... args) { return jdbc.update(sql,args); }
    public List<Map<String,Object>> rows(String sql,Object... args) {
        return jdbc.query(sql,(rs,n) -> {
            Map<String,Object> row=new LinkedHashMap<>();
            for(int i=1;i<=rs.getMetaData().getColumnCount();i++) {
                String key=camel(rs.getMetaData().getColumnLabel(i)); Object value=rs.getObject(i);
                if(value instanceof UUID) value=value.toString();
                if(value instanceof Timestamp time) value=time.toInstant().toString();
                if(value instanceof java.sql.Date date) value=date.toLocalDate().toString();
                row.put(key,value);
            }
            return row;
        },args);
    }
    public Map<String,Object> one(String sql,Object...args) {
        var rows=rows(sql,args); if(rows.size()!=1) throw BillingProblem.missing(); return rows.get(0);
    }
    public boolean exists(String sql,Object...args) { return !rows(sql,args).isEmpty(); }
    public static UUID id(Object value) {
        try { return UUID.fromString(String.valueOf(value)); }
        catch(IllegalArgumentException ex) { throw BillingProblem.invalid("A valid identifier is required"); }
    }
    public static long amount(Map<String,Object> row,String field) { return ((Number)row.get(field)).longValue(); }
    public static String text(Map<String,Object> row,String field) { return Objects.toString(row.get(field),null); }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public void event(UUID intent,String type,String message) {
        update("INSERT INTO billing.timeline(intent_id,type,message) VALUES(?,?,?)",intent,type,message);
    }
    public void publish(Map<String,Object> intent,String type) {
        publish(intent,type,null,amount(intent,"amountMinor"));
    }
    public void publish(Map<String,Object> intent,String type,UUID refund,long effectAmount) {
        requireTransaction();
        UUID event=UUID.randomUUID(); UUID intentId=id(intent.get("id"));
        JSONObject payload=new JSONObject().put("schemaVersion",1).put("eventId",event.toString())
            .put("eventType",type).put("intentId",intentId.toString()).put("accountId",intent.get("accountId"))
            .put("amountMinor",effectAmount).put("intentAmountMinor",intent.get("amountMinor")).put("currency",intent.get("currency"));
        if(refund!=null) payload.put("refundId",refund.toString());
        update("INSERT INTO billing.outbox(id,aggregate_id,type,payload) VALUES(?,?,?,?::jsonb)",event,intentId,type,payload.toString());
    }
    public void caseFor(UUID intent,String reason,String reference) { caseFor(intent,null,reason,reference); }
    /**
     * One-time case: recorded once per reason and reference and never reopened. Use it only for an event
     * that cannot recur (one webhook, one late capture, one ACH return). A problem that can happen again
     * uses escalate(), so a case that staff closed reopens with an audit entry instead of hiding it.
     */
    public void caseFor(UUID intent,UUID refund,String reason,String reference) {
        update("""
            INSERT INTO billing.cases(id,merchant_id,intent_id,refund_id,reason,reference)
            SELECT ?,a.merchant_id,i.id,?,?,? FROM billing.intents i JOIN billing.accounts a ON a.id=i.account_id
            WHERE i.id=? ON CONFLICT(reason,reference) DO NOTHING
            """,UUID.randomUUID(),refund,reason,reference,intent);
    }
    /** Recurring-problem case: opened once, and reopened with an audit entry if the problem recurs after resolution. */
    public void escalate(UUID intent,UUID refund,String reason,String reference,String note) {
        requireTransaction();
        var changed=rows("""
            INSERT INTO billing.cases(id,merchant_id,intent_id,refund_id,reason,reference)
            SELECT ?,a.merchant_id,i.id,?,?,? FROM billing.intents i JOIN billing.accounts a ON a.id=i.account_id WHERE i.id=?
            ON CONFLICT(reason,reference) DO UPDATE SET status='OPEN',resolved_at=NULL,resolved_by=NULL,resolution=NULL,
              release_requested_by=NULL,release_note=NULL,release_requested_at=NULL
            WHERE billing.cases.status='RESOLVED'
            RETURNING id,(xmax<>0) AS reopened
            """,UUID.randomUUID(),refund,reason,reference,intent);
        for(var row:changed) if(Boolean.TRUE.equals(row.get("reopened"))) caseAction(id(row.get("id")),"REOPENED","system",note,null);
    }
    /** Resolves the open cases of one operation for the given reasons; every resolution is audited. */
    public void resolveCases(String reference,String reasonA,String reasonB,String actor,String resolution,String action,String note) {
        requireTransaction();
        for(var row:rows("""
            UPDATE billing.cases SET status='RESOLVED',resolved_at=NOW(),resolved_by=?,resolution=?,
              release_requested_by=NULL,release_note=NULL,release_requested_at=NULL
            WHERE status='OPEN' AND reference=? AND reason IN (?,?) RETURNING id
            """,actor,resolution,reference,reasonA,reasonB)) caseAction(id(row.get("id")),action,actor,note,null);
    }
    public void caseAction(UUID caseId,String action,String actor,String note,String evidenceJson) {
        requireTransaction();
        update("INSERT INTO billing.case_actions(id,case_id,action,actor,note,evidence) VALUES(?,?,?,?,?,?::jsonb)",
            UUID.randomUUID(),caseId,action,actor,note,evidenceJson);
    }
    public void journal(Map<String,Object> intent,String effect,String debit,String credit,long minor) {
        requireTransaction();
        UUID journal=UUID.randomUUID();
        int added=update("""
            INSERT INTO billing.journals(id,merchant_id,intent_id,effect_key,currency)
            SELECT ?,merchant_id,?,?,? FROM billing.accounts WHERE id=? ON CONFLICT(effect_key) DO NOTHING
            """,journal,id(intent.get("id")),effect,intent.get("currency"),id(intent.get("accountId")));
        if(added==0) return;
        update("INSERT INTO billing.ledger_entries(journal_id,line,account,side,amount_minor) VALUES(?,1,?,'DEBIT',?),(?,2,?,'CREDIT',?)",
            journal,debit,minor,journal,credit,minor);
    }
    private static String camel(String name) {
        StringBuilder result=new StringBuilder(); boolean upper=false;
        for(char c:name.toCharArray()) { if(c=='_') upper=true; else {result.append(upper?Character.toUpperCase(c):c); upper=false;} }
        return result.toString();
    }
}
