package com.invoicepayments.identity.config;

import com.invoicepayments.identity.entity.AppUser;
import com.invoicepayments.identity.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DemoUserSeederTest {

    private final AppUserRepository repository = mock(AppUserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    // Only the seeder and mocks are registered: no application, database, or network starts.
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(DemoUserSeeder.class)
        .withBean(AppUserRepository.class, () -> repository)
        .withBean(PasswordEncoder.class, () -> passwordEncoder);

    @Test
    void seederIsAbsentByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(DemoUserSeeder.class);
            verifyNoInteractions(repository, passwordEncoder);
        });
    }

    @Test
    void seederIsAbsentWhenExplicitlyDisabled() {
        contextRunner.withPropertyValues("app.demo.users-enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(DemoUserSeeder.class));
    }

    @Test
    void explicitOptInEnablesSeederOutsideProduction() {
        contextRunner.withPropertyValues("app.demo.users-enabled=true")
            .run(context -> assertThat(context).hasSingleBean(DemoUserSeeder.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "prod,demo", "local,prod"})
    void productionProfileVetoesExplicitOptIn(String profiles) {
        contextRunner.withPropertyValues(
                "app.demo.users-enabled=true",
                "spring.profiles.active=" + profiles)
            .run(context -> {
                assertThat(context).doesNotHaveBean(DemoUserSeeder.class);
                verifyNoInteractions(repository, passwordEncoder);
            });
    }

    @Test
    void existingUsersAreNeverOverwritten() {
        when(repository.existsByUsername(anyString())).thenReturn(true);

        new DemoUserSeeder(repository, passwordEncoder).run();

        verify(repository, never()).save(any(AppUser.class));
        verifyNoInteractions(passwordEncoder);
    }
}
