package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Seeds the default {@code admin} account on startup if the user table is empty, under the {@code local}
 * profile only.
 *
 * <p>The local profile runs on H2 with Flyway off, so nothing else creates an account. Every other profile
 * gets its administrator from the migrations, and {@link DefaultCredentialGuard} refuses the published
 * default password there. The profile is positive ({@code local}) so that an unknown or missing profile
 * seeds nothing: the guard fails closed.
 */
@Component
@Profile("local")
public class LocalAdminSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(LocalAdminSeeder.class);

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Constructs the seeder.
     *
     * @param userRepository  accounts
     * @param passwordEncoder hashes the default password
     */
    public LocalAdminSeeder(AppUserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Seeds the default admin account if the user table is empty; a non-empty table is left alone.
     */
    @PostConstruct
    public void seedDefaultAdmin() {
        if (userRepository.count() == 0) {
            AppUserEntity admin = AppUserEntity.builder()
                    .username(DefaultAdminCredentials.USERNAME)
                    .password(passwordEncoder.encode(DefaultAdminCredentials.PASSWORD))
                    .role(UserRole.ADMIN)
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .build();
            userRepository.save(admin);
            LOG.info("Seeded default admin account (username: {})", DefaultAdminCredentials.USERNAME);
        }
    }
}
