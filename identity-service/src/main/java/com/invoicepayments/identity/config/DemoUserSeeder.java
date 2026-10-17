package com.invoicepayments.identity.config;

import com.invoicepayments.identity.entity.AppUser;
import com.invoicepayments.identity.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.demo.users-enabled", havingValue = "true")
@Profile("!prod")
public class DemoUserSeeder implements CommandLineRunner {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        seedIfMissing("demo", "demo@invoice-payments.test", "Demo User", "USER", "demo123");
        seedIfMissing("admin", "admin@invoice-payments.test", "Finance Operator", "ADMIN", "admin123");
        seedIfMissing("reviewer", "reviewer@invoice-payments.test", "Refund Reviewer", "USER", "reviewer123");
        seedIfMissing("other", "other@invoice-payments.test", "Other Customer", "USER", "other123");
    }

    private void seedIfMissing(String username, String email, String displayName, String role, String rawPassword) {
        if (appUserRepository.existsByUsername(username)) {
            return;
        }
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setEmail(email);
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setStatus("ACTIVE");
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        appUserRepository.save(user);
        log.info("Seeded demo login user: {} ({})", username, role);
    }
}
