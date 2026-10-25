package com.invoicepayments.billing;

import org.apache.kafka.common.security.auth.AuthenticateCallbackHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;

import javax.security.auth.Subject;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.spi.LoginModule;
import javax.security.sasl.Sasl;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Dependency and configuration checks only: no AWS credentials, DNS, or network requests. */
class BillingMskIamConfigurationTest {
    private static final String LOGIN = "software.amazon.msk.auth.iam.IAMLoginModule";
    private static final String CALLBACK = "software.amazon.msk.auth.iam.IAMClientCallbackHandler";

    @Test
    void actualIamClassesInitializeAndSignWithInMemoryTestCredentials() throws Exception {
        var login = (LoginModule) Class.forName(LOGIN).getConstructor().newInstance();
        login.initialize(new Subject(), callbacks -> {}, Map.of(), Map.of());
        assertThat(login.login()).isTrue();
        assertThat(login.commit()).isTrue();
        var handler = (AuthenticateCallbackHandler) Class.forName(CALLBACK).getConstructor().newInstance();
        try {
            handler.configure(Map.of(), "AWS_MSK_IAM", List.of(new AppConfigurationEntry(LOGIN,
                    AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, Map.of())));
            // Configuring the real handler must not resolve the machine's credentials.
            // Supply deliberately fake credentials directly to the local SASL challenge instead.
            var client = Sasl.createSaslClient(new String[]{"AWS_MSK_IAM"}, null, "kafka",
                    "b-1.reference.abcd.c2.kafka.us-east-1.amazonaws.com", Map.of(), callbacks -> {
                        for (var callback : callbacks) {
                            try {
                                callback.getClass().getMethod("setAwsCredentials",
                                        software.amazon.awssdk.auth.credentials.AwsCredentials.class)
                                        .invoke(callback, AwsBasicCredentials.create("test-access-key", "test-secret-key"));
                            } catch (ReflectiveOperationException failure) {
                                throw new java.io.IOException("Unexpected IAM callback API", failure);
                            }
                        }
                    });
            assertThat(client).isNotNull();
            try {
                String signed = new String(client.evaluateChallenge(new byte[0]), StandardCharsets.UTF_8);
                assertThat(signed).contains("kafka-cluster:Connect", "x-amz-signature", "us-east-1");
            } finally {
                client.dispose();
            }
        } finally {
            handler.close();
            login.logout();
        }
    }

    @Test
    void producerAndBothConsumerFactoriesPreserveIamProperties() {
        var properties = new KafkaProperties();
        properties.setBootstrapServers(List.of("localhost:9098"));
        properties.getProperties().putAll(Map.of("security.protocol", "SASL_SSL", "sasl.mechanism", "AWS_MSK_IAM",
                "sasl.jaas.config", LOGIN + " required;", "sasl.client.callback.handler.class", CALLBACK));
        var configuration = new BillingKafkaConfiguration();
        var template = configuration.billingKafkaTemplate(properties);
        var producer = (DefaultKafkaProducerFactory<?, ?>) template.getProducerFactory();
        try {
            for (var config : List.of(producer.getConfigurationProperties(),
                    configuration.billingKafkaListenerFactory(properties, template, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()).getConsumerFactory().getConfigurationProperties(),
                    configuration.billingDltListenerFactory(properties, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()).getConsumerFactory().getConfigurationProperties())) {
                assertThat(config).containsEntry("security.protocol", "SASL_SSL")
                        .containsEntry("sasl.mechanism", "AWS_MSK_IAM")
                        .containsEntry("sasl.jaas.config", LOGIN + " required;")
                        .containsEntry("sasl.client.callback.handler.class", CALLBACK);
            }
        } finally {
            producer.destroy();
        }
    }
}
