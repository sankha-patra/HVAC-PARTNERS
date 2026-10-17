package com.invoicepayments.identity;

import com.invoicepayments.identity.security.JwtAuthFilter;
import com.invoicepayments.identity.security.JwtService;
import com.invoicepayments.identity.security.SecurityConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Fail closed for the isolated identity process; no development authentication bypasses. */
@Configuration(proxyBeanMethods=false)
public class IdentityConfiguration {
    public IdentityConfiguration(Environment env) {
        boolean production=env.acceptsProfiles(Profiles.of("prod"));
        SecurityConfig.validatePublicOrigin(env.getProperty("billing.public-url","http://localhost:4200"),production);
        if(!production) return;
        String secret=env.getProperty("app.jwt.secret","");
        String normalized=secret.toLowerCase(Locale.ROOT);
        if(secret.getBytes(StandardCharsets.UTF_8).length<48 || secret.isBlank() || !secret.equals(secret.trim())
            || normalized.contains("change-me") || normalized.contains("changeme") || normalized.contains("placeholder")
            || normalized.contains("your-secret") || normalized.contains("your_secret") || normalized.contains("example")
            || secret.chars().distinct().count()<8)
            throw new IllegalStateException("Production JWT signing requires a non-placeholder secret of at least 48 UTF-8 bytes");
        if(!env.getProperty("app.jwt.cookie-secure",Boolean.class,false))
            throw new IllegalStateException("Production authentication cookies must be secure");
        long ttl=env.getProperty("app.jwt.ttl-seconds",Long.class,900L);
        if(ttl<=0 || ttl>900) throw new IllegalStateException("Production JWT lifetime must be between 1 and 900 seconds");
        if(env.getProperty("app.demo.users-enabled",Boolean.class,false)
            || env.getProperty("billing.demo-enabled",Boolean.class,false)
            || env.getProperty("billing.simulator-enabled",Boolean.class,false)
            || env.getProperty("billing.erp-simulator-enabled",Boolean.class,false))
            throw new IllegalStateException("Production identity cannot enable demo users or simulators");
    }

    @Bean JwtAuthFilter identityJwtAuthFilter(JwtService jwt) {
        // The isolated identity app has no internal-token callers. Ignore even an inherited env token.
        return new JwtAuthFilter(jwt,"");
    }
}
