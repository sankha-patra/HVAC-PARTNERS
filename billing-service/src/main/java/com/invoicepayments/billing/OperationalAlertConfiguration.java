package com.invoicepayments.billing;

import java.time.Duration;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.support.TaskUtils;

@Configuration(proxyBeanMethods=false) @Profile("billing")
public class OperationalAlertConfiguration {
    /**
     * Scheduled jobs (claims, scans, the outbox relay, email sending, the recovery dispatcher) fail on the scheduler
     * thread, where Spring only logs "Unexpected error occurred in scheduled task". A database outage there is the same
     * DATABASE_UNAVAILABLE an HTTP request raises, at most once a minute.
     */
    @Bean ThreadPoolTaskSchedulerCustomizer databaseOutageAlerts() {
        return scheduler->scheduler.setErrorHandler(failure->{
            TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER.handleError(failure);
            if(OperationalAlert.databaseUnavailable(failure))OperationalAlert.raiseAtMostEvery(Duration.ofMinutes(1),"scheduler","DATABASE_UNAVAILABLE",
                OperationalAlert.Severity.CRITICAL,"A scheduled job could not reach the database; its work stays saved and runs again when the database is back",
                "source","scheduler","job",Thread.currentThread().getName());
        });
    }
}
