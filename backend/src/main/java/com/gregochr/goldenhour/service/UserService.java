package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.TermsConstants;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.service.notification.UserEmailService;
import com.gregochr.goldenhour.util.EmailIdentifier;
import com.gregochr.goldenhour.util.EmailIdentifier.Kind;
import java.time.Instant;

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
import java.util.Optional;

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
    private final UserEmailService userEmailService;
    private final EmailClaims emailClaims;

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
     * <p>The email address (when given) is reserved in the same transaction through
     * {@link EmailClaims#claim}, which refuses an address another account already uses under ANY casing;
     * a concurrent claimant of a brand-new address surfaces as a
     * {@code DataIntegrityViolationException} for the controller to translate (login-registration
     * plan §5 #6). A username containing {@code @} is refused: a sign-in identifier with an {@code @}
     * is read as an email address, and the self-registration pattern already forbids the character.
     *
     * @param username    the desired login name (must be unique, at least 5 characters)
     * @param rawPassword the plain-text password to be hashed
     * @param role        the role to assign
     * @param email       the user's email address
     * @return the persisted {@link AppUserEntity}
     * @throws IllegalArgumentException if the username or email is already taken, or the username
     *                                  contains {@code @}
     */
    @Transactional
    public AppUserEntity createUser(String username, String rawPassword, UserRole role, String email) {
        if (username != null && username.contains("@")) {
            throw new IllegalArgumentException("Username must not contain '@'");
        }
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already exists: " + username);
        }
        boolean hasEmail = email != null && !email.isBlank();
        if (hasEmail) {
            emailClaims.claim(email, null);
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
     * the old account is deleted and re-created rather than blocking signup. The abandoned row is
     * LOCKED first and removed only by a conditional {@code DELETE … WHERE id = :id AND enabled = false
     * AND password = ''} whose row count must be one: a retry that races the verification link's
     * activation deletes nothing (0 rows → "Email already registered") and can never erase an account
     * that has just been activated. The replacement is same-email, DIFFERENT-username — the username
     * check above runs first and refuses the same name.
     *
     * <p>The address is then reserved through {@link EmailClaims#claim}: it refuses an address an ACTIVE
     * account holds under any casing, and reuses silently the reservation the replaced pending row left
     * behind (login-registration plan §5 #6, §2 #14 rule 10).
     *
     * @param username            the desired login name (3–30 chars, alphanumeric/underscore/hyphen)
     * @param email               the user's email address
     * @param marketingEmailOptIn whether the user opted in to marketing emails
     * @param role                the role to assign to the new user
     * @return the persisted pending {@link AppUserEntity}
     * @throws IllegalArgumentException if the username is taken or email is already registered
     *                                  by an active account
     */
    @Transactional
    public AppUserEntity createPendingUser(String username, String email, boolean marketingEmailOptIn,
                                           UserRole role) {
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already exists");
        }

        Optional<AppUserEntity> abandoned = EmailIdentifier.resolve(email, userRepository, Kind.PENDING);
        if (abandoned.isPresent()) {
            Long abandonedId = abandoned.get().getId();
            // Lock for ordering only; the decision is the conditional delete's row count.
            userRepository.findByIdForUpdate(abandonedId);
            if (userRepository.deletePendingById(abandonedId) != 1) {
                throw new IllegalArgumentException(EmailClaims.EMAIL_TAKEN);
            }
        }
        emailClaims.claim(email, null);

        AppUserEntity user = AppUserEntity.builder()
                .username(username)
                .password("")
                .role(role)
                .email(email)
                .enabled(false)
                .createdAt(LocalDateTime.now())
                .passwordChangeRequired(false)
                .marketingEmailOptIn(marketingEmailOptIn)
                .termsAcceptedAt(Instant.now())
                .termsVersion(TermsConstants.CURRENT_TERMS_VERSION)
                .build();
        return userRepository.save(user);
    }

    /**
     * Activates a pending user by setting their password and enabling the account.
     *
     * @param userId      the user's primary key
     * @param rawPassword the plain-text password to be hashed
     * @throws IllegalArgumentException if no user with that id exists, or the account is not
     *                                  awaiting activation
     */
    @Transactional
    public void activateUser(Long userId, String rawPassword) {
        AppUserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        if (!isAwaitingActivation(user)) {
            throw new IllegalArgumentException("Account is not awaiting activation");
        }
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setEnabled(true);
        user.setPasswordChangeRequired(false);
        userRepository.save(user);
        LOG.info("User activated: id={}, username={}", userId, user.getUsername());
    }

    /**
     * Reports whether an account is a self-registration still waiting for its password.
     *
     * <p>That is the sole shape {@link #createPendingUser} produces — not enabled, and holding an
     * empty password rather than a hash. Every other account, including one an admin created and
     * one already activated, fails this check, which is what stops a caller who reaches
     * {@link #activateUser} with someone else's id from overwriting live credentials.
     *
     * @param user the account to test
     * @return true only if the account is awaiting activation
     */
    private static boolean isAwaitingActivation(AppUserEntity user) {
        String password = user.getPassword();
        return !user.isEnabled() && password != null && password.isEmpty();
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
        String email = user.getEmail();
        String username = user.getUsername();
        userRepository.delete(user);
        LOG.info("User deleted: id={}, username={}", id, username);
        userEmailService.sendAccountDeletedEmail(email, username);
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
