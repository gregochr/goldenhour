package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AppUserRepository.LockedAccount;
import com.gregochr.goldenhour.repository.AppUserRepository.PendingStage;
import com.gregochr.goldenhour.repository.EmailVerificationTokenRepository;
import com.gregochr.goldenhour.service.VerificationTokenIssuer.IssuedVerification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * The two short transactions of the pending-registration sweep: claim a reminder stage (with a fresh link)
 * and revert it after a failed send. A bean of its own so {@link PendingRegistrationSweepJob} reaches both
 * through the Spring proxy; a {@code this.x()} call would run the row lock outside a transaction and drop it
 * at once. The row lock is never held across the SMTP send.
 */
@Component
public class PendingRegistrationWriter {

    private final AppUserRepository userRepository;
    private final EmailVerificationTokenRepository tokenRepository;
    private final VerificationTokenIssuer tokenIssuer;
    private final JwtService jwtService;

    /**
     * Constructs the writer.
     *
     * @param userRepository  accounts
     * @param tokenRepository verification links
     * @param tokenIssuer     mints the fresh link
     * @param jwtService      hashes the minted token, for a revert
     */
    public PendingRegistrationWriter(AppUserRepository userRepository,
            EmailVerificationTokenRepository tokenRepository, VerificationTokenIssuer tokenIssuer,
            JwtService jwtService) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.tokenIssuer = tokenIssuer;
        this.jwtService = jwtService;
    }

    /** What a claim attempt came to. */
    public enum Outcome {
        /** The stage was advanced and a fresh link minted: send the email. */
        CLAIMED,
        /** The account is no longer a pending sign-up, or the stage moved under us: nothing written. */
        SKIPPED,
        /** The account holds an unexpired unverified link (a reader's or admin's resend): nothing written. */
        DEFERRED
    }

    /**
     * The result of {@link #claim}.
     *
     * @param outcome         what happened
     * @param issued          the address, username and raw token to mail; null unless CLAIMED
     * @param tokenHash       the hash of the minted token, for a revert; null unless CLAIMED
     * @param previousFinalAt the {@code final_reminder_sent_at} before the advance, for a revert
     */
    public record Claim(Outcome outcome, IssuedVerification issued, String tokenHash,
            LocalDateTime previousFinalAt) {

        private static Claim of(Outcome outcome) {
            return new Claim(outcome, null, null, null);
        }
    }

    /**
     * Claims a reminder stage in one transaction: locks the account, stops if it is not a pending sign-up,
     * defers if it holds an unexpired unverified link, advances the stage (stopping on zero rows) and mints a
     * fresh link, which joins this transaction.
     *
     * @param id       the account's primary key
     * @param expected the stage the account must be at
     * @param next     the stage to move to
     * @param now      the sweep's instant (JVM default zone)
     * @return the outcome, and when CLAIMED what to mail and how to revert
     * @throws IllegalStateException if the link could not be minted after the advance (rolls the advance back)
     */
    @Transactional
    public Claim claim(Long id, int expected, int next, LocalDateTime now) {
        LockedAccount account = userRepository.lockAccount(id).orElse(null);
        if (account == null || !account.pending() || account.role() == UserRole.ADMIN
                || account.email() == null || account.email().isBlank()) {
            return Claim.of(Outcome.SKIPPED);
        }
        if (tokenRepository.existsByUserIdAndVerifiedFalseAndExpiresAtAfter(id, now)) {
            return Claim.of(Outcome.DEFERRED);
        }
        Optional<PendingStage> before = userRepository.findPendingStage(id);
        LocalDateTime previousFinalAt = before.map(PendingStage::getFinalReminderSentAt).orElse(null);
        if (userRepository.advanceReminderStage(id, expected, next, now) != 1) {
            return Claim.of(Outcome.SKIPPED);
        }
        IssuedVerification issued = tokenIssuer.issue(id)
                .orElseThrow(() -> new IllegalStateException("No link could be minted for pending user " + id));
        return new Claim(Outcome.CLAIMED, issued, jwtService.hashToken(issued.rawToken()), previousFinalAt);
    }

    /**
     * Undoes a claim after a failed send, in one transaction: restores both columns and deletes the link just
     * minted (otherwise that live link would make every later run defer for 24 hours).
     *
     * @param id              the account's primary key
     * @param expected        the stage to restore
     * @param next            the stage the claim moved to
     * @param previousFinalAt the {@code final_reminder_sent_at} to restore
     * @param tokenHash       the hash of the link the claim minted
     */
    @Transactional
    public void revert(Long id, int expected, int next, LocalDateTime previousFinalAt, String tokenHash) {
        userRepository.revertReminderStage(id, expected, next, previousFinalAt);
        tokenRepository.deleteByTokenHashInBulk(tokenHash);
    }
}
