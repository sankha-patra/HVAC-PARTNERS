package com.invoicepayments.billing.recovery;

import java.util.Map;
import static com.invoicepayments.billing.BillingStore.requireNoTransaction;

/** Restartable local dispatcher. Every remote call follows a committed durable reservation. */
public final class RecoveryDispatcher {
    private final RecoveryDispatchStore store;private final RecoveryRemote remote;
    public RecoveryDispatcher(RecoveryDispatchStore store,RecoveryRemote remote){this.store=store;this.remote=remote;}
    public boolean tick() {
        requireNoTransaction();store.discover(20);var job=store.claim();if(job==null)return false;
        try {
            if(!store.reserve(job,false))return true;
            try {store.record(job,remote.describe(String.valueOf(job.get("executionArn"))));return true;}
            catch(RecoveryRemote.Failure missing){if(!missing.missing())throw missing;}
            // A lost start ACK reuses the identical Standard name, version and input, even after restart.
            if(!store.reserve(job,true))return true;
            String arn=remote.start(String.valueOf(job.get("workflowVersion")),String.valueOf(job.get("executionName")),String.valueOf(job.get("inputText")));
            store.uncertain(job,String.valueOf(job.get("executionArn")).equals(arn)?"START_ACKNOWLEDGED":"START_IDENTITY_MISMATCH");
        } catch(RecoveryRemote.Failure failure){store.uncertain(job,failure.code());}
        catch(RuntimeException failure){
            // Never log SDK messages or input: service failures may contain sensitive context.
            store.uncertain(job,"REMOTE_UNCERTAIN");
        }
        return true;
    }
}
