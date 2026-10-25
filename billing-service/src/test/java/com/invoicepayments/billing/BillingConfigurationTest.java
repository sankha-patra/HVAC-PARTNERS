package com.invoicepayments.billing;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class BillingConfigurationTest {
    @Test void productionRequiresHttpsStrongJwtSecureCookiesAndShortExpiry() {
        var env=prod();
        env.setProperty("app.jwt.secret","a-secure-test-secret-with-more-than-48-characters-for-prod-tests");
        env.setProperty("app.jwt.cookie-secure","true");
        env.setProperty("billing.stripe.webhook-secret","whsec_test-fixture");
        env.setProperty("billing.provider","STRIPE");env.setProperty("billing.stripe.secret-key","sk_test_fixture");
        assertThatCode(()->new BillingConfiguration(env)).doesNotThrowAnyException();
        env.setProperty("billing.public-url","http://payments.example.test");
        assertThatThrownBy(()->new BillingConfiguration(env)).isInstanceOf(IllegalStateException.class);
    }
    @Test void productionRejectsWeakJwtAndInsecureCookie() {
        var weak=ready();weak.setProperty("app.jwt.secret","short");
        assertThatThrownBy(()->new BillingConfiguration(weak)).isInstanceOf(IllegalStateException.class);
        var insecure=ready();insecure.setProperty("app.jwt.cookie-secure","false");
        assertThatThrownBy(()->new BillingConfiguration(insecure)).isInstanceOf(IllegalStateException.class);
    }
    @Test void productionRequiresWebhookAndTheExplicitTestProvider() {
        var noWebhook=ready();noWebhook.setProperty("billing.stripe.webhook-secret","");
        assertThatThrownBy(()->new BillingConfiguration(noWebhook)).isInstanceOf(IllegalStateException.class);
        var simulator=ready();simulator.setProperty("billing.provider","SIMULATOR");
        assertThatThrownBy(()->new BillingConfiguration(simulator)).isInstanceOf(IllegalStateException.class);
    }
    @Test void productionRejectsSimulatorFlagsAndLongLivedTokens() {
        var demo=ready();demo.setProperty("billing.erp-simulator-enabled","true");
        assertThatThrownBy(()->new BillingConfiguration(demo)).isInstanceOf(IllegalStateException.class);
        var longToken=ready();longToken.setProperty("app.jwt.ttl-seconds","901");
        assertThatThrownBy(()->new BillingConfiguration(longToken)).isInstanceOf(IllegalStateException.class);
    }
    @Test void originMustBeOneExactOrigin() {
        assertThatThrownBy(()->BillingConfiguration.validatePublicOrigin("https://merchant.example/path",true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->BillingConfiguration.validatePublicOrigin("https://user@merchant.example",true)).isInstanceOf(IllegalStateException.class);
        assertThat(BillingConfiguration.validatePublicOrigin("https://merchant.example",true)).isEqualTo("https://merchant.example");
    }
    @Test void admissionWaitMustStayBelowTheDatabaseLockTimeout() {
        for (String wait : java.util.List.of("3s", "4s", "-1ms")) {
            var env = new MockEnvironment().withProperty("billing.stripe.admission-wait", wait);
            assertThatThrownBy(() -> new BillingConfiguration(env).billingProvider(null, null, env,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), "STRIPE", "sk_test_fixture"))
                .as("admission wait %s", wait).isInstanceOf(IllegalArgumentException.class);
        }
        var env = new MockEnvironment().withProperty("billing.stripe.admission-wait", "100ms");
        assertThat(new BillingConfiguration(env).billingProvider(null, null, env,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), "STRIPE", "sk_test_fixture")).isNotNull();
    }

    private static MockEnvironment prod() {return new MockEnvironment().withProperty("spring.profiles.active","prod").withProperty("billing.public-url","https://payments.example.test");}
    private static MockEnvironment ready() {
        return prod().withProperty("app.jwt.secret","a-secure-test-secret-with-more-than-48-characters-for-prod-tests")
            .withProperty("app.jwt.cookie-secure","true").withProperty("billing.stripe.webhook-secret","whsec_test-fixture")
            .withProperty("billing.provider","STRIPE").withProperty("billing.stripe.secret-key","sk_test_fixture");
    }
}
