package com.invoicepayments.billing;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizers;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;

/** One named manager; Boot's default JDBC manager backs off and its customizers are retained. */
@Configuration(proxyBeanMethods=false)
@Profile("billing")
@EnableTransactionManagement(proxyTargetClass=true)
public class BillingTransactionConfiguration {
    @Bean(name="transactionManager")
    BillingDeadlineTransactionManager transactionManager(DataSource source,ObjectProvider<TransactionManagerCustomizers> customizers) {
        var manager=new BillingDeadlineTransactionManager(source);
        customizers.ifAvailable(value->value.customize(manager));
        return manager;
    }
}
