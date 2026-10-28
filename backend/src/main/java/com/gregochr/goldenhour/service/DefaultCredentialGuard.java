package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.service.CredentialChangeWriter.Change;
import com.gregochr.goldenhour.service.CredentialChangeWriter.VersionExpectation;
import com.gregochr.goldenhour.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Disables the published default administrator password ({@code admin} / {@code golden2026}) on every
 * profile except {@code local}.
 *
 * <p>The {@code V10} migration seeds that credential and Flyway runs it in production, so without this guard
 * a published password would open an ADMIN account. A SQL migration matching the V10 hash was rejected: it
 * would miss a row seeded with a random salt (prod runs {@code baseline-on-migrate} and {@code out-of-order})
 * and a renamed row, and could not bump the credential version or revoke sessions as
 * {@link CredentialChangeWriter} does. So on {@link ApplicationReadyEvent} each ADMIN account's hash is
 * tested against the published password, and a match is neutralised through the writer in one transaction:
 * the hash becomes one no password matches (it is not BCrypt-shaped), the credential version is bumped so
 * every live access token dies, every refresh token is revoked and {@code passwordChangeRequired} is set.
 *
 * <p>The profile is negative ({@code !local}) so the guard fails closed: it runs under {@code prod},
 * {@code dev}, {@code test}, {@code integration-test} and no profile at all. It never throws: a failure is
 * logged at ERROR and the application starts. To recover the account see {@code DEVOPS.md}.
 */
@Component
@Profile("!local")
public class DefaultCredentialGuard {

    /** The stored value that replaces the default hash; not BCrypt-shaped, so {@code matches} is false. */
    static final String DISABLED_HASH = "!disabled-default-admin";

    private static final Logger LOG = LoggerFactory.getLogger(DefaultCredentialGuard.class);

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CredentialChangeWriter credentialChangeWriter;

    /**
     * Constructs the guard.
     *
     * @param userRepository         accounts
     * @param passwordEncoder        compares a stored hash with the published password
     * @param credentialChangeWriter neutralises a matching account atomically
     */
    public DefaultCredentialGuard(AppUserRepository userRepository, PasswordEncoder passwordEncoder,
            CredentialChangeWriter credentialChangeWriter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.credentialChangeWriter = credentialChangeWriter;
    }

    /**
     * Neutralises every ADMIN account that still accepts the published default password. Never throws.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void disableDefaultAdminPassword() {
        try {
            List<AppUserEntity> admins = userRepository.findByRole(UserRole.ADMIN);
            for (AppUserEntity admin : admins) {
                neutralise(admin);
            }
        } catch (RuntimeException ex) {
            LOG.error("Default credential check failed; the application is starting regardless: {}",
                    LogSanitizer.sanitize(ex.toString()));
        }
    }

    private void neutralise(AppUserEntity admin) {
        try {
            if (!passwordEncoder.matches(DefaultAdminCredentials.PASSWORD, admin.getPassword())) {
                return;
            }
            credentialChangeWriter.apply(admin.getId(), DISABLED_HASH, Change.adminTemporaryPassword(),
                    VersionExpectation.trusted());
            LOG.warn("Default admin password was still in use for '{}' — sign-in disabled; "
                    + "set a new password (DEVOPS.md)", LogSanitizer.sanitize(admin.getUsername()));
        } catch (RuntimeException ex) {
            LOG.error("Could not disable the default password for user id {}: {}", admin.getId(),
                    LogSanitizer.sanitize(ex.toString()));
        }
    }
}
