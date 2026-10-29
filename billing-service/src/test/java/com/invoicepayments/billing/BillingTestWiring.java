package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.BillingProvider;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

/** Uses production command beans/proxy configuration; legacy tests get no outer transaction. */
public final class BillingTestWiring {
    private static final Queue<AnnotationConfigApplicationContext> contexts=new ConcurrentLinkedQueue<>();
    private BillingTestWiring(){}
    static BillingService payments(BillingStore db,BillingProvider provider,Environment env,boolean simulator,String publicUrl,String merchant) {
        var context=context(db,provider,env,simulator,publicUrl,merchant);
        context.registerBean(BillingService.class);context.refresh();contexts.add(context);
        return context.getBean(BillingService.class);
    }
    static AnnotationConfigApplicationContext context(BillingStore db,BillingProvider provider,Environment env,boolean simulator,String publicUrl,String merchant) {
        var context=new AnnotationConfigApplicationContext();
        var profiles=new LinkedHashSet<String>(List.of(env.getActiveProfiles()));profiles.add("billing");
        context.getEnvironment().setActiveProfiles(profiles.toArray(String[]::new));
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-billing",Map.of(
            "billing.simulator-enabled",simulator,"billing.public-url",publicUrl,"billing.provider-merchant-id",merchant)));
        context.registerBean(javax.sql.DataSource.class,()->Objects.requireNonNull(db.jdbc().getDataSource()),definition->definition.setDestroyMethodName(""));
        context.registerBean(org.springframework.jdbc.core.JdbcTemplate.class,()->db.jdbc());
        context.registerBean(BillingStore.class,()->db);
        context.registerBean(BillingProvider.class,()->provider);
        context.register(BillingTransactionConfiguration.class);
        var scanner=new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
        scanner.setEnvironment(context.getEnvironment());
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile("com\\.invoicepayments\\.billing\\.Billing.*(Commands|Participants|Access|Locks)$")));
        for(var definition:scanner.findCandidateComponents("com.invoicepayments.billing")) {
            try {context.register(Class.forName(definition.getBeanClassName()));}
            catch(ClassNotFoundException failure){throw new IllegalStateException(failure);}
        }
        context.registerBean(BillingService.class);context.registerBean(BillingRefundService.class);
        return context;
    }
    public static com.invoicepayments.billing.provider.SimulatorBillingProvider simulator(org.springframework.jdbc.core.JdbcTemplate jdbc,org.springframework.transaction.PlatformTransactionManager unused) {
        var context=new AnnotationConfigApplicationContext();context.getEnvironment().setActiveProfiles("billing");
        context.registerBean(javax.sql.DataSource.class,()->Objects.requireNonNull(jdbc.getDataSource()),definition->definition.setDestroyMethodName(""));
        context.registerBean(org.springframework.jdbc.core.JdbcTemplate.class,()->jdbc);
        context.register(BillingTransactionConfiguration.class,com.invoicepayments.billing.provider.SimulatorOperationStore.class);
        context.refresh();contexts.add(context);
        return new com.invoicepayments.billing.provider.SimulatorBillingProvider(context.getBean(com.invoicepayments.billing.provider.SimulatorOperationStore.class));
    }
    static BillingInbox inbox(BillingStore db,BillingService payments,BillingRefundService refunds,String merchant) {
        var context=context(db,org.mockito.Mockito.mock(BillingProvider.class),new org.springframework.mock.env.MockEnvironment(),true,"http://localhost:4200",merchant);
        context.registerBean(BillingService.class,()->payments);context.registerBean(BillingRefundService.class,()->refunds);
        context.registerBean(BillingInbox.class);context.refresh();contexts.add(context);return context.getBean(BillingInbox.class);
    }
    private static AnnotationConfigApplicationContext forPayment(BillingStore db,BillingService payments,BillingProvider provider) {
        var context=context(db,provider,new org.springframework.mock.env.MockEnvironment(),payments.simulationEnabled(),payments.configuredPublicUrl(),payments.configuredMerchant());
        context.registerBean(BillingService.class,()->payments);return context;
    }
    private static <T> T finish(AnnotationConfigApplicationContext context,Class<T> type) {
        context.registerBean(type);context.refresh();contexts.add(context);return context.getBean(type);
    }
    static BillingRefundService refunds(BillingStore db,BillingService payments,BillingProvider provider) {
        return finish(forPayment(db,payments,provider),BillingRefundService.class);
    }
    static BillingRecovery recovery(BillingStore db,BillingService payments,BillingRefundService refunds,BillingProvider provider) {
        var context=forPayment(db,payments,provider);context.registerBean(BillingRefundService.class,()->refunds);
        return finish(context,BillingRecovery.class);
    }
    static BillingPaymentRetry retry(BillingStore db,BillingService payments) {
        return finish(forPayment(db,payments,payments.configuredProvider()),BillingPaymentRetry.class);
    }
    static BillingReconciliation reconciliation(BillingStore db,BillingService payments,org.springframework.core.env.Environment env,boolean simulated) {
        var context=context(db,payments.configuredProvider(),env,simulated,payments.configuredPublicUrl(),payments.configuredMerchant());
        context.registerBean(BillingService.class,()->payments);return finish(context,BillingReconciliation.class);
    }
    static BillingEvents events(BillingStore db,String merchant) {
        return finish(context(db,org.mockito.Mockito.mock(BillingProvider.class),new org.springframework.mock.env.MockEnvironment(),true,"http://localhost:4200",merchant),BillingEvents.class);
    }
    static BillingWorkers workers(BillingStore db,BillingRecovery recovery,BillingInbox inbox,org.springframework.beans.factory.ObjectProvider<org.springframework.kafka.core.KafkaTemplate<String,byte[]>> kafka) {
        var context=context(db,org.mockito.Mockito.mock(BillingProvider.class),new org.springframework.mock.env.MockEnvironment(),true,"http://localhost:4200","10000000-0000-0000-0000-000000000001");
        context.registerBean(BillingRecovery.class,()->recovery==null?org.mockito.Mockito.mock(BillingRecovery.class):recovery);
        context.registerBean(BillingInbox.class,()->inbox==null?org.mockito.Mockito.mock(BillingInbox.class):inbox);
        var template=kafka.getIfAvailable();if(template!=null)context.getBeanFactory().registerSingleton("borrowedKafkaTemplate",template);
        return finish(context,BillingWorkers.class);
    }
    static BillingCaseService cases(BillingStore db,BillingService payments,BillingRefundService refunds,BillingProvider provider) {
        var context=forPayment(db,payments,provider);context.registerBean(BillingRefundService.class,()->refunds);return finish(context,BillingCaseService.class);
    }
    public static void closeContexts(){AnnotationConfigApplicationContext context;while((context=contexts.poll())!=null)context.close();}
}
