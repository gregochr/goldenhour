package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.repository.RefreshTokenRepository;
import com.gregochr.goldenhour.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revokes every active refresh token of an account — the response to a replayed or raced refresh token
 * (login-registration plan §2 #13, §2 #14 rule 7).
 *
 * <p>A bean of its own, called by {@link AuthenticationService#refresh} in its OWN {@code REQUIRED}
 * transaction AFTER the rotation's transaction has ended: the revocation therefore COMMITS before the
 * refusal is thrown. Revoking inside the rotation's transaction and then throwing would roll the
 * revocation back with the exception and leave the replacement token usable — the pre-existing bug this
 * fixes. It is never {@code REQUIRES_NEW} nested inside an open transaction either, which would hold one
 * pooled connection while waiting for a second on a public, unlimited endpoint.
 */
@Component
public class RefreshFamilyRevoker {

    private static final Logger LOG = LoggerFactory.getLogger(RefreshFamilyRevoker.class);

    private final RefreshTokenRepository refreshTokenRepository;

    /**
     * Constructs the revoker.
     *
     * @param refreshTokenRepository sessions
     */
    public RefreshFamilyRevoker(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /**
     * Revokes every active refresh token for the user and logs why.
     *
     * @param userId the account's primary key
     * @param reason what triggered the revocation (logged)
     */
    @Transactional
    public void revokeAll(Long userId, String reason) {
        int revoked = refreshTokenRepository.revokeAllActiveByUserId(userId);
        LOG.warn("Refresh token reuse detected (userId={}, {}): revoked {} active token(s)",
                userId, LogSanitizer.sanitize(reason), revoked);
    }
}
