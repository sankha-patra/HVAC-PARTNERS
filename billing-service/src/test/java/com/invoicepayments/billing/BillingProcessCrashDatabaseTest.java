package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import com.sun.net.httpserver.*;
import org.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.invoicepayments.billing.BillingFinancialDatabaseTest.*;
import static com.invoicepayments.billing.BillingStore.*;
import static org.assertj.core.api.Assertions.*;

/** PostgreSQL and the parent-hosted fake Stripe state survive an actual application-process kill. */
@EnabledIfEnvironmentVariable(named="BILLING_TEST_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:15432/invoice_payments_test")
@Timeout(180)
class BillingProcessCrashDatabaseTest {
    BillingFinancialDatabaseTest fixture;
    @BeforeAll static void migrate() throws Exception{migrateOnlyIsolatedDatabase();}
    @BeforeEach void seed(){fixture=new BillingFinancialDatabaseTest();fixture.cleanAndSeedDedicatedDatabase();}
    @AfterEach void cleanup(){fixture.removeOnlyTestFaultTrigger();}
    @Test void killedApplicationRecoversOneSessionAndLaterPaidEvidencePostsOnce() throws Exception {
        try(var stripe=new SurvivingStripe()) {
            var provider=StripeCrashProviderClient.create(stripe.base());
            var payments=BillingTestWiring.payments(fixture.db,provider,new MockEnvironment(),false,"http://localhost:4200",MERCHANT.toString());
            UUID intent=id(payments.create(PAYER,ACCOUNT,UUID.randomUUID().toString(),List.of(INVOICE),"CARD").get("id"));
            var firstLog=Files.createTempFile("billing-killed-", ".log");
            Process first=child("submit",stripe.base(),intent,firstLog);
            try {
                assertThat(stripe.accepted.await(45,TimeUnit.SECONDS)).as("Stripe accepted Session creation; child log: %s",tail(firstLog)).isTrue();
                assertThat(fixture.db.one("SELECT status,provider_id FROM billing.intents WHERE id=?",intent))
                    .containsEntry("status","SUBMITTING").containsEntry("providerId",null);
                assertThat(fixture.db.rows("SELECT 1 FROM pg_stat_activity WHERE application_name='billing-crash-child' AND xact_start IS NOT NULL")).isEmpty();
                first.destroyForcibly();assertThat(first.waitFor(10,TimeUnit.SECONDS)).isTrue();
            } finally {if(first.isAlive())first.destroyForcibly();stripe.releaseReply.countDown();}
            String key=text(fixture.db.one("SELECT operation_key FROM billing.attempts WHERE intent_id=?",intent),"operationKey");
            fixture.db.update("UPDATE billing.intents SET lease_until=NOW()-INTERVAL '1 second',next_recovery_at=NOW()-INTERVAL '1 second' WHERE id=?",intent);
            var restartLog=Files.createTempFile("billing-restarted-", ".log");
            Process restarted=child("recover",stripe.base(),intent,restartLog);
            try {
                assertThat(restarted.waitFor(45,TimeUnit.SECONDS)).as("Recovery child exits: %s",tail(restartLog)).isTrue();
                assertThat(restarted.exitValue()).as(tail(restartLog)).isZero();
            }finally{if(restarted.isAlive())restarted.destroyForcibly();}
            assertThat(stripe.created).hasValue(1);assertThat(stripe.posts).hasValue(2);assertThat(stripe.sessions.keySet()).containsExactly(key);
            assertThat(fixture.db.one("SELECT status,provider_id FROM billing.intents WHERE id=?",intent))
                .containsEntry("status","REQUIRES_ACTION").containsEntry("providerId","cs_test_crash1");
            assertThat(fixture.db.rows("SELECT id FROM billing.journals WHERE intent_id=?",intent)).isEmpty();

            // Session recovery above proves no capture. Now provide paid evidence and race two recorders.
            stripe.paid=true;var paid=provider.lookup(key,"cs_test_crash1");assertThat(paid.state()).isEqualTo("CAPTURED");
            var refunds=BillingTestWiring.refunds(fixture.db,payments,provider);
            var inbox=BillingTestWiring.inbox(fixture.db,payments,refunds,MERCHANT.toString());
            inbox.receive("STRIPE",new ProviderWebhookEvent("evt_process_crash",paid.providerId(),intent.toString(),"CAPTURED",10000,"USD",null));
            var barrier=new CyclicBarrier(2);var threads=Executors.newFixedThreadPool(2);
            try {
                var result=threads.submit(()->{barrier.await(5,TimeUnit.SECONDS);payments.recordResult(intent,null,paid);return true;});
                var webhook=threads.submit(()->{barrier.await(5,TimeUnit.SECONDS);inbox.process("STRIPE","evt_process_crash");return true;});
                assertThat(result.get(20,TimeUnit.SECONDS)).isTrue();assertThat(webhook.get(20,TimeUnit.SECONDS)).isTrue();
            }finally{threads.shutdownNow();}
            assertThat(fixture.db.rows("SELECT id FROM billing.journals WHERE intent_id=?",intent)).hasSize(1);
            assertThat(fixture.db.rows("SELECT id FROM billing.outbox WHERE aggregate_id=? AND type='PAYMENT_CAPTURED'",intent)).hasSize(1);
            assertThat(fixture.db.one("SELECT paid_minor,reserved_minor FROM billing.invoices WHERE id=?",INVOICE))
                .containsEntry("paidMinor",10000L).containsEntry("reservedMinor",0L);
        }
    }
    static Process child(String mode,URI server,UUID intent,Path output) throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        return new ProcessBuilder(java,"-Xmx128m","-cp",classpath,BillingCrashChildMain.class.getName(),mode,server.toString(),intent.toString())
            .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    }
    static String tail(Path path) throws Exception {String value=Files.readString(path);return value.substring(Math.max(0,value.length()-2500));}

    static final class SurvivingStripe implements AutoCloseable {
        final HttpServer server;final ExecutorService executor;
        final ConcurrentMap<String,JSONObject> sessions=new ConcurrentHashMap<>();
        final AtomicInteger created=new AtomicInteger(),posts=new AtomicInteger();
        final CountDownLatch accepted=new CountDownLatch(1),releaseReply=new CountDownLatch(1);
        volatile boolean paid;
        SurvivingStripe() throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            executor=Executors.newCachedThreadPool(task->{var thread=new Thread(task,"fake-stripe-crash");thread.setDaemon(true);return thread;});
            server.setExecutor(executor);server.createContext("/",this::handle);server.start();
        }
        URI base(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort());}
        void handle(HttpExchange exchange) {
            try(exchange) {
                String path=exchange.getRequestURI().getPath();JSONObject response;
                if("POST".equals(exchange.getRequestMethod())&&path.equals("/v1/checkout/sessions")) {
                    var form=form(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                    String key=exchange.getRequestHeaders().getFirst("Idempotency-Key");
                    response=sessions.computeIfAbsent(key,ignored->{
                        int number=created.incrementAndGet();
                        return new JSONObject().put("object","checkout.session").put("id","cs_test_crash"+number).put("livemode",false)
                            .put("mode","payment").put("status","open").put("payment_status","unpaid").put("payment_intent",JSONObject.NULL)
                            .put("amount_total",Long.parseLong(form.get("line_items[0][price_data][unit_amount]"))).put("currency","usd")
                            .put("url","https://checkout.stripe.com/c/pay/crash"+number).put("payment_method_types",new JSONArray().put("card"))
                            .put("metadata",new JSONObject().put("intentId",form.get("metadata[intentId]")).put("idempotencyKey",form.get("metadata[idempotencyKey]")));
                    });
                    if(posts.incrementAndGet()==1){accepted.countDown();releaseReply.await(30,TimeUnit.SECONDS);}
                }else if(path.equals("/v1/checkout/sessions/cs_test_crash1")) {
                    response=new JSONObject(sessions.values().iterator().next().toString());
                    if(paid)response.put("status","complete").put("payment_status","paid").put("payment_intent","pi_crash1");
                }else if(path.equals("/v1/payment_intents/pi_crash1")) {
                    var session=sessions.values().iterator().next();
                    response=new JSONObject().put("object","payment_intent").put("id","pi_crash1").put("livemode",false)
                        .put("metadata",session.getJSONObject("metadata")).put("currency","usd").put("amount",10000).put("amount_received",10000)
                        .put("status","succeeded").put("payment_method_types",new JSONArray().put("card"));
                }else {exchange.sendResponseHeaders(404,-1);return;}
                byte[] body=response.toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();}
        }
        static Map<String,String> form(String body) {
            Map<String,String> form=new LinkedHashMap<>();for(String pair:body.split("&")){var parts=pair.split("=",2);form.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),URLDecoder.decode(parts.length==1?"":parts[1],StandardCharsets.UTF_8));}return form;
        }
        @Override public void close(){releaseReply.countDown();server.stop(0);executor.shutdownNow();}
    }
}
