package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.JwtProperties;
import com.gregochr.goldenhour.entity.RefreshTokenEntity;
import com.gregochr.goldenhour.exception.InvalidCredentialsException;
import com.gregochr.goldenhour.model.AuthTokens;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AppUserRepository.LockedAccount;
import com.gregochr.goldenhour.repository.RefreshTokenRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Objects;

/**
 * The one place access and refresh tokens are issued and refresh rows inserted
 * (login-registration plan §2 #13).
 *
 * <p>Issuance is serialised against {@link CredentialChangeWriter}: both take the user-row lock FIRST
 * (the parent-before-children order every credential path uses). A login that verified the OLD hash just
 * before a credential change committed would otherwise issue an access token carrying the NEW
 * credential version (read after the bump) and insert a refresh row after the revocation. Under the
 * lock {@link #issue} re-reads the stored hash, the credential version and the enabled flag as a SCALAR
 * projection — never a locked entity, which under open-session-in-view is the stale instance the request
 * already holds — and refuses with the generic failure if the hash is not the one the caller verified or
 * the account is disabled. So either the issue commits wholly before the change, whose revocation then
 * catches the new refresh row and whose version bump makes the issued {@code cv} stale, or wholly after
 * it, and sees the new hash.
 *
 * <p>A bean of its own so callers reach it through the Spring proxy: a method on
 * {@link AuthenticationService} would be reached by self-invocation, which bypasses
 * {@code @Transactional}.
 */
@Component
public class TokenIssuer {

    /** The generic failure: it never says whether the hash moved or the account was disabled. */
    static final String ISSUE_REFUSED = "Invalid username or password";

    /** Generic message for all invalid-refresh-token outcomes, to avoid leaking token state. */
    static final String INVALID_TOKEN_MESSAGE = "Refresh token is invalid or expired";

    /** How a refresh-token rotation ended. */
    public enum RotationStatus {
        /** The presented token was consumed and a new pair issued. */
        ROTATED,
        /** The presented token had already been revoked: evidence it was stolen. */
        REVOKED_SEEN,
        /**
         * The presented token was revoked by a credential change that came after it was issued — a
         * password change, a reset, a disable. Another device presenting its now-dead token is the
         * expected sequel to such a change, not theft, and must not revoke the family: that would sign
         * out the device that changed the password and was just handed fresh tokens.
         */
        REVOKED_BY_CREDENTIAL_CHANGE,
        /** A concurrent refresh consumed the token first: the same evidence. */
        LOST_RACE
    }

    /**
     * The result of {@link #rotate}: an outcome instead of an exception, so the caller can commit the
     * theft response (revoking the family) before refusing.
     *
     * @param status the way the rotation ended
     * @param userId the account the presented token belonged to
     * @param tokens the new pair when {@link RotationStatus#ROTATED}, else null
     */
    public record Rotation(RotationStatus status, Long userId, AuthTokens tokens) {
    }

    private final AppUserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;

