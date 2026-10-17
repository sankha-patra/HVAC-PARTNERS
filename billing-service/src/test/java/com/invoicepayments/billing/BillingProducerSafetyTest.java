package com.invoicepayments.billing;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import static org.assertj.core.api.Assertions.assertThat;

class BillingProducerSafetyTest {
    @ParameterizedTest @ValueSource(strings={"default","billing","local-observability","prod"})
    void billingFactoryEnforcesSafetyWithoutProductionYaml(String profile) {
        var properties=new KafkaProperties();properties.setClientId("test-"+profile);
        properties.getProperties().put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,"false");
        properties.getProperties().put(ProducerConfig.ACKS_CONFIG,"0");
        properties.getProperties().put(ProducerConfig.RETRIES_CONFIG,"0");
        properties.getProperties().put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION,"10");
        var template=new BillingKafkaConfiguration().billingKafkaTemplate(properties);
        var factory=(DefaultKafkaProducerFactory<String,byte[]>)template.getProducerFactory();
        try {
            assertThat(factory.getConfigurationProperties())
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,true)
                .containsEntry(ProducerConfig.ACKS_CONFIG,"all")
                .containsEntry(ProducerConfig.RETRIES_CONFIG,Integer.MAX_VALUE)
                .containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION,5);
        } finally {factory.destroy();}
    }
}
