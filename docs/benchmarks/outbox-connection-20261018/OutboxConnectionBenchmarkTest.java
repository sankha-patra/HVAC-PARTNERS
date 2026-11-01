package com.invoicepayments.billing;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.invoicepayments.billing.BillingStore.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Resource-isolation experiment, NOT a payment-throughput or real-Kafka benchmark. */
class OutboxConnectionBenchmarkTest {
    static final int WORKERS=8, EVENTS=64, ACK_MS=200, ROUNDS=6;
    static final String URL="jdbc:postgresql://127.0.0.1:15432/invoice_payments_test";
    static final String PAYLOAD=new JSONObject().put("eventType","BENCHMARK").put("padding","x".repeat(512)).toString();
    static final ThreadLocal<Sample> ACTIVE=new ThreadLocal<>();
    enum Mode { INSIDE_TX_CONTROL, CURRENT_OUTSIDE_TX }
    final AtomicInteger sends=new AtomicInteger();
    final List<Sample> all=new ArrayList<>();
    ScheduledExecutorService clock;
    ExecutorService threads;
    HikariDataSource pool;
    BillingStore db;
    BillingWorkers worker;
    KafkaTemplate<String,byte[]> kafka;
    byte[] expectedPayload;
    volatile CompletableFuture<SendResult<String,byte[]>> gate;
    volatile CountDownLatch entered;

