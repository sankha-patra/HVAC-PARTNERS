package com.invoicepayments.billing;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.*;
import org.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.invoicepayments.billing.BillingFinancialDatabaseTest.*;
import static com.invoicepayments.billing.BillingStore.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Real PostgreSQL17 + Debezium + Kafka. Exclusively uses the allowlisted disposable database. */
@EnabledIfEnvironmentVariable(named="BILLING_CDC_TEST_CONNECT_URL",matches="http://connect:8083")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(180)
class BillingCdcIntegrationTest {
    BillingFinancialDatabaseTest fixture;AnnotationConfigApplicationContext context,listeners;
    BillingCdcCommands commands;BillingCdcReceipts receipts;KafkaConsumer<String,byte[]> consumer;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final String endpoint=System.getenv("BILLING_CDC_TEST_CONNECT_URL");
    ConsumerRecord<String,byte[]> first;
    UUID rolledBack;
    final Set<String> observedTransportIds=new HashSet<>();
    @BeforeAll @Timeout(420) void start() throws Exception {
        migrateOnlyIsolatedDatabase();fixture=new BillingFinancialDatabaseTest();fixture.cleanAndSeedDedicatedDatabase();
        fixture.db.update("UPDATE billing.outbox_relay_control SET mode='POLL',generation=1 WHERE id");
        context=BillingTestWiring.context(fixture.db,fixture.provider,new MockEnvironment(),true,"http://localhost:4200",MERCHANT.toString());
        context.refresh();commands=context.getBean(BillingCdcCommands.class);receipts=new BillingCdcReceipts(commands);
        try(var admin=AdminClient.create(Map.of("bootstrap.servers",System.getenv("BILLING_CDC_TEST_KAFKA")))){
            var topics=admin.listTopics().names().get(15,TimeUnit.SECONDS);
            var desired=List.of(new NewTopic("billing.events.v1",3,(short)1),new NewTopic("billing.events.v1.DLT",3,(short)1),new NewTopic("__debezium-heartbeat.billing_cdc",1,(short)1));
            admin.createTopics(desired.stream().filter(t->!topics.contains(t.name())).toList()).all().get(15,TimeUnit.SECONDS);
        }
        fixture.db.jdbc().execute("DROP PUBLICATION IF EXISTS billing_outbox_cdc");
        fixture.db.jdbc().execute("CREATE PUBLICATION billing_outbox_cdc FOR TABLE billing.outbox_events,billing.cdc_heartbeat WITH (publish='insert,update')");
        var config=new JSONObject(new String(Objects.requireNonNull(getClass().getResourceAsStream("/cdc-connector.json")).readAllBytes(),StandardCharsets.UTF_8));
        var registration=request("PUT","/connectors/billing-outbox/config",config.getJSONObject("config").toString());
        assertThat(registration.statusCode()).as("connector registration response: %s",registration.body()).isBetween(200,201);
        Files.writeString(Path.of(System.getenv("BILLING_CDC_TEST_EVIDENCE")).resolve("connector-registration.txt"),"HTTP "+registration.statusCode());
        state("RUNNING");
        listeners=new AnnotationConfigApplicationContext();listeners.getEnvironment().setActiveProfiles("billing");
        listeners.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("cdc-test",Map.of("billing.role","worker","billing.topics-enabled",false)));
        var properties=new org.springframework.boot.autoconfigure.kafka.KafkaProperties();properties.setBootstrapServers(List.of(System.getenv("BILLING_CDC_TEST_KAFKA")));
        listeners.registerBean(org.springframework.boot.autoconfigure.kafka.KafkaProperties.class,()->properties);
        listeners.registerBean(io.micrometer.core.instrument.MeterRegistry.class,io.micrometer.core.instrument.simple.SimpleMeterRegistry::new);
        listeners.registerBean(BillingCdcCommands.class,()->commands);
        listeners.registerBean(BillingEventCommands.class,()->context.getBean(BillingEventCommands.class));
        listeners.register(BillingKafkaConfiguration.class,BillingCdcReceipts.class,BillingEvents.class);listeners.refresh();
        commands.switchRelay("POLL","CDC",1);
        consumer=new KafkaConsumer<>(consumerConfig("cdc-proof-"+UUID.randomUUID()));consumer.subscribe(List.of("billing.events.v1"));
    }
    @AfterAll void close() throws Exception {
        if(consumer!=null)consumer.close();
        if(listeners!=null)listeners.close();
        if(endpoint!=null)request("DELETE","/connectors/billing-outbox",null);
        if(context!=null)context.close();
        if(fixture!=null){fixture.db.update("UPDATE billing.outbox_relay_control SET mode='POLL',generation=1 WHERE id");fixture.removeOnlyTestFaultTrigger();}
    }
    Map<String,Object> consumerConfig(String group){return Map.of("bootstrap.servers",System.getenv("BILLING_CDC_TEST_KAFKA"),"group.id",group,
        "key.deserializer",StringDeserializer.class,"value.deserializer",ByteArrayDeserializer.class,"auto.offset.reset","earliest","enable.auto.commit",false);}
    UUID capture() {
        UUID invoice=UUID.randomUUID();fixture.invoice(invoice,ACCOUNT,10000);
        UUID intent=id(fixture.payments.create(PAYER,ACCOUNT,UUID.randomUUID().toString(),List.of(invoice),"CARD").get("id"));
        fixture.payments.submit(PAYER,intent);fixture.payments.simulate(PAYER,intent,"CAPTURED");return intent;
    }
    ConsumerRecord<String,byte[]> read(UUID intent) {
        var found=new ArrayList<ConsumerRecord<String,byte[]>>();
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100)).until(()->{
            for(var row:consumer.poll(Duration.ofMillis(500))){
                observe(row);if(intent.toString().equals(row.key())&&intent.toString().equals(new JSONObject(new String(row.value(),StandardCharsets.UTF_8)).optString("intentId")))found.add(row);
            }
            return !found.isEmpty();
        });return found.get(0);
    }
    void observe(ConsumerRecord<String,byte[]> record){
        var header=record.headers().lastHeader("id");if(header!=null)observedTransportIds.add(new String(header.value(),StandardCharsets.UTF_8));
        fixture.events.applyErp(record.value());
    }
    @Test @Order(1) void realCdcPreservesTheContractAndCannotPublishRolledBackWork() throws Exception {
        rolledBack=UUID.randomUUID();
        try(var connection=jdbc.getDataSource().getConnection()){
            connection.setAutoCommit(false);
            try(var st=connection.prepareStatement("INSERT INTO billing.outbox(id,aggregate_id,type,payload) VALUES(?,?,'TEST','{}')")){
                st.setObject(1,rolledBack);st.setObject(2,UUID.randomUUID());st.executeUpdate();
            }connection.rollback();
        }
        UUID intent=capture();first=read(intent);
        var payload=new JSONObject(new String(first.value(),StandardCharsets.UTF_8));
        assertThat(payload.getString("eventType")).isEqualTo("PAYMENT_CAPTURED");
        String transport=new String(first.headers().lastHeader("id").value(),StandardCharsets.UTF_8);
        assertThat(transport).isEqualTo(payload.getString("eventId"));
        assertThat(first.headers().lastHeader("publish_sequence")).isNotNull();
        assertThat(fixture.db.rows("SELECT id FROM billing.outbox_events WHERE id=?",rolledBack)).isEmpty();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(()->assertThat(fixture.db.one("SELECT state FROM billing.outbox WHERE id=?",id(transport)).get("state")).isEqualTo("PUBLISHED"));
        assertThat(fixture.db.rows("SELECT * FROM billing.erp_postings WHERE intent_id=?",intent)).hasSize(1);
    }
    @Test @Order(2) void connectorOutageLeavesDurableWorkAndResumesWithoutAnotherFinancialEffect() throws Exception {
        assertThat(request("PUT","/connectors/billing-outbox/pause",null).statusCode()).isEqualTo(202);state("PAUSED");
        UUID intent=capture();
        assertThat(fixture.db.one("SELECT state FROM billing.outbox WHERE aggregate_id=?",intent).get("state")).isEqualTo("CDC_PENDING");
        assertThat(context.getBean(BillingOutboxCommands.class).claim(20)).isEmpty();
        assertThat(request("PUT","/connectors/billing-outbox/resume",null).statusCode()).isEqualTo(202);state("RUNNING");
        read(intent);assertThat(fixture.db.rows("SELECT * FROM billing.erp_postings WHERE intent_id=?",intent)).hasSize(1);
    }
    @Test @Order(3) void badReceiptIsAuditedWithoutBlockingLaterValidReceiptsInTheSamePartition() throws Exception {
        request("PUT","/connectors/billing-outbox/pause",null);state("PAUSED");UUID intent=capture();
        var config=new HashMap<String,Object>();config.put("bootstrap.servers",System.getenv("BILLING_CDC_TEST_KAFKA"));config.put("key.serializer",StringSerializer.class);config.put("value.serializer",ByteArraySerializer.class);config.put("acks","all");
        var headers=new RecordHeaders();headers.add("id",UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        try(var producer=new KafkaProducer<String,byte[]>(config)){
            producer.send(new ProducerRecord<>("billing.events.v1",null,intent.toString(),first.value(),headers)).get(10,TimeUnit.SECONDS);
        }
        request("PUT","/connectors/billing-outbox/resume",null);state("RUNNING");read(intent);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(()->{
            assertThat(fixture.db.one("SELECT state FROM billing.outbox WHERE aggregate_id=?",intent).get("state")).isEqualTo("PUBLISHED");
            assertThat(fixture.db.rows("SELECT id FROM billing.dead_letters WHERE topic='billing.events.v1.DLT' AND event_key=?",intent.toString())).isNotEmpty();
        });
    }
    @Test @Order(4) @Timeout(600) void actualConnectorKillRestartsFromDurableStateAndDuplicatesHaveOneEffect() throws Exception {
        Path evidence=Path.of(System.getenv("BILLING_CDC_TEST_EVIDENCE"));
        Files.writeString(evidence.resolve("connector-kill-request"),"Kafka receipt observed; kill only this disposable connector");
        // The host kills and restarts Connect; the image scans every bundled plugin first, which can take minutes.
        await().atMost(Duration.ofSeconds(420)).until(()->Files.exists(evidence.resolve("connector-restarted")));
        state("RUNNING");UUID intent=capture();read(intent);
        // Deterministically repeat a delivered record as well as testing real connector restart.
        var config=new HashMap<String,Object>();config.put("bootstrap.servers",System.getenv("BILLING_CDC_TEST_KAFKA"));
        config.put("key.serializer",StringSerializer.class);config.put("value.serializer",ByteArraySerializer.class);config.put("acks","all");
        try(var producer=new KafkaProducer<String,byte[]>(config)){
            producer.send(new ProducerRecord<>("billing.events.v1",null,first.key(),first.value(),new RecordHeaders(first.headers()))).get(10,TimeUnit.SECONDS);
        }
        read(id(first.key()));
        assertThat(fixture.db.rows("SELECT * FROM billing.erp_postings WHERE intent_id=?",id(first.key()))).hasSize(1);
        assertThat(fixture.db.rows("SELECT id FROM billing.journals WHERE intent_id=? AND effect_key LIKE 'capture:%'",id(first.key()))).hasSize(1);
    }
    @Test @Order(5) void deadLetterReplayUsesNewTransportIdentityAndRetainsOriginalEffectIdentity() throws Exception {
        UUID dead=UUID.randomUUID();
        fixture.db.update("INSERT INTO billing.dead_letters(id,merchant_id,topic,partition_id,offset_id,event_key,raw_payload) VALUES(?,?,'billing.events.v1.DLT',0,999,?,?)",dead,MERCHANT,first.key(),first.value());
        fixture.events.replay("operator",dead);
        var saved=fixture.db.one("SELECT replay_outbox_id FROM billing.dead_letters WHERE id=?",dead);
        UUID replay=id(saved.get("replayOutboxId"));
        var matches=new ArrayList<ConsumerRecord<String,byte[]>>();
        await().atMost(Duration.ofSeconds(45)).until(()->{
            for(var row:consumer.poll(Duration.ofMillis(500))){observe(row);var h=row.headers().lastHeader("id");
                if(h!=null&&replay.toString().equals(new String(h.value(),StandardCharsets.UTF_8)))matches.add(row);}
            return !matches.isEmpty();
        });
        assertThat(new JSONObject(new String(matches.get(0).value(),StandardCharsets.UTF_8)).getString("eventId")).isNotEqualTo(replay.toString());
        await().atMost(Duration.ofSeconds(15)).untilAsserted(()->assertThat(fixture.db.one("SELECT state FROM billing.dead_letters WHERE id=?",dead).get("state")).isEqualTo("REPLAYED"));
        assertThat(fixture.db.rows("SELECT * FROM billing.erp_postings WHERE intent_id=?",id(first.key()))).hasSize(1);
    }
    @Test @Order(6) @Timeout(600) void reviewedSlotRebuildResnapshotsRetainedEventsWithoutRepeatingAccounting() throws Exception {
        // This scenario also runs alone, so it can prove the ordering regression
        // without repeating the already verified forced-process-restart scenario.
        if(first==null){
            rolledBack=UUID.randomUUID();
            try(var connection=jdbc.getDataSource().getConnection()){
                connection.setAutoCommit(false);
                try(var st=connection.prepareStatement("INSERT INTO billing.outbox(id,aggregate_id,type,payload) VALUES(?,?,'TEST','{}')")){
                    st.setObject(1,rolledBack);st.setObject(2,UUID.randomUUID());st.executeUpdate();
                }connection.rollback();
            }
            first=read(capture());
        }
        assertThat(request("PUT","/connectors/billing-outbox/stop",null).statusCode()).isEqualTo(204);state("STOPPED");
        await().atMost(Duration.ofSeconds(15)).until(()->!fixture.db.exists("SELECT 1 FROM pg_replication_slots WHERE slot_name='billing_outbox_cdc' AND active"));
        // Two transport rows for the same aggregate deliberately have UUID order
        // opposite to their committed publish sequence. A physical reorder makes
        // an unordered retained-row snapshot fail deterministically.
        UUID older=new UUID(Long.MAX_VALUE,1L),newer=new UUID(0L,2L);
        String retainedPayload=new String(first.value(),StandardCharsets.UTF_8);
        fixture.db.update("INSERT INTO billing.outbox(id,aggregate_id,type,payload) VALUES(?,?,'DLT_REPLAY',?::jsonb)",older,id(first.key()),retainedPayload);
        fixture.db.update("INSERT INTO billing.outbox(id,aggregate_id,type,payload) VALUES(?,?,'DLT_REPLAY',?::jsonb)",newer,id(first.key()),retainedPayload);
        long olderSequence=amount(fixture.db.one("SELECT publish_sequence FROM billing.outbox_events WHERE id=?",older),"publishSequence");
        long newerSequence=amount(fixture.db.one("SELECT publish_sequence FROM billing.outbox_events WHERE id=?",newer),"publishSequence");
        assertThat(olderSequence).isLessThan(newerSequence);
        fixture.db.jdbc().execute("CLUSTER billing.outbox_events USING outbox_events_pkey");
        fixture.db.jdbc().execute("SELECT pg_drop_replication_slot('billing_outbox_cdc')");
        assertThat(request("DELETE","/connectors/billing-outbox/offsets",null).statusCode()).isBetween(200,204);
        assertThat(request("PUT","/connectors/billing-outbox/resume",null).statusCode()).isEqualTo(202);state("RUNNING");
        var snapshotOrder=new ArrayList<UUID>();
        await().atMost(Duration.ofSeconds(60)).until(()->{
            for(var row:consumer.poll(Duration.ofMillis(500))){
                observe(row);var header=row.headers().lastHeader("id");
                if(header!=null){UUID transport=id(new String(header.value(),StandardCharsets.UTF_8));
                    if((transport.equals(older)||transport.equals(newer))&&!snapshotOrder.contains(transport))snapshotOrder.add(transport);}
            }
            return snapshotOrder.size()==2;
        });
        assertThat(snapshotOrder).as("retained events must keep per-aggregate publish sequence after a full resnapshot").containsExactly(older,newer);
        assertThat(fixture.db.rows("SELECT * FROM billing.erp_postings WHERE intent_id=?",id(first.key()))).hasSize(1);
        UUID fresh=capture();read(fresh);
        assertThat(fixture.db.rows("SELECT * FROM billing.erp_postings WHERE intent_id=?",fresh)).hasSize(1);
        assertThat(observedTransportIds).doesNotContain(rolledBack.toString());
    }
    void state(String required){await().atMost(Duration.ofSeconds(300)).ignoreExceptionsInstanceOf(java.io.IOException.class).pollInterval(Duration.ofSeconds(1)).until(()->{
        var response=request("GET","/connectors/billing-outbox/status",null);if(response.statusCode()!=200)return false;
        var json=new JSONObject(response.body());
        if("FAILED".equals(json.getJSONObject("connector").optString("state"))||json.getJSONArray("tasks").toList().stream().anyMatch(t->"FAILED".equals(((Map<?,?>)t).get("state"))))
            throw new AssertionError("Connector/task failed while awaiting "+required+": "+json);
        return required.equals(json.getJSONObject("connector").getString("state"))
            &&(required.equals("STOPPED")||json.getJSONArray("tasks").length()>0)&&json.getJSONArray("tasks").toList().stream().allMatch(t->required.equals(((Map<?,?>)t).get("state")));
    });}
    HttpResponse<String> request(String method,String path,String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(endpoint+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json")
            .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
