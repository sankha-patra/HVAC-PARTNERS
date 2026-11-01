package com.invoicepayments.billing;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.invoicepayments.billing.OperationalAlert.Severity.*;

/**
 * Turns conditions into ALERT log lines that CloudWatch alarms watch, and writes a heartbeat. Run it in one deployment
 * only (the payment worker on AWS, the single app in the hybrid lab).
 *
 * Every check (60 s) writes one line per condition that holds, so the alarm emails once when it starts and once when it
 * has been clear for five minutes; the line carries the current value. A condition whose value cannot be read (the
 * database is down) keeps its last state instead of being reported as cleared. The heartbeat lets AWS tell a crashed
 * app from a sleeping laptop: `HEARTBEAT service=billing db=up|down`.
 */
@Component @Profile("billing")
@ConditionalOnProperty(name="billing.alerts.watch-enabled",havingValue="true")
public class OperationalWatch implements AutoCloseable {
    record Check(String code,OperationalAlert.Severity severity,String reason) {}
    static final long WAL_RETAINED_BYTES=512L*1024*1024;
    private static final Logger log=LoggerFactory.getLogger("operational-alerts");
    private final MeterRegistry registry;private final Clock clock;private final JdbcTemplate jdbc;
    private final KafkaAdmin kafka;private final String connectUrl,connector;private final boolean restartConnector;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Map<String,Object[]> raised=new HashMap<>();
    private int databaseFailures,kafkaFailures,connectorFailures;private AdminClient admin;private Instant restarted;

    @Autowired
    public OperationalWatch(MeterRegistry registry,JdbcTemplate jdbc,ObjectProvider<KafkaAdmin> kafka,Environment env){
        this(registry,Clock.systemUTC(),jdbc,env.getProperty("billing.alerts.kafka-probe",Boolean.class,true)?kafka.getIfAvailable():null,
            env.getProperty("billing.alerts.connect-url",""),env.getProperty("billing.alerts.connector","customer-email-outbox"),
            env.getProperty("billing.alerts.connect-auto-restart",Boolean.class,false));
    }
    OperationalWatch(MeterRegistry registry,Clock clock){this(registry,clock,null,null,"","customer-email-outbox",false);}
    OperationalWatch(MeterRegistry registry,Clock clock,JdbcTemplate jdbc,KafkaAdmin kafka,String connectUrl,String connector,boolean restartConnector){
        this.registry=registry;this.clock=clock;this.jdbc=jdbc;this.kafka=kafka;this.connectUrl=connectUrl;this.connector=connector;this.restartConnector=restartConnector;
        if(!connector.matches("[A-Za-z0-9_.-]{1,100}"))throw new IllegalArgumentException("Connector name required");
    }

