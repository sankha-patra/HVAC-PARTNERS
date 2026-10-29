package com.invoicepayments.billing;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import java.util.Set;

/** Independent Kafka group. A broker replay can repeat this call, not the durable delivery intent. */
@Component @Profile("billing")
@ConditionalOnProperty(name="billing.kafka-enabled",havingValue="true",matchIfMissing=true)
public class BillingNotifications {
    private final BillingNotificationCommands commands;
    public BillingNotifications(BillingNotificationCommands commands){this.commands=commands;}
    @KafkaListener(topics="billing.events.v1",groupId="billing-customer-notifications-v1",containerFactory="billingKafkaListenerFactory",
        autoStartup="#{'${billing.role:combined}' != 'api' && ${billing.consumers.notifications-enabled:true}}")
    public void receive(ConsumerRecord<String,byte[]> event,Acknowledgment ack){apply(event.value());ack.acknowledge();}
    public void apply(byte[] bytes){
        var event=BillingEvents.parse(bytes);String type=event.getString("eventType");
        if(event.getInt("schemaVersion")!=1||!Set.of("PAYMENT_CAPTURED","PAYMENT_REFUNDED","PAYMENT_FAILED","PAYMENT_RETURNED").contains(type))throw new IllegalArgumentException("Unsupported payment notification schema");
        if(Set.of("PAYMENT_CAPTURED","PAYMENT_REFUNDED").contains(type))commands.receive(event);
    }
}
