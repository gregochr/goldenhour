package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.WaitlistEmailEntity;
import com.gregochr.goldenhour.model.WaitlistEntryDto;
import com.gregochr.goldenhour.repository.WaitlistEmailRepository;
import com.gregochr.goldenhour.service.AuthAttemptLimiter.Window;
import com.gregochr.goldenhour.service.notification.UserEmailService;
import com.gregochr.goldenhour.util.EmailIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The waitlist: sign-up from the public form, and the admin's invitations (audit-followups-plan W).
 *
 * <p>An invitation is a random token mailed to the address; only its SHA-256 hash is stored. It is redeemed
 * by {@code POST /api/auth/register} through one conditional update, so it works once, before its expiry, and
 * only for the address it was sent to. A valid invitation lets that registration past the early-access cap
 * and nothing else. The waitlist row is removed when the account is activated, not when it registers.
 *
 * <p>Logs carry waitlist row ids only, never an address.
 */
@Service
public class WaitlistService {

    private static final Logger LOG = LoggerFactory.getLogger(WaitlistService.class);

    /** How long an invitation works. */
    static final int INVITE_DAYS = 7;

    /** Limiter key of the hourly ceiling across every caller of the public sign-up. */
    static final String GLOBAL_KEY = "waitlist:global";

    /** Limiter key prefix for one client address. */
    static final String CLIENT_KEY_PREFIX = "waitlist:client:";

    /** Sign-ups admitted across every caller, per hour. */
    static final int GLOBAL_LIMIT = 60;

    /** Sign-ups admitted per client address, per quarter hour. */
    static final int CLIENT_LIMIT = 5;

    /** What the public sign-up answered. */
    public enum JoinOutcome {
        /** The address is on the list (newly, or already). */
        JOINED,
        /** The CAPTCHA token failed. */
        CAPTCHA_FAILED,
        /** An attempt bound was reached. */
        RATE_LIMITED
    }

    /** What an admin invitation did. */
    public enum InviteOutcome {
        /** A fresh token was stored and the mail handed to the sender. */
        SENT,
        /** No such waitlist row. */
        NOT_FOUND,
        /** The address is not pure ASCII, which registration rejects. */
        NON_ASCII,
        /** No mail sender is configured, so nothing was stored. */
        MAIL_UNAVAILABLE
    }

    private final WaitlistEmailRepository repository;
    private final JwtService jwtService;
    private final UserEmailService userEmailService;
    private final TurnstileService turnstileService;
    private final AuthAttemptLimiter attemptLimiter;
    private final Clock clock;

    /**
     * Creates the service on the JVM's default-zone clock (the waitlist columns are zone-less timestamps).
     *
     * @param repository       the waitlist rows
     * @param jwtService       mints and hashes the invite token
     * @param userEmailService sends the invitation
     * @param turnstileService verifies the sign-up CAPTCHA
     * @param attemptLimiter   bounds the public sign-up
     */
    @Autowired
    public WaitlistService(WaitlistEmailRepository repository, JwtService jwtService,
            UserEmailService userEmailService, TurnstileService turnstileService,
            AuthAttemptLimiter attemptLimiter) {
        this(repository, jwtService, userEmailService, turnstileService, attemptLimiter, Clock.systemDefaultZone());
    }

    /**
     * Creates the service on a supplied clock (tests).
     *
     * @param repository       the waitlist rows
     * @param jwtService       mints and hashes the invite token
     * @param userEmailService sends the invitation
     * @param turnstileService verifies the sign-up CAPTCHA
     * @param attemptLimiter   bounds the public sign-up
     * @param clock            the time source
     */
    WaitlistService(WaitlistEmailRepository repository, JwtService jwtService, UserEmailService userEmailService,
            TurnstileService turnstileService, AuthAttemptLimiter attemptLimiter, Clock clock) {
        this.repository = repository;
        this.jwtService = jwtService;
        this.userEmailService = userEmailService;
        this.turnstileService = turnstileService;
        this.attemptLimiter = attemptLimiter;
        this.clock = clock;
    }

