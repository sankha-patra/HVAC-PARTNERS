package com.invoicepayments.billing;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import static com.invoicepayments.billing.BillingStore.*;

/** Background flows. Database work is in proxied commands; Kafka and provider I/O run with no transaction open. */
@Component
@Profile("billing")
@ConditionalOnProperty(name="billing.workers-enabled",havingValue="true",matchIfMissing=true)
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'${billing.role:combined}' != 'api'")
public class BillingWorkers {
    private final BillingStore db; private final BillingRecovery recovery; private final BillingInbox inbox;
    private final KafkaTemplate<String,byte[]> kafka; private final BillingOutboxCommands outbox;
    private final ThreadPoolExecutor io=pool(4,4,"billing-recovery-");
    private final ThreadPoolExecutor publishers=pool(8,8,"billing-publisher-");
    static final int MAX_OUTBOX_ATTEMPTS=BillingOutboxCommands.MAX_OUTBOX_ATTEMPTS;
    private boolean refundFirst;
    private volatile boolean stopping;
    private BillingRuntimeSettings runtime=new BillingRuntimeSettings(new org.springframework.core.env.StandardEnvironment());
    private long shutdownSeconds=60;
    @org.springframework.beans.factory.annotation.Autowired
    void configureRuntime(org.springframework.core.env.Environment env) {
        runtime=new BillingRuntimeSettings(env);
        shutdownSeconds=env.getProperty("billing.worker-shutdown-seconds",Long.class,60L);
        if(shutdownSeconds<1||shutdownSeconds>90)throw new IllegalArgumentException("Worker drain must be between 1 and 90 seconds");
    }
    public BillingWorkers(BillingStore db,BillingRecovery recovery,BillingInbox inbox,ObjectProvider<KafkaTemplate<String,byte[]>> kafka,BillingOutboxCommands outbox) {
        this.db=db;this.recovery=recovery;this.inbox=inbox;this.kafka=kafka.getIfAvailable();this.outbox=outbox;
    }
    @Scheduled(fixedDelayString="${billing.recovery-interval-ms:1000}")
    public synchronized void recover() {
        if(stopping||!runtime.jobEnabled("recovery"))return;
        int available=io.getMaximumPoolSize()-io.getActiveCount()-io.getQueue().size();
        if(available<=0) return;
        refundFirst=!refundFirst;
        if(refundFirst) {
            var jobs=recovery.claimRefunds(available);available-=jobs.size();
            for(var job:jobs) execute(io,()->recovery.recoverRefund(job),()->recovery.releaseUnstarted(job,true));
        }
        if(available>0) {
            var jobs=recovery.claimPayments(available);available-=jobs.size();
            for(var job:jobs) execute(io,()->recovery.recoverPayment(job),()->recovery.releaseUnstarted(job,false));
        }
        if(available>0 && !refundFirst) for(var job:recovery.claimRefunds(available)) execute(io,()->recovery.recoverRefund(job),()->recovery.releaseUnstarted(job,true));
    }
    @Scheduled(fixedDelayString="${billing.inbox-interval-ms:1000}") public void receive() { if(!stopping&&runtime.jobEnabled("inbox"))inbox.drain(25); }
    // Unsubmitted intents hold invoice reservations; release them after this age (minutes).
    @org.springframework.beans.factory.annotation.Value("${billing.unsubmitted-ttl-minutes:30}") int unsubmittedTtlMinutes=30;
    @Scheduled(fixedDelayString="${billing.expiry-interval-ms:60000}")
    public void expireUnsubmitted() { if(!stopping&&runtime.jobEnabled("expiry"))recovery.expireUnsubmitted(unsubmittedTtlMinutes,20); }
    @Scheduled(fixedDelayString="${billing.outbox-interval-ms:100}")
    public synchronized void publish() {
        if(stopping||!runtime.jobEnabled("publisher"))return;
        int available=publishers.getMaximumPoolSize()-publishers.getActiveCount()-publishers.getQueue().size();
        if(kafka==null || available<=0) return;
        for(var work:claimOutbox(available)) execute(publishers,()->publishOne(work),()->outbox.releaseUnstarted(id(work.get("id")),id(work.get("leaseToken"))));
    }
    public List<Map<String,Object>> claimOutbox(int limit) {
        BillingExecutionBudget.requireTime();return outbox.claim(limit);
    }
    public void publishOne(Map<String,Object> work) {
        if(kafka==null) return;
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Outbox publishing must run outside a database transaction");
        try {
            // This autocommit lookup releases its connection before broker I/O.
            // The token fences DB updates, not Kafka: a pause after this check can
            // still produce a late duplicate after a new owner has claimed the row.
            var owned=db.rows("""
                SELECT id,aggregate_id,payload::text AS payload FROM billing.outbox
                WHERE id=? AND lease_token=? AND state='IN_FLIGHT' AND lease_until>NOW()
                """,id(work.get("id")),id(work.get("leaseToken")));
            if(owned.isEmpty()) return;
            var current=owned.get(0);
            kafka.send("billing.events.v1",text(current,"aggregateId"),text(current,"payload")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).get(12,TimeUnit.SECONDS);
            markPublished(id(work.get("id")),id(work.get("leaseToken")));
        } catch(Exception failure) {
            if(failure instanceof InterruptedException) Thread.currentThread().interrupt();
            failPublication(work,failure);
        }
    }
    /** Classifies the failure here; the bookkeeping is its own unit, after any failed unit has rolled back. */
    private void failPublication(Map<String,Object> work,Exception failure) {
        Throwable cause=failure;
        while(cause.getCause()!=null && cause.getCause()!=cause) cause=cause.getCause();
        boolean permanent=cause instanceof org.apache.kafka.common.errors.RecordTooLargeException
            || cause instanceof org.apache.kafka.common.errors.SerializationException
            || cause instanceof org.apache.kafka.common.errors.InvalidTopicException;
        // Kafka marks transient cluster conditions as retriable; a send that times out waiting is the same.
        boolean brokerUnavailable=cause instanceof org.apache.kafka.common.errors.RetriableException
            || cause instanceof java.util.concurrent.TimeoutException || cause instanceof InterruptedException;
        outbox.recordFailure(id(work.get("id")),id(work.get("leaseToken")),cause.getClass().getSimpleName(),permanent,brokerUnavailable);
    }
    public void markPublished(UUID event,UUID token) {
        BillingExecutionBudget.requireTime();outbox.markPublished(event,token);
    }
    private static ThreadPoolExecutor pool(int size,int capacity,String name) {
        var count=new java.util.concurrent.atomic.AtomicInteger();
        return new ThreadPoolExecutor(size,size,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(capacity),task->{var t=new Thread(task,name+count.incrementAndGet());t.setDaemon(false);return t;});
    }
    private static void execute(ThreadPoolExecutor pool,Runnable task,Runnable abandoned) {
        var work=new ClaimedTask(task,abandoned);
        try {pool.execute(work);}catch(RejectedExecutionException rejected){work.release();}
    }
    static final class ClaimedTask implements Runnable {
        private final Runnable action,abandoned;
        private final java.util.concurrent.atomic.AtomicBoolean started=new java.util.concurrent.atomic.AtomicBoolean();
        ClaimedTask(Runnable action,Runnable abandoned){this.action=action;this.abandoned=abandoned;}
        public void run(){if(started.compareAndSet(false,true))try{action.run();}catch(RuntimeException failure){log(failure);}}
        void release(){if(started.compareAndSet(false,true))try{abandoned.run();}catch(RuntimeException failure){log(failure);}}
        private static void log(RuntimeException failure){
            org.slf4j.LoggerFactory.getLogger(BillingWorkers.class).error("Billing background operation remains durable and will be retried",failure);
            if(OperationalAlert.databaseUnavailable(failure))OperationalAlert.raiseAtMostEvery(java.time.Duration.ofMinutes(1),"worker","DATABASE_UNAVAILABLE",OperationalAlert.Severity.CRITICAL,
                "A background job could not reach the database; its work stays saved and is retried","source","worker");
        }
    }
    @PreDestroy public void close() {
        synchronized(this){stopping=true;io.shutdown();publishers.shutdown();}
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(shutdownSeconds);
        for(var executor:List.of(io,publishers)) {
            try {if(!executor.awaitTermination(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS))releaseQueued(executor.shutdownNow());}
            catch(InterruptedException interrupted){releaseQueued(executor.shutdownNow());Thread.currentThread().interrupt();}
        }
        // Interrupted or abandoned external work remains uncertain. Its durable
        // fenced lease, saved request and operation key survive this process.
    }
    private static void releaseQueued(List<Runnable> queued){for(var task:queued)if(task instanceof ClaimedTask claimed)claimed.release();}
}
