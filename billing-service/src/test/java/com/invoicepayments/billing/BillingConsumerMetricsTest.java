package com.invoicepayments.billing;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.ConsumerFactory;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** The factories are built by hand, so Spring Boot does not attach consumer metrics; consumer lag would be invisible. */
class BillingConsumerMetricsTest {
    @Test
    void everyBillingConsumerReportsItsLag() {
        var properties=new KafkaProperties();properties.setBootstrapServers(List.of("localhost:9"));
        var registry=new SimpleMeterRegistry();
        var configuration=new BillingKafkaConfiguration();
        var template=configuration.billingKafkaTemplate(properties);
        List<ConsumerFactory<?,?>> factories=List.of(
            configuration.billingKafkaListenerFactory(properties,template,registry).getConsumerFactory(),
            configuration.billingDltListenerFactory(properties,registry).getConsumerFactory());
        try {
            for(var factory:factories) {
                int before=lagMeters(registry);
                try(var consumer=factory.createConsumer("lag-test",null)) {
                    assertThat(lagMeters(registry)).isGreaterThan(before);
                }
            }
        } finally {template.getProducerFactory().reset();registry.close();}
    }
    private static int lagMeters(SimpleMeterRegistry registry) {
        return registry.find("kafka.consumer.fetch.manager.records.lag.max").meters().size();
    }
}
