package com.invoicepayments.commerce;

import com.invoicepayments.billing.BillingStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.util.UUID;

/** Fictional local catalog only. Existing prices and inventory are never reset on restart. */
@Component @Profile("billing & !prod")
@ConditionalOnProperty(name="billing.demo-enabled",havingValue="true")
public class CommerceDemoSeeder {
    private final BillingStore db;public CommerceDemoSeeder(BillingStore db){this.db=db;}
    @EventListener(ApplicationReadyEvent.class)
    @Transactional(transactionManager="transactionManager",propagation=Propagation.REQUIRED,rollbackFor=Exception.class,timeout=10,label="billing")
    public void seed(){
        UUID merchant=UUID.fromString("10000000-0000-0000-0000-000000000001"),list=UUID.fromString("50000000-0000-0000-0000-000000000001");
        if(!db.exists("SELECT 1 FROM billing.merchants WHERE id=?",merchant))return;
        db.update("INSERT INTO commerce.price_lists(id,merchant_id,name,currency) VALUES(?,?,'Demo company catalog','INR') ON CONFLICT DO NOTHING",list,merchant);
        db.update("INSERT INTO commerce.account_price_lists(account_id,merchant_id,price_list_id) SELECT id,merchant_id,? FROM billing.accounts WHERE merchant_id=? ON CONFLICT DO NOTHING",list,merchant);
        String[] names={"Air filter set","Thermostat sensor","Maintenance kit","Control relay"};
        for(int n=0;n<names.length;n++){
            UUID product=UUID.fromString("60000000-0000-0000-0000-00000000000"+(n+1));
            db.update("INSERT INTO commerce.products(id,merchant_id,sku,name,category) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",product,merchant,"HVAC-10"+(n+1),names[n],n<2?"Sensors & filters":"Service parts");
            db.update("INSERT INTO commerce.prices(price_list_id,product_id,merchant_id,unit_price_minor) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",list,product,merchant,new long[]{100,250,500,200}[n]);
            db.update("INSERT INTO commerce.stock(product_id,saleable_qty) VALUES(?,25) ON CONFLICT DO NOTHING",product);
        }
    }
}
