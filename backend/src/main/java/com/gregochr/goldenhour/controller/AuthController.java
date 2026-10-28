package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.config.JwtAuthenticationDetails;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.exception.CurrentPasswordMismatchException;
import com.gregochr.goldenhour.exception.InvalidCredentialsException;
import com.gregochr.goldenhour.exception.NewPasswordUnchangedException;
import com.gregochr.goldenhour.exception.TooManyAttemptsException;
import com.gregochr.goldenhour.model.AuthTokens;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.service.AuthAttemptLimiter;
import com.gregochr.goldenhour.service.AuthAttemptLimiter.Window;
import com.gregochr.goldenhour.service.AuthenticationService;
import com.gregochr.goldenhour.service.ClientAddress;
import com.gregochr.goldenhour.service.CredentialChangeWriter.VersionExpectation;
import com.gregochr.goldenhour.service.JwtService;
import com.gregochr.goldenhour.service.PasswordResetService;
import com.gregochr.goldenhour.service.RegistrationService;
import com.gregochr.goldenhour.service.TurnstileGate;
import com.gregochr.goldenhour.service.TurnstileGate.Endpoint;
import com.gregochr.goldenhour.service.TurnstileGate.Outcome;
import com.gregochr.goldenhour.service.TurnstileService;
import com.gregochr.goldenhour.util.EmailIdentifier;
import com.gregochr.goldenhour.util.EmailValidator;
import com.gregochr.goldenhour.util.LogSanitizer;
import com.gregochr.goldenhour.util.UniqueViolation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * REST controller for authentication operations.
 *
 * <p>Handles login, token refresh, and logout. All endpoints under
 * {@code /api/auth} are public (no JWT required).
 *
 * <p>The controller is a thin HTTP layer: request-shape validation and CAPTCHA checks happen
 * here, while credential verification, token issuance/rotation, and the password policy live in
 * {@link AuthenticationService}. Login and change-password go through {@link TurnstileGate} (a verified
 * token, or a bounded fallback when none could be produced); registration verifies the token itself and
 * has no fallback; set-password takes no token at all and is bounded by {@link AuthAttemptLimiter}.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{3,30}$");

    /** The sentence every attempt bound answers with (login-registration plan §2 #12). */
    static final String TOO_MANY_ATTEMPTS = "Too many attempts. Please wait a few minutes and try again.";

    /** The name Spring Security gives the anonymous principal; no account may be called this. */
    private static final String ANONYMOUS_PRINCIPAL = "anonymousUser";

    /** Limiter key prefix for the one-time-token endpoint that sets a first password. */
    static final String SET_PASSWORD_BOUNDS = "set-password";

    /** Limiter key prefix for the one-time-token endpoint that redeems a password-reset link. */
    static final String RESET_PASSWORD_BOUNDS = "reset";

    /** What forgot-password answers for every syntactically valid email, whatever happened. */
    static final String FORGOT_PASSWORD_MESSAGE = "If that email has an account, we've sent a reset link.";

    /** The 400 for an address the app no longer accepts for NEW registrations (plan §4 #21). */
    static final String NON_ASCII_EMAIL = EmailIdentifier.NON_ASCII_MESSAGE;

    /** Requests per endpoint across every caller, per hour, on a one-time-token endpoint. */
    static final int TOKEN_GLOBAL_LIMIT = 200;

    /** Requests per client address per quarter hour on a one-time-token endpoint. */
    static final int TOKEN_CLIENT_LIMIT = 20;

    /** Requests per token per quarter hour on a one-time-token endpoint. */
    static final int TOKEN_LIMIT = 10;

    /** How many leading hex characters of the token's hash name its limiter key. */
    private static final int TOKEN_KEY_LENGTH = 16;

    private final AuthenticationService authenticationService;
    private final AppUserRepository userRepository;
    private final RegistrationService registrationService;
    private final TurnstileService turnstileService;
    private final TurnstileGate turnstileGate;
    private final AuthAttemptLimiter attemptLimiter;
    private final JwtService jwtService;
    private final PasswordResetService passwordResetService;

    /**
     * Authenticates the user and returns a JWT access token plus a refresh token.
     *
     * <p>The Turnstile token goes through {@link TurnstileGate}: a verified token proceeds as it always
     * has, and a request with no token at all (the script could not load) proceeds only inside the
     * gate's bounds and the account's own five-attempt budget.
     *
     * @param body    map containing {@code username} (the username OR the account's email address — the
     *                field keeps its wire name so existing clients work) and {@code password}
     * @param request the request, for the caller's address
     * @return 200 with tokens, 400 for a failed CAPTCHA, 401 if credentials are invalid, or 429 when an
     *         attempt bound is reached
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String identifier = body.get("username");
        String password = body.get("password");

        Outcome outcome = turnstileGate.admit(body.get("turnstileToken"), identifier,
                ClientAddress.of(request), Endpoint.LOGIN);
        ResponseEntity<Map<String, Object>> refusal = refusalFor(outcome);
        if (refusal != null) {
            return refusal;
        }

        try {
            AuthTokens tokens = authenticationService.login(identifier, password,
                    outcome == Outcome.OK_FALLBACK);
            return ResponseEntity.ok(buildAuthResponse(tokens));
        } catch (InvalidCredentialsException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", ex.getMessage()));
        } catch (TooManyAttemptsException ex) {
            return tooManyAttempts();
        }
    }

    /**
     * Changes the authenticated user's password and clears the {@code passwordChangeRequired} flag.
     *
     * <p>Enforces complexity: min 8 chars, upper, lower, digit, special character.
     *
     * <p>The caller must be authenticated: {@code /api/auth/**} is public, so an unauthenticated call
     * arrives as Spring's anonymous principal, which is refused here (401) rather than treated as a
     * user. The Turnstile token goes through {@link TurnstileGate} exactly as on login.
     *
     * <p>The password change ends every OTHER session (the credential version moves and every refresh
     * token is revoked), so the response carries FRESH tokens for this device — the same body as
     * {@code /login} plus the {@code message} — and the device that changed the password stays signed in.
     * The caller's own session version rides in on the filter's details: a session that was superseded
     * while this request hashed the new password is refused (401, "Your session has changed. Sign in
     * again.") and changes nothing.
     *
     * <p>The current password is re-verified (a briefly stolen access token must not become a permanent
     * takeover): missing or blank, wrong, or equal to the new one is a 400 — never a 401, which the
     * client would refresh and retry — and the per-account budget being spent is a 429.
     *
     * @param body    map containing {@code currentPassword} and {@code newPassword}
     * @param request the request, for the caller's address
     * @return 200 with fresh tokens and {@code message} on success, 400 if the current password is
     *         missing or wrong, the new password fails complexity rules or equals the current one, or the
     *         CAPTCHA fails, 401 if unauthenticated or the session is stale, 429 when an attempt bound is
     *         reached
     */
    @PostMapping("/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(@RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String username = authenticatedUsername();
        if (username == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Not authenticated"));
        }

        String currentPassword = body.get("currentPassword");
        if (currentPassword == null || currentPassword.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Current password is required"));
        }

        Outcome outcome = turnstileGate.admit(body.get("turnstileToken"), username,
                ClientAddress.of(request), Endpoint.CHANGE_PASSWORD);
        ResponseEntity<Map<String, Object>> refusal = refusalFor(outcome);
        if (refusal != null) {
            return refusal;
        }

        String newPassword = body.get("newPassword");
        String complexityError = authenticationService.validatePasswordComplexity(newPassword);
        if (complexityError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", complexityError));
        }

        try {
            AuthTokens tokens = authenticationService.changePassword(username, currentPassword, newPassword,
                    sessionVersionExpectation());
            Map<String, Object> response = buildAuthResponse(tokens);
            response.put("message", "Password updated");
            return ResponseEntity.ok(response);
        } catch (CurrentPasswordMismatchException | NewPasswordUnchangedException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (TooManyAttemptsException ex) {
            return tooManyAttempts();
        } catch (InvalidCredentialsException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * Issues a new access token and rotates the refresh token.
     *
     * <p>The supplied refresh token is revoked and a fresh one is issued, extending
     * the session by another {@code jwt.refresh-token-expiry-days} days.
     *
     * @param body map containing {@code refreshToken}
     * @return 200 with new access and refresh tokens, or 401 if the token is invalid
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(@RequestBody Map<String, String> body) {
        String rawRefresh = body.get("refreshToken");
        if (rawRefresh == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Refresh token required"));
        }

        try {
            AuthTokens tokens = authenticationService.refresh(rawRefresh);
            return ResponseEntity.ok(Map.of(
                    "accessToken", tokens.accessToken(),
                    "refreshToken", tokens.refreshToken(),
                    "expiresAt", tokens.accessTokenExpiresAt().toString(),
                    "refreshExpiresAt", tokens.refreshTokenExpiresAt().toString()));
        } catch (InvalidCredentialsException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * Registers a new user with email verification.
     *
     * <p>Marketing email is opt-in: only an explicit {@code "true"} in {@code marketingEmailOptIn}
     * consents; an absent value does not. The username {@code anonymousUser} (Spring Security's
     * anonymous principal) is refused with the same 409 as a taken name.
     *
     * <p>A new registration must use an ASCII email address (400 otherwise): a confusable address is the
     * one input the reset flow cannot make safe by comparison alone. A lost race on the username key or
     * the email reservation surfaces from the service as a {@link DataIntegrityViolationException} and is
     * translated here, outside the rolled-back transaction, to the same 409 a plain duplicate gets.
     *
     * <p>An optional {@code inviteToken} (a waitlist invitation) lets a registration for the address it was sent
     * to past the early-access cap, once. Anything else about it (wrong address, expired, used, unknown) is
     * ignored here and the normal cap applies, so the response never says whether a token was valid.
     *
     * @param body map containing {@code username} and {@code email}, and optionally {@code inviteToken}
     * @return 200 with confirmation message, 400 for validation errors, 403 when registration is closed,
     *         409 for duplicates
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String email = body.get("email");

        if (username == null || username.isBlank() || email == null || email.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Username and email are required"));
        }
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error",
                            "Username must be 3-30 characters: letters, numbers, hyphens, or underscores"));
        }
        if (ANONYMOUS_PRINCIPAL.equalsIgnoreCase(username)) {
            // The anonymous principal's name: an account called this would be reachable by any
            // unauthenticated caller on the endpoints that read the principal's name.
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Username already exists"));
        }
        if (!EmailValidator.isValid(email)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid email address"));
        }
        if (!EmailIdentifier.isAscii(email.trim())) {
            return ResponseEntity.badRequest().body(Map.of("error", NON_ASCII_EMAIL));
        }

        String turnstileToken = body.get("turnstileToken");
        if (!turnstileService.verify(turnstileToken)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "CAPTCHA verification failed. Please try again."));
        }

        if (!"true".equalsIgnoreCase(body.get("termsAccepted"))) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error",
                            "You must accept the Terms & Conditions and Privacy Policy to register."));
        }

        // Opt-in is consent: an absent or unrecognised value is NOT consent (plan §1 #15).
        boolean marketingEmailOptIn = "true".equalsIgnoreCase(body.get("marketingEmailOptIn"));

        try {
            registrationService.register(username, email.trim(), marketingEmailOptIn, body.get("inviteToken"));
            LOG.info("Registration: user='{}' email='{}'",
                    LogSanitizer.sanitize(username), LogSanitizer.sanitize(email.trim()));
            return ResponseEntity.ok(Map.of(
                    "message", "Verification email sent",
                    "email", email.trim()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", ex.getMessage()));
        } catch (DataIntegrityViolationException ex) {
            String conflict = UniqueViolation.conflictMessage(ex).orElseThrow(() -> ex);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", conflict));
        }
    }

    /**
     * Resends a verification email for a pending registration.
     *
     * <p>Returns a generic success message regardless of whether the email exists,
     * to prevent email enumeration attacks.
     *
     * @param body map containing {@code email}
     * @return 200 with generic success, or 429 if rate limited
     */
    @PostMapping("/resend-verification")
    public ResponseEntity<Map<String, Object>> resendVerification(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Email is required"));
        }

        try {
            registrationService.resendVerification(email.trim());
            return ResponseEntity.ok(Map.of(
                    "message", "If that email has a pending registration, a new verification link has been sent"));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * Verifies an email address using the token from the verification link.
     *
     * @param body map containing {@code token}
     * @return 200 with userId, verified flag and the rotated {@code setupToken}, or 400 if the token is bad
     */
    @PostMapping("/verify-email")
    public ResponseEntity<Map<String, Object>> verifyEmail(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Verification token is required"));
        }

        try {
            RegistrationService.VerifiedEmail verified = registrationService.verifyEmail(token);
            return ResponseEntity.ok(Map.of("userId", verified.userId(), "verified", true,
                    "setupToken", verified.setupToken()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * Starts a password reset: mails a one-hour link to the account the address belongs to, if any.
     *
     * <p>Answers the same 200 and sentence for EVERY syntactically valid address (400 for a blank or
     * malformed one), whether it has an account or not, and whether or not a link was sent — a different
     * answer for a known address would say the account exists (login-registration plan §2 #1, §4 #8).
     * The request goes through {@link TurnstileGate}; a token that fails is a 400 (a real error), but a
     * fallback bound that is reached answers the generic 200 with nothing sent, never a 429. The send
     * is decided and answered before it runs, and a failure inside the reset service is logged and
     * swallowed so a known address cannot be told apart by a 500.
     *
     * @param body    map containing {@code email} and optionally {@code turnstileToken}
     * @param request the request, for the caller's address
     * @return 200 with the generic message, or 400 for a blank or invalid address or a failed CAPTCHA
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, Object>> forgotPassword(@RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String email = body.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
        }
        if (!EmailValidator.isValid(email)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid email address"));
        }

        Outcome outcome = turnstileGate.admit(body.get("turnstileToken"), email,
                ClientAddress.of(request), Endpoint.FORGOT);
        if (outcome == Outcome.CAPTCHA_FAILED) {
            return refusalFor(outcome);
        }
        if (outcome != Outcome.RATE_LIMITED) {
            try {
                passwordResetService.requestReset(email.trim());
            } catch (RuntimeException ex) {
                LOG.warn("Password reset request failed: {}", LogSanitizer.sanitize(ex.toString()));
            }
        }
        return ResponseEntity.ok(Map.of("message", FORGOT_PASSWORD_MESSAGE));
    }

    /**
     * Redeems a password-reset link: sets the new password, ends every other session and signs the
     * caller in on this device, as set-password does.
     *
     * <p>No Turnstile: the emailed token is the proof. Three bounds protect the caches and the database
     * (an hourly global ceiling, the client address, the token), checked outermost first, any one refused
     * is a 429. The complexity check runs before the token is looked up.
     *
     * @param body    map containing {@code token} and {@code password}
     * @param request the request, for the caller's address
     * @return 200 with JWT tokens (same shape as /login), 400 with the sentence for an unknown, used or
     *         expired link, a link naming a disabled account, or a password failing complexity, or 429
     *         when an attempt bound is reached
     */
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, Object>> resetPassword(@RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String token = body.get("token");
        String password = body.get("password");

        try {
            requireTokenAndPassword(token, password);
            enforceTokenBounds(RESET_PASSWORD_BOUNDS, token, ClientAddress.of(request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (TooManyAttemptsException ex) {
            return tooManyAttempts();
        }

        String complexityError = authenticationService.validatePasswordComplexity(password);
        if (complexityError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", complexityError));
        }

        try {
            AuthTokens tokens = passwordResetService.resetPassword(token, password);
            return ResponseEntity.ok(buildAuthResponse(tokens));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (InvalidCredentialsException ex) {
            // The reset committed, but the account changed again before the auto-login: sign in normally.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * Sets the password for a newly verified user and auto-logs them in.
     *
     * <p>The account is identified by the one-time verification token from the email link, never
     * by a caller-supplied id: an id alone is a guessable integer, so accepting one would let an
     * anonymous caller overwrite any account's credentials. The token is proof that the caller
     * controls the mailbox, and {@link RegistrationService#setPasswordAndActivate} resolves the
     * user from it.
     *
     * <p>No Turnstile: the token is a 122-bit random value, so it is not guessable and the CAPTCHA
     * protected nothing. A caller-supplied {@code turnstileToken} is ignored. Three bounds protect the
     * caches and the database instead, checked outermost first and any one refused is a 429: an hourly
     * global ceiling, the client address, then the token itself.
     *
     * @param body    map containing {@code token} and {@code password}
     * @param request the request, for the caller's address
     * @return 200 with JWT tokens (same shape as /login), 400 if the token or password is rejected,
     *         or 429 when an attempt bound is reached
     */
    @PostMapping("/set-password")
    public ResponseEntity<Map<String, Object>> setPassword(@RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String token = body.get("token");
        String password = body.get("password");

        try {
            requireTokenAndPassword(token, password);
            enforceTokenBounds(SET_PASSWORD_BOUNDS, token, ClientAddress.of(request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (TooManyAttemptsException ex) {
            return tooManyAttempts();
        }

        String complexityError = authenticationService.validatePasswordComplexity(password);
        if (complexityError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", complexityError));
        }

        RegistrationService.Activation activation;
        try {
            activation = registrationService.setPasswordAndActivate(token, password);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }

        // Auto-login: issue tokens against the hash just written, so a credential change that committed
        // in between issues nothing.
        AuthTokens tokens;
        try {
            tokens = authenticationService.issueTokensFor(activation.userId(), activation.passwordHash());
        } catch (InvalidCredentialsException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", ex.getMessage()));
        }
        LOG.info("Registration complete: user='{}' role={}", LogSanitizer.sanitize(tokens.username()),
                tokens.role());
        return ResponseEntity.ok(buildAuthResponse(tokens));
    }

    /**
     * Updates the authenticated user's marketing email opt-in preference.
     *
     * @param body map containing {@code optIn} (boolean)
     * @return 200 on success, 401 if unauthenticated (the anonymous principal is not a user)
     */
    @PutMapping("/marketing-emails")
    public ResponseEntity<Map<String, Object>> updateMarketingEmails(@RequestBody Map<String, Object> body) {
        String username = authenticatedUsername();
        if (username == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Not authenticated"));
        }

        Object optInObj = body.get("optIn");
        if (optInObj == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "optIn is required"));
        }

        boolean optIn = Boolean.parseBoolean(optInObj.toString());

        AppUserEntity user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "User not found"));
        }

        userRepository.updateMarketingEmailOptIn(user.getId(), optIn);

        LOG.info("Marketing email opt-in updated: user='{}' optIn={}", LogSanitizer.sanitize(username), optIn);
        return ResponseEntity.ok(Map.of("marketingEmailOptIn", optIn));
    }

    /**
     * Logs the caller out: ends the presented refresh token and every outstanding access token.
     *
     * @param body map containing {@code refreshToken}
     * @return 200 on success
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(@RequestBody Map<String, String> body) {
        authenticationService.logout(body.get("refreshToken"), authenticatedUsername());
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }

    /**
     * Requires both fields of a set-password body to be present.
     *
     * @param token    the one-time token
     * @param password the new password
     * @throws IllegalArgumentException when either is missing or blank
     */
    private static void requireTokenAndPassword(String token, String password) {
        if (token == null || token.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("token and password are required");
        }
    }

    /**
     * Returns the signed-in user's name, or null when the caller is not authenticated.
     *
     * <p>{@code /api/auth/**} is permitted without a token and anonymous authentication is not
     * disabled, so an unauthenticated call still has a principal: the anonymous one, named
     * {@code anonymousUser}. It is never a user (login-registration plan §2 #14 rule 5).
     *
     * @return the username, or null
     */
    private static String authenticatedUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication.getName();
    }

    /**
     * Reads the credential version the caller's session was issued at from the filter's OWN details
     * type (login-registration plan §2 #14 rule 5): exact when the token carried a {@code cv} claim,
     * {@code legacyUnchanged} for a token with none (issued before the counter existed). Never a cast of
     * {@code WebAuthenticationDetails}, so no other kind of authentication can be mistaken for one.
     *
     * @return the expectation the writer holds the row to
     */
    static VersionExpectation sessionVersionExpectation() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getDetails() instanceof JwtAuthenticationDetails details
                && details.credentialsVersion() != null) {
            return VersionExpectation.exact(details.credentialsVersion());
        }
        return VersionExpectation.legacyUnchanged();
    }

    /**
     * Counts a request against the three bounds of a one-time-token endpoint, outermost first and
     * stopping at the first refusal: an hourly global ceiling, then the client address, then the token
     * (keyed on the first sixteen hex characters of its hash, never the token itself). A token-keyed
     * bucket alone could be rotated with a fresh random token on every request, which would throttle
     * nothing and churn the caches' entries out from under the real limits.
     *
     * @param prefix the endpoint's key prefix
     * @param token  the raw one-time token
     * @param client the caller's address
     * @throws TooManyAttemptsException when any bound refuses
     */
    private void enforceTokenBounds(String prefix, String token, String client) {
        String tokenKey = jwtService.hashToken(token).substring(0, TOKEN_KEY_LENGTH);
        if (!attemptLimiter.tryAcquire(prefix + ":global", TOKEN_GLOBAL_LIMIT, Window.HOUR)
                || !attemptLimiter.tryAcquire(prefix + ":client:" + client, TOKEN_CLIENT_LIMIT,
                        Window.QUARTER_HOUR)
                || !attemptLimiter.tryAcquire(prefix + ":token:" + tokenKey, TOKEN_LIMIT,
                        Window.QUARTER_HOUR)) {
            throw new TooManyAttemptsException("One-time-token attempt bound reached");
        }
    }

    /**
     * Maps a refusing gate outcome to its response.
     *
     * @param outcome what {@link TurnstileGate#admit} decided
     * @return the 400 or 429 to return, or null when the request may proceed
     */
    private static ResponseEntity<Map<String, Object>> refusalFor(Outcome outcome) {
        return switch (outcome) {
            case CAPTCHA_FAILED -> ResponseEntity.badRequest()
                    .body(Map.of("error", "CAPTCHA verification failed. Please try again."));
            case RATE_LIMITED -> tooManyAttempts();
            case OK_VERIFIED, OK_FALLBACK -> null;
        };
    }

    private static ResponseEntity<Map<String, Object>> tooManyAttempts() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", TOO_MANY_ATTEMPTS));
    }

    /**
     * Builds the full auth response body shared by {@code /login} and {@code /set-password}.
     *
     * <p>Uses a {@link LinkedHashMap} (not {@code Map.of}) because {@code termsVersion} may be
     * {@code null} for pre-terms accounts.
     *
     * @param tokens the issued tokens and user fields
     * @return the response body map
     */
    private Map<String, Object> buildAuthResponse(AuthTokens tokens) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accessToken", tokens.accessToken());
        response.put("refreshToken", tokens.refreshToken());
        response.put("username", tokens.username());
        response.put("role", tokens.role());
        response.put("expiresAt", tokens.accessTokenExpiresAt().toString());
        response.put("refreshExpiresAt", tokens.refreshTokenExpiresAt().toString());
        response.put("passwordChangeRequired", tokens.passwordChangeRequired());
        response.put("marketingEmailOptIn", tokens.marketingEmailOptIn());
        response.put("termsVersion", tokens.termsVersion());
        return response;
    }
}