    /**
     * Adds an address to the waitlist, bounded and CAPTCHA-checked. Idempotent: an address already on the
     * list, including one that lost a race to a concurrent identical sign-up, answers {@link JoinOutcome#JOINED}.
     *
     * @param email          the address, already trimmed and lower-cased
     * @param turnstileToken the CAPTCHA response token
     * @param clientAddress  the caller's address, for the per-address bound
     * @return the outcome
     */
    public JoinOutcome join(String email, String turnstileToken, String clientAddress) {
        boolean admitted = attemptLimiter.tryAcquire(GLOBAL_KEY, GLOBAL_LIMIT, Window.HOUR)
                && attemptLimiter.tryAcquire(CLIENT_KEY_PREFIX + clientAddress, CLIENT_LIMIT,
                        Window.QUARTER_HOUR);
        if (!admitted) {
            return JoinOutcome.RATE_LIMITED;
        }
        if (!turnstileService.verify(turnstileToken)) {
            return JoinOutcome.CAPTCHA_FAILED;
        }
        if (repository.existsByEmail(email)) {
            return JoinOutcome.JOINED;
        }
        try {
            WaitlistEmailEntity saved = repository.save(WaitlistEmailEntity.builder()
                    .email(email)
                    .submittedAt(LocalDateTime.now(clock))
                    .build());
            LOG.info("Waitlist signup: id={}", saved.getId());
        } catch (DataIntegrityViolationException ex) {
            if (!repository.existsByEmail(email)) {
                throw ex;
            }
            LOG.debug("Waitlist signup lost a race to an identical one");
        }
        return JoinOutcome.JOINED;
    }

    /**
     * Returns every waitlist entry, oldest first. The invitation's token hash is never part of the result.
     *
     * @return the entries
     */
    public List<WaitlistEntryDto> list() {
        return repository.findAllByOrderBySubmittedAtAsc().stream()
                .map(e -> new WaitlistEntryDto(e.getId(), e.getEmail(), e.getSubmittedAt(), e.getInvitedAt(),
                        e.getInviteExpiresAt()))
                .toList();
    }

    /**
     * Invites a waitlisted address: stores a fresh token's hash with a seven-day expiry (replacing any earlier
     * invitation and clearing its used mark) and mails the link.
     *
     * @param id the waitlist row
     * @return the outcome
     */
    public InviteOutcome invite(Long id) {
        Optional<WaitlistEmailEntity> found = repository.findById(id);
        if (found.isEmpty()) {
            return InviteOutcome.NOT_FOUND;
        }
        String email = found.get().getEmail();
        if (!EmailIdentifier.isAscii(email)) {
            return InviteOutcome.NON_ASCII;
        }
        if (!userEmailService.isConfigured()) {
            return InviteOutcome.MAIL_UNAVAILABLE;
        }
        String rawToken = jwtService.generateRefreshToken();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiresAt = now.plusDays(INVITE_DAYS);
        if (repository.markInvited(id, jwtService.hashToken(rawToken), now, expiresAt) != 1) {
            return InviteOutcome.NOT_FOUND;
        }
        userEmailService.sendWaitlistInviteEmail(id, email, rawToken, expiresAt);
        LOG.info("Waitlist invitation issued: id={}", id);
        return InviteOutcome.SENT;
    }

    /**
     * Removes a waitlist row.
     *
     * @param id the waitlist row
     * @return true when a row was removed, false when there was none
     */
    @Transactional
    public boolean remove(Long id) {
        if (!repository.existsById(id)) {
            return false;
        }
        repository.deleteById(id);
        LOG.info("Waitlist entry removed: id={}", id);
        return true;
    }

    /**
     * Redeems an invitation for a registration, once. True only when the token is live, unused and was issued
     * to this address (compared case-insensitively); the call marks it used as part of the same statement.
     * Run inside the registration's transaction, a registration that then fails gives the invitation back.
     *
     * @param rawToken the raw token from the link, or null
     * @param email    the address on the registration request
     * @return true when this call redeemed the invitation
     */
    @Transactional
    public boolean redeemInvite(String rawToken, String email) {
        if (rawToken == null || rawToken.isBlank() || email == null || email.isBlank()) {
            return false;
        }
        return repository.redeemInvite(jwtService.hashToken(rawToken.trim()), LocalDateTime.now(clock),
                email.trim()) == 1;
    }

    /**
     * Removes the waitlist row for an address once its account is active. Quiet when there is none.
     *
     * @param email the activated account's stored address
     */
    public void removeByEmail(String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        int removed = repository.deleteByEmailIgnoreCase(email.trim());
        if (removed > 0) {
            LOG.info("Waitlist entry cleared at activation ({} row)", removed);
        }
    }
}
