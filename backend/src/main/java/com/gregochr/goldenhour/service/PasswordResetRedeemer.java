package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AppUserRepository.LockedAccount;
import com.gregochr.goldenhour.repository.PasswordResetTokenRepository;
import com.gregochr.goldenhour.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * The single transactional operation that redeems a password-reset link
 * (login-registration plan §2 #1).
 *
 * <p>A bean of its own so {@link PasswordResetService} calls it through the Spring proxy — a method on
 * the service itself would be a self-invocation and silently run with no transaction. The caller hashes
 * the new password BEFORE calling (never BCrypt inside a transaction: it pins a pooled connection across
 * ~100 ms on a public endpoint). Inside, in the parent-before-children lock order every credential path
 * uses:
 * <ol>
 *   <li>lock the user row and read its enabled/pending state as a SCALAR projection under the lock
 *       ({@link AppUserRepository#lockAccount}) — never off the entity the request loaded before the
 *       hash, which under open-session-in-view is the very instance a locking entity query hands back.
 *       An admin who disabled the account while the password was being hashed wins: the link is refused
 *       with "invalid" and left unconsumed;</li>
 *   <li>consume the token by compare-and-set on unused AND unexpired, judged at an instant taken inside
 *       the transaction — 0 rows means a concurrent redemption won or the link expired meanwhile;</li>
 *   <li>void the account's other unused links;</li>
 *   <li>set the password by compare-and-set on {@code enabled}, and revoke every active refresh token;</li>
 *   <li>re-read and return the row as it now stands, so the response is built from the redeemed state
 *       (an admin-issued temporary password's {@code passwordChangeRequired} is cleared) and not from
 *       the entity resolved before hashing.</li>
 * </ol>
 * Any refusal throws, which rolls back the token's consumption with everything else.
 *
 * <p>TODO(R3c): this operation becomes {@code CredentialChangeWriter.consumeResetAndApply} so the
 * credential-version stamp joins the same transaction.
 */
@Component
public class PasswordResetRedeemer {

    private static final Logger LOG = LoggerFactory.getLogger(PasswordResetRedeemer.class);

    /** The sentence for a link that cannot be redeemed because of the account it names. */
    static final String INVALID_LINK = "This reset link is invalid";

    /** The sentence for a link that was redeemed (possibly a moment ago, by a concurrent request). */
    static final String USED_LINK = "This reset link has already been used";

    /** The sentence for a link that expired, possibly while the new password was being hashed. */
    static final String EXPIRED_LINK = "This reset link has expired";

    private final AppUserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;

    /**
     * Constructs the redeemer.
     *
     * @param userRepository         accounts
     * @param tokenRepository        reset links
     * @param refreshTokenRepository sessions
     * @param clock                  the application clock, read INSIDE the transaction
     */
    public PasswordResetRedeemer(AppUserRepository userRepository, PasswordResetTokenRepository tokenRepository,
            RefreshTokenRepository refreshTokenRepository, Clock clock) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.clock = clock;
    }

    /**
     * Redeems a reset link.
     *
     * @param tokenId         the link's primary key
     * @param userId          the account the link names
     * @param encodedPassword the new password's BCrypt hash, computed before this call
     * @return the account as it stands after the redemption
     * @throws IllegalArgumentException with the sentence to show when the link cannot be redeemed
     */
    @Transactional
    public AppUserEntity redeem(Long tokenId, Long userId, String encodedPassword) {
        LockedAccount account = userRepository.lockAccount(userId).orElse(null);
        if (account == null || !account.active()) {
            LOG.info("Password reset refused: account for user id {} is disabled, pending or gone", userId);
            throw new IllegalArgumentException(INVALID_LINK);
        }
        // The instant is taken HERE, after the BCrypt hash and under the row lock: a link that expired
        // while the password was being hashed must not redeem on the strength of a pre-hash check.
        Instant now = clock.instant();
        if (tokenRepository.markUsed(tokenId, now) != 1) {
            // Zero rows: used (possibly a moment ago, by a concurrent request) or expired. One read of
            // the row, under the account lock held since above, says which; markUsed cleared the
            // persistence context, so this is a fresh read, not the pre-hash entity.
            boolean used = tokenRepository.findById(tokenId)
                    .map(token -> token.getUsedAt() != null).orElse(true);
            throw new IllegalArgumentException(used ? USED_LINK : EXPIRED_LINK);
        }
        tokenRepository.deleteByUserIdAndUsedAtIsNull(userId);
        if (userRepository.applyPasswordReset(userId, encodedPassword) != 1) {
            // Unreachable while the row lock is held (the account was enabled a moment ago), but a
            // count that is not 1 must never be read as success: throwing rolls the token back.
            throw new IllegalArgumentException(INVALID_LINK);
        }
        int revoked = refreshTokenRepository.revokeAllActiveByUserId(userId);
        LOG.info("Password reset redeemed for user id {}: revoked {} active refresh token(s)", userId, revoked);
        return userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException(INVALID_LINK));
    }
}
