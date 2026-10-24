package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.invoicepayments.security.JwtAuthFilter;
import com.invoicepayments.security.JwtService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Configuration(proxyBeanMethods=false)
@Profile("billing")
public class BillingConfiguration {
    @Bean JdbcTemplate billingJdbcTemplate(javax.sql.DataSource source) { return new BillingJdbcTemplate(source); }
    public BillingConfiguration(Environment env) {
        boolean production=env.acceptsProfiles(Profiles.of("prod"));
        validatePublicOrigin(env.getProperty("billing.public-url","http://localhost:4200"),production);
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
        String webhook=env.getProperty("billing.stripe.webhook-secret","");
        if(!webhook.startsWith("whsec_") || webhook.length()<=6 || webhook.toLowerCase(Locale.ROOT).contains("placeholder")
            || webhook.toLowerCase(Locale.ROOT).contains("change-me"))
            throw new IllegalStateException("Production requires a Stripe webhook signing secret");
        if(env.getProperty("app.demo.users-enabled",Boolean.class,false)
            || env.getProperty("billing.demo-enabled",Boolean.class,false)
            || env.getProperty("billing.simulator-enabled",Boolean.class,false)
            || env.getProperty("billing.erp-simulator-enabled",Boolean.class,false))
            throw new IllegalStateException("Production cannot enable billing simulators or demo fixtures");
        if(!"STRIPE".equals(env.getProperty("billing.provider","SIMULATOR"))
            || env.getProperty("billing.stripe.secret-key","").isBlank())
            throw new IllegalStateException("Production billing requires the configured Stripe test-mode provider");
    }

    static String validatePublicOrigin(String value,boolean requireHttps) {
        try {
            URI uri=URI.create(value);
            if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                || (uri.getRawPath()!=null && !uri.getRawPath().isEmpty()) || uri.getPort()==0 || uri.getPort()>65535
                || !("https".equals(uri.getScheme()) || (!requireHttps && "http".equals(uri.getScheme())))
                || !value.equals(uri.getScheme()+"://"+uri.getRawAuthority())) throw new IllegalArgumentException();
            return value;
        } catch(RuntimeException invalid) {
            throw new IllegalStateException(requireHttps?"billing.public-url must be one exact HTTPS origin":"billing.public-url must be one exact HTTP or HTTPS origin");
        }
    }

    @Bean JwtAuthFilter billingJwtAuthFilter(JwtService jwt) {
        // No internal-token fallback is valid in this customer/finance application.
        return new JwtAuthFilter(jwt,"");
    }
    @Bean org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer strictBillingNumbers() {
        return builder -> builder.featuresToDisable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT,
            com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }
    @Bean
    BillingProvider billingProvider(JdbcTemplate jdbc, PlatformTransactionManager manager, Environment env,
        io.micrometer.core.instrument.MeterRegistry registry,
        @Value("${billing.provider:SIMULATOR}") String name,
        @Value("${billing.stripe.secret-key:}") String stripeKey) {
        boolean prod=env.acceptsProfiles(Profiles.of("prod"));
        if(prod && (env.getProperty("billing.demo-enabled",Boolean.class,false)
            || env.getProperty("billing.simulator-enabled",Boolean.class,false)
            || env.getProperty("billing.erp-simulator-enabled",Boolean.class,false)))
            throw new IllegalStateException("Production cannot enable billing simulators or demo fixtures");
        return switch(name) {
            case "SIMULATOR" -> {
                if(prod) throw new IllegalStateException("Production cannot use a simulated payment provider");
                yield new SimulatorBillingProvider(jdbc,manager);
            }
            case "STRIPE" -> {
                if(stripeKey.isBlank()) throw new IllegalStateException("Selecting Stripe requires a non-empty test-mode API key");
                yield new StripeBillingProvider(stripeKey,registry,
                    env.getProperty("billing.stripe.max-concurrent",Integer.class,8),
                    org.springframework.boot.convert.DurationStyle.detectAndParse(env.getProperty("billing.stripe.admission-wait","100ms")));
            }
            default -> throw new IllegalStateException("Unknown billing provider");
        };
    }
    @Bean
    StripeWebhookVerifier stripeWebhookVerifier(@Value("${billing.stripe.webhook-secret:}") String secret) {
        return new StripeWebhookVerifier(secret);
    }
}
