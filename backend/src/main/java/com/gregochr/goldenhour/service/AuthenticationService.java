package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.JwtProperties;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.RefreshTokenEntity;
import com.gregochr.goldenhour.exception.InvalidCredentialsException;
import com.gregochr.goldenhour.exception.TooManyAttemptsException;
import com.gregochr.goldenhour.model.AuthTokens;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.RefreshTokenRepository;
import com.gregochr.goldenhour.util.EmailIdentifier;
import com.gregochr.goldenhour.util.EmailIdentifier.Kind;
import com.gregochr.goldenhour.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
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

    /** Limiter key prefix for the per-account fallback sign-in budget. */
    static final String FALLBACK_USER_KEY_PREFIX = "fallback:login:user:";

    /** Fallback sign-in attempts admitted per account in a quarter hour. */
    static final int FALLBACK_USER_LIMIT = 5;

    /** The throwaway string whose hash a no-candidate sign-in compares against. */
    private static final String DUMMY_PASSWORD = "not-a-real-password";

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
    private final AuthAttemptLimiter attemptLimiter;

    /** Lazily encoded by {@link #dummyHash()}; a benign race at worst encodes it twice. */
    private volatile String dummyHash;

    /**
     * Constructs the service.
     *
     * @param userRepository         user data access
     * @param refreshTokenRepository refresh token persistence
     * @param jwtService             JWT generation and hashing
     * @param passwordEncoder        password hashing
     * @param jwtProperties          token expiry configuration
     * @param attemptLimiter         the per-account budget for fallback sign-ins
     */
    public AuthenticationService(AppUserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            JwtService jwtService,
            PasswordEncoder passwordEncoder,
            JwtProperties jwtProperties,
            AuthAttemptLimiter attemptLimiter) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.jwtProperties = jwtProperties;
        this.attemptLimiter = attemptLimiter;
    }

    /**
     * Authenticates the user on the ordinary path (Turnstile verified, or not configured).
     *
     * @param identifier the login username, or the account's email address
     * @param password   the plain-text password
     * @return the issued tokens and user fields
     * @throws InvalidCredentialsException if the credentials are wrong or the account is disabled
     */
    public AuthTokens login(String identifier, String password) {
        return login(identifier, password, false);
    }

    /**
     * Authenticates the user, stamps {@code lastActiveAt}, and issues an access + refresh token
     * pair.
     *
     * <p><strong>The identifier is a username or an email address</strong> (login-registration plan
     * §2 #10). One containing {@code @} resolves TWO candidates: the exact username match (admin-created
     * usernames are not shape-checked, so one could carry an {@code @}) and the active account the email
     * rule picks ({@link EmailIdentifier}: exact first, then ASCII case-insensitive, exactly one). The
     * password is verified against the username match first and, if that fails or there is none, against
     * the email match; the first that verifies wins — when one account's username is another account's
     * address, a precedence that picked one candidate alone would lock the other account out for good.
     * Both failing is the generic failure. A BCrypt comparison against a fixed dummy hash runs when no
     * candidate resolves, so neither the username path's nor the email path's timing says whether the
     * identifier exists.
     *
     * <p>Deliberately not transactional: the slow BCrypt password check runs with no database
     * connection held. The two writes (last-active stamp, token insert) are independent
     * single-row saves — a failure between them fails the login with only the harmless
     * telemetry stamp persisted, matching the original controller flow.
     *
     * <p>When {@code fallback} is true the request carried no Turnstile token (the script could not
     * load) and {@link TurnstileGate} admitted it on its bounds; the per-ACCOUNT budget is then taken
     * here, once the identifier has resolved to its account(s) and before any password is checked, so
     * every name that reaches one account shares a single five-attempt budget per quarter hour. When no
     * account resolves, the gate's identifier bucket has already counted the attempt and nothing more is
     * taken.
     *
     * @param identifier the login username, or the account's email address
     * @param password   the plain-text password
     * @param fallback   true when the request is on the Turnstile fallback path
     * @return the issued tokens and user fields
     * @throws InvalidCredentialsException if the credentials are wrong or the account is disabled
     * @throws TooManyAttemptsException    if the fallback's per-account budget is spent
     */
    public AuthTokens login(String identifier, String password, boolean fallback) {
        List<AppUserEntity> candidates = loginCandidates(identifier);

        if (fallback) {
            for (AppUserEntity candidate : candidates) {
                if (!attemptLimiter.tryAcquire(FALLBACK_USER_KEY_PREFIX + candidate.getId(),
                        FALLBACK_USER_LIMIT, AuthAttemptLimiter.Window.QUARTER_HOUR)) {
                    LOG.info("Login refused: fallback attempt budget spent for user id {}", candidate.getId());
                    throw new TooManyAttemptsException("Fallback sign-in budget spent for the account");
                }
            }
        }

        if (candidates.isEmpty()) {
            // Same BCrypt cost as a real comparison, so the response time does not reveal that the
            // identifier matched nothing. The result is irrelevant: there is no account to sign in.
            passwordEncoder.matches(password, dummyHash());
        }

        AppUserEntity user = null;
        boolean verifiedButDisabled = false;
        for (AppUserEntity candidate : candidates) {
            if (passwordEncoder.matches(password, candidate.getPassword())) {
                if (candidate.isEnabled()) {
                    user = candidate;
                    break;
                }
                verifiedButDisabled = true;
            }
        }

        if (user == null) {
            if (verifiedButDisabled) {
                LOG.warn("Login failed: identifier='{}' — account disabled", LogSanitizer.sanitize(identifier));
                throw new InvalidCredentialsException("Account is disabled");
            }
            LOG.warn("Login failed: identifier='{}' — bad credentials", LogSanitizer.sanitize(identifier));
            throw new InvalidCredentialsException("Invalid username or password");
        }

        user.setLastActiveAt(LocalDateTime.now());
        userRepository.save(user);

        // Sanitised even though it comes from the database: registration never validates the
        // username's shape, so a stored name can still carry CRLF from the original request.
        LOG.info("Login: user='{}' role={}", LogSanitizer.sanitize(user.getUsername()), user.getRole());
        return issueTokensFor(user);
    }

    /**
     * Resolves the accounts an identifier may name, username candidate first.
     *
     * @param identifier the login username or email address
     * @return zero, one or two distinct accounts
     */
    private List<AppUserEntity> loginCandidates(String identifier) {
        List<AppUserEntity> candidates = new ArrayList<>(2);
        userRepository.findByUsername(identifier).ifPresent(candidates::add);
        if (identifier != null && identifier.contains("@")) {
            EmailIdentifier.resolve(identifier, userRepository, Kind.ACTIVE).ifPresent(byEmail -> {
                if (candidates.isEmpty()) {
                    candidates.add(byEmail);
                } else if (!candidates.get(0).getId().equals(byEmail.getId())) {
                    LOG.warn("Login identifier '{}' is one account's username and another account's email",
                            LogSanitizer.sanitize(identifier));
                    candidates.add(byEmail);
                }
            });
        }
        return candidates;
    }

    /**
     * Returns a BCrypt hash of a throwaway string, encoded once by the live encoder so a comparison
     * against it costs exactly what a real one does.
     *
     * @return the dummy hash
     */
    private String dummyHash() {
        String hash = dummyHash;
        if (hash == null) {
            hash = passwordEncoder.encode(DUMMY_PASSWORD);
            dummyHash = hash;
        }
        return hash;
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

        LOG.info("Password changed: user='{}'", LogSanitizer.sanitize(username));
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
