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

import java.security.SecureRandom;
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

    private static final String TEMP_PW_UPPER   = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String TEMP_PW_LOWER   = "abcdefghijklmnopqrstuvwxyz";
    private static final String TEMP_PW_DIGITS  = "0123456789";
    private static final String TEMP_PW_SPECIAL = "!@#$%&*-_+=?";
    private static final String TEMP_PW_ALL     = TEMP_PW_UPPER + TEMP_PW_LOWER + TEMP_PW_DIGITS + TEMP_PW_SPECIAL;
    private static final int    TEMP_PW_LENGTH  = 12;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

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
     * @param username    the desired login name (must be unique, at least 5 characters)
     * @param rawPassword the plain-text password to be hashed
     * @param role        the role to assign
     * @param email       the user's email address
     * @return the persisted {@link AppUserEntity}
     * @throws IllegalArgumentException if the username is already taken
     */
    @Transactional
    public AppUserEntity createUser(String username, String rawPassword, UserRole role, String email) {
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already exists: " + username);
        }
        AppUserEntity user = AppUserEntity.builder()
                .username(username)
                .password(passwordEncoder.encode(rawPassword))
                .role(role)
                .email(email)
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
     * Resets a user's password to a securely generated temporary password and sets
     * {@code passwordChangeRequired = true} so the user must choose a new password on next login.
     *
     * <p>The raw temporary password is returned to the caller (admin) exactly once and is
     * never stored — only the BCrypt hash is persisted.
     *
     * @param id the user's primary key
     * @return a {@link PasswordResetResult} containing the raw password, username, and email
     * @throws IllegalArgumentException if no user with that id exists
     */
    @Transactional
    public PasswordResetResult resetPassword(Long id) {
        AppUserEntity user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
        String rawPassword = generateTempPassword();
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setPasswordChangeRequired(true);
        userRepository.save(user);
        LOG.info("Password reset for user id={}, username={}", id, user.getUsername());
        return new PasswordResetResult(rawPassword, user.getUsername(), user.getEmail());
    }

    /**
     * Updates the email address of an existing user.
     *
     * @param id    the user's primary key
     * @param email the new email address
     * @throws IllegalArgumentException if no user with that id exists
     */
    @Transactional
    public void setEmail(Long id, String email) {
        AppUserEntity user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
        user.setEmail(email);
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

    /**
     * Creates a disabled user with an empty password for self-registration.
     *
     * <p>If a pending registration exists for the same email (disabled account with empty password),
     * the old account is deleted and re-created rather than blocking signup.
     *
     * @param username the desired login name (3–30 chars, alphanumeric/underscore/hyphen)
     * @param email    the user's email address
     * @return the persisted pending {@link AppUserEntity}
     * @throws IllegalArgumentException if the username is taken or email is already registered
     *                                  by an active account
     */
    @Transactional
    public AppUserEntity createPendingUser(String username, String email) {
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already exists");
        }

        userRepository.findByEmail(email).ifPresent(existing -> {
            if (!existing.isEnabled() && "".equals(existing.getPassword())) {
                // Abandoned pending registration — delete and allow re-creation
                userRepository.delete(existing);
                userRepository.flush();
            } else {
                throw new IllegalArgumentException("Email already registered");
            }
        });

        AppUserEntity user = AppUserEntity.builder()
                .username(username)
                .password("")
                .role(UserRole.LITE_USER)
                .email(email)
                .enabled(false)
                .createdAt(LocalDateTime.now())
                .passwordChangeRequired(false)
                .build();
        return userRepository.save(user);
    }

    /**
     * Activates a pending user by setting their password and enabling the account.
     *
     * @param userId      the user's primary key
     * @param rawPassword the plain-text password to be hashed
     * @throws IllegalArgumentException if no user with that id exists
     */
    @Transactional
    public void activateUser(Long userId, String rawPassword) {
        AppUserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setEnabled(true);
        user.setPasswordChangeRequired(false);
        userRepository.save(user);
        LOG.info("User activated: id={}, username={}", userId, user.getUsername());
    }

    /**
     * Deletes a user account permanently.
     *
     * <p>Admins cannot delete their own account to prevent lockout.
     *
     * @param id              the user's primary key
     * @param currentUsername  the username of the admin performing the deletion
     * @throws IllegalArgumentException if no user with that id exists
     * @throws IllegalStateException    if the admin attempts to delete their own account
     */
    @Transactional
    public void deleteUser(Long id, String currentUsername) {
        AppUserEntity user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
        if (user.getUsername().equals(currentUsername)) {
            throw new IllegalStateException("Cannot delete your own account");
        }
        userRepository.delete(user);
        LOG.info("User deleted: id={}, username={}", id, user.getUsername());
    }

    /** Generates a 12-character random password containing upper, lower, digit, and special chars. */
    private String generateTempPassword() {
        char[] pw = new char[TEMP_PW_LENGTH];
        // Guarantee at least one character from each category.
        pw[0] = TEMP_PW_UPPER.charAt(SECURE_RANDOM.nextInt(TEMP_PW_UPPER.length()));
        pw[1] = TEMP_PW_LOWER.charAt(SECURE_RANDOM.nextInt(TEMP_PW_LOWER.length()));
        pw[2] = TEMP_PW_DIGITS.charAt(SECURE_RANDOM.nextInt(TEMP_PW_DIGITS.length()));
        pw[3] = TEMP_PW_SPECIAL.charAt(SECURE_RANDOM.nextInt(TEMP_PW_SPECIAL.length()));
        for (int i = 4; i < TEMP_PW_LENGTH; i++) {
            pw[i] = TEMP_PW_ALL.charAt(SECURE_RANDOM.nextInt(TEMP_PW_ALL.length()));
        }
        // Fisher-Yates shuffle to avoid predictable positions.
        for (int i = TEMP_PW_LENGTH - 1; i > 0; i--) {
            int j = SECURE_RANDOM.nextInt(i + 1);
            char tmp = pw[i];
            pw[i] = pw[j];
            pw[j] = tmp;
        }
        return new String(pw);
    }
}
