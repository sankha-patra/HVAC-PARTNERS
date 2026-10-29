package com.invoicepayments.billing;

import com.invoicepayments.security.*;
import com.invoicepayments.monitoring.LocalMonitoringSecurityConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Invoice collection and financial operations service. */
@SpringBootApplication(scanBasePackages={"com.invoicepayments.billing","com.invoicepayments.commerce"},exclude=UserDetailsServiceAutoConfiguration.class)
@Import({JwtService.class,LocalMonitoringSecurityConfig.class})
@Profile("billing")
@EnableScheduling
public class BillingApplication {
    public static void main(String[] args) {
        var app=new org.springframework.boot.SpringApplication(BillingApplication.class);
        app.setAdditionalProfiles("billing");
        app.run(args);
    }
}
