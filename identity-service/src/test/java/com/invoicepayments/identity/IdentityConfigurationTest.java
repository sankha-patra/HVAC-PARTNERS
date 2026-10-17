package com.invoicepayments.identity;

import com.invoicepayments.identity.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.*;

class IdentityConfigurationTest {
    @Test void productionRequiresSecureCookieAndStrongNonPlaceholderSecret() {
        var env=production();
        env.setProperty("app.jwt.cookie-secure","true");
        env.setProperty("app.jwt.secret","a-secure-test-secret-with-more-than-48-characters-for-prod-tests");
        assertThatCode(()->new IdentityConfiguration(env)).doesNotThrowAnyException();
        env.setProperty("app.jwt.secret","change-me-this-is-a-long-placeholder-value-that-should-not-work");
        assertThatThrownBy(()->new IdentityConfiguration(env)).isInstanceOf(IllegalStateException.class);
    }
    @Test void productionRequiresHttpsAndRejectsUnsafeOrigins() {
        var env=production();
        env.setProperty("app.jwt.cookie-secure","true");
        env.setProperty("app.jwt.secret","a-secure-test-secret-with-more-than-48-characters-for-prod-tests");
        env.setProperty("billing.public-url","http://pay.example.test");
        assertThatThrownBy(()->new IdentityConfiguration(env)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->SecurityConfig.validatePublicOrigin("https://pay.example.test/path",true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->SecurityConfig.validatePublicOrigin("https://*.example.test",true)).isInstanceOf(IllegalStateException.class);
        assertThat(SecurityConfig.validatePublicOrigin("https://pay.example.test",true)).isEqualTo("https://pay.example.test");
    }
    @Test void productionCookieAndTokenLifetimeFailClosed() {
        var env=production();
        env.setProperty("app.jwt.secret","a-secure-test-secret-with-more-than-48-characters-for-prod-tests");
        env.setProperty("app.jwt.cookie-secure","false");
        assertThatThrownBy(()->new IdentityConfiguration(env)).isInstanceOf(IllegalStateException.class);
        env.setProperty("app.jwt.cookie-secure","true");env.setProperty("app.jwt.ttl-seconds","901");
        assertThatThrownBy(()->new IdentityConfiguration(env)).isInstanceOf(IllegalStateException.class);
    }
    @Test void productionRejectsDemoAndSimulatorFlags() {
        var env=production();
        env.setProperty("app.jwt.cookie-secure","true");
        env.setProperty("app.jwt.secret","a-secure-test-secret-with-more-than-48-characters-for-prod-tests");
        env.setProperty("billing.simulator-enabled","true");
        assertThatThrownBy(()->new IdentityConfiguration(env)).isInstanceOf(IllegalStateException.class);
    }
    private static MockEnvironment production() {
        return new MockEnvironment().withProperty("spring.profiles.active","prod")
            .withProperty("billing.public-url","https://payments.example.test");
    }
}
