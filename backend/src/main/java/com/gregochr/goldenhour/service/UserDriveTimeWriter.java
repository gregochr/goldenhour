package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.UserDriveTimeEntity;
import com.gregochr.goldenhour.repository.UserDriveTimeRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Transactional writer for per-user drive times.
 *
 * <p>Replaces a user's stored drive times atomically — the delete and the inserts share one short
 * transaction, so a failed write leaves the previous drive times intact rather than wiping them.
 *
 * <p>Exists as a separate component so {@link DriveDurationService} can make its slow
 * OpenRouteService HTTP call (and wait on the concurrency semaphore) outside any transaction: the
 * database connection is only held for the duration of this write.
 */
@Component
public class UserDriveTimeWriter {

    private final UserDriveTimeRepository userDriveTimeRepository;

    /**
     * Constructs the writer.
     *
     * @param userDriveTimeRepository JPA repository for per-user drive times
     */
    public UserDriveTimeWriter(UserDriveTimeRepository userDriveTimeRepository) {
        this.userDriveTimeRepository = userDriveTimeRepository;
    }

    /**
     * Atomically replaces the stored drive times for one user.
     *
     * <p>Existing rows for the user are deleted and the new rows inserted in the same
     * transaction. An empty list clears the user's drive times (used when ORS returned no valid
     * durations for any location).
     *
     * @param userId     the user's primary key
     * @param driveTimes the new drive times for that user; may be empty
     */
    @Transactional
    public void replaceForUser(Long userId, List<UserDriveTimeEntity> driveTimes) {
        userDriveTimeRepository.deleteAllByUserId(userId);
        if (!driveTimes.isEmpty()) {
            userDriveTimeRepository.saveAll(driveTimes);
        }
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
     * @param userId the user's primary key
     */
    @Transactional
    public void clearForUser(Long userId) {
        userDriveTimeRepository.deleteAllByUserId(userId);
    }
}
