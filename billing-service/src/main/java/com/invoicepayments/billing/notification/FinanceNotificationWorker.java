package com.invoicepayments.billing.notification;

import org.springframework.scheduling.annotation.Scheduled;
import static com.invoicepayments.billing.BillingStore.*;

/** One message per tick bounds SMTP load; money continues when email is unavailable. */
public final class FinanceNotificationWorker {
    private final FinanceNotificationStore store;private final FinanceMailTransport transport;
    private boolean customerFirst;
    public FinanceNotificationWorker(FinanceNotificationStore store,FinanceMailTransport transport){this.store=store;this.transport=transport;}
    @Scheduled(fixedDelayString="${billing.notifications.interval-ms:1000}")
    public synchronized void tick(){
        customerFirst=!customerFirst;
        var n=store.claim(customerFirst?"CUSTOMER":"FINANCE");if(n==null)return;
        var id=id(n.get("id"));var token=id(n.get("leaseToken"));if(!store.beforeSend(id,token))return;
        try{transport.send(n);}
        catch(FinanceMailTransport.RejectedNotification rejected){store.rejected(id,token);return;}
        catch(RuntimeException failure){
            // Class only: mail server replies can echo addresses. NOTIFICATION_DELIVERY_FAILING alerts on repeats.
            org.slf4j.LoggerFactory.getLogger(FinanceNotificationWorker.class).warn("Email not accepted; it stays queued and is retried notificationId={} failure={}",id,failure.getClass().getSimpleName());
            store.failed(id,token);return;
        }
        try{store.accepted(id,token);}
        catch(RuntimeException uncertainAck){org.slf4j.LoggerFactory.getLogger(FinanceNotificationWorker.class).warn("Email acceptance acknowledgement uncertain; durable lease retained");}
    }
}
