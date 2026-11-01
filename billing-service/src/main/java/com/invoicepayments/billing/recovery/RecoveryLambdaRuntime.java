package com.invoicepayments.billing.recovery;

import com.invoicepayments.billing.*;
import com.invoicepayments.billing.provider.*;
import com.zaxxer.hikari.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;
import java.util.function.BooleanSupplier;

/** Small command-only Spring context. No HTTP server, Kafka, migrations, seeders or background schedules. */
public final class RecoveryLambdaRuntime implements AutoCloseable {
    private final HikariDataSource pool;private final AnnotationConfigApplicationContext context;
    private final RecoveryTask task;
    public RecoveryLambdaRuntime() {this(RecoveryBootstrap.runtimeConfiguration());}
    RecoveryLambdaRuntime(Map<String,String> env) {this(env,provider(env));}
    /** Recovery uses the provider the payments were made with; only that provider's credentials were read. */
    private static BillingProvider provider(Map<String,String> env){
        return "STRIPE".equals(RecoveryBootstrap.provider(env))?new StripeBillingProvider(required(env,"BILLING_STRIPE_SECRET_KEY"))
            :new RazorpayBillingProvider(required(env,"BILLING_RAZORPAY_KEY_ID"),required(env,"BILLING_RAZORPAY_KEY_SECRET"));
    }
    // The fault artifact supplies an adapter with a controlled HTTP transport; no env endpoint switch exists.
    RecoveryLambdaRuntime(Map<String,String> env,BillingProvider provider) {
        if(!RecoverySettings.PROVIDERS.contains(Objects.requireNonNull(provider).name()))throw new IllegalArgumentException("Recovery runtime requires the Razorpay or Stripe protocol");
        if(!"true".equals(env.get("RECOVERY_PRIVATE_ROUTE_READY")))throw new IllegalStateException("A reviewed private database route is required");
        String url=required(env,"RECOVERY_JDBC_URL");
        URI route=URI.create(url.substring("jdbc:".length()));
        if(!url.startsWith("jdbc:postgresql://")||route.getHost()==null||route.getUserInfo()!=null||!privateHost(route.getHost()))
            throw new IllegalArgumentException("Recovery requires a private tailnet database host");
        var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername(required(env,"RECOVERY_DB_USER"));config.setPassword(required(env,"RECOVERY_DB_PASSWORD"));
        config.setMaximumPoolSize(2);config.setMinimumIdle(0);config.setConnectionTimeout(10000);config.setValidationTimeout(2000);
        config.setIdleTimeout(30000);config.setMaxLifetime(120000);config.setKeepaliveTime(30000);
        config.addDataSourceProperty("ApplicationName","payments-recovery-lambda");
        config.addDataSourceProperty("connectTimeout","3");config.addDataSourceProperty("socketTimeout","10");
        config.addDataSourceProperty("socketFactory",RecoverySocksSocketFactory.class.getName());
        config.addDataSourceProperty("sslmode","disable"); // PostgreSQL traffic is inside the restricted WireGuard tunnel.
        config.addDataSourceProperty("options","-c statement_timeout=8000 -c lock_timeout=3000 -c idle_in_transaction_session_timeout=10000");
        pool=new HikariDataSource(config);
        var jdbc=new JdbcTemplate(pool);var db=new BillingStore(jdbc);
        context=new AnnotationConfigApplicationContext();context.getEnvironment().setActiveProfiles("billing");
        var properties=new HashMap<String,Object>(Map.of("billing.simulator-enabled",false,"billing.provider",provider.name(),
            "billing.public-url",env.getOrDefault("BILLING_PUBLIC_URL","http://localhost:4500"),"billing.provider-merchant-id",required(env,"BILLING_PROVIDER_MERCHANT_ID")));
        if("RAZORPAY".equals(provider.name()))properties.put("billing.razorpay.key-id",required(env,"BILLING_RAZORPAY_KEY_ID"));
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("recovery",properties));
        context.registerBean(javax.sql.DataSource.class,()->pool,definition->definition.setDestroyMethodName(""));
        context.registerBean(JdbcTemplate.class,()->jdbc);context.registerBean(BillingStore.class,()->db);context.registerBean(BillingProvider.class,()->provider);
        context.register(BillingTransactionConfiguration.class);
        var scanner=new ClassPathScanningCandidateComponentProvider(false);scanner.setEnvironment(context.getEnvironment());
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile("com\\.invoicepayments\\.billing\\.Billing.*(Commands|Participants|Access|Locks)$")));
        for(var definition:scanner.findCandidateComponents("com.invoicepayments.billing"))try{context.register(Class.forName(definition.getBeanClassName()));}catch(ClassNotFoundException failure){throw new IllegalStateException(failure);}
        context.registerBean(BillingService.class);context.registerBean(BillingRefundService.class);context.registerBean(BillingRecovery.class);
        try{context.refresh();task=new RecoveryTask(db,context.getBean(BillingRecovery.class),context.getBean(PlatformTransactionManager.class),UUID.fromString(required(env,"RECOVERY_CAMPAIGN_ID")),UUID.fromString(required(env,"BILLING_PROVIDER_MERCHANT_ID")),provider.name());}
        catch(RuntimeException failure){context.close();pool.close();throw failure;}
    }
    public Map<String,Object> run(String kind,UUID operation,boolean admit){return run(kind,operation,()->admit);}
    Map<String,Object> run(String kind,UUID operation,BooleanSupplier admit){return runGuarded(pool,task,kind,operation,admit);}
    static Map<String,Object> runGuarded(HikariDataSource pool,RecoveryTask task,String kind,UUID operation,BooleanSupplier admit){
        RecoveryConnectionGate.beforeInvocation(pool);
        // Refill/validation can consume invocation time after thaw; decide whether to claim only after it completes.
        return admit.getAsBoolean()?task.run(kind,operation):task.state(kind,operation,false);
    }
    private static String required(Map<String,String> env,String name){String value=env.get(name);if(value==null||value.isBlank())throw new IllegalStateException("Missing "+name);return value;}
    private static boolean privateHost(String host){
        if(!host.matches("100\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}"))return false;
        int[] parts=Arrays.stream(host.split("\\.")).mapToInt(Integer::parseInt).toArray();return parts[1]>=64&&parts[1]<=127&&parts[2]<=255&&parts[3]<=255;
    }
    public void close(){context.close();pool.close();}
}
