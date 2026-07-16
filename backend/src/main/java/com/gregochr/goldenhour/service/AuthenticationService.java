package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.JwtProperties;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.RefreshTokenEntity;
import com.gregochr.goldenhour.exception.InvalidCredentialsException;
import com.gregochr.goldenhour.model.AuthTokens;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.regex.Pattern;

/**
 * Owns the authentication flows: credential verification, refresh-token rotation, token
 * issuance, password changes, and the password complexity policy.
 *
 * <p>{@link #refresh(String)} is {@link Transactional} and race-safe: the old token is
 * consumed with a database-level compare-and-set
 * ({@link RefreshTokenRepository#revokeIfActive}), so of two concurrent refresh calls
 * presenting the same token exactly one is issued new tokens. Any refresh attempt with an
 * already-consumed token is treated as evidence of token theft and revokes every active
 * token for that user (family revocation). {@link #login} and {@link #changePassword} are
 * deliberately <em>not</em> transactional: BCrypt verification/encoding takes ~100 ms, and a
 * surrounding transaction would pin a pooled database connection across it on a public
 * endpoint. Their writes are independent single-row saves, each atomic on its own.
 */
@Service
public class AuthenticationService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationService.class);

    /** Generic message for all invalid-token outcomes, to avoid leaking token state. */
    private static final String INVALID_TOKEN_MESSAGE = "Refresh token is invalid or expired";

    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final Pattern UPPERCASE = Pattern.compile("[A-Z]");
    private static final Pattern LOWERCASE = Pattern.compile("[a-z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");
    private static final Pattern SPECIAL = Pattern.compile("[^A-Za-z0-9]");

    private final AppUserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final JwtProperties jwtProperties;

    /**
     * Constructs the service.
     *
     * @param userRepository         user data access
     * @param refreshTokenRepository refresh token persistence
     * @param jwtService             JWT generation and hashing
     * @param passwordEncoder        password hashing
     * @param jwtProperties          token expiry configuration
     */
    public AuthenticationService(AppUserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            JwtService jwtService,
            PasswordEncoder passwordEncoder,
            JwtProperties jwtProperties) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.jwtProperties = jwtProperties;
    }

    /**
     * Authenticates the user, stamps {@code lastActiveAt}, and issues an access + refresh token
     * pair.
     *
     * <p>Deliberately not transactional: the slow BCrypt password check runs with no database
     * connection held. The two writes (last-active stamp, token insert) are independent
     * single-row saves — a failure between them fails the login with only the harmless
     * telemetry stamp persisted, matching the original controller flow.
     *
     * @param username the login username
     * @param password the plain-text password
     * @return the issued tokens and user fields
     * @throws InvalidCredentialsException if the credentials are wrong or the account is disabled
     */
    public AuthTokens login(String username, String password) {
        AppUserEntity user = userRepository.findByUsername(username).orElse(null);

        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            LOG.warn("Login failed: user='{}' — bad credentials", username);
            throw new InvalidCredentialsException("Invalid username or password");
        }
        if (!user.isEnabled()) {
            LOG.warn("Login failed: user='{}' — account disabled", username);
            throw new InvalidCredentialsException("Account is disabled");
        }

        user.setLastActiveAt(LocalDateTime.now());
        userRepository.save(user);

        LOG.info("Login: user='{}' role={}", user.getUsername(), user.getRole());
        return issueTokensFor(user);
    }

    /**
     * Rotates a refresh token: consumes the supplied token atomically and issues a new access
     * + refresh token pair. Revoke and reissue share one transaction, so a mid-flow failure
     * rolls back the revoke instead of stranding the user without a valid token.
     *
     * <p>The revoke is a database-level compare-and-set ({@code UPDATE … WHERE revoked =
     * false}): of two concurrent refresh calls presenting the same token, exactly one sees an
     * update count of 1 and rotates; the other is rejected. Presenting an already-revoked
     * token, or losing that race, indicates the token was stolen (the legitimate rotation
     * already consumed it), so every active token for the user is revoked and all sessions
     * must re-authenticate.
     *
     * @param rawRefreshToken the raw refresh token supplied by the client
     * @return the newly issued tokens and user fields
     * @throws InvalidCredentialsException if the token is unknown, revoked, expired, or already
     *                                     consumed by a concurrent refresh, or the user no
     *                                     longer exists or is disabled
     */
    @Transactional
    public AuthTokens refresh(String rawRefreshToken) {
        String hash = jwtService.hashToken(rawRefreshToken);
        RefreshTokenEntity stored = refreshTokenRepository.findByTokenHash(hash).orElse(null);

        if (stored == null) {
            throw new InvalidCredentialsException(INVALID_TOKEN_MESSAGE);
        }
        if (stored.isRevoked()) {
            revokeFamily(stored.getUserId(), "already-revoked token presented");
            throw new InvalidCredentialsException(INVALID_TOKEN_MESSAGE);
        }
        if (stored.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new InvalidCredentialsException(INVALID_TOKEN_MESSAGE);
        }

        AppUserEntity user = userRepository.findById(stored.getUserId()).orElse(null);
        if (user == null || !user.isEnabled()) {
            throw new InvalidCredentialsException("User not found or disabled");
        }

        // Atomic compare-and-set: only one concurrent refresh can consume the token.
        int consumed = refreshTokenRepository.revokeIfActive(hash);
        if (consumed != 1) {
            revokeFamily(stored.getUserId(), "lost rotation race — token concurrently consumed");
            throw new InvalidCredentialsException(INVALID_TOKEN_MESSAGE);
        }

        return issueTokensFor(user);
    }

    /**
     * Issues an access + refresh token pair for an already-authenticated user (e.g. the
     * auto-login after registration set-password).
     *
     * <p>Opens its own transaction when invoked through the Spring proxy. When called
     * internally from {@link #login} the annotation is bypassed (self-invocation) and the
     * single token insert runs in the repository's own implicit transaction — equivalent,
     * since there is only one write.
     *
     * @param user the user to issue tokens for
     * @return the issued tokens and user fields
     */
    @Transactional
    public AuthTokens issueTokensFor(AppUserEntity user) {
        String accessToken = jwtService.generateAccessToken(user.getUsername(), user.getRole());
        String rawRefresh = jwtService.generateRefreshToken();
        String refreshHash = jwtService.hashToken(rawRefresh);
        Date accessExpiresAt = jwtService.extractExpiry(accessToken);

        LocalDateTime refreshExpiresAt = LocalDateTime.now()
                .plusDays(jwtProperties.getRefreshTokenExpiryDays());
        RefreshTokenEntity tokenEntity = RefreshTokenEntity.builder()
                .tokenHash(refreshHash)
                .userId(user.getId())
                .expiresAt(refreshExpiresAt)
                .revoked(false)
                .build();
        refreshTokenRepository.save(tokenEntity);

        return new AuthTokens(
                accessToken,
                rawRefresh,
                accessExpiresAt.toInstant(),
                refreshExpiresAt.toInstant(ZoneOffset.UTC),
                user.getUsername(),
                user.getRole().name(),
                user.isPasswordChangeRequired(),
                user.isMarketingEmailOptIn(),
                user.getTermsVersion());
    }

    /**
     * Changes the user's password and clears the {@code passwordChangeRequired} flag.
     *
     * <p>Complexity validation is the caller's responsibility via
     * {@link #validatePasswordComplexity(String)}. Deliberately not transactional: BCrypt
     * encoding is slow, and both field changes commit atomically anyway as one row update.
     *
     * @param username    the authenticated user's username
     * @param newPassword the new plain-text password
     * @throws InvalidCredentialsException if no user with that username exists
     */
    public void changePassword(String username, String newPassword) {
        AppUserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new InvalidCredentialsException("User not found"));

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setPasswordChangeRequired(false);
        userRepository.save(user);

        LOG.info("Password changed: user='{}'", username);
    }

    /**
     * Revokes the refresh token if it exists, effectively logging the holder out. Unknown
     * tokens are ignored.
     *
     * @param rawRefreshToken the raw refresh token supplied by the client
     */
    @Transactional
    public void revokeRefreshToken(String rawRefreshToken) {
        String hash = jwtService.hashToken(rawRefreshToken);
        refreshTokenRepository.findByTokenHash(hash).ifPresent(token -> {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
        });
    }

    /**
     * Returns {@code null} if the password meets all complexity requirements, or an error
     * message describing the first unmet rule.
     *
     * @param password the candidate password
     * @return error message, or {@code null} if valid
     */
    public String validatePasswordComplexity(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            return "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.";
        }
        if (!UPPERCASE.matcher(password).find()) {
            return "Password must contain at least one uppercase letter.";
        }
        if (!LOWERCASE.matcher(password).find()) {
            return "Password must contain at least one lowercase letter.";
        }
        if (!DIGIT.matcher(password).find()) {
            return "Password must contain at least one number.";
        }
        if (!SPECIAL.matcher(password).find()) {
            return "Password must contain at least one special character.";
        }
        return null;
    }

    private void revokeFamily(Long userId, String reason) {
        int revoked = refreshTokenRepository.revokeAllActiveByUserId(userId);
        LOG.warn("Refresh token reuse detected (userId={}, {}): revoked {} active token(s)",
                userId, reason, revoked);
    }
}
