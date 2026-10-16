package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.PasswordResetTokenEntity;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AppUserRepository.LockedAccount;
import com.gregochr.goldenhour.repository.PasswordResetTokenRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Mints a password-reset link under the account's row lock (login-registration plan §2 #1, §2 #14
 * rule 4).
 *
 * <p>A bean of its own so {@link PasswordResetService} reaches it through the Spring proxy. It takes the
 * user-row lock, then reads the account's address, name and active state as a SCALAR projection under
 * that lock ({@link AppUserRepository#lockAccount}) — under open-session-in-view the request's
 * persistence context already holds the entity resolved earlier, and a locking entity query would hand
 * that stale instance back. It deletes the account's earlier unused links, inserts the new one and
 * returns the STORED address to mail.
 *
 * <p>The row lock is what orders this against an admin's email change: {@code UserService.setEmail} takes
 * the same lock and deletes the account's unused links, so a request racing it either commits first and is
 * deleted, or commits after and mails the NEW address — never a link that survives into the old mailbox.
 */
@Component
public class PasswordResetIssuer {

    /** How long a reset link works. */
    static final Duration LINK_LIFETIME = Duration.ofHours(1);

    private final AppUserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final Clock clock;

    /**
     * Constructs the issuer.
     *
     * @param userRepository  accounts
     * @param tokenRepository reset links
     * @param clock           the application clock
     */
    public PasswordResetIssuer(AppUserRepository userRepository, PasswordResetTokenRepository tokenRepository,
            Clock clock) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.clock = clock;
    }

    /**
     * Where to send a freshly issued link.
     *
     * @param email    the account's STORED address
     * @param username the account's login name
     */
    public record IssuedLink(String email, String username) {
    }

    /**
     * Issues a link for an account.
     *
     * @param userId    the account's primary key
     * @param tokenHash the SHA-256 hex digest of the raw token that will be mailed
     * @return the STORED address and the username to mail, or empty when the account is no longer active
     *         (disabled, pending, gone) or has no address — nothing is stored in that case
     */
    @Transactional
    public Optional<IssuedLink> issue(Long userId, String tokenHash) {
        LockedAccount account = userRepository.lockAccount(userId).orElse(null);
        if (account == null || !account.active() || account.email() == null || account.email().isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        tokenRepository.deleteByUserIdAndUsedAtIsNull(userId);
        tokenRepository.save(PasswordResetTokenEntity.builder()
                .tokenHash(tokenHash)
                .userId(userId)
                .expiresAt(now.plus(LINK_LIFETIME))
                .createdAt(now)
                .build());
        return Optional.of(new IssuedLink(account.email(), account.username()));
    }
}
