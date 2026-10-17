package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.repository.AppUserEmailClaimRepository;
import com.gregochr.goldenhour.util.EmailIdentifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Reserves a normalised email address for the account write that is about to use it
 * (login-registration plan §5 #6).
 *
 * <p>{@code app_user.email} is unique but case-sensitive, so {@code Foo@x} and {@code foo@x} can both be
 * registered, and an application-level "is it taken" check alone races: two concurrent registrations can
 * both see no duplicate and both commit under READ COMMITTED. A reservation row per lower-cased address
 * (primary key) makes the database refuse the second claimant. Every write of an address — create,
 * pending create, email change — claims its key here, in the SAME transaction as the account write.
 *
 * <p><strong>{@link #claim} never recovers from a constraint error in place.</strong> Postgres aborts the
 * transaction on one and Spring marks it rollback-only, so a catch-and-continue would be a lie. It first
 * looks under {@code FOR UPDATE}:
 * <ul>
 *   <li>a reservation exists → under that lock, ask whether another account occupies the key (two
 *       queries, never a bound null — {@code existsOtherOccupant} for an edit, {@code existsAnyOccupant}
 *       for a new account). Nobody does: the reservation is an orphan, or the account's own, and is
 *       reused silently — which is what lets {@code createPendingUser}'s supported retry succeed against
 *       the surviving reservation of the pending row it has just replaced. Somebody does: "Email already
 *       registered".</li>
 *   <li>no reservation → an immediate native INSERT. The one way that can still violate the key is a
 *       genuinely concurrent first claim of a brand-new address; the loser's
 *       {@code DataIntegrityViolationException} propagates out of the transactional method and the
 *       controller translates it to the same 409 OUTSIDE the rolled-back transaction.</li>
 * </ul>
 */
@Component
public class EmailClaims {

    /** The sentence every refusal carries, and the controllers' 409 body. */
    public static final String EMAIL_TAKEN = "Email already registered";

    private final AppUserEmailClaimRepository claims;
    private final Clock clock;

    /**
     * Constructs the service.
     *
     * @param claims the reservation table
     * @param clock  the application clock
     */
    public EmailClaims(AppUserEmailClaimRepository claims, Clock clock) {
        this.claims = claims;
        this.clock = clock;
    }

    /**
     * Claims an address for an account write in the caller's transaction.
     *
     * @param email     the address as stored (it is normalised here)
     * @param forUserId the id of the account being edited, or {@code null} for a new account
     * @throws IllegalArgumentException      ({@link #EMAIL_TAKEN}) when another account already uses the
     *                                       address under any casing
     * @throws org.springframework.dao.DataIntegrityViolationException when a concurrent claimant of a
     *                                       brand-new key won the insert
     */
    @Transactional
    public void claim(String email, Long forUserId) {
        String key = EmailIdentifier.normalise(email);
        if (claims.findByEmailLowerForUpdate(key).isPresent()) {
            boolean occupied = forUserId == null
                    ? claims.existsAnyOccupant(key)
                    : claims.existsOtherOccupant(key, forUserId);
            if (occupied) {
                throw new IllegalArgumentException(EMAIL_TAKEN);
            }
            return;
        }
        claims.insertClaim(key, clock.instant());
    }

    /**
     * Releases an address's reservation — only if no account still uses it. Called after the account
     * write that stopped using the address has flushed, in the same transaction.
     *
     * @param email the address that is no longer used by one account (normalised here)
     * @return true if the reservation was released
     */
    @Transactional
    public boolean release(String email) {
        return claims.releaseIfUnused(EmailIdentifier.normalise(email)) == 1;
    }
}
