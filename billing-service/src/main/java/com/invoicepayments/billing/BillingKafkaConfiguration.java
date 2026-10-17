package com.invoicepayments.billing;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.*;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.util.backoff.FixedBackOff;
import java.util.*;

@Configuration(proxyBeanMethods=false)
@Profile("billing")
@EnableKafka
@ConditionalOnProperty(name="billing.kafka-enabled",havingValue="true",matchIfMissing=true)
public class BillingKafkaConfiguration {
    @Bean KafkaTemplate<String,byte[]> billingKafkaTemplate(KafkaProperties properties) {
        Map<String,Object> config=new HashMap<>(properties.buildProducerProperties());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,ByteArraySerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG,"all");config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,true);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,10000);config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,5000);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG,3000);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }
    private Map<String,Object> consumerProperties(KafkaProperties properties) {
        Map<String,Object> config=new HashMap<>(properties.buildConsumerProperties());
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,ErrorHandlingDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,ErrorHandlingDeserializer.class);
        config.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS,StringDeserializer.class);
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS,ByteArrayDeserializer.class);
        return config;
    }
    @Bean ConcurrentKafkaListenerContainerFactory<String,byte[]> billingKafkaListenerFactory(KafkaProperties properties,KafkaTemplate<String,byte[]> template) {
        var config=consumerProperties(properties);
        var factory=new ConcurrentKafkaListenerContainerFactory<String,byte[]>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        var recoverer=new DeadLetterPublishingRecoverer(template);recoverer.setFailIfSendResultIsError(true);
        var errors=new DefaultErrorHandler(recoverer,new FixedBackOff(1000,4));
        errors.addNotRetryableExceptions(IllegalArgumentException.class);errors.setCommitRecovered(true);
        factory.setCommonErrorHandler(errors);factory.setConcurrency(2);return factory;
    }
    @Bean ConcurrentKafkaListenerContainerFactory<String,byte[]> billingDltListenerFactory(KafkaProperties properties) {
        var factory=new ConcurrentKafkaListenerContainerFactory<String,byte[]>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumerProperties(properties)));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // Keep the original DLT record unacknowledged while the database is unavailable.
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000,FixedBackOff.UNLIMITED_ATTEMPTS)));
        return factory;
    }
    @Bean NewTopic billingEventsTopic(@Value("${kafka.topic.partitions:6}") int partitions,@Value("${kafka.topic.replicas:3}") int replicas,@Value("${kafka.topic.min-insync-replicas:2}") int minIsr) {
        return TopicBuilder.name("billing.events.v1").partitions(partitions).replicas(replicas).config("min.insync.replicas",Integer.toString(minIsr)).build();
    }
    @Bean NewTopic billingDeadLetterTopic(@Value("${kafka.topic.partitions:6}") int partitions,@Value("${kafka.topic.replicas:3}") int replicas,@Value("${kafka.topic.min-insync-replicas:2}") int minIsr) {
        return TopicBuilder.name("billing.events.v1.DLT").partitions(partitions).replicas(replicas).config("min.insync.replicas",Integer.toString(minIsr)).build();
    }
}
