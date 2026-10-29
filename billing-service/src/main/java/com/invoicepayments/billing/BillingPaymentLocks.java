package com.invoicepayments.billing;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;

/** Locking participants must be called through the injected proxy inside a command's transaction. */
@Component
@Profile("billing")
@Transactional(transactionManager="transactionManager",propagation=Propagation.MANDATORY,rollbackFor=Exception.class,label="billing")
public class BillingPaymentLocks {
    private final BillingStore db;
    public BillingPaymentLocks(BillingStore db){this.db=db;}
    public Map<String,Object> locked(UUID intent){requireTransaction();return db.one("SELECT * FROM billing.intents WHERE id=? FOR UPDATE",intent);}
    public List<Map<String,Object>> lockInvoices(UUID intent){requireTransaction();return db.rows("SELECT a.* FROM billing.allocations a JOIN billing.invoices v ON v.id=a.invoice_id WHERE a.intent_id=? ORDER BY v.id FOR UPDATE OF v,a",intent);}
    public Map<String,Object> leaseForRelease(UUID intent,UUID token) {
        requireTransaction();locked(intent);
        if(db.update("UPDATE billing.intents SET lease_token=?,lease_until=NOW()+INTERVAL '120 seconds',next_recovery_at=NOW()+INTERVAL '120 seconds' WHERE id=? AND (lease_until IS NULL OR lease_until<NOW())",token,intent)==0)
            throw BillingProblem.conflict("Recovery is checking this payment with the provider right now. Try the release again in two minutes");
        return db.one("""
            SELECT i.id AS intent_id,i.amount_minor,i.currency,i.method,i.provider_id,i.created_at,i.lease_until,a.original_request,a.request_version,a.created_at AS prepared_at
            FROM billing.intents i LEFT JOIN billing.attempts a ON a.intent_id=i.id WHERE i.id=?
            """,intent);
    }
}
