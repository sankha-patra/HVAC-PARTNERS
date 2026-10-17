package com.invoicepayments.monitoring;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** Local Compose only: the management listener is not published to the host. */
@Configuration(proxyBeanMethods = false)
@Profile("local-observability & !prod")
public class LocalMonitoringSecurityConfig {

    @Bean
    @Order(-1)
    public SecurityFilterChain localPrometheusSecurityFilterChain(
            HttpSecurity http,
            @Value("${management.server.port:0}") int managementPort,
            @Value("${server.port:8084}") int applicationPort) throws Exception {
        return http
            .securityMatcher(prometheusMatcher(managementPort, applicationPort))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .build();
    }

    static RequestMatcher prometheusMatcher(int managementPort, int applicationPort) {
        if (managementPort <= 0 || managementPort == applicationPort) {
            throw new IllegalStateException("Local monitoring requires a separate management port");
        }
        return request -> request.getLocalPort() == managementPort
            && "GET".equals(request.getMethod())
            && "/actuator/prometheus".equals(request.getRequestURI());
    }
}
