package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.RefreshTokenEntity;
import com.gregochr.goldenhour.exception.CurrentPasswordMismatchException;
import com.gregochr.goldenhour.exception.InvalidCredentialsException;
import com.gregochr.goldenhour.exception.NewPasswordUnchangedException;
import com.gregochr.goldenhour.exception.StaleCredentialsException;
import com.gregochr.goldenhour.exception.TooManyAttemptsException;
import com.gregochr.goldenhour.model.AuthTokens;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.RefreshTokenRepository;
import com.gregochr.goldenhour.service.CredentialChangeWriter.Change;
import com.gregochr.goldenhour.service.CredentialChangeWriter.VersionExpectation;
import com.gregochr.goldenhour.service.TokenIssuer.Rotation;
import com.gregochr.goldenhour.util.EmailIdentifier;
import com.gregochr.goldenhour.util.EmailIdentifier.Kind;
import com.gregochr.goldenhour.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Owns the authentication flows: credential verification, refresh-token rotation, token
 * issuance, password changes, and the password complexity policy.
 *
 * <p><strong>Nothing here is transactional, on purpose.</strong> BCrypt verification and encoding take
 * ~100 ms, and a surrounding transaction would pin a pooled database connection across it on a public
 * endpoint. Each flow does its slow work first and then calls ONE transactional method on a separate,
 * proxied bean (login-registration plan §2 #13): {@link TokenIssuer} issues tokens (and rotates a
 * refresh token), {@link CredentialChangeWriter} changes credentials, {@link RefreshFamilyRevoker}
 * answers token theft. A transactional method on this class would be reached by self-invocation and run
 * with no transaction at all.
 *
 * <p>{@link #refresh(String)} is race-safe: the old token is consumed with a database-level
 * compare-and-set inside {@link TokenIssuer#rotate}, so of two concurrent refresh calls presenting the
 * same token exactly one is issued new tokens. Any refresh attempt with an already-consumed token is
 * treated as evidence of token theft and revokes every active token for that user (family revocation) —
 * in a transaction of its own that COMMITS before the refusal is thrown.
 */
@Service
public class AuthenticationService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationService.class);

    /** Limiter key prefix for the per-account fallback sign-in budget. */
    static final String FALLBACK_USER_KEY_PREFIX = "fallback:login:user:";

    /** Fallback sign-in attempts admitted per account in a quarter hour. */
    static final int FALLBACK_USER_LIMIT = 5;

    /** Limiter key prefix for the per-account failed-sign-in count, on every login path. */
    static final String LOGIN_FAILURE_KEY_PREFIX = "login:failures:user:";

    /** Failed sign-ins after which an account refuses further attempts for the rest of the window. */
    static final int LOGIN_FAILURE_LIMIT = 10;

    /** Limiter key prefix for the per-account change-password budget. */
    static final String CHANGE_PASSWORD_USER_KEY_PREFIX = "change-password:user:";

    /** Change-password attempts admitted per account in a quarter hour. */
    static final int CHANGE_PASSWORD_USER_LIMIT = 5;

    /** The sentence for a change-password request with no current password. */
    public static final String CURRENT_PASSWORD_REQUIRED = "Current password is required";

    /** The sentence for a current password that does not match. */
    public static final String CURRENT_PASSWORD_INCORRECT = "Current password is incorrect";

    /** What a neutralised credential's stored value starts with; no BCrypt hash does. */
    private static final String DISABLED_HASH_PREFIX = "!";

    /** The sentence for a new password equal to the current one. */
    public static final String NEW_PASSWORD_UNCHANGED = "New password must differ from the current one";

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
    private final AuthAttemptLimiter attemptLimiter;
    private final TokenIssuer tokenIssuer;
    private final RefreshFamilyRevoker familyRevoker;
    private final CredentialChangeWriter credentialChangeWriter;

    /** Lazily encoded by {@link #dummyHash()}; a benign race at worst encodes it twice. */
    private volatile String dummyHash;

    /**
     * Constructs the service.
     *
     * @param userRepository         user data access
     * @param refreshTokenRepository refresh token persistence
     * @param jwtService             refresh-token hashing
     * @param passwordEncoder        password hashing
     * @param attemptLimiter         the per-account budget for fallback sign-ins
     * @param tokenIssuer            issues token pairs and rotates refresh tokens, under the row lock
     * @param familyRevoker          revokes a whole refresh family in its own committed transaction
     * @param credentialChangeWriter changes credentials atomically
     */
    public AuthenticationService(AppUserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            JwtService jwtService,
            PasswordEncoder passwordEncoder,
            AuthAttemptLimiter attemptLimiter,
            TokenIssuer tokenIssuer,
            RefreshFamilyRevoker familyRevoker,
            CredentialChangeWriter credentialChangeWriter) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.attemptLimiter = attemptLimiter;
        this.tokenIssuer = tokenIssuer;
        this.familyRevoker = familyRevoker;
        this.credentialChangeWriter = credentialChangeWriter;
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
     * connection held. The {@code lastActiveAt} stamp is a column-scoped update — never a save of the
     * entity loaded before the hash, which would write that entity's OLD password hash back over a
     * credential change that committed meanwhile — and the tokens are then issued by
     * {@link TokenIssuer#issue} with the hash this method matched: it re-reads the stored hash under the
     * row lock and refuses if it moved.
     *
     * <p>When {@code fallback} is true the request carried no Turnstile token (the script could not
     * load) and {@link TurnstileGate} admitted it on its bounds; the per-ACCOUNT budget is then taken
     * here, once the identifier has resolved to its account(s) and before any password is checked, so
     * every name that reaches one account shares a single five-attempt budget per quarter hour. When no
     * account resolves, the gate's identifier bucket has already counted the attempt and nothing more is
     * taken.
     *
     * <p>On EVERY path, verified or fallback, an account that has had {@value #LOGIN_FAILURE_LIMIT}
     * failed sign-ins in the quarter hour refuses the next attempt before any password is compared. A
     * failure is a candidate whose password did not match; a match (even on a disabled account) and a
     * successful sign-in never count, and an identifier that resolves to no account records nothing.
     * The counter is in memory, so a restart clears a lockout.
     *
     * @param identifier the login username, or the account's email address
     * @param password   the plain-text password
     * @param fallback  true when the request is on the Turnstile fallback path
     * @return the issued tokens and user fields
     * @throws InvalidCredentialsException if the credentials are wrong or the account is disabled
     * @throws TooManyAttemptsException    if an account has had ten failed sign-ins in the window, or the
     *                                     fallback's per-account budget is spent
     */
    public AuthTokens login(String identifier, String password, boolean fallback) {
        List<AppUserEntity> candidates = loginCandidates(identifier);

        for (AppUserEntity candidate : candidates) {
            if (attemptLimiter.isSpent(LOGIN_FAILURE_KEY_PREFIX + candidate.getId(),
                    LOGIN_FAILURE_LIMIT, AuthAttemptLimiter.Window.QUARTER_HOUR)) {
                LOG.info("Login refused: failed-sign-in limit reached for user id {}", candidate.getId());
                throw new TooManyAttemptsException("Too many failed sign-ins for the account");
            }
        }

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
        List<AppUserEntity> failed = new ArrayList<>();
        for (AppUserEntity candidate : candidates) {
            if (passwordEncoder.matches(password, candidate.getPassword())) {
                if (candidate.isEnabled()) {
                    user = candidate;
                    break;
                }
                verifiedButDisabled = true;
            } else {
                failed.add(candidate);
                if (isNeverValidHash(candidate.getPassword())) {
                    // A stored value no password can match (a neutralised default credential,
                    // "!"-prefixed, or the empty marker of a pending registration) is refused without
                    // BCrypt's cost. Spend that cost here so this row's response time matches every
                    // other row's. A null (impossible in the NOT NULL column) is left alone.
                    passwordEncoder.matches(password, dummyHash());
                }
            }
        }

        for (AppUserEntity candidate : failed) {
            attemptLimiter.recordFailure(LOGIN_FAILURE_KEY_PREFIX + candidate.getId(),
                    AuthAttemptLimiter.Window.QUARTER_HOUR);
        }

        if (user == null) {
            if (verifiedButDisabled) {
                LOG.warn("Login failed: identifier='{}' — account disabled", LogSanitizer.sanitize(identifier));
                throw new InvalidCredentialsException("Account is disabled");
            }
            LOG.warn("Login failed: identifier='{}' — bad credentials", LogSanitizer.sanitize(identifier));
            throw new InvalidCredentialsException("Invalid username or password");
        }

        userRepository.touchLastActiveAt(user.getId(), LocalDateTime.now());

        // Sanitised even though it comes from the database: registration never validates the
        // username's shape, so a stored name can still carry CRLF from the original request.
        LOG.info("Login: user='{}' role={}", LogSanitizer.sanitize(user.getUsername()), user.getRole());
        return tokenIssuer.issue(user.getId(), user.getPassword());
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

    private static boolean isNeverValidHash(String storedHash) {
        return storedHash != null && (storedHash.isEmpty() || storedHash.startsWith(DISABLED_HASH_PREFIX));
    }

    /**
     * Rotates a refresh token: consumes the supplied token atomically and issues a new access
     * + refresh token pair.
     *
     * <p>A non-transactional orchestrator over {@link TokenIssuer#rotate}: the rotation (the account
     * lock, the compare-and-set that consumes the token, the issue) is one transaction that returns an
     * outcome instead of throwing. Presenting an already-revoked token, or losing the compare-and-set to
     * a concurrent refresh, indicates the token was stolen (the legitimate rotation already consumed
     * it), so {@link RefreshFamilyRevoker#revokeAll} then revokes every active token for the user in a
     * transaction of its own, AFTER the rotation's has ended — and only then is the refusal thrown, so
     * the revocation commits instead of being rolled back with the exception (login-registration plan
     * §2 #13, §2 #14 rule 7).
     *
     * @param rawRefreshToken the raw refresh token supplied by the client
     * @return the newly issued tokens and user fields
     * @throws InvalidCredentialsException if the token is unknown, revoked, expired, or already
     *                                     consumed by a concurrent refresh, or the user no
     *                                     longer exists or is disabled
     */
    public AuthTokens refresh(String rawRefreshToken) {
        String hash = jwtService.hashToken(rawRefreshToken);
        Rotation rotation = tokenIssuer.rotate(hash);
        if (rotation.status() == TokenIssuer.RotationStatus.ROTATED) {
            return rotation.tokens();
        }
        if (rotation.status() == TokenIssuer.RotationStatus.REVOKED_BY_CREDENTIAL_CHANGE) {
            // Not theft: the credential change revoked it. The device that made the change keeps its
            // fresh tokens; this one is simply refused and signs in again.
            throw new InvalidCredentialsException(TokenIssuer.INVALID_TOKEN_MESSAGE);
        }
        familyRevoker.revokeAll(rotation.userId(),
                rotation.status() == TokenIssuer.RotationStatus.REVOKED_SEEN
                        ? "already-revoked token presented"
                        : "lost rotation race — token concurrently consumed");
        throw new InvalidCredentialsException(TokenIssuer.INVALID_TOKEN_MESSAGE);
    }

    /**
     * Issues an access + refresh token pair for an account whose password hash the caller has just
     * written (the auto-login after set-password or a reset).
     *
     * <p>A thin delegate to {@link TokenIssuer#issue}, kept for its callers' signatures. The hash is
     * the guard: the issuer re-reads the stored hash under the row lock and refuses if it is not this
     * one, so a credential change that committed in between issues nothing.
     *
     * @param userId               the account's primary key
     * @param verifiedPasswordHash the hash just written
     * @return the issued tokens and user fields
     * @throws InvalidCredentialsException with the generic failure if the account is disabled or its
     *                                     hash has moved
     */
    public AuthTokens issueTokensFor(Long userId, String verifiedPasswordHash) {
        return tokenIssuer.issue(userId, verifiedPasswordHash);
    }

    /**
     * Changes the signed-in user's password after re-verifying the current one, ends every other session
     * and returns fresh tokens for the caller's device.
     *
     * <p>Complexity validation is the caller's responsibility via
     * {@link #validatePasswordComplexity(String)}. The order is: load the user; refuse a missing or blank
     * current password (before the budget, so a malformed request spends none); ALWAYS take the account's
     * change-password budget (five per quarter hour, the window login uses) — {@link TurnstileGate} bounds
     * only the tokenless fallback and the limiter has no failure hook, so the check runs on every
     * attempt; verify the current password; then encode the new one. Deliberately not transactional: both
     * BCrypt operations run with no connection held, and then ONE transaction on
     * {@link CredentialChangeWriter} sets the password, clears the {@code passwordChangeRequired} flag,
     * bumps the credential version and revokes every refresh token. The caller's {@code expectation} makes
     * a stale session lose: two requests authenticated under the same old version can both reach the
     * writer, and a stolen session finishing its hash after the legitimate change committed must not
     * overwrite the new password.
     *
     * @param username        the authenticated user's username
     * @param currentPassword the plain-text password the user signs in with today
     * @param newPassword     the new plain-text password
     * @param expectation     the credential version the caller's session was issued at
     * @return the fresh tokens, so the device that changed the password stays signed in
     * @throws InvalidCredentialsException      if no user with that username exists
     * @throws TooManyAttemptsException         if the account's change-password budget is spent
     * @throws CurrentPasswordMismatchException if the current password is missing, blank or wrong
     * @throws NewPasswordUnchangedException    if the new password is the current one
     * @throws StaleCredentialsException        if the account's credentials moved since the caller's token
     *                                          was issued, or the account is no longer active
     */
    public AuthTokens changePassword(String username, String currentPassword, String newPassword,
            VersionExpectation expectation) {
        AppUserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new InvalidCredentialsException("User not found"));

        if (currentPassword == null || currentPassword.isBlank()) {
            throw new CurrentPasswordMismatchException(CURRENT_PASSWORD_REQUIRED);
        }
        if (!attemptLimiter.tryAcquire(CHANGE_PASSWORD_USER_KEY_PREFIX + user.getId(),
                CHANGE_PASSWORD_USER_LIMIT, AuthAttemptLimiter.Window.QUARTER_HOUR)) {
            LOG.info("Change-password refused: attempt budget spent for user id {}", user.getId());
            throw new TooManyAttemptsException("Change-password budget spent for the account");
        }
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            LOG.warn("Change-password refused: wrong current password for user id {}", user.getId());
            throw new CurrentPasswordMismatchException(CURRENT_PASSWORD_INCORRECT);
        }
        // Compared against the stored hash here, after the current password is verified, rather than as
        // an equality of two request strings.
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new NewPasswordUnchangedException(NEW_PASSWORD_UNCHANGED);
        }

        String encoded = passwordEncoder.encode(newPassword);
        CredentialChangeOutcome outcome = credentialChangeWriter.apply(user.getId(), encoded,
                Change.changePassword(), expectation);

        LOG.info("Password changed: user='{}'", LogSanitizer.sanitize(outcome.username()));
        return tokenIssuer.issue(outcome.userId(), encoded);
    }

    /**
     * Logs a caller out: revokes the presented refresh token and bumps the account's credential
     * version, so every outstanding access token dies at its next request (other devices take one
     * silent refresh). An open SSE stream is cut only when it reconnects.
     *
     * <p>The account is identified only from an UNREVOKED, UNEXPIRED refresh token or, failing that, an
     * authenticated non-anonymous principal; an unknown, revoked or expired token with no principal
     * does nothing. In one transaction: the account row is locked first (parent before children, as
     * {@link TokenIssuer#rotate} does), the presented token revoked with the conditional
     * {@code revokeIfActive}, then the version bumped by the column-scoped
     * {@code bumpCredentialsVersion} — deliberately not the credential-change update, so
     * {@code credentials_changed_at} is not stamped and a replay of the revoked token still reads as
     * {@code REVOKED_SEEN}. Called through the Spring proxy by the controller.
     *
     * @param rawRefreshToken the raw refresh token the client presented, or null
     * @param principalName   the authenticated, non-anonymous username, or null
     */
    @Transactional
    public void logout(String rawRefreshToken, String principalName) {
        String hash = null;
        Long userId = null;
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            hash = jwtService.hashToken(rawRefreshToken);
            RefreshTokenEntity stored = refreshTokenRepository.findByTokenHash(hash).orElse(null);
            if (stored != null && !stored.isRevoked() && !stored.getExpiresAt().isBefore(LocalDateTime.now())) {
                userId = stored.getUserId();
            }
        }
        if (userId == null && principalName != null) {
            userId = userRepository.findByUsername(principalName).map(AppUserEntity::getId).orElse(null);
        }
        if (userId == null) {
            return;
        }
        if (userRepository.lockAccount(userId).isEmpty()) {
            return;
        }
        if (hash != null) {
            refreshTokenRepository.revokeIfActive(hash);
        }
        userRepository.bumpCredentialsVersion(userId);
        LOG.info("Logout: user id {}", userId);
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

}
