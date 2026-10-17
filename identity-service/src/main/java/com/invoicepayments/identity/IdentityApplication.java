package com.invoicepayments.identity;

import com.invoicepayments.identity.entity.AppUser;
import com.invoicepayments.identity.controller.AuthController;
import com.invoicepayments.identity.service.AuthService;
import com.invoicepayments.identity.security.*;
import com.invoicepayments.identity.config.DemoUserSeeder;
import com.invoicepayments.identity.config.LocalMonitoringSecurityConfig;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;

/** Isolated identity process: no legacy money writers, notification consumers or catalog entities. */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude={UserDetailsServiceAutoConfiguration.class})
@EnableJpaRepositories(basePackageClasses=com.invoicepayments.identity.repository.AppUserRepository.class)
@Import({AuthController.class,AuthService.class,JwtService.class,SecurityConfig.class,
    DemoUserSeeder.class,LocalMonitoringSecurityConfig.class,IdentityAdvice.class,IdentityConfiguration.class})
public class IdentityApplication {
    public static void main(String[] args) {
        var app=new org.springframework.boot.SpringApplication(IdentityApplication.class);
        app.setAdditionalProfiles("billing");
        app.run(args);
    }
    @Bean PersistenceManagedTypes identityManagedTypes() {return PersistenceManagedTypes.of(AppUser.class.getName());}
}
