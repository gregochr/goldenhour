package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Service for user management operations.
 *
 * <p>Implements {@link UserDetailsService} so Spring Security can load users for authentication.
 * On startup, seeds a default {@code admin} account if the user table is empty (used when
 * Flyway migrations are disabled, e.g. the local H2 profile).
 */
@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    private static final Logger LOG = LoggerFactory.getLogger(UserService.class);

    private static final String DEFAULT_ADMIN_USERNAME = "admin";
    private static final String DEFAULT_ADMIN_PASSWORD = "golden2026";

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Seeds a default admin account on startup if the user table is empty.
     *
     * <p>This ensures the app is usable immediately when Flyway migrations are disabled
     * (e.g. local H2 profile). The Flyway V10 migration inserts the same account for
     * production PostgreSQL environments, so this method is a no-op there.
     */
    @PostConstruct
    public void seedDefaultAdmin() {
        if (userRepository.count() == 0) {
            AppUserEntity admin = AppUserEntity.builder()
                    .username(DEFAULT_ADMIN_USERNAME)
                    .password(passwordEncoder.encode(DEFAULT_ADMIN_PASSWORD))
                    .role(UserRole.ADMIN)
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .build();
            userRepository.save(admin);
            LOG.info("Seeded default admin account (username: {})", DEFAULT_ADMIN_USERNAME);
        }
    }

    /**
     * Loads a user by username for Spring Security authentication.
     *
     * @param username the login name to search for
     * @return the matching {@link UserDetails}
     * @throws UsernameNotFoundException if no user with that name exists
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "No user found with username: " + username));
    }

    /**
     * Creates a new user with a BCrypt-hashed password.
     *
     * @param username    the desired login name (must be unique)
     * @param rawPassword the plain-text password to be hashed
     * @param role        the role to assign
     * @return the persisted {@link AppUserEntity}
     * @throws IllegalArgumentException if the username is already taken
     */
    @Transactional
    public AppUserEntity createUser(String username, String rawPassword, UserRole role) {
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already exists: " + username);
        }
        AppUserEntity user = AppUserEntity.builder()
                .username(username)
                .password(passwordEncoder.encode(rawPassword))
                .role(role)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .passwordChangeRequired(true)
                .build();
        return userRepository.save(user);
    }

    /**
     * Returns all users ordered by creation time.
     *
     * @return list of all {@link AppUserEntity} instances
     */
    public List<AppUserEntity> listAllUsers() {
        return userRepository.findAll();
    }

    /**
     * Enables or disables an existing user account.
     *
     * @param id      the user's primary key
     * @param enabled {@code true} to enable, {@code false} to disable
     * @throws IllegalArgumentException if no user with that id exists
     */
    @Transactional
    public void setEnabled(Long id, boolean enabled) {
        AppUserEntity user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
        user.setEnabled(enabled);
        userRepository.save(user);
    }

    /**
     * Changes the role of an existing user.
     *
     * @param id   the user's primary key
     * @param role the new role to assign
     * @throws IllegalArgumentException if no user with that id exists
     */
    @Transactional
    public void setRole(Long id, UserRole role) {
        AppUserEntity user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
        user.setRole(role);
        userRepository.save(user);
    }
}
