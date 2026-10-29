package com.invoicepayments.billing;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.json.JSONObject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Kafka consumers. Each record is acknowledged only after its database unit (BillingEventCommands) has committed. */
@Service
@Profile("billing")
@ConditionalOnProperty(name="billing.kafka-enabled",havingValue="true",matchIfMissing=true)
public class BillingEvents {
    private final BillingEventCommands commands;
    public BillingEvents(BillingEventCommands commands) {this.commands=commands;}
    @KafkaListener(topics="billing.events.v1",groupId="billing-erp-simulator-v1",containerFactory="billingKafkaListenerFactory",autoStartup="#{'${billing.role:combined}' != 'api' && ${billing.consumers.erp-enabled:${billing.erp-simulator-enabled:false}}}")
    public void erp(ConsumerRecord<String,byte[]> record,Acknowledgment ack) { applyErp(record.value()); ack.acknowledge(); }
    public void applyErp(byte[] bytes) {
        JSONObject event=parse(bytes);
        if(event.getInt("schemaVersion")!=1 || !Set.of("PAYMENT_CAPTURED","PAYMENT_RETURNED","PAYMENT_REFUNDED","PAYMENT_FAILED").contains(event.getString("eventType"))) throw new IllegalArgumentException("Unsupported billing event schema");
        BillingExecutionBudget.requireTime();commands.applyErp(event);
    }
    @KafkaListener(topics="billing.events.v1.DLT",groupId="billing-dlt-audit-v1",containerFactory="billingDltListenerFactory",autoStartup="#{'${billing.role:combined}' != 'api' && ${billing.consumers.dlt-enabled:true}}")
    public void deadLetter(ConsumerRecord<String,byte[]> record,Acknowledgment ack) {
        BillingExecutionBudget.requireTime();
        commands.recordDeadLetter(record.topic(),record.partition(),record.offset(),record.key(),record.value());
        ack.acknowledge();
    }
    public Map<String,Object> replay(String user,UUID deadLetter) {
        BillingExecutionBudget.requireTime();return commands.replay(user,deadLetter);
    }
    static JSONObject parse(byte[] bytes) {
        if(bytes==null || bytes.length>262144) throw new IllegalArgumentException("Invalid event size");
        try {return new JSONObject(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString());}
        catch(CharacterCodingException|org.json.JSONException ex) {throw new IllegalArgumentException("Malformed billing event",ex);}
    }
    static long exact(JSONObject event,String key) {
        Object value=event.get(key); if(!(value instanceof Number)) throw new IllegalArgumentException("Amount must be numeric");
        try {long amount=new java.math.BigDecimal(value.toString()).longValueExact();if(amount<=0) throw new ArithmeticException();return amount;}
        catch(ArithmeticException failure) {throw new IllegalArgumentException("Amount must be exact positive minor units",failure);}
    }
}
