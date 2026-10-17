package com.invoicepayments.billing;

import org.json.JSONObject;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Supplier;

@Repository
@Profile("billing")
public class BillingStore {
    public final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public BillingStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.transaction=new TransactionTemplate(manager);
    }
    public <T> T tx(Supplier<T> action) { return transaction.execute(s -> action.get()); }
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
        UUID event=UUID.randomUUID(); UUID intentId=id(intent.get("id"));
        JSONObject payload=new JSONObject().put("schemaVersion",1).put("eventId",event.toString())
            .put("eventType",type).put("intentId",intentId.toString()).put("accountId",intent.get("accountId"))
            .put("amountMinor",effectAmount).put("intentAmountMinor",intent.get("amountMinor")).put("currency",intent.get("currency"));
        if(refund!=null) payload.put("refundId",refund.toString());
        update("INSERT INTO billing.outbox(id,aggregate_id,type,payload) VALUES(?,?,?,?::jsonb)",event,intentId,type,payload.toString());
    }
    public void caseFor(UUID intent,String reason,String reference) {
        update("""
            INSERT INTO billing.cases(id,merchant_id,intent_id,reason,reference)
            SELECT ?,a.merchant_id,i.id,?,? FROM billing.intents i JOIN billing.accounts a ON a.id=i.account_id
            WHERE i.id=? ON CONFLICT(reason,reference) DO NOTHING
            """,UUID.randomUUID(),reason,reference,intent);
    }
    public void journal(Map<String,Object> intent,String effect,String debit,String credit,long minor) {
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
