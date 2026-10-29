package com.invoicepayments.billing;

import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;

/** The ERP consumer's posting, the dead-letter audit and an operator's replay, each one unit. Kafka stays in BillingEvents. */
@Service
@Profile("billing")
@Transactional(transactionManager="transactionManager",propagation=Propagation.REQUIRED,rollbackFor=Exception.class,timeout=10,label="billing")
public class BillingEventCommands {
    private final BillingStore db; private final UUID merchant;
    public BillingEventCommands(BillingStore db,@Value("${billing.provider-merchant-id:10000000-0000-0000-0000-000000000001}") String merchant) {this.db=db;this.merchant=id(merchant);}
    /** The event is checked against its authoritative outbox record and posted once per effect. */
    public void applyErp(JSONObject event) {
        BillingExecutionBudget.requireTime();requireTransaction();
        UUID eventId=id(event.getString("eventId")),intent=id(event.getString("intentId"));
        var canonical=db.one("SELECT payload::text AS payload FROM billing.outbox WHERE id=? AND aggregate_id=?",eventId,intent);
        if(!BillingEvents.parse(text(canonical,"payload").getBytes(StandardCharsets.UTF_8)).similar(event)) throw new IllegalArgumentException("Event differs from its authoritative financial outbox record");
        var payment=db.one("SELECT * FROM billing.intents WHERE id=?",intent);
        if(!text(payment,"accountId").equals(event.getString("accountId")) || !text(payment,"currency").equals(event.getString("currency"))) throw new IllegalArgumentException("Event correlation mismatch");
        String kind=event.getString("eventType"); String effect=kind+":"+intent;
        if("PAYMENT_REFUNDED".equals(kind)) {
            UUID refund=id(event.getString("refundId"));
            if(!db.exists("SELECT 1 FROM billing.refunds WHERE id=? AND intent_id=? AND status='REFUNDED' AND amount_minor=?",refund,intent,BillingEvents.exact(event,"amountMinor"))) throw new IllegalArgumentException("Refund event evidence mismatch");
            effect=kind+":"+refund;
        } else if(amount(payment,"amountMinor")!=BillingEvents.exact(event,"amountMinor")) throw new IllegalArgumentException("Event amount mismatch");
        if(event.has("intentAmountMinor") && BillingEvents.exact(event,"intentAmountMinor")!=amount(payment,"amountMinor")) throw new IllegalArgumentException("Original intent amount mismatch");
        if(db.update("INSERT INTO billing.consumer_inbox(consumer,event_id) VALUES('ERP_SIMULATOR',?) ON CONFLICT DO NOTHING",eventId)==0) return;
        int posted=db.update("INSERT INTO billing.erp_postings(intent_id,event_id,kind,status,external_reference,acknowledged_at) VALUES(?,?,?,'ACKNOWLEDGED',?,NOW()) ON CONFLICT(external_reference) DO NOTHING",intent,eventId,kind,"ERP-SIM-"+effect);
        if(posted==0) return;
        db.event(intent,"ERP_ACKNOWLEDGED","ERP simulator acknowledged "+event.getString("eventType"));
    }
    public void recordDeadLetter(String topic,int partition,long offset,String key,byte[] value) {
        BillingExecutionBudget.requireTime();requireTransaction();
        db.update("INSERT INTO billing.dead_letters(id,merchant_id,topic,partition_id,offset_id,event_key,raw_payload) VALUES(?,?,?,?,?,?,?) ON CONFLICT(topic,partition_id,offset_id) DO NOTHING",
            UUID.randomUUID(),merchant,topic,partition,offset,key,value==null?new byte[0]:value);
    }
    public Map<String,Object> replay(String user,UUID deadLetter) {
        BillingExecutionBudget.requireTime();requireTransaction();
        var row=db.one("SELECT * FROM billing.dead_letters WHERE id=? FOR UPDATE",deadLetter);
        if(!db.exists("SELECT 1 FROM billing.staff WHERE merchant_id=? AND user_id=? AND role='OPS'",id(row.get("merchantId")),user)) throw new BillingProblem(403,"FORBIDDEN","Assigned operations access is required");
        if(!"OPEN".equals(row.get("state"))) return Map.of("id",deadLetter,"status",row.get("state"));
        JSONObject payload=BillingEvents.parse((byte[])row.get("rawPayload"));
        UUID intent=id(payload.getString("intentId"));
        if(!db.exists("SELECT 1 FROM billing.intents i JOIN billing.accounts a ON a.id=i.account_id WHERE i.id=? AND a.merchant_id=?",intent,id(row.get("merchantId")))) throw BillingProblem.missing();
        UUID outbox=UUID.randomUUID();
        db.update("INSERT INTO billing.outbox(id,aggregate_id,type,payload) VALUES(?,?,'DLT_REPLAY',?::jsonb)",outbox,intent,payload.toString());
        db.update("UPDATE billing.dead_letters SET state='REPLAY_QUEUED',replay_outbox_id=?,replayed_by=? WHERE id=?",outbox,user,deadLetter);
        return Map.of("id",deadLetter,"status","REPLAY_QUEUED");
    }
}
