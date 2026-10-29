package com.invoicepayments.billing;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class BillingRuntimeSettingsTest {
    @Test void apiRoleCannotEnableBackgroundWorkOrConsumersEvenWithOverrides() {
        var env=new MockEnvironment().withProperty("billing.role","api")
            .withProperty("billing.jobs.recovery-enabled","true").withProperty("billing.erp-simulator-enabled","true");
        var settings=new BillingRuntimeSettings(env);
        assertThat(settings.servesApi()).isTrue();
        for(String name:new String[]{"recovery","inbox","expiry","publisher"})assertThat(settings.jobEnabled(name)).isFalse();
        for(String name:new String[]{"dlt","erp","cdc"})assertThat(settings.consumerEnabled(name)).isFalse();
    }
    @Test void workerRoleOwnsJobsButNoCustomerApiAndEachJobCanBeDisabled() {
        var settings=new BillingRuntimeSettings(new MockEnvironment().withProperty("billing.role","worker")
            .withProperty("billing.jobs.publisher-enabled","false").withProperty("billing.consumers.dlt-enabled","false"));
        assertThat(settings.servesApi()).isFalse();
        assertThat(settings.jobEnabled("recovery")).isTrue();assertThat(settings.jobEnabled("publisher")).isFalse();
        assertThat(settings.consumerEnabled("dlt")).isFalse();assertThat(settings.consumerEnabled("cdc")).isTrue();
    }
    @Test void legacyCombinedRoleRetainsItsMasterSwitchAndSimulatorOptIn() {
        var settings=new BillingRuntimeSettings(new MockEnvironment());
        assertThat(settings.role()).isEqualTo("combined");assertThat(settings.servesApi()).isTrue();
        assertThat(settings.jobEnabled("recovery")).isTrue();assertThat(settings.consumerEnabled("erp")).isFalse();
        settings=new BillingRuntimeSettings(new MockEnvironment().withProperty("billing.workers-enabled","false"));
        assertThat(settings.jobEnabled("recovery")).isFalse();
    }
    @Test void rejectsUnknownRoleAndIncompleteOrOversubscribedFleetBudgets() {
        assertThatIllegalArgumentException().isThrownBy(()->new BillingRuntimeSettings(new MockEnvironment().withProperty("billing.role","workers")));
        assertThatIllegalArgumentException().isThrownBy(()->new BillingRuntimeSettings(new MockEnvironment().withProperty("billing.stripe.fleet-concurrency-budget","16")));
        assertThatIllegalArgumentException().isThrownBy(()->new BillingRuntimeSettings(new MockEnvironment()
            .withProperty("billing.stripe.fleet-concurrency-budget","16").withProperty("billing.stripe.maximum-fleet-tasks","4")
            .withProperty("billing.stripe.max-concurrent","8")));
        assertThatCode(()->new BillingRuntimeSettings(new MockEnvironment().withProperty("billing.stripe.fleet-concurrency-budget","16")
            .withProperty("billing.stripe.maximum-fleet-tasks","4").withProperty("billing.stripe.max-concurrent","4"))).doesNotThrowAnyException();
    }
}
