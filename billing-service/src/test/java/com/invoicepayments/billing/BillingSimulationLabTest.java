package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static com.invoicepayments.billing.BillingStore.*;
import static com.invoicepayments.billing.provider.LocalPaymentLab.Fault.*;
import static org.assertj.core.api.Assertions.*;

/** Whole HTTP billing API + real PG + separately committed loopback external doubles. No live credentials or endpoints. */
@EnabledIfEnvironmentVariable(named="BILLING_LAB_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:15432/invoice_payments_simulation_test")
@SpringBootTest(classes={BillingApplication.class,BillingSimulationLabTest.LabBeans.class},
    webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "billing.workers-enabled=false","billing.kafka-enabled=false","billing.demo-enabled=false",
        "billing.erp-simulator-enabled=false","billing.simulator-enabled=true","billing.provider=SIMULATOR",
        "billing.public-url=http://localhost:4200","billing.stripe.secret-key=",
        "billing.stripe.webhook-secret=whsec_localLabOnly",
        "server.address=127.0.0.1","server.tomcat.threads.max=24","management.server.port=0",
        "management.server.address=127.0.0.1","management.tracing.enabled=false","management.health.redis.enabled=false",
        "spring.data.redis.repositories.enabled=false","spring.cache.type=none",
        "spring.cloud.discovery.enabled=false","eureka.client.enabled=false","spring.flyway.clean-disabled=true",
        "spring.datasource.hikari.maximum-pool-size=8","spring.datasource.hikari.minimum-idle=1",
        "spring.datasource.hikari.connection-timeout=3000","app.jwt.cookie-secure=false",
        "app.jwt.secret=local-lab-only-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ-strong-secret"
    })
