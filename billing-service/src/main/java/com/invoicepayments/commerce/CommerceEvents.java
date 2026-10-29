package com.invoicepayments.commerce;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.json.JSONObject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import java.nio.ByteBuffer;
import java.nio.charset.*;

/** Acknowledges after the proxied commerce transaction commits; duplicate transport is harmless. */
@Component @Profile("billing")
@ConditionalOnProperty(name="billing.kafka-enabled",havingValue="true",matchIfMissing=true)
public class CommerceEvents {
    private final CommerceCommands commands;
    public CommerceEvents(CommerceCommands commands){this.commands=commands;}
    @KafkaListener(topics="billing.events.v1",groupId="commerce-orders-v1",containerFactory="billingKafkaListenerFactory",autoStartup="#{'${billing.role:combined}' != 'api' && ${commerce.consumer-enabled:true}}")
    public void payment(ConsumerRecord<String,byte[]> record,Acknowledgment ack){apply(record.value());ack.acknowledge();}
    public void apply(byte[] bytes){
        if(bytes==null || bytes.length>262144)throw new IllegalArgumentException("Invalid payment event size");
        try{commands.applyPaymentEvent(new JSONObject(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()));}
        catch(CharacterCodingException malformed){throw new IllegalArgumentException("Malformed payment event",malformed);}
    }
}
