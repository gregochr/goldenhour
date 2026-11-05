package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.RegionDriveTimeEntity;
import com.gregochr.goldenhour.repository.RegionDriveTimeRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Transactional writer for the shared region-base drive-time matrix.
 *
 * <p>The exact shape of {@link UserDriveTimeWriter}, and for the same two reasons: the delete and
 * the inserts share one short transaction, so a failed write leaves the previous matrix intact
 * rather than wiping it; and it is a separate component so {@link RegionDriveDurationService} can
 * make its slow OpenRouteService call outside any transaction, holding no database connection
 * while it waits.
 */
@Component
public class RegionDriveTimeWriter {

    private final RegionDriveTimeRepository repository;
    private final RegionRepository regionRepository;
    private final RegionDriveMatrixMemo matrixMemo;

    /**
     * Constructs the writer.
     *
     * @param repository       JPA repository for the shared matrix
     * @param regionRepository carries the column-scoped writes of {@code regions.drives_measured_at}
     * @param matrixMemo       memo of the served matrix, cleared after a commit that changes the rows
     */
    public RegionDriveTimeWriter(RegionDriveTimeRepository repository,
            RegionRepository regionRepository, RegionDriveMatrixMemo matrixMemo) {
        this.repository = repository;
        this.regionRepository = regionRepository;
        this.matrixMemo = matrixMemo;
    }

    /**
     * Replaces a region's drive times, and stamps them as fully measured, only while the base is still the one
     * they were measured from.
     *
     * <p>The compare-and-set comes first, as in {@link UserDriveTimeWriter#storeIfHomeUnchanged}: it is an
     * update, so it takes the region row's lock, and {@code RegionService.setBase} writes that row before it
     * clears the drive times. A move either commits before this begins, and the compare-and-set matches nothing,
     * or waits until this commits and then discards what was stored here.
     *
     * <p><b>A stamp means "fully measured from this base, as of this instant"</b> (reach-scoped-card-plan D1b).
     * {@code complete} is false when ORS returned fewer slots than destinations: the rows are still stored, the
     * stamp is not, so the destinations it missed stay PENDING rather than reading UNROUTABLE.
     *
     * @param regionId   the region's primary key
     * @param baseLat    the base latitude the drive times were measured from
     * @param baseLon    the base longitude the drive times were measured from
     * @param driveTimes the new rows for that region
     * @param measuredAt the instant to stamp, taken before the roster was read
     * @param complete   whether ORS returned a duration slot for every destination
     * @return true if stored; false if the base has moved or been cleared since, and nothing was written
     */
    @Transactional
    public boolean storeIfBaseUnchanged(Long regionId, double baseLat, double baseLon,
            List<RegionDriveTimeEntity> driveTimes, Instant measuredAt, boolean complete) {
        boolean unchanged = complete
                ? regionRepository.stampDrivesMeasuredAtIfBaseIs(regionId, baseLat, baseLon, measuredAt) == 1
                : regionRepository.lockIfBaseIs(regionId, baseLat, baseLon) == 1;
        if (!unchanged) {
            return false;
        }
        replaceForRegion(regionId, driveTimes);
        // A location moved after the roster was read was routed to its OLD point; drop that row so it reads PENDING.
        repository.deleteMovedSince(regionId, LocalDateTime.ofInstant(measuredAt, ZoneOffset.UTC));
        return true;
    }

    /**
     * Atomically replaces the stored drive times originating at one region.
     *
     * @param regionId   the region's primary key
     * @param driveTimes the new rows for that region; may be empty
     */
    @Transactional
    public void replaceForRegion(Long regionId, List<RegionDriveTimeEntity> driveTimes) {
        repository.deleteAllByRegionId(regionId);
        if (!driveTimes.isEmpty()) {
            repository.saveAll(driveTimes);
        }
        matrixMemo.invalidateAfterCommit();
    }

    /**
     * Discards a region's drive times outright, leaving them unknown until the next refresh, and nulls the
     * region's drive-measured stamp so every drive from the new base reads PENDING rather than UNROUTABLE.
     *
     * <p><b>Unknown is safe here; wrong is not</b> — the rule {@link UserDriveTimeWriter} states
     * for a moved house, applied to a moved base. Every stored row describes a journey from the
     * old town; the Plan tab renders a spot with no drive time without a drive line and passes it
     * at every reach tier, so discarding degrades visibly and honestly, where keeping the numbers
     * would gate spots in and out on figures that are quietly wrong with nothing on screen saying
     * so.
     *
     * <p>Deliberately not a re-route: that is an external, rate-limited, failure-prone call per
     * region, which does not belong inside saving an admin form. The nightly job refills it.
     *
     * @param regionId the region's primary key
     */
    @Transactional
    public void clearForRegion(Long regionId) {
        repository.deleteAllByRegionId(regionId);
        regionRepository.clearDrivesMeasuredAt(regionId);
        matrixMemo.invalidateAfterCommit();
    }

    /**
     * Discards every region base's drive time to one location, because the location's coordinates moved and each
     * row describes a journey to the old point. The roster's other rows and every stamp are untouched: the
     * location's {@code drive_target_changed_at} bump is what makes it read PENDING until the nightly sweep
     * measures it again.
     *
     * @param locationId the location's primary key
     */
    @Transactional
    public void clearForLocation(Long locationId) {
        repository.deleteAllByLocationId(locationId);
        matrixMemo.invalidateAfterCommit();
    }
}