    /**
     * Constructs the issuer.
     *
     * @param userRepository         accounts
     * @param refreshTokenRepository sessions
     * @param jwtService             token generation and hashing
     * @param jwtProperties          token expiry configuration
     */
    public TokenIssuer(AppUserRepository userRepository, RefreshTokenRepository refreshTokenRepository,
            JwtService jwtService, JwtProperties jwtProperties) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
    }

    /**
     * Issues an access and refresh token pair for an account whose password hash the caller has just
     * verified or written.
     *
     * @param userId               the account's primary key
     * @param verifiedPasswordHash the hash the caller matched (login), wrote (set-password, reset,
     *                             change-password) or read under the lock (refresh)
     * @return the issued tokens and user fields
     * @throws InvalidCredentialsException with the generic failure if the account is gone or disabled,
     *                                     or its stored hash is no longer the one the caller verified
     */
    @Transactional
    public AuthTokens issue(Long userId, String verifiedPasswordHash) {
        LockedAccount account = userRepository.lockAccount(userId).orElse(null);
        if (account == null || !account.active()
                || !Objects.equals(account.password(), verifiedPasswordHash)) {
            throw new InvalidCredentialsException(ISSUE_REFUSED);
        }
        return issueUnderLock(userId, account);
    }

    /**
     * Rotates a refresh token: locks the account, consumes the presented token with a database-level
     * compare-and-set and issues a new pair, in one transaction — returning an OUTCOME for the theft
     * cases rather than throwing, so the caller's family revocation commits before the refusal is thrown
     * (a thrown runtime exception here would roll the revocation back with it).
     *
     * <p>The account row is locked BEFORE the compare-and-set on the refresh row, the order every
     * credential path uses; the reverse deadlocked against {@link CredentialChangeWriter}, which holds
     * the user row and then revokes refresh rows.
     *
     * @param refreshTokenHash the SHA-256 hash of the presented refresh token
     * @return how the rotation ended
     * @throws InvalidCredentialsException if the token is unknown or expired, or the account is gone or
     *                                     disabled; nothing has been written in those cases
     */
    @Transactional
    public Rotation rotate(String refreshTokenHash) {
        RefreshTokenEntity stored = refreshTokenRepository.findByTokenHash(refreshTokenHash).orElse(null);
        if (stored == null) {
            throw new InvalidCredentialsException(INVALID_TOKEN_MESSAGE);
        }
        Long userId = stored.getUserId();
        if (stored.isRevoked()) {
            return new Rotation(issuedBeforeCredentialChange(stored, userId)
                    ? RotationStatus.REVOKED_BY_CREDENTIAL_CHANGE : RotationStatus.REVOKED_SEEN, userId, null);
        }
        if (stored.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new InvalidCredentialsException(INVALID_TOKEN_MESSAGE);
        }

        LockedAccount account = userRepository.lockAccount(userId).orElse(null);
        if (account == null || !account.enabled()) {
            throw new InvalidCredentialsException("User not found or disabled");
        }

        // Atomic compare-and-set: only one concurrent refresh can consume the token.
        if (refreshTokenRepository.revokeIfActive(refreshTokenHash) != 1) {
            // The token may have been read as live before a credential change that held the row lock
            // revoked it: that is the change's doing, not a concurrent rotation, so it is not theft.
            return new Rotation(issuedBeforeCredentialChange(stored, userId)
                    ? RotationStatus.REVOKED_BY_CREDENTIAL_CHANGE : RotationStatus.LOST_RACE, userId, null);
        }
        return new Rotation(RotationStatus.ROTATED, userId, issueUnderLock(userId, account));
    }

    /**
     * Reports whether the token was issued before the account's credentials last changed. A refresh row
     * has no issue instant of its own, so it is derived from its expiry (a wall-clock {@code LocalDateTime}
     * taken in the JVM's zone when it was issued) and the configured lifetime. Every
     * token issued before a credential change is revoked BY it, so a revoked token older than the stamp
     * says nothing about theft; one issued after the stamp can only have been revoked by rotation or a
     * logout, which does. A scalar read, not a lock: the decision needs no atomicity.
     */
    private boolean issuedBeforeCredentialChange(RefreshTokenEntity token, Long userId) {
        Instant changedAt = userRepository.findCredentialsChangedAt(userId).orElse(null);
        if (changedAt == null) {
            return false;
        }
        // Subtract the lifetime in wall-clock time, then convert: the expiry was written as the JVM's local
        // date-time plus whole days, and a DST change inside the window makes 30 days of Instant arithmetic
        // an hour off.
        Instant issuedAt = token.getExpiresAt().minusDays(jwtProperties.getRefreshTokenExpiryDays())
                .atZone(ZoneId.systemDefault()).toInstant();
        return issuedAt.isBefore(changedAt);
    }

    private AuthTokens issueUnderLock(Long userId, LockedAccount account) {
        String accessToken = jwtService.generateAccessToken(account.username(), account.role(), userId,
                account.credentialsVersion());
        String rawRefresh = jwtService.generateRefreshToken();
        String refreshHash = jwtService.hashToken(rawRefresh);
        Date accessExpiresAt = jwtService.extractExpiry(accessToken);

        LocalDateTime refreshExpiresAt = LocalDateTime.now()
                .plusDays(jwtProperties.getRefreshTokenExpiryDays());
        refreshTokenRepository.save(RefreshTokenEntity.builder()
                .tokenHash(refreshHash)
                .userId(userId)
                .expiresAt(refreshExpiresAt)
                .revoked(false)
                .build());

        return new AuthTokens(
                accessToken,
                rawRefresh,
                accessExpiresAt.toInstant(),
                refreshExpiresAt.toInstant(ZoneOffset.UTC),
                account.username(),
                account.role().name(),
                account.passwordChangeRequired(),
                account.marketingEmailOptIn(),
                account.termsVersion());
    }
}
