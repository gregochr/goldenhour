package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.RegistrationProperties;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.EmailVerificationTokenEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.exception.RegistrationClosedException;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.EmailVerificationTokenRepository;
import com.gregochr.goldenhour.service.AuthAttemptLimiter.Window;
import com.gregochr.goldenhour.service.CredentialChangeWriter.Change;
import com.gregochr.goldenhour.service.VerificationTokenIssuer.IssuedVerification;
import com.gregochr.goldenhour.service.notification.UserEmailService;
import com.gregochr.goldenhour.util.EmailIdentifier;
import com.gregochr.goldenhour.util.EmailIdentifier.Kind;
import com.gregochr.goldenhour.util.LogSanitizer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Orchestrates the self-registration flow: account creation, email verification,
 * and password activation.
 */
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private static final Logger LOG = LoggerFactory.getLogger(RegistrationService.class);

    /** Verification tokens expire after 24 hours. */
    private static final int TOKEN_EXPIRY_HOURS = 24;

    /** How long the rotated setup token lives, at most. */
    static final int SETUP_TOKEN_MINUTES = 30;

    /** The sentence for an unknown, rotated or already-used link. */
    static final String LINK_USED = "This link has already been used \u2014 sign in or request a new one";

    /** Maximum tokens a user can request in the rate-limit window. */
    private static final int RATE_LIMIT_MAX_TOKENS = 3;

    /** Rate-limit window in minutes. */
    private static final int RATE_LIMIT_WINDOW_MINUTES = 5;

    /** Limiter key prefix for the per-account resend budget. */
    static final String RESEND_USER_KEY_PREFIX = "resend:user:";

    /** Resends admitted per pending account in a quarter hour. */
    static final int RESEND_USER_LIMIT = 3;

    /** The sentence a refused resend carries (the controller answers it as a 429). */
    static final String RESEND_REFUSED = "Too many verification requests. Please try again later.";

    private final UserService userService;
    private final AppUserRepository userRepository;
    private final EmailVerificationTokenRepository tokenRepository;
    private final JwtService jwtService;
    private final UserEmailService userEmailService;
    private final RegistrationProperties registrationProperties;
    private final AuthAttemptLimiter attemptLimiter;
    private final VerificationTokenIssuer verificationTokenIssuer;
    private final CredentialChangeWriter credentialChangeWriter;
    private final PasswordEncoder passwordEncoder;

    /**
     * The account a verification link activated, and the hash it now holds — what the caller needs to
     * sign the reader in against exactly the password just written.
     *
     * @param userId       the activated account's primary key
     * @param passwordHash the BCrypt hash just written
     */
    public record Activation(Long userId, String passwordHash) {
    }

    /**
     * The result of a successful verification.
     *
     * @param userId     the verified account's primary key
     * @param setupToken the raw rotated token that {@code set-password} accepts (never mailed)
     */
    public record VerifiedEmail(Long userId, String setupToken) {
    }

    /**
     * Registers a new pending user and sends a verification email.
     *
     * @param username            the desired login name
     * @param email               the user's email address
     * @param marketingEmailOptIn whether the user opted in to marketing emails
     * @return the created pending user
     * @throws IllegalArgumentException      if the username or email is already taken
     * @throws RegistrationClosedException   if the early-access cap has been reached
     */
    @Transactional
    public AppUserEntity register(String username, String email, boolean marketingEmailOptIn) {
        long nonAdminCount = userRepository.countByRoleNot(UserRole.ADMIN);
        if (nonAdminCount >= registrationProperties.getMaxUsers()) {
            LOG.info("Registration cap reached ({}/{}): rejecting username='{}'",
                    nonAdminCount, registrationProperties.getMaxUsers(), LogSanitizer.sanitize(username));
            throw new RegistrationClosedException();
        }

        UserRole role = UserRole.valueOf(registrationProperties.getDefaultRole());
        AppUserEntity user = userService.createPendingUser(username, email, marketingEmailOptIn, role);

        String rawToken = jwtService.generateRefreshToken();
        saveVerificationToken(rawToken, user.getId());

        userEmailService.sendVerificationEmail(email, username, rawToken);
        LOG.info("Registration initiated: username='{}', email='{}'",
                LogSanitizer.sanitize(username), LogSanitizer.sanitize(email));
        return user;
    }

    /**
     * Resends a verification email for a pending user.
     *
     * <p>Returns silently if the email is not found (prevents email enumeration). The address is
     * matched by the one email rule ({@link EmailIdentifier}: exact first, then ASCII
     * case-insensitive, pending accounts only, exactly one) and the link goes to the STORED address,
     * never the request's text. Throws if the rate limit is exceeded.
     *
     * <p>The limit is the in-memory {@link AuthAttemptLimiter} — three per pending account per quarter
     * hour. The older database count below stays as a second check but can never trip on its own: the
     * issuer deletes every unverified token before inserting, so that count is at most one (plan §1 #12).
     *
     * <p>Not transactional: the token is minted by {@link VerificationTokenIssuer#issue} under the
     * account's row lock, which also re-reads the stored address and the pending state, so a resend that
     * resolved the account before an admin's email change committed mails the NEW address and stores no
     * token for the old one (login-registration plan §2 #14 rule 4).
     *
     * @param email the email address to resend verification to
     * @throws IllegalStateException if the rate limit is exceeded
     */
    public void resendVerification(String email) {
        AppUserEntity user = findPendingUserByEmail(email);
        if (user == null) {
            LOG.debug("Resend verification requested for unknown email — ignoring silently");
            return;
        }

        if (!attemptLimiter.tryAcquire(RESEND_USER_KEY_PREFIX + user.getId(), RESEND_USER_LIMIT,
                Window.QUARTER_HOUR)) {
            LOG.info("Verification resend refused: budget spent for user id {}", user.getId());
            throw new IllegalStateException(RESEND_REFUSED);
        }

        long recentCount = tokenRepository.countByUserIdAndCreatedAtAfter(
                user.getId(), LocalDateTime.now().minusMinutes(RATE_LIMIT_WINDOW_MINUTES));
        if (recentCount >= RATE_LIMIT_MAX_TOKENS) {
            throw new IllegalStateException(RESEND_REFUSED);
        }

        Optional<IssuedVerification> issued = verificationTokenIssuer.issue(user.getId());
        if (issued.isEmpty()) {
            LOG.info("Verification resend dropped: user id {} is no longer pending", user.getId());
            return;
        }

        userEmailService.sendVerificationEmail(issued.get().email(), issued.get().username(),
                issued.get().rawToken());
        LOG.info("Verification resent: username='{}', email='{}'",
                LogSanitizer.sanitize(issued.get().username()), LogSanitizer.sanitize(issued.get().email()));
    }

    /**
     * Verifies an email verification token and rotates it.
     *
     * <p>The emailed link is single-use: one conditional update swaps its hash for a fresh one (the
     * {@code setupToken}, returned here and never mailed) and marks it verified, with an expiry of at
     * most {@value #SETUP_TOKEN_MINUTES} minutes from now. Of two concurrent verifications exactly one
     * gets the row; the other, and any later reuse of the mailed link, is told the link has been used.
     * {@code set-password} accepts only the rotated value.
     *
     * @param rawToken the raw token string from the verification link
     * @return the user ID and the setup token that authorises the password step
     * @throws IllegalArgumentException if the token is unknown, expired or already used
     */
    @Transactional
    public VerifiedEmail verifyEmail(String rawToken) {
        String hash = jwtService.hashToken(rawToken);
        EmailVerificationTokenEntity token = tokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new IllegalArgumentException(LINK_USED));

        if (token.isVerified()) {
            throw new IllegalArgumentException(LINK_USED);
        }
        LocalDateTime now = LocalDateTime.now();
        if (token.getExpiresAt().isBefore(now)) {
            throw new IllegalArgumentException("This verification link has expired");
        }

        String setupToken = jwtService.generateRefreshToken();
        LocalDateTime cap = now.plusMinutes(SETUP_TOKEN_MINUTES);
        LocalDateTime expiresAt = token.getExpiresAt().isBefore(cap) ? token.getExpiresAt() : cap;
        if (tokenRepository.rotateOnVerify(token.getId(), hash, jwtService.hashToken(setupToken),
                expiresAt) != 1) {
            throw new IllegalArgumentException(LINK_USED);
        }

        LOG.info("Email verified: userId={}", token.getUserId());
        return new VerifiedEmail(token.getUserId(), setupToken);
    }

    /**
     * Sets the password for a verified user and activates their account.
     *
     * <p>The account is resolved from the one-time verification token rather than from a
     * caller-supplied id — the token is the only evidence that the caller controls the mailbox
     * the account was registered with. The token must already have been through
     * {@link #verifyEmail(String)} (the emailed link was clicked) and is deleted here, so it
     * cannot be replayed to reset the password a second time.
     *
     * <p>Deliberately NOT transactional: the token is read and checked (hash, verified, not expired) and
     * the password encoded with no connection held, and then ONE transaction on the SEPARATE
     * {@link CredentialChangeWriter#consumeVerificationAndApply} locks the user row, re-checks under the
     * lock that the account is still pending, deletes every verification token of the user, and
     * activates it — so token consumption and the credential change stay atomic, and a reader who
     * verified two links and activated with one cannot overwrite the live password with the other.
     *
     * @param rawToken    the raw verification token from the email link
     * @param rawPassword the plain-text password
     * @return the activated account's primary key and the hash it now holds
     * @throws IllegalArgumentException if the token is unknown, unverified or expired, or has already
     *                                  been used
     */
    public Activation setPasswordAndActivate(String rawToken, String rawPassword) {
        String hash = jwtService.hashToken(rawToken);
        EmailVerificationTokenEntity token = tokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired verification token"));

        if (!token.isVerified() || token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Invalid or expired verification token");
        }

        String encoded = passwordEncoder.encode(rawPassword);
        CredentialChangeOutcome outcome = credentialChangeWriter.consumeVerificationAndApply(token.getId(),
                token.getUserId(), encoded, Change.activation());
        LOG.info("User activated: id={}", outcome.userId());
        return new Activation(outcome.userId(), encoded);
    }

    private void saveVerificationToken(String rawToken, Long userId) {
        String hash = jwtService.hashToken(rawToken);
        EmailVerificationTokenEntity entity = EmailVerificationTokenEntity.builder()
                .tokenHash(hash)
                .userId(userId)
                .expiresAt(LocalDateTime.now().plusHours(TOKEN_EXPIRY_HOURS))
                .verified(false)
                .createdAt(LocalDateTime.now())
                .build();
        tokenRepository.save(entity);
    }

    /**
     * Resends a verification email for a user. Admin action — no rate limiting.
     *
     * <p>Invalidates any existing unverified tokens for the user before generating a new one, through the
     * same {@link VerificationTokenIssuer} the reader's resend uses: the account's row is locked and its
     * STORED address mailed, not the address the admin's list showed.
     *
     * @param user the user to resend verification for (only its id is used)
     * @return true if a link was sent; false if the account is no longer pending, so nothing was stored
     */
    public boolean adminResendVerification(AppUserEntity user) {
        Optional<IssuedVerification> issued = verificationTokenIssuer.issue(user.getId());
        if (issued.isEmpty()) {
            LOG.info("Admin verification resend dropped: user id {} is not awaiting verification", user.getId());
            return false;
        }
        userEmailService.sendVerificationEmail(issued.get().email(), issued.get().username(),
                issued.get().rawToken());
        LOG.info("Admin resent verification: username='{}', email='{}'",
                LogSanitizer.sanitize(issued.get().username()), LogSanitizer.sanitize(issued.get().email()));
        return true;
    }

    private AppUserEntity findPendingUserByEmail(String email) {
        return EmailIdentifier.resolve(email, userRepository, Kind.PENDING).orElse(null);
    }
}
