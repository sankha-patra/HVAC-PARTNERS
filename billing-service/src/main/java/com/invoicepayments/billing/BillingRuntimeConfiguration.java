package com.invoicepayments.billing;

import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false)
@Profile("billing")
public class BillingRuntimeConfiguration {
    @Bean BillingRuntimeSettings billingRuntimeSettings(Environment env){return new BillingRuntimeSettings(env);}
    @Bean org.springframework.boot.ApplicationRunner billingRelayReadiness(Environment env,org.springframework.jdbc.core.JdbcTemplate jdbc){
        return args->{
            String required=env.getProperty("billing.required-relay-mode","");
            if(!required.isBlank()&&!required.equals(jdbc.queryForObject("SELECT mode FROM billing.outbox_relay_control WHERE id",String.class)))
                throw new IllegalStateException("The database relay handover has not completed for this deployment");
        };
    }
}
