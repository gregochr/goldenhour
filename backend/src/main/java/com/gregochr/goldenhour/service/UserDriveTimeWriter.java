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
 * writes, and a save can move the home in that gap. {@link #storeIfHomeUnchanged} and
 * {@link #stampIfHomeUnchanged} both begin with a compare-and-set of the stamp on the coordinates
 * the caller measured from, and write nothing when it matches no row.
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
     * transaction, so a failed write leaves the previous drive times and their stamp intact. An
     * empty list clears the user's drive times (ORS answered, but with no valid duration to any
     * location).
     *
     * @param userId       the user's primary key
     * @param originLat    the latitude the drive times were measured from
     * @param originLon    the longitude the drive times were measured from
     * @param driveTimes   the new drive times for that user; may be empty
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
     * Stamps a calculation that produced no answer to store, leaving the stored drive times as they
     * are — only while the home is still the one the attempt was made from.
     *
     * <p>The manual refresh's path when ORS gives no answer at all (unconfigured, an empty
     * response, or no locations): it has always stamped that attempt, which is what its response
     * reports and what its cooldown reads. Guarded like {@link #storeIfHomeUnchanged}, because a
     * stamp landing after a move would put the cooldown back on someone who has just moved house and
     * has no drive times at all.
     *
     * @param userId       the user's primary key
     * @param originLat    the latitude the attempt was made from
     * @param originLon    the longitude the attempt was made from
     * @param calculatedAt when the attempt was made
     * @return {@code true} if stamped; {@code false} if the home has moved since
     */
    @Transactional
    public boolean stampIfHomeUnchanged(Long userId, double originLat, double originLon,
            Instant calculatedAt) {
        return userRepository.stampDriveTimesIfHomeIs(userId, originLat, originLon, calculatedAt) == 1;
    }

    /**
     * Discards a user's drive times outright, leaving them unknown until the next refresh.
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
     * postcode. The user refreshes when they are ready.
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
