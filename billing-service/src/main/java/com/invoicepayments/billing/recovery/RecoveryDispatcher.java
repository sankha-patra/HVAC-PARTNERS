package com.invoicepayments.billing.recovery;

import com.invoicepayments.billing.OperationalAlert;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import static com.invoicepayments.billing.BillingStore.requireNoTransaction;

/** Restartable local dispatcher. Every remote call follows a committed durable reservation. */
public final class RecoveryDispatcher {
    static final int FAILURES_BEFORE_ALERT=3;
    static final Duration ALERT_REPEAT=Duration.ofMinutes(15);
    private final RecoveryDispatchStore store;private final RecoveryRemote remote;private final Clock clock;
    private int consecutiveFailures;private Instant alerted;
    public RecoveryDispatcher(RecoveryDispatchStore store,RecoveryRemote remote){this(store,remote,Clock.systemUTC());}
    RecoveryDispatcher(RecoveryDispatchStore store,RecoveryRemote remote,Clock clock){this.store=store;this.remote=remote;this.clock=clock;}
    public synchronized boolean tick() {
        requireNoTransaction();store.discover(20);var job=store.claim();if(job==null)return false;
        boolean describing=true;
        try {
            if(!store.reserve(job,false))return true;
            try {store.record(job,remote.describe(String.valueOf(job.get("executionArn"))));succeeded();return true;}
            catch(RecoveryRemote.Failure missing){if(!missing.missing())throw missing;}
            describing=false;
            // A lost start ACK reuses the identical Standard name, version and input, even after restart.
            if(!store.reserve(job,true))return true;
            String arn=remote.start(String.valueOf(job.get("workflowVersion")),String.valueOf(job.get("executionName")),String.valueOf(job.get("inputText")));
            store.uncertain(job,String.valueOf(job.get("executionArn")).equals(arn)?"START_ACKNOWLEDGED":"START_IDENTITY_MISMATCH");
            succeeded();
        } catch(RecoveryRemote.Failure failure){store.uncertain(job,failure.code(),describing);failed(failure.code());}
        catch(RuntimeException failure){
            // Never log SDK messages or input: service failures may contain sensitive context. The class name is safe.
            store.uncertain(job,"REMOTE_UNCERTAIN",describing);
            // Only an AWS SDK failure (no credentials, no network) counts; a database failure has its own alert.
            if(failure instanceof software.amazon.awssdk.core.exception.SdkException)failed(failure.getClass().getSimpleName());
        }
        return true;
    }
    /** Repeated AWS failures (expired or revoked credentials, no network, throttling) are emailed with the error code. */
    private void failed(String code) {
        consecutiveFailures++;
        Instant now=clock.instant();
        if(consecutiveFailures>=FAILURES_BEFORE_ALERT&&(alerted==null||!now.isBefore(alerted.plus(ALERT_REPEAT)))) {
            alerted=now;
            OperationalAlert.raise("RECOVERY_DISPATCH_FAILING",OperationalAlert.Severity.CRITICAL,
                "The recovery dispatcher cannot call AWS Step Functions; uncertain payments wait unrecovered until it works again",
                "errorCode",code,"consecutiveFailures",consecutiveFailures);
        }
    }
    private void succeeded() {
        if(alerted!=null)org.slf4j.LoggerFactory.getLogger("operational-alerts").info("ALERT_RESOLVED code=RECOVERY_DISPATCH_FAILING");
        consecutiveFailures=0;alerted=null;
    }
    /** Headroom of the live campaign: emailed (as a condition) when it ends within a day or has used 80% of its starts. */
    public Map<String,Object> campaignHealth(){return store.health();}
}
