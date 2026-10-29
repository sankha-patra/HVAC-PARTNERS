package com.invoicepayments.billing;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;

/** A receipt means observed in Kafka, not processed by ERP. Connector offsets never stand in for this receipt. */
@Component
@Profile("billing")
@ConditionalOnProperty(name="billing.kafka-enabled",havingValue="true",matchIfMissing=true)
public class BillingCdcReceipts {
    private final BillingCdcCommands commands;
    public BillingCdcReceipts(BillingCdcCommands commands){this.commands=commands;}
    @KafkaListener(topics="billing.events.v1",groupId="billing-cdc-receipts-v1",containerFactory="billingKafkaListenerFactory",
        autoStartup="#{'${billing.role:combined}' != 'api' && ${billing.consumers.cdc-enabled:true}}")
    public void receive(ConsumerRecord<String,byte[]> record,Acknowledgment ack){
        var header=record.headers().lastHeader("id");
        if(header!=null){
            String transport=new String(header.value(),StandardCharsets.UTF_8);
            commands.recordDelivery(BillingStore.id(transport),record.key(),record.value());
        }
        // Old polling records have no CDC header and require no second acknowledgment.
        ack.acknowledge();
    }
}
