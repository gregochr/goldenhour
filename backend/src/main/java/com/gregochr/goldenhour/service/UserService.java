package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.TermsConstants;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.exception.StaleCredentialsException;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AppUserRepository.DormancyState;
import com.gregochr.goldenhour.repository.AppUserRepository.LockedAccount;
import com.gregochr.goldenhour.repository.EmailVerificationTokenRepository;
import com.gregochr.goldenhour.repository.PasswordResetTokenRepository;
import com.gregochr.goldenhour.repository.UserDriveTimeRepository;
import com.gregochr.goldenhour.service.CredentialChangeWriter.Change;
import com.gregochr.goldenhour.service.CredentialChangeWriter.VersionExpectation;
import com.gregochr.goldenhour.util.EmailIdentifier;
import com.gregochr.goldenhour.util.EmailIdentifier.Kind;
import com.gregochr.goldenhour.util.LogSanitizer;
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
import java.time.Clock;
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

    /** The sentence for an administrator deleting their own account, and the 409 body for it. */
    public static final String ADMIN_SELF_DELETE_REFUSED = "Admin accounts are deleted by another admin";

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailClaims emailClaims;
    private final CredentialChangeWriter credentialChangeWriter;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final EmailVerificationTokenRepository verificationTokenRepository;
    private final Clock clock;
    private final UserDriveTimeRepository userDriveTimeRepository;

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
     * is read as an email address, and the self-registration pattern already forbids the character. A
     * non-ASCII address is refused too, as {@code /register} refuses it: the reservation key is
     * lower-cased in Java while the database's {@code LOWER()} folds some characters differently, so the
     * claim table can guarantee uniqueness only for addresses both fold alike.
     *
     * <p>The new row's {@code credentialsChangedAt} is stamped at creation, so even a legacy token
     * (one with no credential version) meets a non-null stamp on a recreated row.
     *
     * @param username    the desired login name (must be unique, at least 5 characters)
     * @param rawPassword the plain-text password to be hashed
     * @param role        the role to assign
     * @param email       the user's email address
     * @return the persisted {@link AppUserEntity}
     * @throws IllegalArgumentException if the username or email is already taken, the username
     *                                  contains {@code @}, or the email is not ASCII
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
        if (hasEmail && !EmailIdentifier.isAscii(email.trim())) {
            throw new IllegalArgumentException(EmailIdentifier.NON_ASCII_MESSAGE);
        }
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
                .credentialsChangedAt(clock.instant())
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
     * <p>⚠️ The two directions are not symmetric (login-registration plan §2 #13, §2 #14 rule 6).
     * DISABLING goes through {@link CredentialChangeWriter} with a NULL password: it bumps the credential
     * version, revokes every refresh token and voids the account's unused reset and verification links,
     * so an admin disable ends every session at once — and, because the password column is left out of
     * the statement, a disable that waited on the row lock behind a concurrent password change can never
     * restore the stale hash it loaded. RE-ENABLING is the scoped {@code updateEnabled} and nothing
     * more: it revives no old access token, refresh token or link.
     *
     * @param id      the user's primary key
     * @param enabled {@code true} to enable, {@code false} to disable
     * @throws IllegalArgumentException if no user with that id exists
     */
    @Transactional
    public void setEnabled(Long id, boolean enabled) {
        if (enabled) {
            if (userRepository.updateEnabled(id, true) != 1) {
                throw new IllegalArgumentException("User not found: " + id);
            }
            return;
        }
        credentialChangeWriter.apply(id, null, Change.disable(), VersionExpectation.trusted());
    }

    /**
     * Resets a user's password to a securely generated temporary password and sets
     * {@code passwordChangeRequired = true} so the user must choose a new password on next login.
     *
     * <p>The raw temporary password is returned to the caller (admin) exactly once and is
     * never stored — only the BCrypt hash is persisted.
     *
     * <p>Deliberately not transactional: the temporary password is hashed first, with no connection
     * held, and then {@link CredentialChangeWriter#apply} sets it, bumps the credential version (the
     * account's access tokens stop authenticating at their next request) and revokes its refresh
     * tokens in one transaction. The username and the address the temporary password is mailed to come
     * from the writer's {@link CredentialChangeOutcome}, read under the row lock AFTER the change — never
     * from an entity loaded before the hash, so an email change that committed meanwhile cannot send the
     * password to the old, possibly compromised, mailbox.
     *
     * @param id the user's primary key
     * @return a {@link PasswordResetResult} containing the raw password, username, and email
     * @throws IllegalArgumentException if no user with that id exists
     */
    public PasswordResetResult resetPassword(Long id) {
        if (!userRepository.existsById(id)) {
            throw new IllegalArgumentException("User not found: " + id);
        }
        String rawPassword = generateTempPassword();
        CredentialChangeOutcome outcome = credentialChangeWriter.apply(id, passwordEncoder.encode(rawPassword),
                Change.adminTemporaryPassword(), VersionExpectation.trusted());
        LOG.info("Password reset for user id={}, username={}", id, LogSanitizer.sanitize(outcome.username()));
        return new PasswordResetResult(rawPassword, outcome.username(), outcome.email());
    }

    /**
     * Updates the email address of an existing user (login-registration plan §2 #13).
     *
     * <p>One transaction: the user row is LOCKED first (the writer's lock order, parent first), the new
     * lower-cased address is CLAIMED — the claim table's unique key refuses a case variant of another
     * account's address race-free — the address is written by a scoped update, the account's unused
     * password-reset links and ALL its verification links are deleted (a link mailed to the old or
     * compromised mailbox must not outlive the move: a reset link could overwrite the password within its
     * hour, and a verification link, keyed by user alone, could activate a pending account), and the old
     * key is released only if no account still uses it. {@code PasswordResetIssuer} and
     * {@code VerificationTokenIssuer} take the same row lock, so a request racing this either commits
     * first and is deleted, or commits after and mails the NEW address.
     *
     * @param id    the user's primary key
     * @param email the new email address
     * @return true if the account is a pending registration, whose verification link was voided and which
     *         now needs the admin's resend
     * @throws IllegalArgumentException if no user with that id exists, the address is not ASCII, or
     *                                  another account already uses it under any casing
     *                                  ({@link EmailClaims#EMAIL_TAKEN})
     */
    @Transactional
    public boolean setEmail(Long id, String email) {
        if (!EmailIdentifier.isAscii(email.trim())) {
            throw new IllegalArgumentException(EmailIdentifier.NON_ASCII_MESSAGE);
        }
        LockedAccount account = userRepository.lockAccount(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
        emailClaims.claim(email, id);
        userRepository.updateEmail(id, email);
        resetTokenRepository.deleteByUserIdAndUsedAtIsNull(id);
        verificationTokenRepository.deleteAllByUserIdInBulk(id);
        if (account.email() != null && !account.email().isBlank()) {
            emailClaims.release(account.email());
        }
        LOG.info("Email changed for user id={}: unused reset and verification links voided", id);
        return account.pending();
    }

    /**
     * Changes the role of an existing user.
     *
     * <p>A scoped update: the role column is {@code updatable = false}, and the JWT filter takes the
     * authority from the row, so the demotion is effective at the account's next request.
     *
     * @param id   the user's primary key
     * @param role the new role to assign
     * @throws IllegalArgumentException if no user with that id exists
     */
    @Transactional
    public void setRole(Long id, UserRole role) {
        if (userRepository.updateRole(id, role) != 1) {
            throw new IllegalArgumentException("User not found: " + id);
        }
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
                .credentialsChangedAt(clock.instant())
                .passwordChangeRequired(false)
                .marketingEmailOptIn(marketingEmailOptIn)
                .termsAcceptedAt(Instant.now())
                .termsVersion(TermsConstants.CURRENT_TERMS_VERSION)
                .build();
        return userRepository.save(user);
    }

    /**
     * Deletes a user account permanently (the admin path).
     *
     * <p>Admins cannot delete their own account to prevent lockout. One transaction, in the parent-before-
     * children lock order every credential and deletion path takes: the user row is locked FIRST and read as
     * a scalar projection ({@link AppUserRepository#lockAccount}, login-registration plan §2 #14 rule 1), so
     * a drive-time calculation that holds the row finishes before the purge and cannot re-insert between the
     * purge and the delete; the dependent rows are purged ({@link #purgeUserRows}); the account is deleted;
     * and its email claim is released if no account still uses the address. The confirmation email is NOT
     * sent here: the caller sends it from the {@link DeletedAccount} this returns, after this method has
     * returned and the transaction has committed — a delete that fails at commit must not have mailed "your
     * account was removed".
     *
     * @param id              the user's primary key
     * @param currentUsername the username of the admin performing the deletion
     * @return the deleted account's name and stored address, for the after-commit email
     * @throws IllegalArgumentException if no user with that id exists
     * @throws IllegalStateException    if the admin attempts to delete their own account
     */
    @Transactional
    public DeletedAccount deleteUser(Long id, String currentUsername) {
        LockedAccount account = userRepository.lockAccount(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
        if (account.username().equals(currentUsername)) {
            throw new IllegalStateException("Cannot delete your own account");
        }
        purgeUserRows(id);
        if (userRepository.deleteAccountById(id) != 1) {
            throw new IllegalArgumentException("User not found: " + id);
        }
        releaseEmailClaim(account.email());
        LOG.info("User deleted: id={}, username={}", id, LogSanitizer.sanitize(account.username()));
        return new DeletedAccount(account.username(), account.email());
    }

    /**
     * Deletes a never-verified sign-up the pending-registration sweep has finished with (no email is sent).
     *
     * <p>One transaction, the admin delete's order (user row first): the row is locked; the method stops if
     * the account is no longer a pending non-admin sign-up, or if it holds ANY unexpired verification link
     * (verified or not — a reader mid-flow keeps the place until that link expires); then the dependent rows
     * are purged ({@link #purgeUserRows}), the row is deleted by the conditional
     * {@code deletePendingById}, and the email claim is released.
     *
     * @param id  the account's primary key
     * @param now the sweep's instant, in the JVM default zone (the frame the token {@code expires_at} is in)
     * @return true if a row was deleted
     */
    @Transactional
    public boolean purgeAbandonedPending(Long id, LocalDateTime now) {
        LockedAccount account = userRepository.lockAccount(id).orElse(null);
        if (account == null || !account.pending() || account.role() == UserRole.ADMIN) {
            return false;
        }
        if (verificationTokenRepository.existsByUserIdAndExpiresAtAfter(id, now)) {
            return false;
        }
        purgeUserRows(id);
        if (userRepository.deletePendingById(id) != 1) {
            throw new IllegalStateException("Pending user " + id + " vanished under its row lock");
        }
        releaseEmailClaim(account.email());
        LOG.info("Abandoned pending registration purged: id={}", id);
        return true;
    }

    /**
     * Deletes a dormant LITE_USER the dormant-account sweep has warned and waited for (the caller sends the
     * account-deleted email after this method has returned and its transaction has committed).
     *
     * <p>One transaction, the admin delete's order (user row first): the row is locked and read as a scalar
     * ({@link AppUserRepository#lockDormancyState}); the method stops unless the account is STILL an enabled
     * LITE_USER with a password and a non-blank email, STILL inactive at or before {@code inactiveCutoff}, and
     * STILL carries a warning stamped after its last activity and at or before {@code warnCutoff}. Then the
     * dependent rows are purged ({@link #purgeUserRows}), the row is deleted by the conditional
     * {@link AppUserRepository#deleteDormantIfUnchanged} (which re-checks every one of those conditions in its
     * WHERE), and the email claim is released. A promotion, a disable or a sign-in at any point saves the
     * account.
     *
     * @param id             the account's primary key
     * @param inactiveCutoff the latest last-activity instant that is still dormant (now minus the inactive days)
     * @param warnCutoff     the latest warning instant whose grace period has ended (now minus the grace days)
     * @return the deleted account's name and stored address, for the after-commit email; empty if the account
     *         no longer qualifies
     */
    @Transactional
    public Optional<DeletedAccount> purgeDormant(Long id, LocalDateTime inactiveCutoff, LocalDateTime warnCutoff) {
        DormancyState state = userRepository.lockDormancyState(id).orElse(null);
        if (state == null || !state.touchable() || state.lastActivity().isAfter(inactiveCutoff)) {
            return Optional.empty();
        }
        LocalDateTime warned = state.warningSentAt();
        if (warned == null || !warned.isAfter(state.lastActivity()) || warned.isAfter(warnCutoff)) {
            return Optional.empty();
        }
        purgeUserRows(id);
        if (userRepository.deleteDormantIfUnchanged(id, UserRole.LITE_USER, inactiveCutoff, warnCutoff) != 1) {
            throw new IllegalStateException("Dormant user " + id + " no longer qualified under its row lock");
        }
        releaseEmailClaim(state.email());
        LOG.info("Dormant account purged: id={}", id);
        return Optional.of(new DeletedAccount(state.username(), state.email()));
    }

    /**
     * Checks a signed-in reader's password for a destructive request, with no transaction and no connection
     * held through the BCrypt hash.
     *
     * @param username    the authenticated login name
     * @param rawPassword the password the reader typed
     * @return the stored hash the password matched — handed back to {@link #deleteOwnAccount} so it can hold
     *         the row to exactly that credential — or empty when the password is wrong or the account has no
     *         password to match
     * @throws StaleCredentialsException if the account no longer exists
     */
    public Optional<String> verifyPassword(String username, String rawPassword) {
        AppUserEntity user = userRepository.findByUsername(username)
                .orElseThrow(StaleCredentialsException::new);
        String hash = user.getPassword();
        if (hash == null || hash.isEmpty() || !passwordEncoder.matches(rawPassword, hash)) {
            return Optional.empty();
        }
        return Optional.of(hash);
    }

    /**
     * Deletes the caller's own account (login-registration plan §2 #7, §2 #14 rule 1).
     *
     * <p>The caller has verified the password by BCrypt OUTSIDE any transaction ({@link #verifyPassword});
     * this method runs the row-lock-first transaction. Under the lock it reads the account as a scalar
     * projection — never the entity the request's persistence context may hold from before the hash — and
     * refuses unless the stored hash is still {@code verifiedHash}, the credential version still equals the
     * session's, and the role is still not ADMIN. The purge then runs, and the delete is itself a
     * compare-and-set on the same three conditions whose row count must be one: zero throws, which rolls the
     * purge back. A stolen session that finished its BCrypt just before the owner's password change
     * committed therefore deletes nothing. Finally the account's email claim is released if no account still
     * uses the address. The confirmation email is the caller's, after commit.
     *
     * @param username     the authenticated login name
     * @param verifiedHash the stored hash the caller's BCrypt check matched
     * @param expectation  the session's credential version: {@code exact(cv)}, or {@code legacyUnchanged()}
     *                     for a token with no {@code cv}; {@code trusted()} is refused, since nothing here
     *                     may skip the version check
     * @return the deleted account's name and stored address, for the after-commit email
     * @throws IllegalStateException     ({@link #ADMIN_SELF_DELETE_REFUSED}) if the locked row's role is ADMIN
     * @throws StaleCredentialsException if the account is gone, its password or credential version has moved
     *                                   since the caller authenticated, or it is no longer active
     */
    @Transactional
    public DeletedAccount deleteOwnAccount(String username, String verifiedHash, VersionExpectation expectation) {
        long expectedVersion = expectedVersionOf(expectation);
        Long id = userRepository.findByUsernameForUpdate(username)
                .map(AppUserEntity::getId)
                .orElseThrow(StaleCredentialsException::new);
        LockedAccount account = userRepository.lockAccount(id).orElseThrow(StaleCredentialsException::new);
        if (account.role() == UserRole.ADMIN) {
            throw new IllegalStateException(ADMIN_SELF_DELETE_REFUSED);
        }
        if (!account.active() || !account.password().equals(verifiedHash)
                || account.credentialsVersion() != expectedVersion) {
            LOG.info("Account deletion refused: credentials moved for user id={}", id);
            throw new StaleCredentialsException();
        }
        purgeUserRows(id);
        if (userRepository.deleteOwnAccountIfUnchanged(id, verifiedHash, expectedVersion, UserRole.ADMIN) != 1) {
            LOG.info("Account deletion refused at the delete: credentials moved for user id={}", id);
            throw new StaleCredentialsException();
        }
        releaseEmailClaim(account.email());
        LOG.info("Account deleted by its owner: id={}, username={}", id, LogSanitizer.sanitize(account.username()));
        return new DeletedAccount(account.username(), account.email());
    }

    /**
     * Deletes the rows that reference an account and have no {@code ON DELETE} rule of their own — the one
     * step both deletion paths share. {@code user_drive_time.user_id} (V67) has no cascade, so a delete of a
     * user holding drive times violates the foreign key in Postgres (H2 has no such key, so only the
     * CI-only {@code AccountDeletionIntegrationTest} can show it). Every other table that references the
     * account cascades or nulls its own reference. Must run inside the deleting transaction, after the
     * user row is locked and before the user row is deleted.
     *
     * @param userId the account's primary key
     */
    private void purgeUserRows(Long userId) {
        userDriveTimeRepository.deleteAllByUserId(userId);
    }

    /** Releases an address's email claim, if the deleted account had one and no account still uses it. */
    private void releaseEmailClaim(String email) {
        if (email != null && !email.isBlank()) {
            emailClaims.release(email);
        }
    }

    /** The version the delete's compare-and-set holds the row to: the session's {@code cv}, or 0 for none. */
    private static long expectedVersionOf(VersionExpectation expectation) {
        return switch (expectation.mode()) {
            case EXACT -> expectation.version();
            case LEGACY_UNCHANGED -> 0L;
            case TRUSTED -> throw new IllegalArgumentException(
                    "An account deletion must be held to the caller's credential version");
        };
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
