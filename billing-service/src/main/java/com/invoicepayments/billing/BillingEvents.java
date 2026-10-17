package com.invoicepayments.billing;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;

@Service
@Profile("billing")
@ConditionalOnProperty(name="billing.kafka-enabled",havingValue="true",matchIfMissing=true)
public class BillingEvents {
    private final BillingStore db; private final UUID merchant;
    public BillingEvents(BillingStore db,@Value("${billing.provider-merchant-id:10000000-0000-0000-0000-000000000001}") String merchant) {this.db=db;this.merchant=id(merchant);}
    @KafkaListener(topics="billing.events.v1",groupId="billing-erp-simulator-v1",containerFactory="billingKafkaListenerFactory",autoStartup="${billing.erp-simulator-enabled:false}")
    public void erp(ConsumerRecord<String,byte[]> record,Acknowledgment ack) { applyErp(record.value()); ack.acknowledge(); }
    public void applyErp(byte[] bytes) {
        JSONObject event=parse(bytes); UUID eventId=id(event.getString("eventId")),intent=id(event.getString("intentId"));
        if(event.getInt("schemaVersion")!=1 || !Set.of("PAYMENT_CAPTURED","PAYMENT_RETURNED","PAYMENT_REFUNDED","PAYMENT_FAILED").contains(event.getString("eventType"))) throw new IllegalArgumentException("Unsupported billing event schema");
        db.tx(() -> {
            var canonical=db.one("SELECT payload::text AS payload FROM billing.outbox WHERE id=? AND aggregate_id=?",eventId,intent);
            if(!parse(text(canonical,"payload").getBytes(StandardCharsets.UTF_8)).similar(event)) throw new IllegalArgumentException("Event differs from its authoritative financial outbox record");
            var payment=db.one("SELECT * FROM billing.intents WHERE id=?",intent);
            if(!text(payment,"accountId").equals(event.getString("accountId")) || !"USD".equals(event.getString("currency"))) throw new IllegalArgumentException("Event correlation mismatch");
            String kind=event.getString("eventType"); String effect=kind+":"+intent;
            if("PAYMENT_REFUNDED".equals(kind)) {
                UUID refund=id(event.getString("refundId"));
                if(!db.exists("SELECT 1 FROM billing.refunds WHERE id=? AND intent_id=? AND status='REFUNDED' AND amount_minor=?",refund,intent,exact(event,"amountMinor"))) throw new IllegalArgumentException("Refund event evidence mismatch");
                effect=kind+":"+refund;
            } else if(amount(payment,"amountMinor")!=exact(event,"amountMinor")) throw new IllegalArgumentException("Event amount mismatch");
            if(event.has("intentAmountMinor") && exact(event,"intentAmountMinor")!=amount(payment,"amountMinor")) throw new IllegalArgumentException("Original intent amount mismatch");
            if(db.update("INSERT INTO billing.consumer_inbox(consumer,event_id) VALUES('ERP_SIMULATOR',?) ON CONFLICT DO NOTHING",eventId)==0) return null;
            int posted=db.update("INSERT INTO billing.erp_postings(intent_id,event_id,kind,status,external_reference,acknowledged_at) VALUES(?,?,?,'ACKNOWLEDGED',?,NOW()) ON CONFLICT(external_reference) DO NOTHING",intent,eventId,kind,"ERP-SIM-"+effect);
            if(posted==0) return null;
            db.event(intent,"ERP_ACKNOWLEDGED","ERP simulator acknowledged "+event.getString("eventType"));return null;
        });
    }
    @KafkaListener(topics="billing.events.v1.DLT",groupId="billing-dlt-audit-v1",containerFactory="billingDltListenerFactory")
    public void deadLetter(ConsumerRecord<String,byte[]> record,Acknowledgment ack) {
        db.tx(() -> {
            db.update("INSERT INTO billing.dead_letters(id,merchant_id,topic,partition_id,offset_id,event_key,raw_payload) VALUES(?,?,?,?,?,?,?) ON CONFLICT(topic,partition_id,offset_id) DO NOTHING",
                UUID.randomUUID(),merchant,record.topic(),record.partition(),record.offset(),record.key(),record.value()==null?new byte[0]:record.value());return null;
        });ack.acknowledge();
    }
    public Map<String,Object> replay(String user,UUID deadLetter) {
        return db.tx(() -> {
            var row=db.one("SELECT * FROM billing.dead_letters WHERE id=? FOR UPDATE",deadLetter);
            if(!db.exists("SELECT 1 FROM billing.staff WHERE merchant_id=? AND user_id=? AND role='OPS'",id(row.get("merchantId")),user)) throw new BillingProblem(403,"FORBIDDEN","Assigned operations access is required");
            if(!"OPEN".equals(row.get("state"))) return Map.of("id",deadLetter,"status",row.get("state"));
            JSONObject payload=parse((byte[])row.get("rawPayload"));
            UUID intent=id(payload.getString("intentId"));
            if(!db.exists("SELECT 1 FROM billing.intents i JOIN billing.accounts a ON a.id=i.account_id WHERE i.id=? AND a.merchant_id=?",intent,id(row.get("merchantId")))) throw BillingProblem.missing();
            UUID outbox=UUID.randomUUID();
            db.update("INSERT INTO billing.outbox(id,aggregate_id,type,payload) VALUES(?,?,'DLT_REPLAY',?::jsonb)",outbox,intent,payload.toString());
            db.update("UPDATE billing.dead_letters SET state='REPLAY_QUEUED',replay_outbox_id=?,replayed_by=? WHERE id=?",outbox,user,deadLetter);
            return Map.of("id",deadLetter,"status","REPLAY_QUEUED");
        });
    }
    static JSONObject parse(byte[] bytes) {
        if(bytes==null || bytes.length>262144) throw new IllegalArgumentException("Invalid event size");
        try {return new JSONObject(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString());}
        catch(CharacterCodingException|org.json.JSONException ex) {throw new IllegalArgumentException("Malformed billing event",ex);}
    }
    private static long exact(JSONObject event,String key) {
        Object value=event.get(key); if(!(value instanceof Number)) throw new IllegalArgumentException("Amount must be numeric");
        try {long amount=new java.math.BigDecimal(value.toString()).longValueExact();if(amount<=0) throw new ArithmeticException();return amount;}
        catch(ArithmeticException failure) {throw new IllegalArgumentException("Amount must be exact positive minor units",failure);}
    }
}
