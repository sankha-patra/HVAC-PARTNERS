package com.invoicepayments.billing.notification;

import com.invoicepayments.billing.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import java.util.UUID;

@Configuration(proxyBeanMethods=false) @Profile("billing")
@ConditionalOnProperty(name="billing.notifications.enabled",havingValue="true")
public class FinanceNotificationConfiguration {
    @Bean FinanceMailTransport financeMailTransport(Environment env){return new SmtpFinanceMailTransport(env);}
    @Bean FinanceNotificationWorker financeNotificationWorker(FinanceNotificationStore store,FinanceMailTransport mail,Environment env){
        if(!new BillingRuntimeSettings(env).jobEnabled("notifications"))throw new IllegalArgumentException("Email sender needs the notification worker role enabled");
        return new FinanceNotificationWorker(store,mail);
    }
    /**
     * Production finance route: the merchant's Carrier app-owner mailbox from deployment configuration. Refund approval
     * and finance case emails go there; without it they are dropped as NO_ACTIVE_ROUTE (and alerted).
     */
    @Bean @ConditionalOnProperty(name="billing.notifications.finance-recipient")
    ApplicationListener<ApplicationReadyEvent> financeRoute(BillingStore db,Environment env){return event->{
        if(env.getProperty("billing.notifications.test-mode",Boolean.class,false))return;
        String recipient=SmtpFinanceMailTransport.address(env.getRequiredProperty("billing.notifications.finance-recipient"));
        UUID merchant=UUID.fromString(env.getRequiredProperty("billing.provider-merchant-id"));
        db.update("INSERT INTO billing.notification_routes(merchant_id,recipient,enabled) VALUES(?,?,true) ON CONFLICT(merchant_id) DO UPDATE SET recipient=EXCLUDED.recipient,enabled=true",merchant,recipient);
    };}
    /** Lab-only opt-in. Production routing is explicit merchant configuration, never an arbitrary request field. */
    @Bean @ConditionalOnProperty(name="billing.notifications.test-mode",havingValue="true")
    ApplicationListener<ApplicationReadyEvent> testFinanceRoute(BillingStore db,Environment env){return event->{
        if(!env.getProperty("billing.demo-enabled",Boolean.class,false)||env.acceptsProfiles(Profiles.of("prod")))throw new IllegalStateException("Test finance routing requires a non-production demo");
        String recipient=SmtpFinanceMailTransport.address(env.getRequiredProperty("billing.notifications.test-recipient"));
        UUID merchant=UUID.fromString(env.getRequiredProperty("billing.provider-merchant-id"));
        db.update("INSERT INTO billing.notification_routes(merchant_id,recipient,enabled) VALUES(?,?,true) ON CONFLICT(merchant_id) DO UPDATE SET recipient=EXCLUDED.recipient,enabled=true",merchant,recipient);
        // Explicit test recipient for fictional demo buyers only. Production contacts come from verified identity data.
        db.update("""
            INSERT INTO billing.customer_notification_contacts(account_id,user_id,recipient,enabled,verified_at)
            SELECT m.account_id,m.user_id,?,true,NOW() FROM billing.memberships m JOIN billing.accounts a ON a.id=m.account_id
            JOIN identity.app_users u ON u.id::text=m.user_id WHERE a.merchant_id=? AND u.username IN ('demo','other') AND m.role='PAYER'
            ON CONFLICT(account_id,user_id) DO UPDATE SET recipient=EXCLUDED.recipient,enabled=true,
            verified_at=CASE WHEN billing.customer_notification_contacts.recipient=EXCLUDED.recipient AND billing.customer_notification_contacts.enabled THEN billing.customer_notification_contacts.verified_at ELSE NOW() END,
            eligible_from=CASE WHEN billing.customer_notification_contacts.recipient=EXCLUDED.recipient AND billing.customer_notification_contacts.enabled THEN billing.customer_notification_contacts.eligible_from ELSE NOW() END
            """,recipient,merchant);
    };}
}
