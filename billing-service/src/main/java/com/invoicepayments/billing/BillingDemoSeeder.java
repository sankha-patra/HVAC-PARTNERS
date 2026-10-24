package com.invoicepayments.billing;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
@Profile("billing & !prod")
@ConditionalOnProperty(name="billing.demo-enabled",havingValue="true")
public class BillingDemoSeeder implements ApplicationRunner {
    private final BillingStore db;
    public BillingDemoSeeder(BillingStore db) {this.db=db;}
    @Override public void run(ApplicationArguments args) {
        db.tx(()->{
            UUID merchant=UUID.fromString("10000000-0000-0000-0000-000000000001");
            UUID primary=UUID.fromString("20000000-0000-0000-0000-000000000001"),other=UUID.fromString("20000000-0000-0000-0000-000000000002");
            db.update("INSERT INTO billing.merchants(id,name) VALUES(?,'Northstar Commercial HVAC — fictional merchant') ON CONFLICT DO NOTHING",merchant);
            db.update("INSERT INTO billing.accounts(id,merchant_id,name,external_reference) VALUES(?,?,'Harbor Facilities — demo customer','DEMO-HARBOR'),(?,?,'Pinecrest Services — separate demo customer','DEMO-PINE') ON CONFLICT DO NOTHING",primary,merchant,other,merchant);
            db.update("INSERT INTO billing.memberships(account_id,user_id,role) SELECT ?,id::text,'PAYER' FROM identity.app_users WHERE username='demo' ON CONFLICT DO NOTHING",primary);
            db.update("INSERT INTO billing.memberships(account_id,user_id,role) SELECT ?,id::text,'PAYER' FROM identity.app_users WHERE username='other' ON CONFLICT DO NOTHING",other);
            db.update("INSERT INTO billing.staff(merchant_id,user_id,role) SELECT ?,id::text,'FINANCE' FROM identity.app_users WHERE username='admin' ON CONFLICT DO NOTHING",merchant);
            // The application team (service provider) gets OPS only: it reads and annotates cases but never decides.
            db.update("INSERT INTO billing.staff(merchant_id,user_id,role) SELECT ?,id::text,'OPS' FROM identity.app_users WHERE username='support' ON CONFLICT DO NOTHING",merchant);
            db.update("INSERT INTO billing.staff(merchant_id,user_id,role) SELECT ?,id::text,'REFUND_APPROVER' FROM identity.app_users WHERE username='reviewer' ON CONFLICT DO NOTHING",merchant);
            for(int i=1;i<=4;i++) db.update("INSERT INTO billing.invoices(id,account_id,number,currency,total_minor,due_date) VALUES(?,?,?,'USD',?,CURRENT_DATE+?) ON CONFLICT DO NOTHING",
                UUID.fromString("30000000-0000-0000-0000-00000000000"+i),i==4?other:primary,"DEMO-INV-10"+i,new long[]{0,112550,87525,14000,22500}[i],i*7);
            return null;
        });
    }
}