@ActiveProfiles("billing")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(180)
class BillingSimulationLabTest {
    static final String JWT="local-lab-only-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ-strong-secret";
    static final UUID MERCHANT=UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final String PAYER="lab-payer",MAKER="lab-finance",CHECKER="lab-checker";
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry registry){
        assertThat(System.getenv("BILLING_LAB_JDBC_URL")).isEqualTo(LocalPaymentLab.URL);
        assertThat(System.getenv("BILLING_LAB_DB_USER")).isEqualTo("billing_test");
        assertThat(System.getenv("BILLING_LAB_DB_PASSWORD")).isNotBlank();
        registry.add("spring.datasource.url",()->LocalPaymentLab.URL);
        registry.add("spring.datasource.username",()->"billing_test");
        registry.add("spring.datasource.password",()->System.getenv("BILLING_LAB_DB_PASSWORD"));
    }
    @TestConfiguration(proxyBeanMethods=false) static class LabBeans {
        @Bean(destroyMethod="close") LocalPaymentLab lab() throws Exception {return new LocalPaymentLab();}
        @Bean @Primary BillingProvider networkLabProvider(LocalPaymentLab lab){return lab.provider();}
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource source;
    @Autowired BillingStore db;
    @Autowired BillingInbox inbox;
    @Autowired BillingRecovery recovery;
    @Autowired LocalPaymentLab lab;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    final JSONObject report=new JSONObject().put("synthetic",true).put("liveProviderOrBankOrErp",false)
        .put("scenarios",new JSONArray()).put("loadPhases",new JSONArray());
    record Fixture(UUID account,UUID invoice){}
    record Reply(int status,String body){JSONObject json(){return new JSONObject(body);}}

    @BeforeAll void prepareOnlyDedicatedLabDatabase() throws Exception {
        try(var connection=source.getConnection()){
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
            assertThat(jdbc.queryForObject("SELECT current_database()",String.class)).isEqualTo("invoice_payments_simulation_test");
            assertThat(jdbc.queryForObject("SELECT current_user",String.class)).isEqualTo("billing_test");
        }
        // Never point this destructive fixture reset at an app database. URL/user/name/version are checked above.
        jdbc.execute("TRUNCATE billing.merchants,billing.provider_operations,billing.outbox,billing.consumer_inbox RESTART IDENTITY CASCADE");
        lab.reset();
        jdbc.update("INSERT INTO billing.merchants(id,name) VALUES(?,'Synthetic lab merchant')",MERCHANT);
        jdbc.update("INSERT INTO billing.staff(merchant_id,user_id,role) VALUES(?,?,'FINANCE'),(?,?,'REFUND_APPROVER'),(?,?,'REFUND_APPROVER')",MERCHANT,MAKER,MERCHANT,MAKER,MERCHANT,CHECKER);
        report.put("startedAt",Instant.now()).put("javaVersion",System.getProperty("java.version")).put("os",System.getProperty("os.name"))
            .put("availableProcessors",Runtime.getRuntime().availableProcessors()).put("appDbPool",8).put("externalDbPool",8)
            .put("appHttpMaxThreads",24).put("externalHttpThreads",16).put("providerTransportTimeoutMs",800)
            .put("scope","Random-port billing HTTP server; real PostgreSQL 17; loopback external doubles with separate commits; test-only Stripe transport relay; no Kafka in this lab")
            .put("limitations",new JSONArray(List.of("Same machine and PostgreSQL failure domain","Synthetic contracts do not prove vendor conformance","Bearer identities injected for test; no identity/gateway/browser in load","Workers disabled; recovery driven explicitly","ERP relay is test-only; existing Kafka integration is separate","No production capacity, availability, clearing-time, RPO or RTO claim")));
    }
    @BeforeEach void normalFaults(){lab.erpFault=NORMAL;lab.bankOutage=false;lab.latencyMs=0;}
    @AfterAll void writeEvidence() throws Exception {
        report.put("finishedAt",Instant.now());
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target","billing-lab-report.json"),report.toString(2),StandardCharsets.UTF_8);
    }

    @Test @Order(1) void acceptedPaymentWithLostResponseRecoversSameOperationAndDeduplicatesSignedEvidence() throws Exception {
        Fixture f=fixture();UUID intent=create(f,"CARD");lab.plan(intent,DISCONNECT_AFTER_ACCEPT);
        int before=lab.paymentPosts.get();assertThat(command("/intents/"+intent+"/submit",new JSONObject(),PAYER).getString("status")).isEqualTo("UNKNOWN");
        assertThat(lab.paymentCount(intent)).isEqualTo(1);assertMoney(f,0,12525);
        recover(intent);assertThat(lab.paymentPosts.get()).isEqualTo(before+2);
        int postsAfterRecovery=lab.paymentPosts.get();recover(intent);assertThat(lab.paymentPosts.get()).isEqualTo(postsAfterRecovery+1);
        assertThat(lab.paymentCount(intent)).isEqualTo(1);assertMoney(f,0,12525);
        // Restore responses and restart the listener without erasing provider evidence.
        lab.plan(intent,NORMAL);lab.restartHttp();recover(intent);
        assertThat(lab.paymentCount(intent)).isEqualTo(1);assertMoney(f,12525,0);
        int postsAfterConfirmation=lab.paymentPosts.get();
        assertThat(command("/intents/"+intent+"/submit",new JSONObject(),PAYER).getString("status")).isEqualTo("CAPTURED");
        assertThat(lab.paymentPosts.get()).isEqualTo(postsAfterConfirmation);
        String event="evt_"+UUID.randomUUID().toString().replace("-","");JSONObject evidence=lab.snapshot(intent);
        webhook(event,"checkout.session.completed",evidence,false,400);
        assertThat(count("SELECT count(*) FROM billing.webhook_inbox WHERE event_id=?",event)).isZero();
        webhook(event,"checkout.session.completed",evidence,true,202);webhook(event,"checkout.session.completed",evidence,true,202);
        webhook(event,"checkout.session.completed",new JSONObject(evidence.toString()).put("amount_total",12526),true,409);
        inbox.process("STRIPE",event);inbox.process("STRIPE",event);assertMoney(f,12525,0);
        String stale="evt_"+UUID.randomUUID().toString().replace("-","");
        webhook(stale,"checkout.session.completed",new JSONObject(evidence.toString()).put("payment_status","unpaid"),true,202);inbox.process("STRIPE",stale);
        assertThat(count("SELECT count(*) FROM billing.journals WHERE effect_key=?","capture:"+intent)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM billing.webhook_inbox WHERE event_id=?",event)).isEqualTo(1);
        assertMoney(f,12525,0);passed("Accepted then socket closed; bounded saved-request replay after listener restart retains one provider payment; invalid signature denied; duplicate/reordered signed delivery has one effect");
    }

    @Test @Order(2) void timeoutRateLimitAndOutageRemainUncertainWithoutReleasingReservations() throws Exception {
        for(var fault:List.of(TIMEOUT_AFTER_ACCEPT,RATE_LIMIT,UNAVAILABLE,WRONG_AMOUNT)){
            Fixture f=fixture();UUID intent=create(f,"CARD");lab.plan(intent,fault);
            assertThat(command("/intents/"+intent+"/submit",new JSONObject(),PAYER).getString("status")).isEqualTo("UNKNOWN");
            assertMoney(f,0,12525);assertThat(count("SELECT count(*) FROM billing.journals WHERE intent_id=?",intent)).isZero();
            assertThat(lab.paymentCount(intent)).isEqualTo(fault==RATE_LIMIT||fault==UNAVAILABLE?0:1);
            int posts=lab.paymentPosts.get();recover(intent);assertThat(lab.paymentPosts.get()).isEqualTo(posts+1);
            assertThat(lab.paymentCount(intent)).isEqualTo(fault==RATE_LIMIT||fault==UNAVAILABLE?0:1);
            assertMoney(f,0,12525);assertThat(count("SELECT count(*) FROM billing.journals WHERE intent_id=?",intent)).isZero();
        }
        passed("Socket timeout after independent commit, HTTP 429/503 and corrupt amount keep unknown outcomes and reservations");
    }

    @Test @Order(3) void delayedAchAndProviderOutageRecoverThroughOriginalLookup() throws Exception {
        Fixture f=fixture();UUID intent=create(f,"ACH");lab.plan(intent,PENDING);
        assertThat(command("/intents/"+intent+"/submit",new JSONObject(),PAYER).getString("status")).isEqualTo("PROCESSING");
        assertMoney(f,0,12525);lab.plan(intent,UNAVAILABLE);recover(intent);assertMoney(f,0,12525);
        lab.restartHttp();lab.complete(intent);recover(intent);assertMoney(f,12525,0);assertThat(lab.paymentCount(intent)).isEqualTo(1);
        passed("Asynchronous ACH remains processing; lookup outage and external HTTP listener restart preserve evidence; original lookup captures after recovery");
    }

    @Test @Order(4) void refundAcceptedWithLostAcknowledgementNeedsIndependentApprovalAndSignedRecovery() throws Exception {
        Fixture f=fixture();UUID intent=create(f,"CARD");command("/intents/"+intent+"/submit",new JSONObject(),PAYER);
        JSONObject refund=command("/finance/intents/"+intent+"/refunds",new JSONObject().put("amountMinor",2500).put("reason","Synthetic adjustment"),MAKER);
        UUID refundId=id(refund.get("id"));
        Reply selfApproval=send("POST","/finance/refunds/"+refundId+"/approve",new JSONObject(),MAKER,null);
        assertThat(selfApproval.status).isEqualTo(403);assertThat(selfApproval.json().getString("code")).isEqualTo("SEPARATION_OF_DUTIES");
        assertThat(lab.refundCount(refundId)).isZero();lab.plan(intent,DISCONNECT_AFTER_ACCEPT);
        assertThat(command("/finance/refunds/"+refundId+"/approve",new JSONObject(),CHECKER).getString("status")).isEqualTo("UNKNOWN");
        assertThat(lab.refundCount(refundId)).isEqualTo(1);assertMoney(f,12525,0);
        String event="evt_"+UUID.randomUUID().toString().replace("-","");
        webhook(event,"refund.updated",lab.refundSnapshot(refundId),true,202);inbox.process("STRIPE",event);
        webhook(event,"refund.updated",lab.refundSnapshot(refundId),true,202);inbox.process("STRIPE",event);
        assertMoney(f,10025,0);assertThat(count("SELECT count(*) FROM billing.journals WHERE effect_key=?","refund:"+refundId)).isEqualTo(1);
        passed("Refund maker denied self-approval; external refund commits before lost ACK; duplicate signed recovery posts once");
    }

    @Test @Order(5) void bankOutageDelayedCreditAndMismatchCannotInventCash() throws Exception {
        Fixture f=fixture();UUID intent=create(f,"CARD");command("/intents/"+intent+"/submit",new JSONObject(),PAYER);
        lab.createBankEvidence(intent,125);lab.bankOutage=true;
        assertThat(external("GET","/bank/reports/"+intent,null).status).isEqualTo(503);lab.bankOutage=false;
        JSONObject evidence=external("GET","/bank/reports/"+intent,null).json().put("merchantId",MERCHANT);
        UUID settlement=id(command("/finance/reconciliation/import",evidence,MAKER).get("id"));
        assertThat(external("GET","/bank/credits/"+intent,null).status).isEqualTo(404);assertBankCash(settlement,0);
        lab.bankCredit(intent,true,12399);Reply wrong=external("GET","/bank/credits/"+intent,null);
        JSONObject credit=wrong.json().put("evidenceHash",hash(wrong.body));
        Reply selfCheck=send("POST","/finance/settlements/"+settlement+"/bank-confirmation",credit,MAKER,null);
        assertThat(selfCheck.status).isEqualTo(403);assertThat(selfCheck.json().getString("code")).isEqualTo("SEPARATION_OF_DUTIES");
        assertThat(command("/finance/settlements/"+settlement+"/bank-confirmation",credit,CHECKER).getString("status")).isEqualTo("EXCEPTION");assertBankCash(settlement,0);
        lab.bankCredit(intent,true,12400);Reply exact=external("GET","/bank/credits/"+intent,null);
        JSONObject confirmed=exact.json().put("evidenceHash",hash(exact.body));
        assertThat(command("/finance/settlements/"+settlement+"/bank-confirmation",confirmed,CHECKER).getString("status")).isEqualTo("MATCHED");
        command("/finance/settlements/"+settlement+"/bank-confirmation",confirmed,CHECKER);assertBankCash(settlement,12400);
        passed("Bank outage, unavailable credit and amount mismatch do not book cash; independent exact evidence and duplicate confirmation post once");
    }

    @Test @Order(6) void externalErpOutageLostAckAndWrongAckDoNotCreateFalseLocalAcknowledgements() throws Exception {
        Fixture f=fixture();UUID intent=create(f,"CARD");command("/intents/"+intent+"/submit",new JSONObject(),PAYER);
        JSONObject event=captureEvent(intent);UUID eventId=id(event.get("eventId"));
        for(var fault:List.of(UNAVAILABLE,DISCONNECT_AFTER_ACCEPT,WRONG_ACK)){
            lab.erpFault=fault;assertThat(relay(event)).isFalse();assertThat(count("SELECT count(*) FROM billing.erp_postings WHERE event_id=?",eventId)).isZero();
        }
        assertThat(lab.erpCount(eventId)).isEqualTo(1);lab.erpFault=NORMAL;assertThat(relay(event)).isTrue();assertThat(relay(event)).isTrue();
        assertThat(lab.erpCount(eventId)).isEqualTo(1);assertThat(count("SELECT count(*) FROM billing.erp_postings WHERE event_id=?",eventId)).isEqualTo(1);
        JSONObject changed=new JSONObject(event.toString()).put("amountMinor",1);
        assertThat(external("POST","/erp/postings",changed).status).isEqualTo(409);
        passed("External ERP outage, lost committed ACK, corrupt ACK and altered duplicate; retried event has one remote/local effect");
    }

    @Test @Order(7) void boundedArrivalLoadMeasuresDropsLatencyAndErpBacklogDrain() throws Exception {
        int steady=setting("billing.lab.steadyCount",40,10,300),burst=setting("billing.lab.burstCount",120,40,500);
        int workers=setting("billing.lab.concurrency",8,2,16),queue=setting("billing.lab.queueCapacity",16,1,64);
        Set<UUID> captured=ConcurrentHashMap.newKeySet();
        JSONObject normal=phase("steady",steady,setting("billing.lab.steadyRate",10,1,30),workers,queue,5,captured);
        JSONObject overload=phase("overload",burst,setting("billing.lab.burstRate",500,200,1000),workers,queue,100,captured);
        assertThat(normal.getInt("successful")).isPositive();assertThat(overload.getInt("droppedBeforeStart")).isPositive();
        assertThat(normal.getInt("failed")).isZero();assertThat(overload.getInt("failed")).isZero();
        lab.latencyMs=0;lab.erpFault=NORMAL;
        long drainStart=System.nanoTime();int before=0;
        for(UUID intent:captured){JSONObject event=captureEvent(intent);if(count("SELECT count(*) FROM billing.erp_postings WHERE event_id=?",id(event.get("eventId")))==0)before++;
            assertThat(relay(event)).isTrue();assertThat(relay(event)).isTrue();
            assertThat(lab.paymentCount(intent)).isEqualTo(1);assertThat(count("SELECT count(*) FROM billing.journals WHERE effect_key=?","capture:"+intent)).isEqualTo(1);
            assertThat(lab.erpCount(id(event.get("eventId")))).isEqualTo(1);
        }
        int after=0;for(UUID intent:captured)if(count("SELECT count(*) FROM billing.erp_postings WHERE intent_id=?",intent)==0)after++;
        assertThat(after).isZero();
        assertThat(count("SELECT count(*) FROM (SELECT journal_id FROM billing.ledger_entries GROUP BY journal_id HAVING SUM(CASE WHEN side='DEBIT' THEN amount_minor ELSE -amount_minor END)<>0) bad")).isZero();
        report.put("erpBacklog",new JSONObject().put("unacknowledgedBefore",before).put("unacknowledgedAfter",after)
            .put("drainMs",millis(System.nanoTime()-drainStart)).put("duplicateEachEvent",true).put("path","Test-only HTTP relay; Kafka outbox stays unpublished in this lab"));
        passed("Open-arrival steady/overload measurements; bounded admission; all offered work accounted; unique captures, balanced journals, ERP drain and duplicate replay");
    }

    private JSONObject phase(String name,int offered,int rate,int workers,int capacity,int remoteDelay,Set<UUID> captured) throws Exception {
        List<Fixture> fixtures=new ArrayList<>();for(int i=0;i<offered;i++)fixtures.add(fixture()); // Provisioning excluded from timing.
        lab.latencyMs=remoteDelay;
        var pool=new ThreadPoolExecutor(workers,workers,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(capacity),new ThreadPoolExecutor.AbortPolicy());pool.prestartAllCoreThreads();
        AtomicInteger submitted=new AtomicInteger(),completed=new AtomicInteger(),successful=new AtomicInteger(),failed=new AtomicInteger(),dropped=new AtomicInteger();
        Queue<Double> workflowTimes=new ConcurrentLinkedQueue<>(),apiTimes=new ConcurrentLinkedQueue<>(),schedulerLag=new ConcurrentLinkedQueue<>();
        Map<String,AtomicInteger> statuses=new ConcurrentHashMap<>();
        long start=System.nanoTime();
        try {
            for(int index=0;index<offered;index++){
                long planned=start+index*1_000_000_000L/rate;
                while(System.nanoTime()<planned)LockSupport.parkNanos(Math.min(planned-System.nanoTime(),1_000_000L));
                schedulerLag.add(millis(System.nanoTime()-planned));Fixture f=fixtures.get(index);
                try{pool.execute(()->{
                    try{
                        Reply created=measured("/accounts/"+f.account+"/intents",new JSONObject().put("invoiceIds",new JSONArray().put(f.invoice)).put("method","CARD"),apiTimes,statuses);
                        if(created.status!=200)throw new IllegalStateException("create status");UUID intent=id(created.json().get("id"));
                        Reply result=measured("/intents/"+intent+"/submit",new JSONObject(),apiTimes,statuses);
                        if(result.status!=200||!"CAPTURED".equals(result.json().optString("status")))throw new IllegalStateException("capture not definitive");
                        captured.add(intent);successful.incrementAndGet();
                    }catch(Exception error){failed.incrementAndGet();}
                    finally{workflowTimes.add(millis(System.nanoTime()-planned));completed.incrementAndGet();}
                });submitted.incrementAndGet();}catch(RejectedExecutionException rejected){dropped.incrementAndGet();}
            }
        } finally {pool.shutdown();if(!pool.awaitTermination(90,TimeUnit.SECONDS)){pool.shutdownNow();throw new IllegalStateException("Lab load exceeded bounded duration");}}
        double elapsed=millis(System.nanoTime()-start);
        JSONObject result=new JSONObject().put("name",name).put("offered",offered).put("targetWorkflowsPerSecond",rate)
            .put("workerConcurrency",workers).put("queueCapacity",capacity).put("providerDelayPerHttpRequestMs",remoteDelay)
            .put("submitted",submitted.get()).put("completed",completed.get()).put("successful",successful.get()).put("failed",failed.get())
            .put("droppedBeforeStart",dropped.get()).put("elapsedMs",elapsed).put("successfulWorkflowsPerSecond",successful.get()*1000.0/elapsed)
            .put("workflowLatencyIncludingQueueMs",percentiles(workflowTimes)).put("apiRequestLatencyMs",percentiles(apiTimes))
            .put("arrivalSchedulerLagMs",percentiles(schedulerLag)).put("httpStatuses",new JSONObject(statuses.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,e->e.getValue().get()))))
            .put("measurementScope","Two HTTP API commands per admitted workflow; test bearer auth; actual Stripe adapter through 800ms loopback test transport; unique account/invoice per workflow; no browser/gateway/Kafka/identity load");
        report.getJSONArray("loadPhases").put(result);
        assertThat(submitted.get()+dropped.get()).isEqualTo(offered);assertThat(completed.get()).isEqualTo(submitted.get());
        assertThat(successful.get()+failed.get()).isEqualTo(completed.get());return result;
    }
    private Reply measured(String path,JSONObject body,Queue<Double> times,Map<String,AtomicInteger> statuses) throws Exception {
        long start=System.nanoTime();try{Reply result=send("POST",path,body,PAYER,UUID.randomUUID().toString());statuses.computeIfAbsent(Integer.toString(result.status),key->new AtomicInteger()).incrementAndGet();return result;}
        catch(Exception error){statuses.computeIfAbsent("transport_exception",key->new AtomicInteger()).incrementAndGet();throw error;}
        finally{times.add(millis(System.nanoTime()-start));}
    }
    private static JSONObject percentiles(Collection<Double> values){List<Double> sorted=values.stream().sorted().toList();JSONObject result=new JSONObject().put("samples",sorted.size());if(!sorted.isEmpty())for(int p:List.of(50,95,99))result.put("p"+p,sorted.get(Math.max(0,(int)Math.ceil(sorted.size()*p/100.0)-1)));return result;}
    private static int setting(String name,int fallback,int min,int max){int value=Integer.getInteger(name,fallback);if(value<min||value>max)throw new IllegalArgumentException(name+" is outside bounded lab limits");return value;}
    private static double millis(long nanos){return nanos/1_000_000.0;}
    private void passed(String name){report.getJSONArray("scenarios").put(new JSONObject().put("scenario",name).put("passed",true));}
    private Fixture fixture(){UUID account=UUID.randomUUID(),invoice=UUID.randomUUID();jdbc.update("INSERT INTO billing.accounts(id,merchant_id,name,external_reference) VALUES(?,?,'Synthetic customer',?)",account,MERCHANT,"LAB-"+account);jdbc.update("INSERT INTO billing.memberships(account_id,user_id,role) VALUES(?,?,'PAYER')",account,PAYER);jdbc.update("INSERT INTO billing.invoices(id,account_id,number,currency,total_minor,due_date) VALUES(?,?,?,'USD',12525,CURRENT_DATE)",invoice,account,"LAB-"+invoice);return new Fixture(account,invoice);}
    private UUID create(Fixture f,String method) throws Exception {return id(command("/accounts/"+f.account+"/intents",new JSONObject().put("invoiceIds",new JSONArray().put(f.invoice)).put("method",method),PAYER).get("id"));}
    private JSONObject command(String path,JSONObject body,String user) throws Exception {Reply response=send("POST",path,body,user,UUID.randomUUID().toString());assertThat(response.status).as(path+" "+response.body).isEqualTo(200);return response.json();}
    private Reply send(String method,String path,JSONObject body,String user,String key) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/billing"+path)).timeout(Duration.ofSeconds(8)).header("Content-Type","application/json");
        if(user!=null)builder.header("Authorization","Bearer "+token(user));if(key!=null)builder.header("Idempotency-Key",key);
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body.toString()));
        var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());return new Reply(response.statusCode(),response.body());
    }
    private Reply external(String method,String path,JSONObject body) throws Exception {var request=HttpRequest.newBuilder(lab.origin().resolve(path)).timeout(Duration.ofMillis(800)).header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body.toString())).build();var response=http.send(request,HttpResponse.BodyHandlers.ofString());return new Reply(response.statusCode(),response.body());}
    private String token(String user){return Jwts.builder().subject(user).claim("role","USER").issuedAt(Date.from(Instant.now().minusSeconds(5))).expiration(Date.from(Instant.now().plusSeconds(3600))).signWith(Keys.hmacShaKeyFor(JWT.getBytes(StandardCharsets.UTF_8))).compact();}
    private void recover(UUID intent){jdbc.update("UPDATE billing.intents SET lease_until=NULL,next_recovery_at=NOW()-INTERVAL '1 second' WHERE id=?",intent);var work=recovery.claimPayments(20).stream().filter(row->intent.equals(id(row.get("id")))).findFirst().orElseThrow();recovery.recoverPayment(work);}
    private void webhook(String event,String type,JSONObject object,boolean valid,int expected) throws Exception {
        byte[] raw=new JSONObject().put("id",event).put("object","event").put("livemode",false).put("type",type).put("data",new JSONObject().put("object",object)).toString().getBytes(StandardCharsets.UTF_8);
        String time=Long.toString(Instant.now().getEpochSecond());Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec("whsec_localLabOnly".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));mac.update((time+".").getBytes(StandardCharsets.US_ASCII));String signature=HexFormat.of().formatHex(mac.doFinal(raw));
        var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/billing/webhooks/stripe")).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json").header("Stripe-Signature","t="+time+",v1="+(valid?signature:"0".repeat(64))).POST(HttpRequest.BodyPublishers.ofByteArray(raw)).build(),HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isEqualTo(expected);
    }
    private JSONObject captureEvent(UUID intent){return new JSONObject(jdbc.queryForObject("SELECT payload::text FROM billing.outbox WHERE aggregate_id=? AND type='PAYMENT_CAPTURED'",String.class,intent));}
    private boolean relay(JSONObject event){try{Reply response=external("POST","/erp/postings",event);if(response.status!=200)return false;JSONObject ack=response.json();if(!event.getString("eventId").equals(ack.optString("eventId"))||event.getLong("amountMinor")!=ack.optLong("amountMinor")||!"ACKNOWLEDGED".equals(ack.optString("status")))return false;new BillingEvents(db,MERCHANT.toString()).applyErp(event.toString().getBytes(StandardCharsets.UTF_8));return true;}catch(Exception failure){return false;}}
    private void assertMoney(Fixture f,long paid,long reserved){assertThat(db.one("SELECT paid_minor,reserved_minor FROM billing.invoices WHERE id=?",f.invoice)).containsEntry("paidMinor",paid).containsEntry("reservedMinor",reserved);}
    private void assertBankCash(UUID settlement,long expected){assertThat(count("SELECT COALESCE(sum(e.amount_minor),0) FROM billing.ledger_entries e JOIN billing.journals j ON j.id=e.journal_id WHERE j.effect_key=? AND e.account='BANK_CASH'","settlement:"+settlement)).isEqualTo(expected);}
    private long count(String sql,Object... args){return Objects.requireNonNull(jdbc.queryForObject(sql,Long.class,args));}
}
