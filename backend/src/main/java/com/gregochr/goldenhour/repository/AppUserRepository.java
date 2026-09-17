package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Spring Data repository for {@link AppUserEntity}.
 *
 * <p><strong>Every settings write is a column-scoped update here, never a {@code save()}.</strong>
 * The entity marks its seven settings columns {@code updatable = false} (its class Javadoc records
 * why), so a whole-entity save cannot write them at all; these updates are the only way in. Each
 * writes exactly the columns its own request changes, so two settings saves in two tabs, or a
 * settings save and the nightly drive-time job, can no longer discard one another's change.
 *
 * <p>Every update below carries {@code clearAutomatically = true} and
 * {@code flushAutomatically = true}, for the reasons
 * {@link #updateComingUpLastSeenAtByUsername} records: pending changes are flushed first, and a
 * caller that reads the row back in the same transaction sees the write rather than a stale
 * managed instance.
 */
public interface AppUserRepository extends JpaRepository<AppUserEntity, Long> {

    /**
     * Finds a user by their unique login name.
     *
     * @param username the username to search for
     * @return an {@link Optional} containing the user, or empty if not found
     */
    Optional<AppUserEntity> findByUsername(String username);

    /**
     * Returns whether a user with the given username already exists.
     *
     * @param username the username to check
     * @return {@code true} if a matching user exists
     */
    boolean existsByUsername(String username);

    /**
     * Returns whether a user with the given email already exists.
     *
     * @param email the email to check
     * @return {@code true} if a matching user exists
     */
    boolean existsByEmail(String email);

    /**
     * Finds a user by their email address.
     *
     * @param email the email to search for
     * @return an {@link Optional} containing the user, or empty if not found
     */
    Optional<AppUserEntity> findByEmail(String email);

    /**
     * Counts the number of users whose role is not the specified value.
     *
     * <p>Used to count non-admin users for the early-access registration cap.
     *
     * @param role the role to exclude from the count
     * @return the number of users with a different role
     */
    long countByRoleNot(UserRole role);

    /**
     * Updates only the Coming-up last-seen instant (plan D3/P5) — never a whole-entity save.
     *
     * <p>{@code AppUserEntity} carries neither {@code @Version} nor {@code @DynamicUpdate}, so
     * Hibernate's default UPDATE writes every mapped column. This write races two other callers
     * that load and save the same row independently — {@code saveHome} and
     * {@code saveMapColourPreferences} — and a full-entity save from any of them can silently
     * restore whichever OTHER column it read stale, discarding a concurrent change (a Codex
     * review finding on PR #695). A column-scoped bulk update touches only this one column, so
     * opening the Coming up tab can never overwrite an unrelated profile/settings write.
     *
     * <p>{@code clearAutomatically = true} evicts the persistence context after the update, so a
     * caller that reads the row back in the same transaction (to build a response) sees this
     * write rather than a stale managed instance cached from an earlier load; {@code
     * flushAutomatically = true} flushes any pending changes first, so this bulk update is never
     * itself the one that becomes stale against an uncommitted change earlier in the transaction.
     *
     * @param username the caller's username
     * @param seenAt   the instant to record
     * @return the number of rows updated — 1, or 0 if the username no longer matches a row
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE AppUserEntity u SET u.comingUpLastSeenAt = :seenAt WHERE u.username = :username")
    int updateComingUpLastSeenAtByUsername(@Param("username") String username,
            @Param("seenAt") Instant seenAt);

    /**
     * Loads a user by login name and LOCKS the row until the calling transaction ends
     * ({@code SELECT … FOR UPDATE}).
     *
     * <p>For a write whose decision depends on the row as it stands. {@code saveHome} decides from
     * the stored home whether the save moves it, and a move must discard the drive times measured
     * from the old one. Read without a lock, that decision could be made against a home another
     * save changes before this one commits — so a save re-sending the old home would write it back
     * and keep drive times measured from the new one. Under the lock, nothing else can write the
     * row between the read and the commit: another save waits, and so does the drive-time
     * refresh's compare-and-set ({@link #stampDriveTimesIfHomeIs}), which then re-checks its
     * condition against the committed result.
     *
     * <p>Must be called inside a transaction; the lock is released when it commits.
     *
     * @param username the login name
     * @return the locked user, or empty if no user has that name
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM AppUserEntity u WHERE u.username = :username")
    Optional<AppUserEntity> findByUsernameForUpdate(@Param("username") String username);

    /**
     * Writes the home fields together — postcode, coordinates and radius — and no other column.
     *
     * <p>A {@code null} radius keeps the stored one ({@code coalesce}): the client omits it when
     * only the postcode changed, and treating that as a reset would silently undo a radius the user
     * had widened on purpose.
     *
     * <p>Writes no drive-time stamp. A save that moves the home clears that separately
     * ({@link #clearDriveTimesCalculatedAt}), and one that does not move it must leave it alone.
     *
     * @param id               the user's primary key
     * @param postcode         the home postcode
     * @param latitude         the home latitude
     * @param longitude        the home longitude
     * @param localRadiusMiles the radius to store, already clamped, or {@code null} to keep the
     *                         stored one
     * @return the number of rows updated — 1, or 0 if no user has that id
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE AppUserEntity u SET u.homePostcode = :postcode, u.homeLatitude = :latitude, "
            + "u.homeLongitude = :longitude, "
            + "u.localRadiusMiles = coalesce(:localRadiusMiles, u.localRadiusMiles) "
            + "WHERE u.id = :id")
    int updateHome(@Param("id") Long id, @Param("postcode") String postcode,
            @Param("latitude") double latitude, @Param("longitude") double longitude,
            @Param("localRadiusMiles") Integer localRadiusMiles);

    /**
     * Clears the drive-time stamp — the column half of discarding drive times when the home moves.
     *
     * <p>Not bookkeeping: the stamp is what the settings dialog reads to say when drive times were
     * last calculated, and it is the refresh cooldown's input, so leaving it set would lock someone
     * who has just moved out of recalculating while they are served no drive times at all.
     *
     * @param id the user's primary key
     * @return the number of rows updated — 1, or 0 if no user has that id
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE AppUserEntity u SET u.driveTimesCalculatedAt = null WHERE u.id = :id")
    int clearDriveTimesCalculatedAt(@Param("id") Long id);

    /**
     * Writes the map colour scale alone.
     *
     * @param username       the caller's username
     * @param mapColourScale the validated scale, {@code "temp"} or {@code "verdict"}
     * @return the number of rows updated — 1, or 0 if the username no longer matches a row
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE AppUserEntity u SET u.mapColourScale = :mapColourScale "
            + "WHERE u.username = :username")
    int updateMapColourScaleByUsername(@Param("username") String username,
            @Param("mapColourScale") String mapColourScale);

    /**
     * Stamps the drive times as calculated — but only while the home is still the one they were
     * measured from. A compare-and-set.
     *
     * <p>A drive-time refresh reads the home, spends seconds routing from it, and only then
     * stores. A save can move the home in between, and when it does, what was measured describes a
     * journey from a house the user no longer lives in. This update matches no row in that case, so
     * the caller learns that its result is stale from the count. The comparison and the write are
     * one statement: a check read first and written after would reopen the gap it closes. On
     * Postgres, an update that waits for a concurrent writer's row lock re-checks its condition
     * against the row that writer committed, so a move that commits while this waits is seen.
     *
     * <p>Compares the coordinates alone, because they are the whole input to the measurement: two
     * postcodes that geocode to one point yield the same drive times. {@code home_latitude} and
     * {@code home_longitude} are {@code DOUBLE PRECISION}, which round-trips a Java {@code double}
     * exactly, so the equality is exact. A home cleared to null never matches.
     *
     * @param id           the user's primary key
     * @param latitude     the latitude the drive times were measured from
     * @param longitude    the longitude the drive times were measured from
     * @param calculatedAt the instant to record
     * @return 1 if stamped; 0 if the home has moved since (or no user has that id)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE AppUserEntity u SET u.driveTimesCalculatedAt = :calculatedAt "
            + "WHERE u.id = :id AND u.homeLatitude = :latitude AND u.homeLongitude = :longitude")
    int stampDriveTimesIfHomeIs(@Param("id") Long id, @Param("latitude") double latitude,
            @Param("longitude") double longitude, @Param("calculatedAt") Instant calculatedAt);
}
