package com.invoicepayments.billing.recovery;

import com.invoicepayments.billing.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import java.time.Duration;

@Configuration(proxyBeanMethods=false) @Profile("billing")
@ConditionalOnProperty(name="billing.aws-recovery.enabled",havingValue="true")
public class RecoveryDispatchConfiguration {
    @Bean RecoverySettings recoverySettings(Environment env){return RecoverySettings.from(env);}
    @Bean @org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
    RecoveryDispatchStore recoveryDispatchStore(BillingStore db,PlatformTransactionManager manager,RecoverySettings settings){var store=new RecoveryDispatchStore(db,manager,settings);store.initialize();return store;}
    @Bean(destroyMethod="close") SfnClient recoverySfnClient(){return SfnClient.builder().region(Region.AP_SOUTHEAST_2)
        .credentialsProvider(ProfileCredentialsProvider.create("payments-lab"))
        .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(5))
            .retryPolicy(software.amazon.awssdk.core.retry.RetryPolicy.none()).build()).build();}
    @Bean RecoveryRemote recoveryRemote(SfnClient client){return new StepFunctionsRecoveryRemote(client);}
    @Bean RecoveryDispatcher recoveryDispatcher(RecoveryDispatchStore store,RecoveryRemote remote){return new RecoveryDispatcher(store,remote);}
    @Bean DispatchJob recoveryDispatchJob(RecoveryDispatcher dispatcher){return new DispatchJob(dispatcher);}
    public static final class DispatchJob {
        private final RecoveryDispatcher dispatcher;DispatchJob(RecoveryDispatcher dispatcher){this.dispatcher=dispatcher;}
        @Scheduled(fixedDelayString="${billing.aws-recovery.interval-ms:1000}") public synchronized void run(){dispatcher.tick();}
    }
}