    @Scheduled(fixedDelayString="${billing.alerts.watch-interval-ms:60000}",initialDelayString="${billing.alerts.watch-initial-delay-ms:60000}")
    public synchronized void check() {
        boolean database=databaseReachable();
        log.info("HEARTBEAT service=billing db={}",database?"up":"down");
        // code -> value that breaches (true), is clear (false), or cannot be read now (null: keep the last state).
        var results=new LinkedHashMap<Check,Object[]>();
        double outbox=gauge("billing.outbox.oldest.seconds");
        results.put(new Check("OUTBOX_NOT_DRAINING",WARNING,"Committed events are not reaching Kafka; payments are safe, order updates and emails wait"),
            state(outbox,outbox>60,"oldestSeconds"));
        double unowned=gauge("billing.recovery.unowned.oldest.seconds");
        results.put(new Check("RECOVERY_WORK_NOT_CLAIMED",WARNING,"Uncertain payments or refunds are due but no recovery workflow or claim owns them"),
            state(unowned,unowned>180,"oldestSeconds"));
        double expected=gauge("billing.cdc.expected"),slot=gauge("billing.cdc.slot.active");
        results.put(new Check("CDC_SLOT_INACTIVE",CRITICAL,"Debezium is not reading the outbox replication slot; events and WAL are piling up"),
            state(Double.isNaN(expected)?Double.NaN:slot,expected==1&&slot==0,"slotActive"));
        double heartbeat=gauge("billing.cdc.heartbeat.age.seconds");
        results.put(new Check("DEBEZIUM_STALLED",CRITICAL,"Debezium has stopped updating its heartbeat, so outbox events are not being read"),
            state(Double.isNaN(expected)?Double.NaN:heartbeat,expected==1&&heartbeat>60,"heartbeatAgeSeconds"));
        double wal=gauge("billing.cdc.wal.retained.bytes");
        results.put(new Check("CDC_WAL_RETAINED",WARNING,"The outbox replication slot is holding a lot of WAL; Debezium is behind or stopped"),
            state(wal,wal>WAL_RETAINED_BYTES,"retainedBytes"));
        double stuck=sum(gauge("billing.payments.stuck.unowned"),gauge("billing.refunds.stuck.unowned"));
        results.put(new Check("MONEY_OPERATION_STUCK",WARNING,"A payment or refund has been uncertain for over 15 minutes and no finance case owns it yet"),
            state(stuck,stuck>0,"count"));
        double dead=gauge("billing.deadletters.open");
        results.put(new Check("DEAD_LETTER_OPEN",WARNING,"A Kafka event could not be applied and was parked; its order update or email waits for a replay"),
            state(dead,dead>0,"count"));
        double failing=gauge("billing.notifications.failing"),unrouted=gauge("billing.notifications.unrouted.recent");
        results.put(new Check("NOTIFICATION_DELIVERY_FAILING",WARNING,"Buyer or finance emails are being refused by the mail server or have no configured recipient"),
            state(sum(failing,unrouted),failing>0||unrouted>0,"failing",(long)(Double.isNaN(failing)?0:failing),"unrouted",(long)(Double.isNaN(unrouted)?0:unrouted)));
        double rejected=registry.find("billing.provider.credentials.rejected").gauges().stream().mapToDouble(Gauge::value).filter(v->!Double.isNaN(v)).sum();
        results.put(new Check("PROVIDER_CREDENTIALS_REJECTED",CRITICAL,"The payment provider rejected the app's API credentials; new payments cannot start"),
            state(rejected,rejected>0,"operations"));
        if(kafka!=null)results.put(new Check("KAFKA_UNREACHABLE",CRITICAL,"The billing app cannot reach the Kafka broker; committed payments wait in the outbox"),kafkaState());
        if(!connectUrl.isBlank())results.put(new Check("DEBEZIUM_TASK_NOT_RUNNING",CRITICAL,"Kafka Connect reports the outbox connector or its task is not running; it does not restart by itself"),connectorState());
        for(var entry:results.entrySet()) {
            Check c=entry.getKey();Object[] value=entry.getValue();
            // Unreadable now (database down): a raised condition is repeated with its last value, so no false OK is sent.
            if(value==null)value=raised.get(c.code());
            if(value==null)continue;
            if(Boolean.TRUE.equals(value[0])) {
                OperationalAlert.raise(c.code(),c.severity(),c.reason(),Arrays.copyOfRange(value,1,value.length));raised.put(c.code(),value);
            } else if(raised.remove(c.code())!=null)log.info("ALERT_RESOLVED code={}",c.code());
        }
    }
    /** null when the value is unreadable, otherwise {breached, field, value, ...}. */
    private static Object[] state(double value,boolean breached,Object... fields) {
        if(Double.isNaN(value))return null;
        if(fields.length==1)return new Object[]{breached,fields[0],(long)value};
        var out=new Object[fields.length+1];out[0]=breached;System.arraycopy(fields,0,out,1,fields.length);return out;
    }
    private boolean databaseReachable() {
        if(jdbc==null)return true;
        try{jdbc.queryForObject("SELECT 1",Integer.class);databaseFailures=0;return true;}
        catch(RuntimeException failure) {
            // Two checks in a row, so one slow query is not an outage. Raised at most once a minute.
            if(++databaseFailures>=2)OperationalAlert.raiseAtMostEvery(Duration.ofMinutes(1),"watch","DATABASE_UNAVAILABLE",CRITICAL,
                "The billing app cannot reach PostgreSQL (independent check, with or without traffic); payments fail safely until it returns",
                "source","watch","error",failure.getClass().getSimpleName(),"consecutiveChecks",databaseFailures);
            return false;
        }
    }
    private Object[] kafkaState() {
        try {
            if(admin==null) {
                // Back off reconnects, so a Kafka outage is not a reconnect warning every second in the shipped log.
                var config=new HashMap<>(kafka.getConfigurationProperties());
                config.put("reconnect.backoff.ms",5000);config.put("reconnect.backoff.max.ms",60000);
                admin=AdminClient.create(config);
            }
            admin.describeCluster().nodes().get(5,TimeUnit.SECONDS);kafkaFailures=0;return new Object[]{false};
        } catch(Exception failure) {
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            return new Object[]{++kafkaFailures>=2,"error",failure.getClass().getSimpleName()};
        }
    }
    private Object[] connectorState() {
        String state;
        try {
            var response=http.send(HttpRequest.newBuilder(URI.create(connectUrl+"/connectors/"+connector+"/status")).timeout(Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)state="HTTP_"+response.statusCode();
            else {
                var status=new JSONObject(response.body());state=status.getJSONObject("connector").optString("state");
                for(Object task:status.optJSONArray("tasks")==null?new org.json.JSONArray():status.getJSONArray("tasks"))
                    if(!"RUNNING".equals(((JSONObject)task).optString("state")))state="TASK_"+((JSONObject)task).optString("state");
                if(status.optJSONArray("tasks")==null||status.getJSONArray("tasks").isEmpty())state="NO_TASK";
            }
        } catch(Exception failure) {
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            state="UNREACHABLE";
        }
        // PAUSED is deliberate. Two bad checks in a row, so Connect's own 1-4 minute startup is not an outage.
        boolean bad=!"RUNNING".equals(state)&&!"PAUSED".equals(state);
        connectorFailures=bad?connectorFailures+1:0;
        boolean broken=connectorFailures>=2;
        if(broken&&restartConnector&&!"UNREACHABLE".equals(state)&&(restarted==null||!clock.instant().isBefore(restarted.plus(Duration.ofMinutes(5))))) {
            restarted=clock.instant();
            try {
                http.send(HttpRequest.newBuilder(URI.create(connectUrl+"/connectors/"+connector+"/restart?includeTasks=true&onlyFailed=false"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding());
                log.warn("Restart requested for connector {} after state {}",connector,state);
            } catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();}
        }
        return new Object[]{broken,"state",state.replaceAll("[^A-Z0-9_]","_"),"autoRestart",restartConnector};
    }
    private static double sum(double a,double b){return Double.isNaN(a)||Double.isNaN(b)?Double.NaN:a+b;}
    private double gauge(String name){Gauge g=registry.find(name).gauge();return g==null?0:g.value();}
    @Override public synchronized void close(){if(admin!=null)admin.close(Duration.ofSeconds(2));}
}
