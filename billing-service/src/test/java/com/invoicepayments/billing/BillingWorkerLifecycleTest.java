package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.StripeWebhookVerifier;
import com.invoicepayments.billing.provider.RazorpayWebhookVerifier;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** Role exclusion, stop fencing and drain of BillingWorkers. No database, broker or provider: collaborators are mocks. */
class BillingWorkerLifecycleTest {
    final BillingStore db=mock(BillingStore.class);
    final BillingRecovery recovery=mock(BillingRecovery.class);
    final BillingInbox inbox=mock(BillingInbox.class);
    final BillingOutboxCommands outbox=mock(BillingOutboxCommands.class);
    @SuppressWarnings("unchecked") final KafkaTemplate<String,byte[]> kafka=mock(KafkaTemplate.class);
    /** Stands in for a provider call that has not answered yet. */
    final CountDownLatch provider=new CountDownLatch(1);
    final List<BillingWorkers> opened=new ArrayList<>();

    @AfterEach void stopEverything() {
        provider.countDown();
        for(var workers:opened)workers.close();
    }

    // ---- Role exclusion in a real (tiny) Spring context ----

    @Configuration(proxyBeanMethods=false) @EnableScheduling static class Scheduling {}

    ApplicationContextRunner roles(String... properties) {
        return new ApplicationContextRunner()
            // Environment properties are applied before withBean registrations, which evaluate each mocked
            // class's @Profile("billing"); an initializer would run too late.
            .withPropertyValues("spring.profiles.active=billing")
            // The first fixed-delay run still happens at once; later runs cannot interfere with assertions.
            .withPropertyValues("billing.worker-shutdown-seconds=1","billing.recovery-interval-ms=3600000","billing.inbox-interval-ms=3600000",
                "billing.expiry-interval-ms=3600000","billing.outbox-interval-ms=3600000")
            .withPropertyValues(properties)
            .withBean(BillingStore.class,()->db).withBean(BillingRecovery.class,()->recovery)
            .withBean(BillingInbox.class,()->inbox).withBean(BillingOutboxCommands.class,()->outbox)
            .withBean(BillingService.class,()->mock(BillingService.class)).withBean(BillingRefundService.class,()->mock(BillingRefundService.class))
            .withBean(StripeWebhookVerifier.class,()->mock(StripeWebhookVerifier.class))
            .withBean(RazorpayWebhookVerifier.class,()->mock(RazorpayWebhookVerifier.class))
            .withBean(BillingReconciliation.class,()->mock(BillingReconciliation.class))
            .withBean(BillingCaseService.class,()->mock(BillingCaseService.class))
            .withBean(BillingPaymentRetry.class,()->mock(BillingPaymentRetry.class))
            .withUserConfiguration(Scheduling.class,BillingWorkers.class,BillingController.class);
    }

