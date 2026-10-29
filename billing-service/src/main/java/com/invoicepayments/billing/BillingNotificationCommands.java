package com.invoicepayments.billing;

import org.json.JSONObject;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.invoicepayments.billing.BillingStore.*;

/** Consumer receipt and delivery intent commit together. Never calls SMTP or trusts an address in Kafka. */
@Service @Profile("billing")
@Transactional(transactionManager="transactionManager",propagation=Propagation.REQUIRED,rollbackFor=Exception.class,timeout=10,label="billing")
public class BillingNotificationCommands {
    private final BillingStore db;
    public BillingNotificationCommands(BillingStore db){this.db=db;}
    public void receive(JSONObject event){
        BillingExecutionBudget.requireTime();requireTransaction();
        UUID eventId=id(event.getString("eventId")),intent=id(event.getString("intentId"));String type=event.getString("eventType");
        if(event.getInt("schemaVersion")!=1||!Set.of("PAYMENT_CAPTURED","PAYMENT_REFUNDED").contains(type))throw new IllegalArgumentException("Unsupported customer notification event");
        var source=db.one("SELECT payload::text AS payload,publish_sequence FROM billing.outbox WHERE id=? AND aggregate_id=?",eventId,intent);
        if(!BillingEvents.parse(text(source,"payload").getBytes(StandardCharsets.UTF_8)).similar(event))throw new IllegalArgumentException("Notification differs from canonical payment event");
        var payment=db.one("SELECT i.*,a.merchant_id FROM billing.intents i JOIN billing.accounts a ON a.id=i.account_id WHERE i.id=?",intent);
        if(!text(payment,"accountId").equals(event.getString("accountId"))||!text(payment,"currency").equals(event.getString("currency"))
            ||event.has("intentAmountMinor")&&BillingEvents.exact(event,"intentAmountMinor")!=amount(payment,"amountMinor"))throw new IllegalArgumentException("Notification payment scope mismatch");
        // Validate immutable canonical content even on replay. First-time evidence failures roll back this receipt.
        if(db.update("INSERT INTO billing.consumer_inbox(consumer,event_id) VALUES('CUSTOMER_EMAIL_V1',?) ON CONFLICT DO NOTHING",eventId)==0)return;
        UUID operation=intent;long minor=BillingEvents.exact(event,"amountMinor");String kind="CUSTOMER_PAYMENT_RECEIVED";
        if("PAYMENT_REFUNDED".equals(type)){
            operation=id(event.getString("refundId"));kind="CUSTOMER_REFUND_CONFIRMED";
            if(!db.exists("SELECT 1 FROM billing.refunds WHERE id=? AND intent_id=? AND amount_minor=? AND status='REFUNDED'",operation,intent,minor)
                ||!db.exists("SELECT 1 FROM billing.journals WHERE intent_id=? AND effect_key=?",intent,"refund:"+operation))throw new IllegalArgumentException("Refund receipt has no confirmed money evidence");
        }else if(minor!=amount(payment,"amountMinor")||!db.exists("SELECT 1 FROM billing.journals WHERE intent_id=? AND effect_key=?",intent,"capture:"+intent))throw new IllegalArgumentException("Payment receipt has no capture journal");
        var owners=db.rows("SELECT user_id FROM billing.payment_notification_owners WHERE intent_id=? AND account_id=?",intent,id(payment.get("accountId")));
        String user=owners.isEmpty()?null:text(owners.get(0),"userId");String recipient=null;long contactRevision=0;
        long first=amount(db.one("SELECT minimum_sequence FROM billing.notification_activation WHERE consumer='CUSTOMER_EMAIL_V1'"),"minimumSequence");
        boolean activated=amount(source,"publishSequence")>=first;
        boolean returned="PAYMENT_CAPTURED".equals(type)&&db.exists("SELECT 1 FROM billing.journals WHERE intent_id=? AND effect_key=?",intent,"return:"+intent);
        if(user!=null&&activated&&!returned){
            var routes=db.rows("""
                SELECT c.recipient,c.revision FROM billing.customer_notification_contacts c
                JOIN billing.memberships m ON m.account_id=c.account_id AND m.user_id=c.user_id
                JOIN billing.outbox o ON o.id=?
                WHERE c.account_id=? AND c.user_id=? AND c.enabled AND c.verified_at IS NOT NULL
                AND c.eligible_from<=o.created_at AND c.verified_at<=o.created_at
                """,eventId,id(payment.get("accountId")),user);
            if(!routes.isEmpty()){recipient=text(routes.get(0),"recipient");contactRevision=amount(routes.get(0),"revision");}
        }
        db.update("""
            INSERT INTO billing.notification_deliveries(merchant_id,event_key,event_type,intent_id,operation_id,recipient,state,last_error,
              audience,account_id,recipient_user_id,amount_minor,currency,created_at,contact_revision)
            VALUES(?,?,?,?,?,?,?,?,'CUSTOMER',?,?,?,?,(SELECT created_at FROM billing.outbox WHERE id=?),?) ON CONFLICT DO NOTHING
            """,id(payment.get("merchantId")),"customer:"+kind+":"+operation,kind,intent,operation,recipient,
                recipient==null?"SUPPRESSED":"PENDING",recipient==null?(returned?"PAYMENT_RETURNED":!activated?"PRE_ACTIVATION":user==null?"NO_SAVED_PAYER":"NO_ELIGIBLE_VERIFIED_CONTACT"):null,
                id(payment.get("accountId")),user,minor,text(payment,"currency"),eventId,contactRevision);
    }
}
