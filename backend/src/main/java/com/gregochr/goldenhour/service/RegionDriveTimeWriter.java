package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.RegionDriveTimeEntity;
import com.gregochr.goldenhour.repository.RegionDriveTimeRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Constructs the writer.
     *
     * @param repository JPA repository for the shared matrix
     */
    public RegionDriveTimeWriter(RegionDriveTimeRepository repository) {
        this.repository = repository;
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
    }

    /**
     * Discards a region's drive times outright, leaving them unknown until the next refresh.
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
    }
}
