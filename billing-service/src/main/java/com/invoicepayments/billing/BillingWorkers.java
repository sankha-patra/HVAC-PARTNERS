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
            var rows=db.rows("SELECT id,aggregate_id,payload::text AS payload,attempts FROM billing.outbox WHERE state<>'PUBLISHED' AND next_attempt_at<=NOW() AND (lease_until IS NULL OR lease_until<NOW()) ORDER BY next_attempt_at,created_at FOR UPDATE SKIP LOCKED LIMIT ?",Math.min(limit,50));
            for(var row:rows) {UUID token=UUID.randomUUID();row.put("leaseToken",token);
                db.update("UPDATE billing.outbox SET state='IN_FLIGHT',lease_token=?,lease_until=NOW()+INTERVAL '30 seconds',next_attempt_at=NOW()+INTERVAL '30 seconds' WHERE id=?",token,id(row.get("id")));}
            return rows;
        });
    }
    public void publishOne(Map<String,Object> work) {
        if(kafka==null) return;
        try {
            kafka.send("billing.events.v1",text(work,"aggregateId"),text(work,"payload").getBytes(java.nio.charset.StandardCharsets.UTF_8)).get(12,TimeUnit.SECONDS);
            markPublished(id(work.get("id")),id(work.get("leaseToken")));
        } catch(Exception failure) {
            if(failure instanceof InterruptedException) Thread.currentThread().interrupt();
            db.update("UPDATE billing.outbox SET state='NEW',attempts=attempts+1,lease_token=NULL,lease_until=NULL,next_attempt_at=NOW()+INTERVAL '10 seconds' WHERE id=? AND lease_token=?",id(work.get("id")),id(work.get("leaseToken")));
        }
    }
    public void markPublished(UUID event,UUID token) {
        db.tx(() -> {
            int changed=db.update("UPDATE billing.outbox SET state='PUBLISHED',published_at=NOW(),lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?",event,token);
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
