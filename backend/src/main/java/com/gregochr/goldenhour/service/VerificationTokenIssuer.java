package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EmailVerificationTokenEntity;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AppUserRepository.LockedAccount;
import com.gregochr.goldenhour.repository.EmailVerificationTokenRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Mints a verification link for a pending registration under the account's row lock
 * (login-registration plan §2 #14 rule 4), behind BOTH the reader's resend and the admin's.
 *
 * <p>Each used to resolve the user, then delete and insert with no lock, and mail the address the
 * caller had read. A resend that resolved the old address before an admin's email change committed would
 * insert a token after the change's delete and mail the old mailbox — a link for the admin-moved account
 * in a mailbox the admin had replaced. Here the user-row lock is taken first, the account's address and
 * pending state are read as a SCALAR projection under it ({@link AppUserRepository#lockAccount}) — never
 * the entity the request already holds — the account's earlier unverified links are deleted, the new one
 * is inserted, and the STORED address is returned to mail.
 *
 * <p>A bean of its own so callers reach it through the Spring proxy.
 */
@Component
public class VerificationTokenIssuer {

    /** Verification links expire after 24 hours. */
    static final int TOKEN_EXPIRY_HOURS = 24;

    private final AppUserRepository userRepository;
    private final EmailVerificationTokenRepository tokenRepository;
    private final JwtService jwtService;

    /**
     * Constructs the issuer.
     *
     * @param userRepository  accounts
     * @param tokenRepository verification links
     * @param jwtService      token generation and hashing
     */
    public VerificationTokenIssuer(AppUserRepository userRepository,
            EmailVerificationTokenRepository tokenRepository, JwtService jwtService) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.jwtService = jwtService;
    }

    /**
     * Where to send a freshly issued link, and the raw token that is its proof. Not logged.
     *
     * @param email    the account's STORED address
     * @param username the account's login name
     * @param rawToken the raw token to put in the link; only its hash is stored
     */
    public record IssuedVerification(String email, String username, String rawToken) {

        @Override
        public String toString() {
            return "IssuedVerification[email=" + email + ", username=" + username + ", rawToken=<redacted>]";
        }
    }

    /**
     * Issues a verification link for an account.
     *
     * @param userId the account's primary key
     * @return the STORED address, the username and the raw token, or empty when the account is no longer
     *         pending (activated, disabled, gone) or has no address — nothing is stored in that case
     */
    @Transactional
    public Optional<IssuedVerification> issue(Long userId) {
        LockedAccount account = userRepository.lockAccount(userId).orElse(null);
        if (account == null || !account.pending() || account.email() == null || account.email().isBlank()) {
            return Optional.empty();
        }
        tokenRepository.deleteAll(tokenRepository.findByUserIdAndVerifiedFalse(userId));
        String rawToken = jwtService.generateRefreshToken();
        LocalDateTime now = LocalDateTime.now();
        tokenRepository.save(EmailVerificationTokenEntity.builder()
                .tokenHash(jwtService.hashToken(rawToken))
                .userId(userId)
                .expiresAt(now.plusHours(TOKEN_EXPIRY_HOURS))
                .verified(false)
                .createdAt(now)
                .build());
        return Optional.of(new IssuedVerification(account.email(), account.username(), rawToken));
    }
}