    @Test @Timeout(value=180,unit=TimeUnit.SECONDS)
    @SuppressWarnings({"unchecked","rawtypes"})
    void compareConnectionIsolation() throws Exception {
        assertThat(System.getenv("BILLING_TEST_JDBC_URL")).isEqualTo(URL);
        assertThat(System.getenv("BILLING_TEST_DB_USER")).isEqualTo("billing_test");
        JSONObject report=new JSONObject().put("startedAt",Instant.now().toString()).put("configuration",configuration());
        JSONArray rounds=new JSONArray(), probes=new JSONArray();
        try {
            pool=new HikariDataSource();pool.setJdbcUrl(URL);pool.setUsername("billing_test");
            pool.setPassword(System.getenv("BILLING_TEST_DB_PASSWORD"));pool.setMaximumPoolSize(WORKERS);
            pool.setMinimumIdle(WORKERS);pool.setConnectionTimeout(500);pool.setPoolName("isolated-outbox-benchmark");
            try(Connection c=pool.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT current_database(),current_user")) {
                assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
                assertThat(c.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
                assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("invoice_payments_test");
                assertThat(r.getString(2)).isEqualTo("billing_test");
                report.put("databaseVersion",c.getMetaData().getDatabaseProductVersion());
            }
            Flyway.configure().dataSource(pool).schemas("billing").defaultSchema("billing")
                .locations("classpath:db/billing").cleanDisabled(true).load().migrate();
            List<Connection> warm=new ArrayList<>();
            try {for(int i=0;i<WORKERS;i++) warm.add(pool.getConnection());}
            finally {for(Connection c:warm)c.close();}
            MeasuredDataSource measured=new MeasuredDataSource(pool);
            db=new BillingStore(new JdbcTemplate(measured),new DataSourceTransactionManager(measured));
            expectedPayload=db.jdbc.queryForObject("SELECT ?::jsonb::text",String.class,PAYLOAD).getBytes(StandardCharsets.UTF_8);
            report.getJSONObject("configuration").put("publishedPayloadUtf8Bytes",expectedPayload.length);
            threads=Executors.newFixedThreadPool(WORKERS);clock=Executors.newScheduledThreadPool(WORKERS);
            kafka=mock(KafkaTemplate.class);
            when(kafka.send(anyString(),anyString(),any(byte[].class))).thenAnswer(call -> {
                assertThat((byte[])call.getArgument(2)).isEqualTo(expectedPayload);
                sends.incrementAndGet();Sample sample=ACTIVE.get();sample.sendInsideTransaction=
                    org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
                if(gate!=null) {entered.countDown();return gate;}
                TimedAck ack=new TimedAck(sample);long dispatched=System.nanoTime();
                clock.schedule(()->{sample.ackCompletionMs=ms(System.nanoTime()-dispatched);ack.complete(null);},ACK_MS,TimeUnit.MILLISECONDS);
                return ack;
            });
            var beans=new DefaultListableBeanFactory();beans.registerSingleton("benchmarkKafka",kafka);
            worker=new BillingWorkers(db,null,null,(ObjectProvider)beans.getBeanProvider(KafkaTemplate.class));
            for(int round=0;round<2;round++)for(Mode mode:order(round))runRound(mode,round,16,false);
            for(int round=0;round<ROUNDS;round++)for(Mode mode:order(round))rounds.put(runRound(mode,round,EVENTS,true));
            for(boolean fail:new boolean[]{false,true})for(Mode mode:Mode.values())probes.put(runProbe(mode,fail));
            JSONObject summaries=new JSONObject();
            for(Mode mode:Mode.values())summaries.put(mode.name(),summarize(all.stream().filter(s->s.mode==mode).toList()));
            report.put("rounds",rounds).put("summary",summaries).put("contentionChecks",probes)
                .put("completedAt",Instant.now().toString()).put("passed",true);
            Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target/outbox-benchmark-report.json"),report.toString(2));
            System.out.println("OUTBOX_BENCHMARK_SUMMARY "+summaries);
        } finally {
            if(gate!=null)gate.completeExceptionally(new TimeoutException("benchmark cleanup"));
            if(threads!=null){threads.shutdownNow();threads.awaitTermination(10,TimeUnit.SECONDS);}
            if(clock!=null){clock.shutdownNow();clock.awaitTermination(10,TimeUnit.SECONDS);}
            if(worker!=null)worker.close();if(pool!=null)pool.close();
        }
    }

    JSONObject runRound(Mode mode,int round,int count,boolean measured) throws Exception {
        reset();int before=sends.get();List<Sample> samples=new ArrayList<>();
        for(int offset=0;offset<count;offset+=WORKERS) {
            var claims=seedAndClaim();CountDownLatch start=new CountDownLatch(1);List<Future<Sample>> pending=new ArrayList<>();
            for(var claim:claims)pending.add(threads.submit(()->{start.await();return publish(mode,round,claim);}));
            start.countDown();for(var future:pending)samples.add(future.get(15,TimeUnit.SECONDS));
        }
        assertThat(sends.get()-before).isEqualTo(count);assertStates(count,"PUBLISHED");
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        for(Sample sample:samples) {
            assertThat(sample.sendInsideTransaction).isEqualTo(mode==Mode.INSIDE_TX_CONTROL);
            assertThat(sample.holdMs).hasSize(mode==Mode.INSIDE_TX_CONTROL?1:2);
            assertThat(sample.ackCompletionMs).isGreaterThanOrEqualTo(ACK_MS-1.0);
            assertThat(sample.ackWaitMs).isGreaterThanOrEqualTo(0);
        }
        if(measured)all.addAll(samples);
        return new JSONObject().put("round",round+1).put("mode",mode.name()).put("events",count)
            .put("sends",sends.get()-before).put("published",count).put("samples",new JSONArray(samples.stream().map(Sample::json).toList()))
            .put("summary",summarize(samples));
    }

    JSONObject runProbe(Mode mode,boolean fail) throws Exception {
        reset();var claims=seedAndClaim();gate=new CompletableFuture<>();entered=new CountDownLatch(WORKERS);
        int before=sends.get();List<Future<Sample>> pending=new ArrayList<>();
        try {
            for(var claim:claims)pending.add(threads.submit(()->publish(mode,-1,claim)));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            int active=pool.getHikariPoolMXBean().getActiveConnections();
            assertThat(active).isEqualTo(mode==Mode.INSIDE_TX_CONTROL?WORKERS:0);
            long began=System.nanoTime();boolean succeeded=false;String error="";
            try(Connection c=pool.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT 1")) {
                assertThat(r.next()).isTrue();assertThat(r.getInt(1)).isEqualTo(1);succeeded=true;
            } catch(SQLTransientConnectionException expected) {error=expected.getClass().getSimpleName();}
            double elapsed=ms(System.nanoTime()-began);
            assertThat(succeeded).isEqualTo(mode==Mode.CURRENT_OUTSIDE_TX);
            if(fail)gate.completeExceptionally(new TimeoutException("controlled transient broker failure"));else gate.complete(null);
            for(var future:pending)future.get(5,TimeUnit.SECONDS);
            assertThat(sends.get()-before).isEqualTo(WORKERS);assertStates(WORKERS,fail?"NEW":"PUBLISHED");
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            return new JSONObject().put("mode",mode.name()).put("ackOutcome",fail?"TRANSIENT_TIMEOUT":"SUCCESS")
                .put("waitingSends",WORKERS).put("connectionsHeldDuringWait",active).put("probeSucceeded",succeeded)
                .put("probeElapsedMs",elapsed).put("probeException",error).put("connectionTimeoutMs",500)
                .put("finalState",fail?"NEW":"PUBLISHED").put("finalStateCount",WORKERS).put("connectionsHeldAfterCompletion",0);
        } finally {gate.completeExceptionally(new TimeoutException("probe cleanup"));gate=null;entered=null;}
    }

    Sample publish(Mode mode,int round,Map<String,Object> claim) {
        Sample sample=new Sample(mode,round,text(claim,"id"));ACTIVE.set(sample);long begin=System.nanoTime();
        try {if(mode==Mode.CURRENT_OUTSIDE_TX)worker.publishOne(claim);else insideTransactionControl(claim);return sample;}
        finally {sample.publishElapsedMs=ms(System.nanoTime()-begin);ACTIVE.remove();}
    }

    // Reconstructed anti-pattern. Same ownership predicate, payload and completion method,
    // but row ownership and the Kafka wait share one transaction. This is not a historical commit.
    void insideTransactionControl(Map<String,Object> claim) {
        try {
            db.tx(()->{
                var owned=db.rows("SELECT id,aggregate_id,payload::text AS payload FROM billing.outbox WHERE id=? AND lease_token=? AND state='IN_FLIGHT' AND lease_until>NOW() FOR UPDATE",id(claim.get("id")),id(claim.get("leaseToken")));
                assertThat(owned).hasSize(1);var current=owned.get(0);
                try {kafka.send("billing.events.v1",text(current,"aggregateId"),text(current,"payload").getBytes(StandardCharsets.UTF_8)).get(12,TimeUnit.SECONDS);}
                catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new CompletionException(e);}
                worker.markPublished(id(claim.get("id")),id(claim.get("leaseToken")));return null;
            });
        } catch(CompletionException failure) {
            Throwable cause=failure;while(cause.getCause()!=null)cause=cause.getCause();
            if(!(cause instanceof TimeoutException))throw failure;
            db.tx(()->{
                var rows=db.rows("SELECT id FROM billing.outbox WHERE id=? AND lease_token=? AND state='IN_FLIGHT' AND lease_until>NOW() FOR UPDATE",id(claim.get("id")),id(claim.get("leaseToken")));
                assertThat(rows).hasSize(1);
                db.update("UPDATE billing.outbox SET state='NEW',lease_token=NULL,lease_until=NULL,next_attempt_at=NOW()+INTERVAL '10 seconds' WHERE id=?",id(claim.get("id")));return null;
            });
        }
    }