    @Test void apiRoleServesTheControllerAndSchedulesNoBackgroundWork() {
        roles("billing.role=api","billing.workers-enabled=true","billing.jobs.recovery-enabled=true").run(context->{
            assertThat(context).hasNotFailed().hasSingleBean(BillingController.class).doesNotHaveBean(BillingWorkers.class);
            assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks()).isEmpty();
        });
        verifyNoInteractions(recovery,inbox,outbox);
    }

    @Test void workerRoleSchedulesAllFourJobsAndHasNoCustomerController() {
        roles("billing.role=worker").run(context->{
            assertThat(context).hasNotFailed().hasSingleBean(BillingWorkers.class).doesNotHaveBean(BillingController.class);
            assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks()).hasSize(4);
        });
    }

    @Test void combinedRoleKeepsBothAndItsMasterSwitchStillRemovesWorkers() {
        roles().run(context->assertThat(context).hasNotFailed().hasSingleBean(BillingWorkers.class).hasSingleBean(BillingController.class));
        roles("billing.workers-enabled=false").run(context->{
            assertThat(context).hasNotFailed().hasSingleBean(BillingController.class).doesNotHaveBean(BillingWorkers.class);
            assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks()).isEmpty();
        });
    }

    @ParameterizedTest @ValueSource(strings={"API","Worker","workers"})
    void misspelledRoleFailsStartupInsteadOfSilentlyRunningWorkers(String role) {
        roles("billing.role="+role).run(context->assertThat(context).hasFailed());
    }

    @ParameterizedTest @ValueSource(strings={"0","91"})
    void drainWindowOutsideTheTerminationBudgetFailsStartup(String seconds) {
        roles("billing.role=worker","billing.worker-shutdown-seconds="+seconds).run(context->assertThat(context).hasFailed());
    }

    @Test void apiRoleRegistersNoRunningKafkaConsumersAndNoTopicAdministration() {
        new ApplicationContextRunner()
            .withPropertyValues("spring.profiles.active=billing")
            // Overrides that would start every consumer in another role.
            .withPropertyValues("billing.role=api","billing.erp-simulator-enabled=true","billing.consumers.erp-enabled=true",
                "billing.consumers.dlt-enabled=true","billing.consumers.cdc-enabled=true")
            .withBean(KafkaProperties.class,KafkaProperties::new).withBean(MeterRegistry.class,SimpleMeterRegistry::new)
            .withBean(BillingEventCommands.class,()->mock(BillingEventCommands.class))
            .withBean(BillingCdcCommands.class,()->mock(BillingCdcCommands.class))
            .withUserConfiguration(BillingKafkaConfiguration.class,BillingEvents.class,BillingCdcReceipts.class)
            .run(context->{
                assertThat(context).hasNotFailed().doesNotHaveBean(NewTopic.class);
                var containers=context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainers();
                assertThat(containers).hasSize(3).allSatisfy(container->{
                    assertThat(container.isAutoStartup()).isFalse();assertThat(container.isRunning()).isFalse();
                });
            });
    }

    // ---- Job switches on a directly constructed worker (defence if a conditional is bypassed) ----

    BillingWorkers workers(MockEnvironment env) {
        @SuppressWarnings("unchecked") ObjectProvider<KafkaTemplate<String,byte[]>> kafkaProvider=mock(ObjectProvider.class);
        when(kafkaProvider.getIfAvailable()).thenReturn(kafka);
        var workers=new BillingWorkers(db,recovery,inbox,kafkaProvider,outbox);
        workers.configureRuntime(env);opened.add(workers);return workers;
    }
    static MockEnvironment worker() {return new MockEnvironment().withProperty("billing.role","worker");}
    static void runEveryJob(BillingWorkers workers) {workers.recover();workers.receive();workers.expireUnsubmitted();workers.publish();}
    static Map<String,Object> job(int number) {return Map.of("id",UUID.randomUUID(),"number",number);}

    @ParameterizedTest @ValueSource(strings={"recovery","inbox","expiry","publisher"})
    void eachJobSwitchStopsOnlyItsOwnClaims(String disabled) {
        runEveryJob(workers(worker().withProperty("billing.jobs."+disabled+"-enabled","false")));
        int recoveryRuns=disabled.equals("recovery")?0:1;
        verify(recovery,times(recoveryRuns)).claimRefunds(anyInt());verify(recovery,times(recoveryRuns)).claimPayments(anyInt());
        verify(inbox,times(disabled.equals("inbox")?0:1)).drain(25);
        verify(recovery,times(disabled.equals("expiry")?0:1)).expireUnsubmitted(anyInt(),anyInt());
        verify(outbox,times(disabled.equals("publisher")?0:1)).claim(anyInt());
    }

    @Test void apiOrMasterSwitchedOffWorkerClaimsNothingEvenWhenInvokedDirectly() {
        runEveryJob(workers(new MockEnvironment().withProperty("billing.role","api")));
        runEveryJob(workers(new MockEnvironment().withProperty("billing.workers-enabled","false")));
        verifyNoInteractions(recovery,inbox,outbox);
    }

    @Test void noJobClaimsAfterStop() {
        var workers=workers(worker());
        workers.close();
        runEveryJob(workers);
        verifyNoInteractions(recovery,inbox,outbox);
    }

    // ---- Drain ----

    @Test void stopDrainsStartedProviderWorkWithoutInterruptingItAndClaimsNothingMeanwhile() throws Exception {
        var job=job(0);var started=new CountDownLatch(1);
        var finished=new AtomicBoolean();var interrupted=new AtomicBoolean();
        when(recovery.claimPayments(anyInt())).thenReturn(List.of(job));
        doAnswer(call->{
            started.countDown();
            try{provider.await();finished.set(true);}catch(InterruptedException stop){interrupted.set(true);}
            return null;
        }).when(recovery).recoverPayment(job);
        var workers=workers(worker().withProperty("billing.worker-shutdown-seconds","10"));
        workers.recover();
        assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();

        var closing=CompletableFuture.runAsync(workers::close);
        Thread.sleep(300);
        assertThat(closing).as("close waits for the provider call").isNotDone();
        // Scheduler ticks that arrive during the drain must not take more work.
        runEveryJob(workers);
        verify(recovery,times(1)).claimPayments(anyInt());verify(recovery,times(1)).claimRefunds(anyInt());
        verifyNoInteractions(inbox,outbox);

        provider.countDown();
        closing.get(5,TimeUnit.SECONDS);
        assertThat(finished).isTrue();assertThat(interrupted).isFalse();
        verify(recovery,never()).releaseUnstarted(any(),anyBoolean());
    }

    @Test void drainTimeoutReleasesEveryQueuedClaimExactlyOnceAndNeverReleasesStartedWork() throws Exception {
        // More claims than threads: four start, four wait in the queue, one is rejected. This is the
        // over-claim that the bounded queue exists to absorb.
        List<Map<String,Object>> jobs=IntStream.range(0,9).mapToObj(BillingWorkerLifecycleTest::job).toList();
        var running=new CountDownLatch(4);var interrupted=new AtomicInteger();
        Set<Object> started=ConcurrentHashMap.newKeySet();
        when(recovery.claimPayments(anyInt())).thenReturn(jobs);
        doAnswer(call->{
            started.add(call.getArgument(0));running.countDown();
            try{provider.await();}catch(InterruptedException stop){interrupted.incrementAndGet();}
            return null;
        }).when(recovery).recoverPayment(any());
        var workers=workers(worker().withProperty("billing.worker-shutdown-seconds","1"));
        workers.recover();
        assertThat(running.await(5,TimeUnit.SECONDS)).isTrue();
        verify(recovery).releaseUnstarted(jobs.get(8),false);
        // One failed release must not strand the others.
        doThrow(new IllegalStateException("database unavailable")).when(recovery).releaseUnstarted(jobs.get(4),false);

        long begin=System.nanoTime();
        workers.close();
        assertThat(Duration.ofNanos(System.nanoTime()-begin)).isBetween(Duration.ofMillis(900),Duration.ofSeconds(10));

        for(int queued=4;queued<9;queued++){
            verify(recovery,times(1)).releaseUnstarted(jobs.get(queued),false);
            verify(recovery,never()).recoverPayment(jobs.get(queued));
        }
        for(int active=0;active<4;active++)verify(recovery,never()).releaseUnstarted(jobs.get(active),false);
        assertThat(started).containsExactlyInAnyOrderElementsOf(jobs.subList(0,4));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(interrupted).hasValue(4));
        verify(recovery,never()).releaseUnstarted(any(),eq(true));
    }

    @Test void aClaimedTaskIsEitherRunOrReleasedNeverBothEvenUnderRace() throws Exception {
        var ran=new AtomicInteger();var released=new AtomicInteger();
        var runFirst=new BillingWorkers.ClaimedTask(ran::incrementAndGet,released::incrementAndGet);
        runFirst.run();runFirst.release();runFirst.run();
        assertThat(ran).hasValue(1);assertThat(released).hasValue(0);
        var releaseFirst=new BillingWorkers.ClaimedTask(ran::incrementAndGet,released::incrementAndGet);
        releaseFirst.release();releaseFirst.run();releaseFirst.release();
        assertThat(ran).hasValue(1);assertThat(released).hasValue(1);

        var race=Executors.newFixedThreadPool(2);
        try {
            for(int round=0;round<500;round++){
                var outcomes=new AtomicInteger();var gate=new CountDownLatch(1);
                var task=new BillingWorkers.ClaimedTask(outcomes::incrementAndGet,outcomes::incrementAndGet);
                var a=race.submit(()->{gate.await();task.run();return null;});
                var b=race.submit(()->{gate.await();task.release();return null;});
                gate.countDown();a.get(5,TimeUnit.SECONDS);b.get(5,TimeUnit.SECONDS);
                assertThat(outcomes).hasValue(1);
            }
        } finally {race.shutdownNow();}
    }
}
