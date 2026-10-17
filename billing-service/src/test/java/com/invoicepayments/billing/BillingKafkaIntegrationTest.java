package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.SimulatorBillingProvider;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.invoicepayments.billing.BillingStore.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * Real Kafka transport and PostgreSQL transactions, with no app context or external PSP.
 * Run exclusively: the financial database suite truncates the same allowlisted throwaway DB.
 * Three colocated test brokers exercise replication settings, not AWS availability zones.
 */
@EnabledIfEnvironmentVariable(named = "BILLING_TEST_JDBC_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:15432/invoice_payments_test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("billing-reliability-test-database")
@Timeout(90)
class BillingKafkaIntegrationTest {
    private static final String TOPIC = "billing.events.v1", DLT = TOPIC + ".DLT";
    private static final String ERP_GROUP = "billing-erp-simulator-v1", DLT_GROUP = "billing-dlt-audit-v1";
    private static final int PARTITIONS = 3;
    private static final UUID MERCHANT = UUID.fromString("a1000000-0000-0000-0000-000000000001");
    private final int brokerCount = Integer.getInteger("billing.kafka.test.brokers", 3);
    private final int minimumIsr = brokerCount == 3 ? 2 : 1;
    private EmbeddedKafkaKraftBroker broker;
    private AdminClient admin;
    private KafkaTemplate<String, byte[]> producer;
    private JdbcTemplate jdbc;
    private FaultingAuditStore store;
    private BillingEvents events;
    private BillingWorkers workers;
    private ConcurrentMessageListenerContainer<String, byte[]> erp;
    private ConcurrentMessageListenerContainer<String, byte[]> audit;

    @BeforeAll @Timeout(180)
    void startOnlyLocalThrowawayResources() throws Exception {
        String url = System.getenv("BILLING_TEST_JDBC_URL");
        assertThat(url).isEqualTo("jdbc:postgresql://127.0.0.1:15432/invoice_payments_test");
        assertThat(System.getenv("BILLING_TEST_DB_USER")).isEqualTo("billing_test");
        assertThat(brokerCount).as("Explicit supported test topology").isIn(1, 3);
        DriverManagerDataSource source = new DriverManagerDataSource(url, System.getenv("BILLING_TEST_DB_USER"),
                System.getenv("BILLING_TEST_DB_PASSWORD"));
        try (Connection connection = source.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT current_database(), current_user")) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("invoice_payments_test");
            assertThat(result.getString(2)).isEqualTo("billing_test");
        }
        Flyway.configure().dataSource(source).schemas("billing").defaultSchema("billing")
                .locations("classpath:db/billing").cleanDisabled(true).load().migrate();
        jdbc = new JdbcTemplate(source);
        // Exactly one reset before any Kafka consumer is running, never an application database.
        jdbc.execute("TRUNCATE billing.merchants,billing.provider_operations,billing.outbox,billing.consumer_inbox RESTART IDENTITY CASCADE");
        store = new FaultingAuditStore(jdbc, new DataSourceTransactionManager(source));
        store.update("INSERT INTO billing.merchants(id,name) VALUES(?,'Kafka integration merchant')", MERCHANT);
        events = new BillingEvents(store, MERCHANT.toString());

        broker = new EmbeddedKafkaKraftBroker(brokerCount, PARTITIONS);
        broker.adminTimeout(40);
        broker.brokerProperties(Map.of(
                "auto.create.topics.enable", "false",
                "default.replication.factor", Integer.toString(brokerCount),
                "min.insync.replicas", Integer.toString(minimumIsr),
                "offsets.topic.replication.factor", Integer.toString(brokerCount),
                "offsets.topic.num.partitions", "3",
                "transaction.state.log.replication.factor", Integer.toString(brokerCount),
                "transaction.state.log.min.isr", Integer.toString(minimumIsr),
                "group.initial.rebalance.delay.ms", "0",
                "unclean.leader.election.enable", "false"));
        broker.afterPropertiesSet();
        broker.addTopics(new NewTopic(TOPIC, PARTITIONS, (short) brokerCount)
                        .configs(Map.of("min.insync.replicas", Integer.toString(minimumIsr))),
                new NewTopic(DLT, PARTITIONS, (short) brokerCount)
                        .configs(Map.of("min.insync.replicas", Integer.toString(minimumIsr))));
        admin = AdminClient.create(Map.of("bootstrap.servers", broker.getBrokersAsString(),
                "default.api.timeout.ms", "10000", "request.timeout.ms", "5000"));

        KafkaProperties properties = new KafkaProperties();
        properties.setBootstrapServers(Arrays.asList(broker.getBrokersAsString().split(",")));
        properties.getProperties().put("security.protocol", "PLAINTEXT");
        var configuration = new BillingKafkaConfiguration();
        producer = configuration.billingKafkaTemplate(properties);
        erp = configuration.billingKafkaListenerFactory(properties, producer).createContainer(TOPIC);
        erp.setBeanName("integration-erp");
        erp.getContainerProperties().setGroupId(ERP_GROUP);
        erp.getContainerProperties().setPollTimeout(200);
        erp.getContainerProperties().setShutdownTimeout(10000);
        erp.getContainerProperties().setMessageListener((AcknowledgingMessageListener<String, byte[]>) events::erp);
        audit = configuration.billingDltListenerFactory(properties).createContainer(DLT);
        audit.setBeanName("integration-dlt-audit");
        audit.getContainerProperties().setGroupId(DLT_GROUP);
        audit.getContainerProperties().setPollTimeout(200);
        audit.getContainerProperties().setShutdownTimeout(10000);
        audit.getContainerProperties().setMessageListener((AcknowledgingMessageListener<String, byte[]>) events::deadLetter);

        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("integrationKafka", producer);
        @SuppressWarnings({"unchecked", "rawtypes"})
        ObjectProvider<KafkaTemplate<String, byte[]>> kafka = (ObjectProvider) beans.getBeanProvider(KafkaTemplate.class);
        workers = new BillingWorkers(store, null, null, kafka); // Only the real outbox path is invoked.
        audit.start();
        erp.start();
        ContainerTestUtils.waitForAssignment(audit, PARTITIONS);
        ContainerTestUtils.waitForAssignment(erp, PARTITIONS);
        System.out.printf("Billing Kafka integration topology: %d colocated KRaft brokers, RF=%d, minISR=%d%n",
                brokerCount, brokerCount, minimumIsr);
    }

    @AfterAll @Timeout(60)
    void stopEveryTestResource() {
        if (store != null) store.failAudit.set(false);
        try {
            if (erp != null) erp.stop();
        } finally {
            try {
                if (audit != null) audit.stop();
            } finally {
                if (workers != null) workers.close();
                if (producer != null) ((DefaultKafkaProducerFactory<?, ?>) producer.getProducerFactory()).destroy();
                if (admin != null) admin.close(Duration.ofSeconds(5));
                if (broker != null) broker.destroy();
            }
        }
    }

    @Test @Order(1)
    void configuredBrokerCountReplicationAndMinimumIsrAreActuallyPresent() throws Exception {
        assertThat(admin.describeCluster().nodes().get(15, TimeUnit.SECONDS)).hasSize(brokerCount);
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
            var topics = admin.describeTopics(List.of(TOPIC, DLT)).allTopicNames().get(10, TimeUnit.SECONDS);
            for (var topic : topics.values()) {
                assertThat(topic.partitions()).hasSize(PARTITIONS);
                for (var partition : topic.partitions()) {
                    assertThat(partition.replicas()).hasSize(brokerCount);
                    assertThat(partition.isr()).hasSize(brokerCount);
                }
            }
        });
        var resources = List.of(new ConfigResource(ConfigResource.Type.TOPIC, TOPIC),
                new ConfigResource(ConfigResource.Type.TOPIC, DLT));
        for (var configuration : admin.describeConfigs(resources).all().get(10, TimeUnit.SECONDS).values()) {
            assertThat(configuration.get("min.insync.replicas").value()).isEqualTo(Integer.toString(minimumIsr));
        }
    }

    @Test @Order(2)
    void committedOutboxTravelsThroughKafkaAndDuplicateDeliveryPostsOneErpEffect() throws Exception {
        UUID account = UUID.randomUUID(), invoice = UUID.randomUUID();
        String payer = "kafka-payer";
        store.update("INSERT INTO billing.accounts(id,merchant_id,name,external_reference) VALUES(?,?,'Transport account',?)", account, MERCHANT, account.toString());
        store.update("INSERT INTO billing.memberships(account_id,user_id,role) VALUES(?,?,'PAYER')", account, payer);
        store.update("INSERT INTO billing.invoices(id,account_id,number,currency,total_minor,due_date) VALUES(?,?,'TRANSPORT-1','USD',12345,CURRENT_DATE)", invoice, account);
        var adapter = new SimulatorBillingProvider(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()));
        var payments = new BillingService(store, adapter, new MockEnvironment(), true, "http://localhost:4200", MERCHANT.toString());
        UUID intent = id(payments.create(payer, account, UUID.randomUUID().toString(), List.of(invoice), "CARD").get("id"));
        payments.submit(payer, intent);
        payments.simulate(payer, intent, "CAPTURED");
        var outbox = store.one("SELECT id,payload::text AS payload,state FROM billing.outbox WHERE aggregate_id=? AND type='PAYMENT_CAPTURED'", intent);
        UUID event = id(outbox.get("id"));
        assertThat(outbox.get("state")).isEqualTo("NEW");
        workers.publish();
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
            assertThat(store.one("SELECT state FROM billing.outbox WHERE id=?", event).get("state")).isEqualTo("PUBLISHED");
            assertThat(count("SELECT count(*) FROM billing.erp_postings WHERE event_id=? AND status='ACKNOWLEDGED'", event)).isEqualTo(1);
        });

        byte[] payload = text(outbox, "payload").getBytes(StandardCharsets.UTF_8);
        producer.send(TOPIC, intent.toString(), payload).get(10, TimeUnit.SECONDS);
        RecordMetadata duplicate = producer.send(TOPIC, intent.toString(), payload).get(10, TimeUnit.SECONDS).getRecordMetadata();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(committed(ERP_GROUP, new TopicPartition(TOPIC, duplicate.partition()))).isGreaterThan(duplicate.offset()));
        assertThat(count("SELECT count(*) FROM billing.consumer_inbox WHERE consumer='ERP_SIMULATOR' AND event_id=?", event)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM billing.erp_postings WHERE intent_id=?", intent)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM billing.journals WHERE intent_id=?", intent)).isEqualTo(1);
        assertThat(store.one("SELECT paid_minor,reserved_minor FROM billing.invoices WHERE id=?", invoice))
                .containsEntry("paidMinor", 12345L).containsEntry("reservedMinor", 0L);
    }

    @Test @Order(3)
    void malformedUtf8AndJsonTravelToDltAndAreAuditedWithoutChangingTheirBytes() throws Exception {
        for (byte[] payload : List.of(new byte[]{(byte) 0xc3, (byte) 0x28, 0, (byte) 0xff},
                "{ definitely not JSON".getBytes(StandardCharsets.UTF_8))) {
            String key = "malformed-" + UUID.randomUUID();
            RecordMetadata source = producer.send(TOPIC, key, payload).get(10, TimeUnit.SECONDS).getRecordMetadata();
            await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
                var rows = store.rows("SELECT raw_payload,topic,partition_id FROM billing.dead_letters WHERE event_key=?", key);
                assertThat(rows).hasSize(1);
                var row = rows.get(0);
                assertThat((byte[]) row.get("rawPayload")).containsExactly(payload);
                assertThat(row).containsEntry("topic", DLT).containsEntry("partitionId", source.partition());
                assertThat(committed(ERP_GROUP, new TopicPartition(TOPIC, source.partition()))).isGreaterThan(source.offset());
            });
            assertThat(count("SELECT count(*) FROM billing.dead_letters WHERE event_key=?", key)).isEqualTo(1);
        }
    }

    @Test @Order(4)
    void dltAuditDatabaseFailureDoesNotCommitUntilDurableAuditRecovers() throws Exception {
        String key = "audit-recovery-" + UUID.randomUUID();
        byte[] payload = new byte[]{0, 1, (byte) 0xff, 42};
        store.blockedKey = key;
        store.failedAuditAttempts.set(0);
        store.failAudit.set(true);
        RecordMetadata record = producer.send(DLT, 0, key, payload).get(10, TimeUnit.SECONDS).getRecordMetadata();
        TopicPartition partition = new TopicPartition(DLT, record.partition());
        try {
            // More attempts than the main listener's finite retry budget proves the audit keeps retrying.
            await().atMost(Duration.ofSeconds(20)).until(() -> store.failedAuditAttempts.get() >= 6);
            assertThat(count("SELECT count(*) FROM billing.dead_letters WHERE event_key=?", key)).isZero();
            assertThat(committed(DLT_GROUP, partition)).isLessThanOrEqualTo(record.offset());
            assertThat(admin.listTopics().names().get(10, TimeUnit.SECONDS)).doesNotContain(DLT + ".DLT");
        } finally {
            store.failAudit.set(false);
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            var rows = store.rows("SELECT raw_payload FROM billing.dead_letters WHERE event_key=?", key);
            assertThat(rows).hasSize(1);
            var row = rows.get(0);
            assertThat((byte[]) row.get("rawPayload")).containsExactly(payload);
            assertThat(committed(DLT_GROUP, partition)).isGreaterThan(record.offset());
        });
        assertThat(count("SELECT count(*) FROM billing.dead_letters WHERE event_key=?", key)).isEqualTo(1);
        assertThat(admin.listTopics().names().get(10, TimeUnit.SECONDS)).doesNotContain(DLT + ".DLT");
    }

    private long committed(String group, TopicPartition partition) throws Exception {
        OffsetAndMetadata offset = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                .get(10, TimeUnit.SECONDS).get(partition);
        return offset == null ? -1 : offset.offset();
    }

    private long count(String query, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(query, Long.class, args));
    }

    private static final class FaultingAuditStore extends BillingStore {
        final AtomicBoolean failAudit = new AtomicBoolean();
        final AtomicInteger failedAuditAttempts = new AtomicInteger();
        volatile String blockedKey;

        FaultingAuditStore(JdbcTemplate jdbc, DataSourceTransactionManager manager) { super(jdbc, manager); }

        @Override public int update(String sql, Object... args) {
            if (failAudit.get() && sql.startsWith("INSERT INTO billing.dead_letters")
                    && args.length > 5 && Objects.equals(args[5], blockedKey)) {
                failedAuditAttempts.incrementAndGet();
                throw new DataAccessResourceFailureException("Injected transient audit database failure");
            }
            return super.update(sql, args);
        }
    }
}
