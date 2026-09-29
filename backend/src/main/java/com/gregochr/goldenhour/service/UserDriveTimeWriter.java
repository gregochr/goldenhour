package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.UserDriveTimeEntity;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.UserDriveTimeRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Transactional writer for per-user drive times: the rows, and the stamp that says when they were
 * calculated.
 *
 * <p>Exists as a separate component so {@link DriveDurationService} can make its slow
 * OpenRouteService HTTP call (and wait on the concurrency semaphore) outside any transaction: the
 * database connection is only held for the duration of a write here.
 *
 * <p><strong>Nothing measured is stored unless it was measured from the user's home as it stands
 * when it is stored.</strong> A refresh reads the home, routes from it for seconds, and only then
 * writes, and a save can move the home in that gap. Both {@link #storeIfHomeUnchanged} and
 * {@link #clearIfHomeUnchanged} begin with a compare-and-set on the coordinates the caller measured
 * from, and write nothing when it matches no row.
 *
 * <p>⚠️ There is deliberately no "stamp only, no rows" method here — one existed
 * ({@code stampIfHomeUnchanged}) for the case where ORS gave no answer at all, so the manual
 * refresh's 30-minute cooldown still applied to a failed attempt; advancing
 * {@code driveTimesCalculatedAt} with no rows stored broke the stamp's own meaning ("drive times
 * were stored for the roster read at this instant") and, on the one realistic sequence that reaches
 * it (a postcode change, whose only enabled button is this one, followed by an ORS failure), left
 * the scheduled job reading a non-null stamp newer than the roster and skipping that user
 * indefinitely. {@code UserSettingsService.refreshDriveTimes} tracks a failed ATTEMPT in its own
 * in-memory map instead, and never calls a writer method at all when ORS gave no answer.
 *
 * <p><strong>The invariant this class exists to hold: no outcome on any path may leave a non-null
 * stamp with zero rows.</strong> {@link DriveDurationService#measureForUser} distinguishes two
 * different kinds of nothing — an empty {@code Optional} (no answer from ORS at all) and a present
 * but empty {@code List} (ORS answered; no destination had a valid duration, "confirmed
 * unreachable") — and the two routes treat "confirmed unreachable" differently on purpose:
 *
 * <table>
 *   <caption>What each outcome does to the rows and the stamp, on each route</caption>
 *   <tr><th>outcome</th><th>route</th><th>rows</th><th>stamp</th></tr>
 *   <tr><td rowspan="2">rows stored (ORS answered, &ge;1 valid duration)</td>
 *       <td>manual</td><td>replaced</td><td>set to the roster-read instant</td></tr>
 *   <tr><td>scheduled</td><td>replaced</td><td>set to the roster-read instant</td></tr>
 *   <tr><td rowspan="2">no answer at all (unconfigured, rate-limited, or an empty/failed response)</td>
 *       <td>manual</td><td>unchanged</td><td>unchanged</td></tr>
 *   <tr><td>scheduled</td><td>unchanged</td><td>unchanged</td></tr>
 *   <tr><td rowspan="2">confirmed unreachable (ORS answered; no valid duration anywhere)</td>
 *       <td>manual</td><td><b>cleared</b></td><td><b>set to null</b></td></tr>
 *   <tr><td>scheduled</td><td>unchanged</td><td>unchanged</td></tr>
 *   <tr><td rowspan="2">home moved during measurement</td>
 *       <td>manual</td><td>unchanged (409, nothing written)</td>
 *       <td>unchanged (409, nothing written)</td></tr>
 *   <tr><td>scheduled</td><td>unchanged (counted superseded)</td>
 *       <td>unchanged (counted superseded)</td></tr>
 * </table>
 *
 * <p>The manual/scheduled split on "confirmed unreachable" is deliberate, not an inconsistency: a
 * person pressed the Settings button and is looking at the result, so clearing stale rows the
 * moment ORS has confirmed they no longer apply is the honest answer — keeping them would let the
 * reach lens and a leave-by time keep using drive times ORS has just invalidated. The scheduled job
 * runs with nobody watching, so a transient ORS wobble that returns zero valid durations for one
 * night must not silently wipe a user's drive times overnight; see {@link DriveTimeRefreshJob}'s own
 * javadoc for that reasoning. Clearing on the manual path leaves the EXACT state a home move already
 * produces (rows gone, stamp null) — a state the rest of the system already handles: no "Last
 * calculated" line, the reach lens treats the location as unknown, and a null stamp makes the
 * scheduled job measure the user again on its very next run.
 */
@Component
public class UserDriveTimeWriter {

    private final UserDriveTimeRepository userDriveTimeRepository;
    private final AppUserRepository userRepository;

    /**
     * Constructs the writer.
     *
     * @param userDriveTimeRepository JPA repository for per-user drive times
     * @param userRepository          stamps the calculation on the user's row
     */
    public UserDriveTimeWriter(UserDriveTimeRepository userDriveTimeRepository,
            AppUserRepository userRepository) {
        this.userDriveTimeRepository = userDriveTimeRepository;
        this.userRepository = userRepository;
    }

    /**
     * Replaces a user's drive times and stamps them as calculated — only while the home is still
     * the one they were measured from.
     *
     * <p>The compare-and-set comes FIRST, and that order is what makes the whole method safe. It is
     * an update, so it takes the user row's lock, and every write that moves a home takes that lock
     * before it touches drive times ({@code UserSettingsService.saveHome} locks the row as it reads
     * it). So a move either commits before this begins, and the compare-and-set matches nothing, or
     * waits until this commits, and then discards what was stored here as measured from the old
     * home. Both parties take the user row before the drive-time rows, so neither can deadlock the
     * other.
     *
     * <p>When it stores, it stores atomically: the stamp, the delete and the inserts share one
     * transaction, so a failed write leaves the previous drive times and their stamp intact.
     *
     * <p>⚠️ {@code driveTimes} is expected non-empty — every live caller reaches this method only
     * after confirming it measured at least one valid duration, routing an empty answer to
     * {@link #clearIfHomeUnchanged} instead (see the class javadoc's table). An empty list here
     * would still stamp (a non-null {@code calculatedAt} with zero rows behind it), which is
     * exactly the invariant this class exists to hold — the defensive {@code isEmpty()} guard
     * below exists only so a future caller's mistake clears rather than corrupts, never so an empty
     * list becomes a second, quieter way to reach that state.
     *
     * @param userId       the user's primary key
     * @param originLat    the latitude the drive times were measured from
     * @param originLon    the longitude the drive times were measured from
     * @param driveTimes   the new drive times for that user; expected non-empty (see above)
     * @param calculatedAt when they were calculated
     * @return {@code true} if stored; {@code false} if the home has moved since, and nothing was
     *         written
     */
    @Transactional
    public boolean storeIfHomeUnchanged(Long userId, double originLat, double originLon,
            List<UserDriveTimeEntity> driveTimes, Instant calculatedAt) {
        if (userRepository.stampDriveTimesIfHomeIs(userId, originLat, originLon, calculatedAt) != 1) {
            return false;
        }
        userDriveTimeRepository.deleteAllByUserId(userId);
        if (!driveTimes.isEmpty()) {
            userDriveTimeRepository.saveAll(driveTimes);
        }
        return true;
    }

    /**
     * Clears a user's drive times AND their calculated-at stamp together — only while the home is
     * still the one they were measured from. The manual-refresh counterpart to
     * {@link #storeIfHomeUnchanged} for a "confirmed unreachable" answer (see the class javadoc's
     * table): ORS answered, and confirmed no destination has a valid duration, so the stored rows
     * are known-stale rather than merely unmeasured.
     *
     * <p>The guard is the same compare-and-set shape {@link #storeIfHomeUnchanged} uses, on its own
     * dedicated repository method, {@link AppUserRepository#clearDriveTimesCalculatedAtIfHomeIs} — a
     * literal {@code SET ... = NULL}, not {@link AppUserRepository#stampDriveTimesIfHomeIs} called
     * with a {@code null} instant, so this write never depends on how a bound null binds on a given
     * JDBC driver (see that method's own javadoc). The row delete reuses {@link #clearForUser}
     * exactly — no third way to delete rows. The guard comes first, in the same order and for the
     * same reason {@code storeIfHomeUnchanged}'s javadoc gives: it takes the user row's lock before
     * the drive-time rows, so it orders correctly against a concurrent {@code saveHome} without a
     * separate lock of its own.
     *
     * @param userId    the user's primary key
     * @param originLat the latitude the (empty) measurement was taken from
     * @param originLon the longitude the (empty) measurement was taken from
     * @return {@code true} if cleared; {@code false} if the home has moved since, and nothing was
     *         written
     */
    @Transactional
    public boolean clearIfHomeUnchanged(Long userId, double originLat, double originLon) {
        if (userRepository.clearDriveTimesCalculatedAtIfHomeIs(userId, originLat, originLon) != 1) {
            return false;
        }
        clearForUser(userId);
        return true;
    }

    /**
     * Discards a user's drive times outright, leaving them unknown until a refresh measures them
     * from the new home.
     *
     * <p><b>Unknown is safe here; wrong is not.</b> A drive time is measured from one origin, and
     * the moment the user's home moves every stored row describes a journey nobody is going to
     * make. The rest of this product treats an absent drive time as <em>unknown</em> — the spot
     * still renders, it simply shows no drive line, and the reach lens passes it at every tier —
     * so discarding degrades gracefully. Keeping the old numbers instead would gate a location in
     * or out on a figure that is quietly forty minutes wrong, and nothing on screen would say so.
     *
     * <p>Deliberately not a re-route. Recalculating means an external routing call per location,
     * which is slow, rate-limited and able to fail — none of which belongs inside saving a
     * postcode. Whichever of two refreshes first measures from the new home ends the gap: the
     * nightly {@link DriveTimeRefreshJob}, which routes every enabled user with a saved home, or
     * the user's own press of <em>Refresh drive times</em> in the Settings dialog
     * ({@code UserSettingsService.refreshDriveTimes}). The dialog enables that button for Pro and
     * admin accounts only, so a LITE account waits for the nightly run.
     *
     * <p>Callers must hold the user row's lock first, as {@code UserSettingsService.saveHome}
     * does: that is the ordering {@link #storeIfHomeUnchanged} relies on.
     *
     * @param userId the user's primary key
     */
    @Transactional
    public void clearForUser(Long userId) {
        userDriveTimeRepository.deleteAllByUserId(userId);
    }
}
