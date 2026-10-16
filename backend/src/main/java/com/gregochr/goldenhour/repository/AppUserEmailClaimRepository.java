package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AppUserEmailClaimEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * Data access for the email reservations behind {@code EmailClaims} (login-registration plan §5 #6).
 *
 * <p>Three rules shape this interface, each one a defect the plan's review found:
 * <ul>
 *   <li><strong>A claim is an immediate native INSERT</strong> ({@link #insertClaim}), never
 *       {@code save()}: the entity's id is assigned, so {@code save()} is a {@code merge} — a SELECT
 *       then a deferred INSERT — and a claimer whose SELECT lands after a concurrent first claim
 *       committed would produce a silent duplicate, with the violation firing at flush, after the
 *       registration email had been sent.</li>
 *   <li><strong>Two occupancy queries, never a bound null.</strong> {@code id <> :forUserId} with a null
 *       is UNKNOWN for every row and would make the check fail OPEN (every reservation reading as an
 *       orphan). {@link #existsOtherOccupant} is for an edit, {@link #existsAnyOccupant} for a new
 *       account — the rule {@code AppUserRepository#clearDriveTimesCalculatedAtIfHomeIs} records.</li>
 *   <li><strong>Release is conditional</strong> ({@link #releaseIfUnused}): the key stays reserved while
 *       ANY account still uses it.</li>
 * </ul>
 */
public interface AppUserEmailClaimRepository extends JpaRepository<AppUserEmailClaimEntity, String> {

    /**
     * Finds a reservation and LOCKS it until the transaction ends ({@code SELECT … FOR UPDATE}). Only
     * the row's existence is used, so a stale entity from an open-session-in-view persistence context
     * cannot mislead the caller.
     *
     * @param emailLower the lower-cased address
     * @return the reservation, or empty if the address has never been claimed
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM AppUserEmailClaimEntity c WHERE c.emailLower = :emailLower")
    Optional<AppUserEmailClaimEntity> findByEmailLowerForUpdate(@Param("emailLower") String emailLower);

    /**
     * Reports whether any account currently uses the address (case-insensitively). For a NEW account.
     *
     * @param emailLower the lower-cased address
     * @return true if an account holds it
     */
    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END FROM AppUserEntity u "
            + "WHERE LOWER(u.email) = :emailLower")
    boolean existsAnyOccupant(@Param("emailLower") String emailLower);

    /**
     * Reports whether an account OTHER than the one being edited uses the address. The account being
     * edited never counts as its own occupant, so changing only the casing of an address that account
     * alone holds succeeds.
     *
     * @param emailLower the lower-cased address
     * @param forUserId  the edited account's id; never null — use {@link #existsAnyOccupant} for a new
     *                   account
     * @return true if another account holds it
     */
    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END FROM AppUserEntity u "
            + "WHERE LOWER(u.email) = :emailLower AND u.id <> :forUserId")
    boolean existsOtherOccupant(@Param("emailLower") String emailLower, @Param("forUserId") Long forUserId);

    /**
     * Writes a reservation immediately. The primary key refuses a second claimant of the same key; the
     * caller treats that violation as "taken" and lets it propagate (Postgres aborts the transaction on
     * one, so it is never recovered from in place).
     *
     * @param emailLower the lower-cased address
     * @param claimedAt  when it is claimed
     * @return 1
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO app_user_email_claim (email_lower, claimed_at) VALUES (:emailLower, :claimedAt)",
            nativeQuery = true)
    int insertClaim(@Param("emailLower") String emailLower, @Param("claimedAt") Instant claimedAt);

    /**
     * Releases a reservation — but only when no account still uses the address. Run in the same
     * transaction as, and after, the account write has flushed, so the key stays reserved while ANY
     * account (a legacy case-duplicate pair shares one) still holds it.
     *
     * <p>The subquery is deliberately CORRELATED on the reservation's own key: H2 caches the result of an
     * uncorrelated subquery across executions of the same statement in a session, so a second release in
     * one session could read the first one's answer. Postgres has no such cache; the shape is equivalent.
     *
     * @param emailLower the lower-cased address
     * @return 1 if released; 0 if an account still uses it (or it was never reserved)
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM app_user_email_claim c WHERE c.email_lower = :emailLower "
            + "AND NOT EXISTS (SELECT 1 FROM app_user u WHERE LOWER(u.email) = c.email_lower)",
            nativeQuery = true)
    int releaseIfUnused(@Param("emailLower") String emailLower);
}
