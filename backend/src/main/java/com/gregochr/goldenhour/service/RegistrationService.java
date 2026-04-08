package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.RegistrationProperties;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.EmailVerificationTokenEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.exception.RegistrationClosedException;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.EmailVerificationTokenRepository;
import com.gregochr.goldenhour.service.notification.UserEmailService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

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

    /** Maximum tokens a user can request in the rate-limit window. */
    private static final int RATE_LIMIT_MAX_TOKENS = 3;

    /** Rate-limit window in minutes. */
    private static final int RATE_LIMIT_WINDOW_MINUTES = 5;

    private final UserService userService;
    private final AppUserRepository userRepository;
    private final EmailVerificationTokenRepository tokenRepository;
    private final JwtService jwtService;
    private final UserEmailService userEmailService;
    private final RegistrationProperties registrationProperties;

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
                    nonAdminCount, registrationProperties.getMaxUsers(), username);
            throw new RegistrationClosedException();
        }

        UserRole role = UserRole.valueOf(registrationProperties.getDefaultRole());
        AppUserEntity user = userService.createPendingUser(username, email, marketingEmailOptIn, role);

        String rawToken = jwtService.generateRefreshToken();
        saveVerificationToken(rawToken, user.getId());

        userEmailService.sendVerificationEmail(email, username, rawToken);
        LOG.info("Registration initiated: username='{}', email='{}'", username, email);
        return user;
    }

    /**
     * Resends a verification email for a pending user.
     *
     * <p>Returns silently if the email is not found (prevents email enumeration).
     * Throws if the rate limit is exceeded.
     *
     * @param email the email address to resend verification to
     * @throws IllegalStateException if the rate limit is exceeded
     */
    @Transactional
    public void resendVerification(String email) {
        AppUserEntity user = findPendingUserByEmail(email);
        if (user == null) {
            LOG.debug("Resend verification requested for unknown email — ignoring silently");
            return;
        }

        long recentCount = tokenRepository.countByUserIdAndCreatedAtAfter(
                user.getId(), LocalDateTime.now().minusMinutes(RATE_LIMIT_WINDOW_MINUTES));
        if (recentCount >= RATE_LIMIT_MAX_TOKENS) {
            throw new IllegalStateException("Too many verification requests. Please try again later.");
        }

        // Invalidate old unverified tokens
        List<EmailVerificationTokenEntity> oldTokens = tokenRepository.findByUserIdAndVerifiedFalse(user.getId());
        tokenRepository.deleteAll(oldTokens);

        String rawToken = jwtService.generateRefreshToken();
        saveVerificationToken(rawToken, user.getId());

        userEmailService.sendVerificationEmail(email, user.getUsername(), rawToken);
        LOG.info("Verification resent: username='{}', email='{}'", user.getUsername(), email);
    }

    /**
     * Verifies an email verification token.
     *
     * @param rawToken the raw token string from the verification link
     * @return the user ID associated with the verified token
     * @throws IllegalArgumentException if the token is invalid, expired, or already used
     */
    @Transactional
    public Long verifyEmail(String rawToken) {
        String hash = jwtService.hashToken(rawToken);
        EmailVerificationTokenEntity token = tokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new IllegalArgumentException("Invalid verification token"));

        if (token.isVerified()) {
            throw new IllegalArgumentException("This verification link has already been used");
        }
        if (token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("This verification link has expired");
        }

        token.setVerified(true);
        tokenRepository.save(token);

        LOG.info("Email verified: userId={}", token.getUserId());
        return token.getUserId();
    }

    /**
     * Sets the password for a verified user and activates their account.
     *
     * @param userId      the user's primary key
     * @param rawPassword the plain-text password
     */
    public void setPasswordAndActivate(Long userId, String rawPassword) {
        userService.activateUser(userId, rawPassword);
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
     * <p>Invalidates any existing unverified tokens for the user before generating a new one.
     *
     * @param user the user to resend verification for
     */
    @Transactional
    public void adminResendVerification(AppUserEntity user) {
        List<EmailVerificationTokenEntity> oldTokens = tokenRepository.findByUserIdAndVerifiedFalse(user.getId());
        tokenRepository.deleteAll(oldTokens);

        String rawToken = jwtService.generateRefreshToken();
        saveVerificationToken(rawToken, user.getId());

        userEmailService.sendVerificationEmail(user.getEmail(), user.getUsername(), rawToken);
        LOG.info("Admin resent verification: username='{}', email='{}'", user.getUsername(), user.getEmail());
    }

    private AppUserEntity findPendingUserByEmail(String email) {
        return userService.listAllUsers().stream()
                .filter(u -> email.equalsIgnoreCase(u.getEmail()))
                .filter(u -> !u.isEnabled())
                .filter(u -> "".equals(u.getPassword()))
                .findFirst()
                .orElse(null);
    }
}
