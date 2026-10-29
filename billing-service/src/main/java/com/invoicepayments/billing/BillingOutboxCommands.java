package com.invoicepayments.billing;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;

/**
 * The outbox publisher's database units: claim, mark published, and failure bookkeeping. The Kafka send happens in
 * BillingWorkers between them, with no transaction open. Every update is fenced by the claim's lease token.
 */
@Service
@Profile("billing")
@Transactional(transactionManager="transactionManager",propagation=Propagation.REQUIRED,rollbackFor=Exception.class,timeout=10,label="billing")
public class BillingOutboxCommands {
    // Claims consume the budget, so repeated worker crashes cannot retry forever. A broker outage gives the
    // claim back: the event is not at fault, so it waits (BillingOutboxNotDraining alerts) instead of being parked.
    static final int MAX_OUTBOX_ATTEMPTS=50;
    private final BillingStore db;
    public BillingOutboxCommands(BillingStore db) {this.db=db;}
    public List<Map<String,Object>> claim(int limit) {
        BillingExecutionBudget.requireTime();requireTransaction();
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
    }
    public void markPublished(UUID event,UUID token) {
        BillingExecutionBudget.requireTime();requireTransaction();
        int changed=db.update("UPDATE billing.outbox SET state='PUBLISHED',published_at=NOW(),lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=? AND state='IN_FLIGHT' AND lease_until>NOW()",event,token);
        if(changed==1) db.update("UPDATE billing.dead_letters SET state='REPLAYED' WHERE replay_outbox_id=?",event);
    }
    public void releaseUnstarted(UUID event,UUID token){
        requireTransaction();BillingExecutionBudget.requireTime();
        db.update("UPDATE billing.outbox SET state='NEW',lease_token=NULL,lease_until=NULL,next_attempt_at=NOW(),attempts=GREATEST(attempts-1,0) WHERE id=? AND lease_token=? AND state='IN_FLIGHT'",event,token);
    }
    /**
     * A permanent failure, or the attempt limit for a failure that is not a broker outage, quarantines the event with
     * its audit row. Otherwise the claim is given back; a broker outage does not use up the event's attempts.
     */
    public void recordFailure(UUID event,UUID token,String cause,boolean permanent,boolean brokerUnavailable) {
        BillingExecutionBudget.requireTime();requireTransaction();
        var owned=db.rows("""
            SELECT id,aggregate_id,attempts FROM billing.outbox
            WHERE id=? AND lease_token=? AND state='IN_FLIGHT' AND lease_until>NOW() FOR UPDATE
            """,event,token);
        if(owned.isEmpty()) return;
        var current=owned.get(0);
        if(permanent || (!brokerUnavailable && amount(current,"attempts")>=MAX_OUTBOX_ATTEMPTS))
            quarantine(current,cause);
        else db.update("UPDATE billing.outbox SET state='NEW',attempts=attempts-?,lease_token=NULL,lease_until=NULL,next_attempt_at=NOW()+INTERVAL '10 seconds' WHERE id=?",brokerUnavailable?1:0,id(current.get("id")));
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
}
