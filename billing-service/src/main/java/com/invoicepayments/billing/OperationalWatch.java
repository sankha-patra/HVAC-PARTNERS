package com.invoicepayments.billing;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static com.invoicepayments.billing.OperationalAlert.Severity.*;

/**
 * Turns the BillingMetrics gauges that the Prometheus rules watch into ALERT log lines, so the same conditions
 * reach CloudWatch alarms on AWS. Run it in one deployment only (the payment worker); thresholds match
 * monitoring/prometheus/billing-rules.yml. Raised when a condition starts, repeated while it lasts, resolved once.
 */
@Component @Profile("billing")
@ConditionalOnProperty(name="billing.alerts.watch-enabled",havingValue="true")
public class OperationalWatch {
    record Check(String code,OperationalAlert.Severity severity,String reason) {}
    static final Duration REPEAT=Duration.ofMinutes(15);
    private final MeterRegistry registry;private final Clock clock;
    private final Map<String,Instant> raised=new HashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    public OperationalWatch(MeterRegistry registry){this(registry,Clock.systemUTC());}
    OperationalWatch(MeterRegistry registry,Clock clock){this.registry=registry;this.clock=clock;}

    @Scheduled(fixedDelayString="${billing.alerts.watch-interval-ms:60000}",initialDelayString="${billing.alerts.watch-initial-delay-ms:60000}")
    public synchronized void check() {
        var breached=new LinkedHashMap<Check,String>();
        double outbox=gauge("billing.outbox.oldest.seconds");
        if(outbox>60)breached.put(new Check("OUTBOX_NOT_DRAINING",WARNING,
            "Committed events are not reaching Kafka; payments are safe, order updates and emails wait"),"oldestSeconds="+(long)outbox);
        double payments=gauge("billing.recovery.due.oldest.seconds"),refunds=gauge("billing.refunds.due.oldest.seconds");
        if(payments>180||refunds>180)breached.put(new Check("RECOVERY_WORK_NOT_CLAIMED",WARNING,
            "Uncertain payments or refunds are due but no recovery has claimed them"),"oldestSeconds="+(long)Math.max(payments,refunds));
        if(gauge("billing.cdc.expected")==1&&gauge("billing.cdc.slot.active")==0)breached.put(new Check("CDC_SLOT_INACTIVE",CRITICAL,
            "Debezium is not reading the outbox replication slot; events and WAL are piling up"),"slot=billing_outbox_cdc");
        double stuck=gauge("billing.payments.stuck.technical")+gauge("billing.refunds.stuck.technical");
        if(stuck>0)breached.put(new Check("MONEY_OPERATION_STUCK",WARNING,
            "A payment or refund has exhausted automatic recovery and needs engineering"),"count="+(long)stuck);
        Instant now=clock.instant();
        for(var entry:breached.entrySet()) {
            Check c=entry.getKey();Instant last=raised.get(c.code());
            if(last==null||!now.isBefore(last.plus(REPEAT))) {
                String[] kv=entry.getValue().split("=",2);
                OperationalAlert.raise(c.code(),c.severity(),c.reason(),kv[0],kv[1]);raised.put(c.code(),now);
            }
        }
        for(var it=raised.keySet().iterator();it.hasNext();) {
            String code=it.next();
            if(breached.keySet().stream().noneMatch(c->c.code().equals(code))) {
                LoggerFactory.getLogger("operational-alerts").info("RESOLVED alert code={}",code);it.remove();
            }
        }
    }
    private double gauge(String name){Gauge g=registry.find(name).gauge();return g==null?0:g.value();}
}
