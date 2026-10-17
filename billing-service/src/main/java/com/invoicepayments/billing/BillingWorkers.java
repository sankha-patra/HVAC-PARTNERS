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

@Component
@Profile("billing")
@ConditionalOnProperty(name="billing.workers-enabled",havingValue="true",matchIfMissing=true)
public class BillingWorkers {
    private final BillingStore db; private final BillingRecovery recovery; private final BillingInbox inbox;
    private final KafkaTemplate<String,byte[]> kafka;
    private final ThreadPoolExecutor io=pool(4,4,"billing-recovery-");
    private final ThreadPoolExecutor publishers=pool(8,8,"billing-publisher-");
    // Claims consume the budget, so repeated worker crashes cannot retry forever.
    static final int MAX_OUTBOX_ATTEMPTS=50;
    private boolean refundFirst;
    public BillingWorkers(BillingStore db,BillingRecovery recovery,BillingInbox inbox,ObjectProvider<KafkaTemplate<String,byte[]>> kafka) {
        this.db=db;this.recovery=recovery;this.inbox=inbox;this.kafka=kafka.getIfAvailable();
    }
    @Scheduled(fixedDelayString="${billing.recovery-interval-ms:1000}")
    public void recover() {
        int available=io.getMaximumPoolSize()-io.getActiveCount()-io.getQueue().size();
        if(available<=0) return;
        refundFirst=!refundFirst;
        if(refundFirst) {
            var jobs=recovery.claimRefunds(available);available-=jobs.size();
            for(var job:jobs) execute(io,()->recovery.recoverRefund(job));
        }
        if(available>0) {
            var jobs=recovery.claimPayments(available);available-=jobs.size();
            for(var job:jobs) execute(io,()->recovery.recoverPayment(job));
        }
        if(available>0 && !refundFirst) for(var job:recovery.claimRefunds(available)) execute(io,()->recovery.recoverRefund(job));
    }
    @Scheduled(fixedDelayString="${billing.inbox-interval-ms:1000}") public void receive() { inbox.drain(25); }
    @Scheduled(fixedDelayString="${billing.outbox-interval-ms:100}")
    public void publish() {
        int available=publishers.getMaximumPoolSize()-publishers.getActiveCount()-publishers.getQueue().size();
        if(kafka==null || available<=0) return;
        for(var work:claimOutbox(available)) execute(publishers,()->publishOne(work));
    }
    public List<Map<String,Object>> claimOutbox(int limit) {
        return db.tx(() -> {
            // Only the oldest unpublished event for each payment is eligible. A head
            // that is leased, locked or waiting for retry blocks its successors.
            var rows=db.rows("""
                SELECT o.id,o.aggregate_id,o.payload::text AS payload,o.attempts,o.publish_sequence
                FROM billing.outbox o
                WHERE o.state IN ('NEW','IN_FLIGHT') AND o.next_attempt_at<=NOW()
                  AND (o.lease_until IS NULL OR o.lease_until<NOW())
                  AND NOT EXISTS (SELECT 1 FROM billing.outbox earlier
                    WHERE earlier.aggregate_id=o.aggregate_id AND earlier.state IN ('NEW','IN_FLIGHT')
                      AND earlier.publish_sequence<o.publish_sequence)
                ORDER BY o.publish_sequence FOR UPDATE OF o SKIP LOCKED LIMIT ?
                """,Math.max(0,Math.min(limit,50)));
            List<Map<String,Object>> claimed=new ArrayList<>();
            for(var row:rows) {
                if(amount(row,"attempts")>=MAX_OUTBOX_ATTEMPTS) {
                    quarantine(row,"AttemptLimitAfterExpiredLease");continue;
                }
                UUID token=UUID.randomUUID();row.put("leaseToken",token);
                db.update("UPDATE billing.outbox SET state='IN_FLIGHT',attempts=attempts+1,lease_token=?,lease_until=NOW()+INTERVAL '30 seconds',next_attempt_at=NOW()+INTERVAL '30 seconds' WHERE id=?",token,id(row.get("id")));
                claimed.add(row);
            }
            return claimed;
        });
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
    private void failPublication(Map<String,Object> work,Exception failure) {
        db.tx(() -> {
            var owned=db.rows("""
                SELECT id,aggregate_id,attempts FROM billing.outbox
                WHERE id=? AND lease_token=? AND state='IN_FLIGHT' AND lease_until>NOW() FOR UPDATE
                """,id(work.get("id")),id(work.get("leaseToken")));
            if(owned.isEmpty()) return null;
            var current=owned.get(0);
            Throwable cause=failure;
            while(cause.getCause()!=null && cause.getCause()!=cause) cause=cause.getCause();
            boolean permanent=cause instanceof org.apache.kafka.common.errors.RecordTooLargeException
                || cause instanceof org.apache.kafka.common.errors.SerializationException
                || cause instanceof org.apache.kafka.common.errors.InvalidTopicException;
            if(permanent || amount(current,"attempts")>=MAX_OUTBOX_ATTEMPTS)
                quarantine(current,cause.getClass().getSimpleName());
            else db.update("UPDATE billing.outbox SET state='NEW',lease_token=NULL,lease_until=NULL,next_attempt_at=NOW()+INTERVAL '10 seconds' WHERE id=?",id(current.get("id")));
            return null;
        });
    }
    // Caller holds the outbox row lock. Audit and terminal state commit together.
    private void quarantine(Map<String,Object> work,String reason) {
        UUID event=id(work.get("id"));
        int reopened=db.update("UPDATE billing.dead_letters SET state='OPEN',failure_reason=? WHERE replay_outbox_id=?",reason,event);
        if(reopened==0) {
            int audited=db.update("""
                INSERT INTO billing.dead_letters(id,merchant_id,topic,event_key,raw_payload,source_outbox_id,failure_reason)
                SELECT ?,a.merchant_id,'billing.events.v1',o.aggregate_id::text,convert_to(o.payload::text,'UTF8'),o.id,?
                FROM billing.outbox o JOIN billing.intents i ON i.id=o.aggregate_id
                JOIN billing.accounts a ON a.id=i.account_id WHERE o.id=?
                ON CONFLICT(source_outbox_id) DO NOTHING
                """,UUID.randomUUID(),reason,event);
            if(audited==0 && !db.exists("SELECT 1 FROM billing.dead_letters WHERE source_outbox_id=?",event))
                throw new IllegalStateException("Cannot quarantine an outbox event without merchant ownership");
        }
        db.update("UPDATE billing.outbox SET state='DEAD_LETTER',lease_token=NULL,lease_until=NULL WHERE id=?",event);
    }
    public void markPublished(UUID event,UUID token) {
        db.tx(() -> {
            int changed=db.update("UPDATE billing.outbox SET state='PUBLISHED',published_at=NOW(),lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=? AND state='IN_FLIGHT' AND lease_until>NOW()",event,token);
            if(changed==1) db.update("UPDATE billing.dead_letters SET state='REPLAYED' WHERE replay_outbox_id=?",event);return null;
        });
    }
    private static ThreadPoolExecutor pool(int size,int capacity,String name) {
        var count=new java.util.concurrent.atomic.AtomicInteger();
        return new ThreadPoolExecutor(size,size,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(capacity),task->{var t=new Thread(task,name+count.incrementAndGet());t.setDaemon(true);return t;});
    }
    private static void execute(ThreadPoolExecutor pool,Runnable task) {
        try {pool.execute(()->{try{task.run();}catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger(BillingWorkers.class).error("Billing background operation will be retried",error);}});}
        catch(RejectedExecutionException ignored) { /* Durable fenced leases expire; no work is acknowledged. */ }
    }
    @PreDestroy public void close() {io.shutdown();publishers.shutdown();}
}
