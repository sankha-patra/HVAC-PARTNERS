package com.invoicepayments.billing.recovery;

import com.invoicepayments.billing.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import java.time.Duration;

@Configuration(proxyBeanMethods=false) @Profile("billing")
@ConditionalOnProperty(name="billing.aws-recovery.enabled",havingValue="true")
public class RecoveryDispatchConfiguration {
    @Bean RecoverySettings recoverySettings(Environment env){return RecoverySettings.from(env);}
    @Bean @org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
    RecoveryDispatchStore recoveryDispatchStore(BillingStore db,PlatformTransactionManager manager,RecoverySettings settings){var store=new RecoveryDispatchStore(db,manager,settings);store.initialize();return store;}
    /**
     * The dispatcher's own narrow identity (states:StartExecution/DescribeExecution only) from the standard chain, for
     * example AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY of the payments-hybrid-dispatcher user. A named profile is a
     * developer fallback only: a personal sign-in expires and holds far more access than the dispatcher needs.
     */
    @Bean(destroyMethod="close") SfnClient recoverySfnClient(Environment env){
        String profile=env.getProperty("billing.aws-recovery.profile","");
        AwsCredentialsProvider credentials=profile.isBlank()?DefaultCredentialsProvider.builder().build():ProfileCredentialsProvider.create(profile);
        return SfnClient.builder().region(Region.AP_SOUTHEAST_2).credentialsProvider(credentials)
            .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(5))
                .retryPolicy(software.amazon.awssdk.core.retry.RetryPolicy.none()).build()).build();
    }
    @Bean RecoveryRemote recoveryRemote(SfnClient client){return new StepFunctionsRecoveryRemote(client);}
    @Bean RecoveryDispatcher recoveryDispatcher(RecoveryDispatchStore store,RecoveryRemote remote){return new RecoveryDispatcher(store,remote);}
    @Bean DispatchJob recoveryDispatchJob(RecoveryDispatcher dispatcher){return new DispatchJob(dispatcher);}
    public static final class DispatchJob {
        private final RecoveryDispatcher dispatcher;DispatchJob(RecoveryDispatcher dispatcher){this.dispatcher=dispatcher;}
        @Scheduled(fixedDelayString="${billing.aws-recovery.interval-ms:1000}") public void run(){dispatcher.tick();}
        /** Written every minute while it holds, like the other condition alerts, so the alarm sends one email and one OK. */
        @Scheduled(fixedDelay=60000,initialDelay=30000) public void campaign(){
            var health=dispatcher.campaignHealth();
            long starts=((Number)health.get("starts")).longValue(),max=((Number)health.get("maxStarts")).longValue(),left=((Number)health.get("secondsLeft")).longValue();
            if(left<86400||starts*5>=max*4)OperationalAlert.raise("RECOVERY_CAMPAIGN_EXPIRING",OperationalAlert.Severity.WARNING,
                "The recovery campaign ends soon or has used most of its starts; after that uncertain payments are not recovered automatically",
                "secondsLeft",Math.max(left,0),"starts",starts,"maxStarts",max);
        }
    }
}