    void reset(){db.jdbc.execute("TRUNCATE billing.dead_letters,billing.outbox RESTART IDENTITY");}
    List<Map<String,Object>> seedAndClaim() {
        for(int i=0;i<WORKERS;i++)db.update("INSERT INTO billing.outbox(id,aggregate_id,type,payload) VALUES(?,?,'BENCHMARK',?::jsonb)",UUID.randomUUID(),UUID.randomUUID(),PAYLOAD);
        var claims=worker.claimOutbox(WORKERS);assertThat(claims).hasSize(WORKERS);return claims;
    }
    void assertStates(int count,String state) {
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM billing.outbox",Integer.class)).isEqualTo(count);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM billing.outbox WHERE state=? AND lease_token IS NULL AND lease_until IS NULL",Integer.class,state)).isEqualTo(count);
    }
    static Mode[] order(int round){return round%2==0?Mode.values():new Mode[]{Mode.CURRENT_OUTSIDE_TX,Mode.INSIDE_TX_CONTROL};}
    static double ms(long ns){return ns/1_000_000.0;}
    static JSONObject stats(List<Double> values) {
        double[] a=values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return new JSONObject().put("count",a.length).put("mean",Arrays.stream(a).average().orElse(0))
            .put("median",a.length%2==0?(a[a.length/2-1]+a[a.length/2])/2:a[a.length/2])
            .put("p95",a[(int)Math.ceil(a.length*.95)-1]).put("min",a[0]).put("max",a[a.length-1]);
    }
    static JSONObject summarize(List<Sample> samples) {
        return new JSONObject().put("connectionHoldMs",stats(samples.stream().flatMap(s->s.holdMs.stream()).toList()))
            .put("connectionMsPerEvent",stats(samples.stream().map(s->s.holdMs.stream().mapToDouble(Double::doubleValue).sum()).toList()))
            .put("connectionAcquisitionMs",stats(samples.stream().flatMap(s->s.acquireMs.stream()).toList()))
            .put("publishElapsedMs",stats(samples.stream().map(s->s.publishElapsedMs).toList()))
            .put("observedAckWaitMs",stats(samples.stream().map(s->s.ackWaitMs).toList()));
    }
    static JSONObject configuration() {
        return new JSONObject().put("comparison","Reconstructed inside-TX control vs unmodified current BillingWorkers.publishOne")
            .put("workers",WORKERS).put("poolSize",WORKERS).put("warmupRoundsPerMode",2).put("warmupEventsPerRound",16)
            .put("measuredRoundsPerMode",ROUNDS).put("eventsPerRound",EVENTS).put("scheduledAckDelayMs",ACK_MS)
            .put("payloadUtf8BytesBeforePostgresJsonbFormatting",PAYLOAD.getBytes(StandardCharsets.UTF_8).length)
            .put("runOrder","AB BA AB BA AB BA, with A=inside-TX control and B=current outside-TX")
            .put("java",System.getProperty("java.version")).put("os",System.getProperty("os.name"))
            .put("processors",Runtime.getRuntime().availableProcessors())
            .put("connectionHoldDefinition","Time from successful Hikari checkout until close returns, measured directly by DataSource wrapper")
            .put("connectionAcquisitionDefinition","Elapsed Hikari getConnection call, separate from connection hold")
            .put("connectionMsPerEventDefinition","Sum of all instrumented connection holds during publishOne/control, preventing bias from 1 vs 2 checkouts")
            .put("publishElapsedDefinition","Worker invocation start to return, excludes executor queue, setup and claim")
            .put("ackWaitDefinition","Elapsed timed CompletableFuture.get call with 200ms scheduled completion, not actual Kafka latency")
            .put("percentileDefinition","Median midpoint for even samples, p95 nearest rank, raw values retained")
            .put("exclusions","Seed/claim SQL, probes, warmup, broker/network, HTTP/payment flow, production users and throughput")
            .put("contentionDefinition","8 held acknowledgement futures and a single independent SELECT, 500ms connection timeout, no real Kafka")
            .put("limitations","Local synthetic acknowledgement delay; reconstructed control; success benchmark plus transient timeout state checks, not a full failure drill");
    }

    static class Sample {
        final Mode mode;final int round;final String eventId;final List<Double> holdMs=new ArrayList<>(),acquireMs=new ArrayList<>();
        double publishElapsedMs,ackWaitMs;volatile double ackCompletionMs;boolean sendInsideTransaction;
        Sample(Mode mode,int round,String eventId){this.mode=mode;this.round=round;this.eventId=eventId;}
        JSONObject json(){return new JSONObject().put("eventId",eventId).put("holdMs",holdMs).put("acquireMs",acquireMs)
            .put("publishElapsedMs",publishElapsedMs).put("ackWaitMs",ackWaitMs).put("ackCompletionMs",ackCompletionMs)
            .put("sendInsideTransaction",sendInsideTransaction);}
    }
    static class TimedAck extends CompletableFuture<SendResult<String,byte[]>> {
        final Sample sample;TimedAck(Sample sample){this.sample=sample;}
        @Override public SendResult<String,byte[]> get(long timeout,TimeUnit unit) throws InterruptedException,ExecutionException,TimeoutException {
            long start=System.nanoTime();try{return super.get(timeout,unit);}finally{sample.ackWaitMs=ms(System.nanoTime()-start);}
        }
    }
    static class MeasuredDataSource extends AbstractDataSource {
        final HikariDataSource delegate;MeasuredDataSource(HikariDataSource delegate){this.delegate=delegate;}
        @Override public Connection getConnection() throws SQLException {
            Sample sample=ACTIVE.get();long start=System.nanoTime();Connection c=delegate.getConnection();long obtained=System.nanoTime();
            if(sample==null)return c;sample.acquireMs.add(ms(obtained-start));AtomicBoolean closed=new AtomicBoolean();
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                try{return method.invoke(c,args);}catch(InvocationTargetException e){throw e.getCause();}
                finally{if(method.getName().equals("close")&&closed.compareAndSet(false,true))sample.holdMs.add(ms(System.nanoTime()-obtained));}
            });
        }
        @Override public Connection getConnection(String user,String password)throws SQLException{throw new SQLFeatureNotSupportedException("Use configured pool credentials");}
    }
}
