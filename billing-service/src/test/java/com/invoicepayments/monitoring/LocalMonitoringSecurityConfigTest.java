package com.invoicepayments.monitoring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalMonitoringSecurityConfigTest {

    @Test
    void onlyGetPrometheusOnTheSeparateManagementPortMatches() {
        var matcher = LocalMonitoringSecurityConfig.prometheusMatcher(9090, 8084);
        var request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.setLocalPort(9090);
        assertThat(matcher.matches(request)).isTrue();

        request.setLocalPort(8084);
        assertThat(matcher.matches(request)).isFalse();
        request.setLocalPort(9090);
        request.setRequestURI("/actuator/env");
        assertThat(matcher.matches(request)).isFalse();
        request.setRequestURI("/actuator/prometheus");
        request.setMethod("POST");
        assertThat(matcher.matches(request)).isFalse();
    }

    @Test
    void missingOrSharedManagementPortFailsClosed() {
        assertThatThrownBy(() -> LocalMonitoringSecurityConfig.prometheusMatcher(0, 8084))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LocalMonitoringSecurityConfig.prometheusMatcher(8084, 8084))
            .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "prod,local-observability", "local-observability,prod"})
    void productionCannotEnableTheLocalScrapeChain(String profiles) {
        new ApplicationContextRunner()
            .withUserConfiguration(LocalMonitoringSecurityConfig.class)
            .withPropertyValues("spring.profiles.active=" + profiles)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(SecurityFilterChain.class);
            });
    }

    @Test
    void localScrapeChainIsNotEnabledByDefault() {
        new ApplicationContextRunner()
            .withUserConfiguration(LocalMonitoringSecurityConfig.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(SecurityFilterChain.class);
            });
    }
}
