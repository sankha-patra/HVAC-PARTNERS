package com.invoicepayments.billing.notification;

import com.invoicepayments.billing.BillingStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;

/** Short durable claims. A lease fences acknowledgements, not external SMTP delivery. */
@Repository @Profile("billing")
public class FinanceNotificationStore {
    private static final String ROUTE="SELECT 1 FROM billing.active_notification_destinations r WHERE r.merchant_id=n.merchant_id AND r.audience=n.audience AND r.account_id IS NOT DISTINCT FROM n.account_id AND r.user_id IS NOT DISTINCT FROM n.recipient_user_id AND r.recipient=n.recipient AND r.contact_revision=n.contact_revision";
    private final BillingStore db;private final TransactionTemplate tx;
    public FinanceNotificationStore(BillingStore db,PlatformTransactionManager manager){this.db=db;tx=new TransactionTemplate(manager);tx.setTimeout(10);}
    public Map<String,Object> claim(){return claim("FINANCE");}
    public Map<String,Object> claim(String preferredAudience){
        if(!Set.of("FINANCE","CUSTOMER").contains(preferredAudience))throw new IllegalArgumentException("Notification audience required");
        requireNoTransaction();
        return tx.execute(s->{
            db.update("""
                UPDATE billing.notification_deliveries n SET state='SUPPRESSED',lease_token=NULL,lease_until=NULL,
                last_error=CASE WHEN n.attempts>0 THEN 'ROUTE_REVOKED_AFTER_UNCONFIRMED_SEND' ELSE 'ROUTE_REVOKED' END
                WHERE state IN ('PENDING','IN_FLIGHT') AND (lease_until IS NULL OR lease_until<NOW())
                AND NOT EXISTS(%s)
                """.formatted(ROUTE));
            db.update("UPDATE billing.notification_deliveries SET state='REVIEW',lease_token=NULL,lease_until=NULL,last_error='ATTEMPTS_EXHAUSTED' WHERE attempts=8 AND state='IN_FLIGHT' AND lease_until<NOW()");
            var rows=db.rows("""
                SELECT n.* FROM billing.notification_deliveries n
                WHERE n.attempts<8 AND n.next_at<=NOW() AND (n.state='PENDING' OR (n.state='IN_FLIGHT' AND n.lease_until<NOW()))
                AND EXISTS(%s)
                ORDER BY CASE WHEN n.audience=? THEN 0 ELSE 1 END,n.next_at,n.created_at,n.id FOR UPDATE OF n SKIP LOCKED LIMIT 1
                """.formatted(ROUTE),preferredAudience);
            if(rows.isEmpty())return null;
            UUID id=id(rows.get(0).get("id")),token=UUID.randomUUID();
            db.update("UPDATE billing.notification_deliveries SET state='IN_FLIGHT',attempts=attempts+1,lease_token=?,lease_until=NOW()+INTERVAL '120 seconds' WHERE id=?",token,id);
            return db.one("""
                SELECT n.*,o.order_number,
                  CASE WHEN n.audience='CUSTOMER' THEN n.amount_minor WHEN LEFT(n.event_type,7)='REFUND_' THEN r.amount_minor ELSE i.amount_minor END AS display_amount_minor,
                  CASE WHEN n.audience='CUSTOMER' THEN n.currency WHEN LEFT(n.event_type,7)='REFUND_' AND r.id IS NULL THEN NULL ELSE i.currency END AS display_currency
                FROM billing.notification_deliveries n JOIN billing.intents i ON i.id=n.intent_id
                LEFT JOIN commerce.orders o ON o.payment_reference=i.payment_reference AND o.account_id=i.account_id
                  AND (n.audience<>'CUSTOMER' OR n.account_id=i.account_id)
                LEFT JOIN billing.refunds r ON r.id=n.operation_id AND r.intent_id=i.id
                WHERE n.id=?
                """,id);
        });
    }
    public void accepted(UUID id,UUID token){requireNoTransaction();tx.executeWithoutResult(s->db.update("UPDATE billing.notification_deliveries SET state='SENT',accepted_at=NOW(),lease_token=NULL,lease_until=NULL,last_error=NULL WHERE id=? AND state='IN_FLIGHT' AND lease_token=?",id,token));}
    public void rejected(UUID id,UUID token){requireNoTransaction();tx.executeWithoutResult(s->db.update("UPDATE billing.notification_deliveries SET state='REVIEW',lease_token=NULL,lease_until=NULL,last_error='TEMPLATE_REJECTED' WHERE id=? AND state='IN_FLIGHT' AND lease_token=?",id,token));}
    /** Check ownership and current routing immediately before SMTP. Revocation during SMTP cannot retract mail. */
    public boolean beforeSend(UUID id,UUID token){requireNoTransaction();return Boolean.TRUE.equals(tx.execute(s->{
        int returned=db.update("""
            UPDATE billing.notification_deliveries n SET state='SUPPRESSED',lease_token=NULL,lease_until=NULL,last_error='PAYMENT_RETURNED'
            WHERE id=? AND lease_token=? AND state='IN_FLIGHT' AND event_type='CUSTOMER_PAYMENT_RECEIVED'
            AND EXISTS(SELECT 1 FROM billing.journals j WHERE j.intent_id=n.intent_id AND j.effect_key='return:'||n.intent_id)
            """,id,token);
        if(returned==1)return false;
        int count=db.update("""
            UPDATE billing.notification_deliveries n SET lease_until=NOW()+INTERVAL '120 seconds'
            WHERE id=? AND lease_token=? AND state='IN_FLIGHT' AND lease_until>NOW()
            AND EXISTS(%s)
            AND NOT (event_type='CUSTOMER_PAYMENT_RECEIVED' AND EXISTS(
              SELECT 1 FROM billing.journals j WHERE j.intent_id=n.intent_id AND j.effect_key='return:'||n.intent_id))
            """.formatted(ROUTE),id,token);
        if(count==0)db.update("""
            UPDATE billing.notification_deliveries n SET state='SUPPRESSED',lease_token=NULL,lease_until=NULL,last_error='PAYMENT_RETURNED'
            WHERE id=? AND lease_token=? AND state='IN_FLIGHT' AND event_type='CUSTOMER_PAYMENT_RECEIVED'
            AND EXISTS(SELECT 1 FROM billing.journals j WHERE j.intent_id=n.intent_id AND j.effect_key='return:'||n.intent_id)
            """,id,token);
        if(count==0)db.update("UPDATE billing.notification_deliveries n SET state='SUPPRESSED',lease_token=NULL,lease_until=NULL,last_error=CASE WHEN attempts>1 THEN 'ROUTE_REVOKED_AFTER_UNCONFIRMED_SEND' ELSE 'ROUTE_CHANGED' END WHERE id=? AND lease_token=? AND state='IN_FLIGHT' AND NOT EXISTS(%s)".formatted(ROUTE),id,token);
        return count==1;
    }));}
    public void failed(UUID id,UUID token){requireNoTransaction();tx.executeWithoutResult(s->db.update("""
        UPDATE billing.notification_deliveries SET state=CASE WHEN attempts>=8 THEN 'REVIEW' ELSE 'PENDING' END,
        next_at=NOW()+make_interval(secs=>LEAST(3600,30*(2^LEAST(attempts,7))::int)),lease_token=NULL,lease_until=NULL,last_error='SMTP_NOT_CONFIRMED'
        WHERE id=? AND state='IN_FLIGHT' AND lease_token=?
        """,id,token));}
}
