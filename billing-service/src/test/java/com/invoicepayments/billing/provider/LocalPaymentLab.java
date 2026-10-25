package com.invoicepayments.billing.provider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-classpath only. Synthetic contracts, independent commits, loopback sockets; never a production adapter. */
public final class LocalPaymentLab implements AutoCloseable {
    public static final String URL = "jdbc:postgresql://127.0.0.1:15432/invoice_payments_simulation_test";
    public enum Fault { NORMAL, PENDING, DISCONNECT_AFTER_ACCEPT, TIMEOUT_AFTER_ACCEPT, RATE_LIMIT, UNAVAILABLE, WRONG_AMOUNT, WRONG_ACK }
    private final HikariDataSource source;
    private final JdbcTemplate external;
    private final TransactionTemplate tx;
    private HttpServer server;
    private final ExecutorService threads = Executors.newFixedThreadPool(16);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<String,Fault> plans = new ConcurrentHashMap<>();
    public final AtomicInteger paymentPosts = new AtomicInteger();
    public volatile Fault erpFault = Fault.NORMAL;
    public volatile boolean bankOutage;
    public volatile int latencyMs;

    public LocalPaymentLab() throws IOException {
        if (!URL.equals(System.getenv("BILLING_LAB_JDBC_URL")) || !"billing_test".equals(System.getenv("BILLING_LAB_DB_USER"))
            || System.getenv("BILLING_LAB_DB_PASSWORD") == null) throw new IllegalStateException("Exact disposable lab database settings required");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(URL); config.setUsername("billing_test"); config.setPassword(System.getenv("BILLING_LAB_DB_PASSWORD"));
        config.setMaximumPoolSize(8); config.setMinimumIdle(1); config.setConnectionTimeout(3000); config.setPoolName("synthetic-external-systems");
        source = new HikariDataSource(config); external = new JdbcTemplate(source);
        if (!"invoice_payments_simulation_test".equals(external.queryForObject("SELECT current_database()",String.class))
            || !"billing_test".equals(external.queryForObject("SELECT current_user",String.class))) throw new IllegalStateException("Lab database mismatch");
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        external.execute("CREATE SCHEMA IF NOT EXISTS billing_lab");
        external.execute("CREATE TABLE IF NOT EXISTS billing_lab.payments(op_key TEXT PRIMARY KEY, id TEXT UNIQUE NOT NULL, pi TEXT UNIQUE NOT NULL, intent UUID UNIQUE NOT NULL, minor BIGINT NOT NULL, method TEXT NOT NULL, state TEXT NOT NULL)");
        external.execute("CREATE TABLE IF NOT EXISTS billing_lab.refunds(op_key TEXT PRIMARY KEY, id TEXT UNIQUE NOT NULL, intent UUID NOT NULL, refund_id UUID UNIQUE NOT NULL, pi TEXT NOT NULL, minor BIGINT NOT NULL)");
        external.execute("CREATE TABLE IF NOT EXISTS billing_lab.bank(intent UUID PRIMARY KEY, reference TEXT UNIQUE NOT NULL, gross BIGINT NOT NULL, fee BIGINT NOT NULL, credit BIGINT NOT NULL, available BOOLEAN NOT NULL DEFAULT FALSE)");
        external.execute("CREATE TABLE IF NOT EXISTS billing_lab.erp(event_id UUID PRIMARY KEY, fingerprint TEXT NOT NULL, payload JSONB NOT NULL)");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),32);
        server.createContext("/",this::handle); server.setExecutor(threads); server.start();
    }

    public URI origin() { return URI.create("http://127.0.0.1:"+server.getAddress().getPort()); }

    /** Restart the external HTTP listener without resetting its independently committed evidence. */
    public void restartHttp() throws IOException {
        int port=server.getAddress().getPort();server.stop(0);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),32);
        server.createContext("/",this::handle);server.setExecutor(threads);server.start();
    }

    private volatile StripeBillingProvider adapter;

    /**
     * Keeps the real adapter parser/form generation; rewrites its fixed host only in this test transport. The bean
     * forwards to the current adapter, so each drill can start with a fresh one (see restartAdapter).
     */
    public BillingProvider provider() {
        restartAdapter();
        return new CurrentAdapter();
    }
    /**
     * Starts a new incident with a healthy connection, as after Stripe recovers. The pause for repeated failures
     * lives in the adapter, so failures acted out in one drill would otherwise pause new payments in the next.
     */
    public void restartAdapter() {
        adapter = new StripeBillingProvider("sk_test_localLabOnly", request -> {
            if (!"https".equals(request.uri().getScheme()) || !"api.stripe.com".equals(request.uri().getHost()))
                throw new IOException("Unexpected original adapter destination");
            HttpRequest.Builder wire = HttpRequest.newBuilder(origin().resolve(request.uri().getRawPath()))
                .timeout(Duration.ofMillis(800)).method(request.method(),request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
            request.headers().map().forEach((name,values)->values.forEach(value->wire.header(name,value)));
            HttpResponse<String> response = client.send(wire.build(),HttpResponse.BodyHandlers.ofString());
            return new StripeBillingProvider.Response(response.statusCode(),response.body());
        });
    }
    private final class CurrentAdapter implements BillingProvider {
        @Override public String name() { return adapter.name(); }
        @Override public ProviderPermit admitNewPayment() { return adapter.admitNewPayment(); }
        @Override public ProviderResult submit(ProviderCommand command) { return adapter.submit(command); }
        @Override public String paymentRequestVersion() { return adapter.paymentRequestVersion(); }
        @Override public ProviderResult submitSaved(ProviderCommand command, String version, java.time.Instant preparedAt) { return adapter.submitSaved(command, version, preparedAt); }
        @Override public ProviderResult recoverPayment(ProviderCommand command, String version, java.time.Instant preparedAt, String providerId) { return adapter.recoverPayment(command, version, preparedAt, providerId); }
        @Override public ProviderResult discoverPayment(ProviderCommand saved, String version, java.time.Instant preparedAt) { return adapter.discoverPayment(saved, version, preparedAt); }
        @Override public ProviderResult lookup(String idempotencyKey, String providerId) { return adapter.lookup(idempotencyKey, providerId); }
        @Override public ProviderResult verifyPaymentReference(ProviderCommand expected, String providerId) { return adapter.verifyPaymentReference(expected, providerId); }
        @Override public ProviderResult refund(RefundCommand command) { return adapter.refund(command); }
        @Override public ProviderResult lookupRefund(String idempotencyKey, String providerId) { return adapter.lookupRefund(idempotencyKey, providerId); }
        @Override public ProviderResult verifyRefundReference(RefundCommand expected, String providerId) { return adapter.verifyRefundReference(expected, providerId); }
        @Override public String refundRequestVersion() { return adapter.refundRequestVersion(); }
        @Override public ProviderResult recoverRefund(RefundCommand command, String version, java.time.Instant preparedAt, String providerId) { return adapter.recoverRefund(command, version, preparedAt, providerId); }
        @Override public ProviderResult closePayment(ProviderCommand saved, String version, java.time.Instant preparedAt, String providerId) { return adapter.closePayment(saved, version, preparedAt, providerId); }
        @Override public ProviderResult findRefund(RefundCommand saved, String providerId) { return adapter.findRefund(saved, providerId); }
    }

    public void reset() {
        // Caller independently asserts the exact database before any destructive test cleanup.
        external.execute("TRUNCATE billing_lab.payments,billing_lab.refunds,billing_lab.bank,billing_lab.erp");
        plans.clear(); paymentPosts.set(0); latencyMs=0; bankOutage=false; erpFault=Fault.NORMAL;
    }
    public void plan(UUID intent,Fault fault) { plans.put(intent.toString(),fault); }
    public void complete(UUID intent) { external.update("UPDATE billing_lab.payments SET state='CAPTURED' WHERE intent=?",intent); plans.remove(intent.toString()); }
    public long paymentCount(UUID intent) { return external.queryForObject("SELECT count(*) FROM billing_lab.payments WHERE intent=?",Long.class,intent); }
    public long erpCount(UUID event) { return external.queryForObject("SELECT count(*) FROM billing_lab.erp WHERE event_id=?",Long.class,event); }
    public long refundCount(UUID refund) { return external.queryForObject("SELECT count(*) FROM billing_lab.refunds WHERE refund_id=?",Long.class,refund); }
    public JSONObject snapshot(UUID intent) { return session(payment(intent)); }
    public JSONObject refundSnapshot(UUID refund) { return refund(external.queryForMap("SELECT * FROM billing_lab.refunds WHERE refund_id=?",refund)); }
    public void createBankEvidence(UUID intent,long fee) {
        var payment=payment(intent);
        if (!"CAPTURED".equals(payment.get("state"))) throw new IllegalStateException("External capture required");
        long gross=((Number)payment.get("minor")).longValue();
        external.update("INSERT INTO billing_lab.bank(intent,reference,gross,fee,credit) VALUES(?,?,?,?,?) ON CONFLICT(intent) DO NOTHING",
            intent,"LAB-Bank/"+intent,gross,fee,gross-fee);
    }
    public void bankCredit(UUID intent,boolean available,long amount) { external.update("UPDATE billing_lab.bank SET available=?,credit=? WHERE intent=?",available,amount,intent); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path=exchange.getRequestURI().getPath();
            if (exchange.getRequestMethod().equals("POST") && path.equals("/v1/checkout/sessions")) { acceptPayment(exchange); return; }
            if (path.startsWith("/v1/checkout/sessions/")) {
                var row=external.queryForMap("SELECT * FROM billing_lab.payments WHERE id=?",path.substring(path.lastIndexOf('/')+1));
                if (plans.get(row.get("intent").toString())==Fault.UNAVAILABLE) { reply(exchange,503,new JSONObject()); return; }
                pause(latencyMs); reply(exchange,200,session(row)); return;
            }
            if (path.startsWith("/v1/payment_intents/")) {
                var row=external.queryForMap("SELECT * FROM billing_lab.payments WHERE pi=?",path.substring(path.lastIndexOf('/')+1));
                pause(latencyMs); reply(exchange,200,paymentIntent(row)); return;
            }
            if (path.equals("/v1/refunds") && exchange.getRequestMethod().equals("POST")) { acceptRefund(exchange); return; }
            if (path.startsWith("/v1/refunds/")) { reply(exchange,200,refund(external.queryForMap("SELECT * FROM billing_lab.refunds WHERE id=?",path.substring(path.lastIndexOf('/')+1)))); return; }
            if (path.startsWith("/bank/")) { bank(exchange,path); return; }
            if (path.equals("/erp/postings") && exchange.getRequestMethod().equals("POST")) { erp(exchange); return; }
            reply(exchange,404,new JSONObject().put("error","Synthetic endpoint missing"));
        } catch (Exception error) {
            // Fault responses must not dump credentials, application data, or internal exception messages.
            try { reply(exchange,500,new JSONObject().put("error","Synthetic fixture request failed")); } catch(IOException ignored) { exchange.close(); }
        }
    }

    private void acceptPayment(HttpExchange exchange) throws Exception {
        paymentPosts.incrementAndGet(); var form=form(exchange);
        UUID intent=UUID.fromString(form.get("client_reference_id")); Fault fault=plans.getOrDefault(intent.toString(),Fault.NORMAL);
        if (fault==Fault.RATE_LIMIT || fault==Fault.UNAVAILABLE) { reply(exchange,fault==Fault.RATE_LIMIT?429:503,new JSONObject()); return; }
        String key=exchange.getRequestHeaders().getFirst("Idempotency-Key");
        long amount=Long.parseLong(form.get("line_items[0][price_data][unit_amount]"));
        if (!Objects.equals(key,form.get("metadata[idempotencyKey]")) || !intent.toString().equals(form.get("metadata[intentId]"))
            || !"usd".equals(form.get("line_items[0][price_data][currency]")) || amount<=0) { reply(exchange,400,new JSONObject()); return; }
        String suffix=UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString().replace("-","");
        Map<String,Object> row=tx.execute(status->{
            external.update("INSERT INTO billing_lab.payments(op_key,id,pi,intent,minor,method,state) VALUES(?,?,?,?,?,?,?) ON CONFLICT(op_key) DO NOTHING",
                key,"cs_test_"+suffix,"pi_"+suffix,intent,amount,form.get("allowed_payment_method_types[0]"),fault==Fault.PENDING?"PROCESSING":"CAPTURED");
            return external.queryForMap("SELECT * FROM billing_lab.payments WHERE op_key=?",key);
        });
        if (!intent.equals(row.get("intent")) || ((Number)row.get("minor")).longValue()!=amount) { reply(exchange,409,new JSONObject()); return; }
        // The external commit already happened. Application rollback cannot undo it.
        if (fault==Fault.DISCONNECT_AFTER_ACCEPT) { exchange.close(); return; }
        pause(fault==Fault.TIMEOUT_AFTER_ACCEPT?1200:latencyMs);
        JSONObject response=session(row); if (fault==Fault.WRONG_AMOUNT) response.put("amount_total",amount+1);
        reply(exchange,200,response);
    }

    private void acceptRefund(HttpExchange exchange) throws Exception {
        var form=form(exchange); UUID intent=UUID.fromString(form.get("metadata[intentId]"));
        UUID refundId=UUID.fromString(form.get("metadata[refundId]")); String key=exchange.getRequestHeaders().getFirst("Idempotency-Key");
        long minor=Long.parseLong(form.get("amount")); String pi=form.get("payment_intent");
        if (!Objects.equals(key,form.get("metadata[idempotencyKey]"))) {reply(exchange,400,new JSONObject()); return;}
        Map<String,Object> row=tx.execute(status->{
            var captured=external.queryForMap("SELECT * FROM billing_lab.payments WHERE pi=? FOR UPDATE",pi);
            var prior=external.queryForList("SELECT * FROM billing_lab.refunds WHERE op_key=?",key);
            if (!prior.isEmpty()) {
                var previous=prior.get(0);
                if(!refundId.equals(previous.get("refund_id")) || !intent.equals(previous.get("intent"))
                    || minor!=((Number)previous.get("minor")).longValue() || !pi.equals(previous.get("pi"))) throw new IllegalArgumentException();
                return previous;
            }
            long total=external.queryForObject("SELECT COALESCE(sum(minor),0) FROM billing_lab.refunds WHERE pi=?",Long.class,pi);
            if(!"CAPTURED".equals(captured.get("state")) || !intent.equals(captured.get("intent")) || minor<=0 || total+minor>((Number)captured.get("minor")).longValue()) throw new IllegalArgumentException();
            String id="re_"+refundId.toString().replace("-","");
            external.update("INSERT INTO billing_lab.refunds(op_key,id,intent,refund_id,pi,minor) VALUES(?,?,?,?,?,?)",key,id,intent,refundId,pi,minor);
            return external.queryForMap("SELECT * FROM billing_lab.refunds WHERE op_key=?",key);
        });
        if(plans.get(intent.toString())==Fault.DISCONNECT_AFTER_ACCEPT){exchange.close();return;}
        reply(exchange,200,refund(row));
    }

    private void bank(HttpExchange exchange,String path) throws IOException {
        if(bankOutage){reply(exchange,503,new JSONObject());return;}
        UUID intent=UUID.fromString(path.substring(path.lastIndexOf('/')+1));
        var row=external.queryForMap("SELECT * FROM billing_lab.bank WHERE intent=?",intent);
        if(path.startsWith("/bank/credits/")) {
            if(!Boolean.TRUE.equals(row.get("available"))){reply(exchange,404,new JSONObject().put("status","NOT_YET_CREDITED"));return;}
            reply(exchange,200,new JSONObject().put("bankReference",row.get("reference")).put("amountMinor",row.get("credit")).put("currency","USD").put("synthetic",true));return;
        }
        long gross=((Number)row.get("gross")).longValue(),fee=((Number)row.get("fee")).longValue();
        reply(exchange,200,new JSONObject().put("externalId","LAB-report-"+intent).put("source","SIMULATOR")
            .put("grossMinor",gross).put("feeMinor",fee).put("netMinor",gross-fee).put("bankReference",row.get("reference"))
            .put("lines",new org.json.JSONArray().put(new JSONObject().put("intentId",intent).put("amountMinor",gross))));
    }

    private void erp(HttpExchange exchange) throws Exception {
        if(erpFault==Fault.UNAVAILABLE){reply(exchange,503,new JSONObject());return;}
        JSONObject event=new JSONObject(read(exchange)); UUID id=UUID.fromString(event.getString("eventId"));
        String fingerprint=com.invoicepayments.billing.BillingStore.hash(event.toString());
        boolean same=tx.execute(status->{
            external.update("INSERT INTO billing_lab.erp(event_id,fingerprint,payload) VALUES(?,?,?::jsonb) ON CONFLICT(event_id) DO NOTHING",id,fingerprint,event.toString());
            return fingerprint.equals(external.queryForObject("SELECT fingerprint FROM billing_lab.erp WHERE event_id=?",String.class,id));
        });
        if(!same){reply(exchange,409,new JSONObject());return;}
        if(erpFault==Fault.DISCONNECT_AFTER_ACCEPT){exchange.close();return;}
        reply(exchange,200,new JSONObject().put("eventId",erpFault==Fault.WRONG_ACK?UUID.randomUUID():id)
            .put("amountMinor",event.getLong("amountMinor")).put("status","ACKNOWLEDGED").put("synthetic",true));
    }

    private Map<String,Object> payment(UUID id) {return external.queryForMap("SELECT * FROM billing_lab.payments WHERE intent=?",id);}
    private JSONObject metadata(Map<String,Object> row){return new JSONObject().put("intentId",row.get("intent")).put("idempotencyKey",row.get("op_key"));}
    private JSONObject session(Map<String,Object> row){
        boolean captured="CAPTURED".equals(row.get("state"));
        return new JSONObject().put("id",row.get("id")).put("object","checkout.session").put("livemode",false).put("mode","payment")
            .put("currency","usd").put("amount_total",row.get("minor")).put("payment_intent",row.get("pi"))
            .put("payment_status",captured?"paid":"unpaid").put("status","complete").put("metadata",metadata(row))
            .put("payment_method_types",new JSONArray().put(row.get("method")));
    }
    private JSONObject paymentIntent(Map<String,Object> row){return new JSONObject().put("id",row.get("pi")).put("object","payment_intent")
        .put("livemode",false).put("currency","usd").put("amount",row.get("minor")).put("amount_received","CAPTURED".equals(row.get("state"))?row.get("minor"):0)
        .put("status","CAPTURED".equals(row.get("state"))?"succeeded":"processing").put("metadata",metadata(row))
        .put("payment_method_types",new JSONArray().put(row.get("method")));}
    private JSONObject refund(Map<String,Object> row){return new JSONObject().put("id",row.get("id")).put("object","refund").put("status","succeeded")
        .put("payment_intent",row.get("pi")).put("currency","usd").put("amount",row.get("minor"))
        .put("metadata",metadata(row).put("refundId",row.get("refund_id")));}
    private static String read(HttpExchange exchange) throws IOException {
        byte[] bytes=exchange.getRequestBody().readNBytes(262145); if(bytes.length>262144)throw new IOException("Body too large");return new String(bytes,StandardCharsets.UTF_8);
    }
    private static Map<String,String> form(HttpExchange exchange) throws IOException {
        Map<String,String> values=new HashMap<>();for(String pair:read(exchange).split("&")){String[] parts=pair.split("=",2);if(parts.length==2)values.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),URLDecoder.decode(parts[1],StandardCharsets.UTF_8));}return values;
    }
    private static void pause(int ms) throws InterruptedException {if(ms>0)Thread.sleep(ms);}
    private static void reply(HttpExchange exchange,int status,JSONObject body) throws IOException {
        byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(status,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}finally{exchange.close();}
    }
    @Override public void close(){server.stop(0);threads.shutdownNow();source.close();}
}
