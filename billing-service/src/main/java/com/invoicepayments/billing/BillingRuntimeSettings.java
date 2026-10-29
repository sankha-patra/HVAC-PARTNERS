package com.invoicepayments.billing;

import org.springframework.core.env.Environment;
import java.util.Set;

/** Deployment ownership is explicit; worker and API processes retain the same financial command code. */
public final class BillingRuntimeSettings {
    private final Environment env;
    private final String role;
    public BillingRuntimeSettings(Environment env) {
        this.env=env;this.role=env.getProperty("billing.role","combined");
        if(!Set.of("api","worker","combined").contains(role))throw new IllegalArgumentException("billing.role must be api, worker or combined");
        int budget=env.getProperty("billing.stripe.fleet-concurrency-budget",Integer.class,0);
        int tasks=env.getProperty("billing.stripe.maximum-fleet-tasks",Integer.class,0);
        int slots=env.getProperty("billing.stripe.max-concurrent",Integer.class,8);
        if(budget<0||tasks<0||slots<1||(budget==0)!=(tasks==0)||(budget>0&&(long)tasks*slots>budget))
            throw new IllegalArgumentException("Stripe permits across the maximum fleet, including rollout surge, must fit the configured account budget");
        if(env.getProperty("billing.require-separated-role",Boolean.class,false)&&role.equals("combined"))
            throw new IllegalArgumentException("This deployment requires an explicit api or worker role");
    }
    public String role(){return role;}
    public boolean servesApi(){return !role.equals("worker");}
    public boolean jobEnabled(String name){
        if(!Set.of("recovery","inbox","expiry","publisher","notifications").contains(name))throw new IllegalArgumentException("Unknown billing job");
        return !role.equals("api")&&env.getProperty("billing.workers-enabled",Boolean.class,true)
            &&env.getProperty("billing.jobs."+name+"-enabled",Boolean.class,true);
    }
    public boolean consumerEnabled(String name){
        if(!Set.of("dlt","cdc","erp","notifications").contains(name))throw new IllegalArgumentException("Unknown billing consumer");
        boolean fallback=!name.equals("erp")||env.getProperty("billing.erp-simulator-enabled",Boolean.class,false);
        return !role.equals("api")&&env.getProperty("billing.kafka-enabled",Boolean.class,true)
            &&env.getProperty("billing.consumers."+name+"-enabled",Boolean.class,fallback);
    }
}
