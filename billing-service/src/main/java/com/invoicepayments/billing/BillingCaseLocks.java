package com.invoicepayments.billing;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;

@Component
@Profile("billing")
@Transactional(transactionManager="transactionManager",propagation=Propagation.MANDATORY,rollbackFor=Exception.class,label="billing")
public class BillingCaseLocks {
    private final BillingStore db;private final BillingPaymentLocks payments;
    public BillingCaseLocks(BillingStore db,BillingPaymentLocks payments){this.db=db;this.payments=payments;}
    public Map<String,Object> lock(Map<String,Object> item) {
        requireTransaction();
        UUID caseId=id(item.get("id"));
        if(item.get("intentId")!=null) payments.locked(id(item.get("intentId")));
        if(item.get("refundId")!=null) db.one("SELECT id FROM billing.refunds WHERE id=? FOR UPDATE",id(item.get("refundId")));
        db.one("SELECT id FROM billing.cases WHERE id=? FOR UPDATE",caseId);
        return db.one("""
            SELECT c.*,i.status AS payment_status,r.status AS refund_status FROM billing.cases c
            LEFT JOIN billing.intents i ON i.id=c.intent_id LEFT JOIN billing.refunds r ON r.id=c.refund_id WHERE c.id=?
            """,caseId);
    }
}
