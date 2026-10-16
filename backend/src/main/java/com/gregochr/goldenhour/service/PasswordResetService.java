package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.PasswordResetTokenEntity;
import com.gregochr.goldenhour.model.AuthTokens;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.PasswordResetTokenRepository;
import com.gregochr.goldenhour.service.AuthAttemptLimiter.Window;
import com.gregochr.goldenhour.service.PasswordResetIssuer.IssuedLink;
import com.gregochr.goldenhour.service.notification.UserEmailService;
import com.gregochr.goldenhour.util.EmailIdentifier;
import com.gregochr.goldenhour.util.EmailIdentifier.Kind;
import com.gregochr.goldenhour.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * The user-facing "forgot my password" flow: request a link by email, then redeem it for a new password
 * (login-registration plan §2 #1).
 *
 * <p>Deliberately not transactional. The two transactional steps are separate PROXIED beans —
 * {@link PasswordResetIssuer} and {@link PasswordResetRedeemer} — because a transactional method on this
 * class would be reached by self-invocation and run with no transaction at all; and because BCrypt must
 * run with no database connection held.
 *
 * <h2>Requesting a link</h2>
 * The address is resolved by the one email rule ({@link EmailIdentifier}): exact first, then ASCII
 * case-insensitive, active accounts only, exactly one. An unknown, disabled, pending or ambiguous address
 * does nothing, and so does a known one whose per-account budget (three per fifteen minutes) is spent —
 * silently, never a 429, which would say the account exists. The mail goes to the STORED address the issuer
 * read under the row lock, never to the request's text.
 *
 * <h2>Redeeming a link</h2>
 * The link must exist, be unused and unexpired; the new password is hashed before anything transactional;
 * {@link PasswordResetRedeemer} then consumes the link, sets the password and revokes every active refresh
 * token in one transaction, and the reader is signed in on the device that reset (as set-password does)
 * from the row the redeemer returned.
 *
 * <p>TODO(R3c): the redeemed account's other devices are signed out at their next refresh today, at their
 * next request once the credential-version claim lands.
 */
@Service
public class PasswordResetService {

    private static final Logger LOG = LoggerFactory.getLogger(PasswordResetService.class);

    /** Limiter key prefix for the per-account reset-request budget. */
    static final String REQUEST_USER_KEY_PREFIX = "forgot:user:";

    /** Reset links requested per account in a quarter hour before further requests are silently dropped. */
    static final int REQUEST_USER_LIMIT = 3;

    /** The sentence for an unknown link. */
    static final String INVALID_LINK = "This reset link is invalid";

    /** The sentence for a redeemed link. */
    static final String USED_LINK = "This reset link has already been used";

    /** The sentence for an expired link. */
    static final String EXPIRED_LINK = "This reset link has expired";

    private final AppUserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordResetIssuer issuer;
    private final PasswordResetRedeemer redeemer;
    private final AuthenticationService authenticationService;
    private final UserEmailService userEmailService;
    private final AuthAttemptLimiter attemptLimiter;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    /**
     * Constructs the service.
     *
     * @param userRepository        accounts, for the address lookup
     * @param tokenRepository       reset links, for the pre-hash checks
     * @param issuer                mints a link under the account's row lock
     * @param redeemer              redeems a link in one transaction
     * @param authenticationService issues the signed-in session after a reset
     * @param userEmailService      sends the link
     * @param attemptLimiter        the per-account request budget
     * @param jwtService            token generation and hashing
     * @param passwordEncoder       password hashing
     * @param clock                 the application clock
     */
    public PasswordResetService(AppUserRepository userRepository, PasswordResetTokenRepository tokenRepository,
            PasswordResetIssuer issuer, PasswordResetRedeemer redeemer,
            AuthenticationService authenticationService, UserEmailService userEmailService,
            AuthAttemptLimiter attemptLimiter, JwtService jwtService, PasswordEncoder passwordEncoder,
            Clock clock) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.issuer = issuer;
        this.redeemer = redeemer;
        this.authenticationService = authenticationService;
        this.userEmailService = userEmailService;
        this.attemptLimiter = attemptLimiter;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /**
     * Mails a reset link to the account the address identifies, if there is exactly one active account
     * and its request budget allows it. Returns normally in every case — the caller answers the same
     * sentence whatever happened.
     *
     * @param email the address as typed
     */
    public void requestReset(String email) {
        Optional<AppUserEntity> resolved = EmailIdentifier.resolve(email, userRepository, Kind.ACTIVE);
        if (resolved.isEmpty()) {
            LOG.debug("Password reset requested for '{}': no single active account — ignoring silently",
                    LogSanitizer.sanitize(email));
            return;
        }
        Long userId = resolved.get().getId();
        if (!attemptLimiter.tryAcquire(REQUEST_USER_KEY_PREFIX + userId, REQUEST_USER_LIMIT,
                Window.QUARTER_HOUR)) {
            LOG.info("Password reset request dropped: request budget spent for user id {}", userId);
            return;
        }

        String rawToken = jwtService.generateRefreshToken();
        Optional<IssuedLink> issued = issuer.issue(userId, jwtService.hashToken(rawToken));
        if (issued.isEmpty()) {
            LOG.info("Password reset request dropped: user id {} is no longer active", userId);
            return;
        }
        userEmailService.sendPasswordResetLinkEmail(issued.get().email(), issued.get().username(), rawToken);
        LOG.info("Password reset link issued for user id {}", userId);
    }

    /**
     * Redeems a reset link: sets the new password, ends every other session and signs the reader in.
     *
     * @param rawToken    the raw token from the emailed link
     * @param rawPassword the new plain-text password, already checked against the complexity policy
     * @return the issued tokens, for the device that reset the password
     * @throws IllegalArgumentException with the sentence to show when the link is unknown, used,
     *                                  expired, or names an account that is disabled or pending
     */
    public AuthTokens resetPassword(String rawToken, String rawPassword) {
        PasswordResetTokenEntity token = tokenRepository.findByTokenHash(jwtService.hashToken(rawToken))
                .orElseThrow(() -> new IllegalArgumentException(INVALID_LINK));
        Instant now = clock.instant();
        if (token.getUsedAt() != null) {
            throw new IllegalArgumentException(USED_LINK);
        }
        if (!token.getExpiresAt().isAfter(now)) {
            throw new IllegalArgumentException(EXPIRED_LINK);
        }

        // BCrypt before any transaction opens: a pooled connection must not be pinned across the hash.
        // The expiry above is only a cheap early refusal; the redeemer re-judges it at its own instant,
        // taken inside its transaction, because the hash takes long enough for a link to expire.
        String encoded = passwordEncoder.encode(rawPassword);
        AppUserEntity redeemed = redeemer.redeem(token.getId(), token.getUserId(), encoded);
        LOG.info("Password reset complete: user id {}", redeemed.getId());
        return authenticationService.issueTokensFor(redeemed);
    }
}
