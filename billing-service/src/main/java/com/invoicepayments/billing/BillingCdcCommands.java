package com.invoicepayments.billing;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;

/** Relay handover and observed Kafka delivery are local commands; neither calls the connector or broker. */
@Service
@Profile("billing")
@Transactional(transactionManager="transactionManager",propagation=Propagation.REQUIRED,rollbackFor=Exception.class,timeout=10,label="billing")
public class BillingCdcCommands {
    private final BillingStore db;
    public BillingCdcCommands(BillingStore db){this.db=db;}
    public Map<String,Object> switchRelay(String expectedMode,String nextMode,long expectedGeneration){
        requireTransaction();BillingExecutionBudget.requireTime();
        // The drain query must see event writers that committed while FOR UPDATE waited.
        // REQUIRED joins an outer transaction, so an annotation alone cannot guarantee its isolation.
        if(!"read committed".equals(text(db.one("SHOW transaction_isolation"),"transactionIsolation")))
            throw new IllegalTransactionStateException("Relay handover requires READ COMMITTED transaction isolation");
        if(!Set.of("POLL","CDC").contains(expectedMode)||!Set.of("POLL","CDC").contains(nextMode)||expectedGeneration<1)
            throw BillingProblem.invalid("Relay mode and generation are required");
        var control=db.one("SELECT * FROM billing.outbox_relay_control WHERE id FOR UPDATE");
        if(!expectedMode.equals(control.get("mode"))||amount(control,"generation")!=expectedGeneration)
            throw new BillingProblem(409,"RELAY_CHANGED","Re-read relay mode and generation before changing it");
        if(expectedMode.equals(nextMode))return control;
        if(db.exists("""
            SELECT 1 FROM billing.outbox o WHERE state IN ('NEW','IN_FLIGHT','CDC_PENDING')
            OR (state='DEAD_LETTER' AND NOT EXISTS
              (SELECT 1 FROM billing.dead_letters d WHERE d.source_outbox_id=o.id AND d.state='REPLAYED'))
            """))
            throw new BillingProblem(409,"RELAY_NOT_DRAINED","All existing relay work must reach Kafka before handover");
        return db.one("UPDATE billing.outbox_relay_control SET mode=?,generation=generation+1,changed_at=NOW() WHERE id RETURNING *",nextMode);
    }
    public void recordDelivery(UUID transportId,String aggregateId,byte[] bytes){
        requireTransaction();BillingExecutionBudget.requireTime();
        var received=BillingEvents.parse(bytes);
        var found=db.rows("""
            SELECT o.aggregate_id,e.payload::text AS payload,o.state,(o.payload=e.payload) AS canonical_matches
            FROM billing.outbox o JOIN billing.outbox_events e ON e.id=o.id
            WHERE o.id=? FOR UPDATE OF o
            """,transportId);
        if(found.isEmpty())throw new IllegalArgumentException("Unknown CDC transport identity");
        var canonical=found.get(0);
        if(!Boolean.TRUE.equals(canonical.get("canonicalMatches"))||!Objects.equals(aggregateId,text(canonical,"aggregateId"))
            ||!new org.json.JSONObject(text(canonical,"payload")).similar(received))
            throw new IllegalArgumentException("Kafka receipt differs from the canonical outbox event");
        if(!Set.of("CDC_PENDING","PUBLISHED").contains(text(canonical,"state")))
            throw new IllegalStateException("CDC receipt cannot acknowledge a different relay");
        db.update("UPDATE billing.outbox SET state='PUBLISHED',published_at=COALESCE(published_at,NOW()) WHERE id=? AND state='CDC_PENDING'",transportId);
        db.update("UPDATE billing.dead_letters SET state='REPLAYED' WHERE replay_outbox_id=? AND state='REPLAY_QUEUED'",transportId);
    }
}
