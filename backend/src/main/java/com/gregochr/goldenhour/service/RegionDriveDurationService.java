package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.OpenRouteServiceClient;
import com.gregochr.goldenhour.config.OrsProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionDriveTimeEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.RegionDriveTimeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes the shared drive-time matrix from every region's base town to the whole location roster.
 *
 * <p><strong>The same job as {@link DriveDurationService}, asked from a different origin.</strong>
 * That one answers "how far is each location from your house" and its rows are per user; this one
 * answers "how far is each location from Keswick", which is the same answer for every reader. The
 * split is the privacy seam {@code plan-panel-data-contracts.md} draws: per-user drive times stay
 * on the never-cached {@code /api/user/settings/*} endpoints, and this matrix rides the shared,
 * ETag-revalidated payload the Plan tab already fetches.
 *
 * <p>Deliberately not transactional, for the reason {@link DriveDurationService} records: the ORS
 * call runs outside any transaction, and persistence is delegated to {@link RegionDriveTimeWriter},
 * which replaces one region's rows in a single short transaction. The account-wide concurrency
 * limit lives inside the client ({@code OrsRateLimiter}), so a region sweep and a user sweep queue
 * on the same two permits rather than on one each.
 */
@Service
public class RegionDriveDurationService {

    private static final Logger LOG = LoggerFactory.getLogger(RegionDriveDurationService.class);

    private final OpenRouteServiceClient orsClient;
    private final OrsProperties orsProperties;
    private final LocationRepository locationRepository;
    private final RegionDriveTimeRepository driveTimeRepository;
    private final RegionDriveTimeWriter driveTimeWriter;

    /**
     * Constructs a {@code RegionDriveDurationService}.
     *
     * @param orsClient           client for the ORS matrix API
     * @param orsProperties       ORS configuration (API key, enabled flag)
     * @param locationRepository  JPA repository for locations
     * @param driveTimeRepository JPA repository for the shared matrix, for the serve-side read
     * @param driveTimeWriter     transactional writer for one region's rows
     */
    public RegionDriveDurationService(OpenRouteServiceClient orsClient, OrsProperties orsProperties,
            LocationRepository locationRepository,
            RegionDriveTimeRepository driveTimeRepository,
            RegionDriveTimeWriter driveTimeWriter) {
        this.orsClient = orsClient;
        this.orsProperties = orsProperties;
        this.locationRepository = locationRepository;
        this.driveTimeRepository = driveTimeRepository;
        this.driveTimeWriter = driveTimeWriter;
    }

    /**
     * Recalculates and stores the drive times from one region's base to every location.
     *
     * <p>A region with no base is skipped and reports zero: it cannot be an origin, so it has no
     * journeys to measure. The previous rows are left untouched in that case — clearing them is
     * {@link RegionDriveTimeWriter#clearForRegion} 's job, called when the base actually moves.
     *
     * @param region the origin region
     * @return the number of locations with a valid drive time from this base
     */
    public int refreshForRegion(RegionEntity region) {
        if (!orsProperties.isConfigured()) {
            LOG.warn("ORS not configured — skipping drive time refresh for region {}",
                    region.getName());
            return 0;
        }
        if (!hasBase(region)) {
            return 0;
        }

        List<LocationEntity> locations = locationRepository.findAll();
        if (locations.isEmpty()) {
            return 0;
        }

        List<double[]> destinations = locations.stream()
                .map(loc -> new double[]{loc.getLat(), loc.getLon()})
                .toList();

        LOG.info("Refreshing region drive times for '{}' from base '{}' ({}, {}) to {} locations",
                region.getName(), region.getBaseName(), region.getBaseLat(), region.getBaseLon(),
                destinations.size());

        List<Double> durations = orsClient.fetchDurations(
                region.getBaseLat(), region.getBaseLon(), destinations);
        if (durations.isEmpty()) {
            LOG.warn("ORS returned empty durations for region '{}'", region.getName());
            return 0;
        }

        List<RegionDriveTimeEntity> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(locations.size(), durations.size()); i++) {
            Double seconds = durations.get(i);
            if (seconds != null && seconds >= 0) {
                rows.add(new RegionDriveTimeEntity(
                        region.getId(), locations.get(i).getId(), (int) Math.round(seconds)));
            }
        }

        if (rows.isEmpty()) {
            // ⚠️ NOT `replaceForRegion(id, List.of())`. ORS answers an unroutable SOURCE — a base
            // mistyped onto water, which is exactly what the nullable base columns exist to guard
            // against — with a non-empty durations row whose every entry is null. That clears the
            // `durations.isEmpty()` guard above, and replacing with an empty list would DELETE a
            // working matrix and insert nothing, while the caller logged that it had been left in
            // place. Same rule as the empty-response arm: an unusable answer leaves the previous
            // matrix alone.
            LOG.warn("ORS returned no usable durations for region '{}' — leaving the previous "
                    + "matrix in place", region.getName());
            return 0;
        }

        driveTimeWriter.replaceForRegion(region.getId(), rows);
        LOG.info("Region drive time refresh complete for '{}' — {} locations updated",
                region.getName(), rows.size());
        return rows.size();
    }

    /**
     * Whether a region carries a complete base — all three columns, or none of them.
     *
     * @param region the region to test
     * @return true when the region can serve as an origin
     */
    public static boolean hasBase(RegionEntity region) {
        return region != null
                && region.getBaseName() != null && !region.getBaseName().isBlank()
                && region.getBaseLat() != null && region.getBaseLon() != null;
    }

    /**
     * The whole matrix, in the shape the Plan tab consumes it: region id to location id to whole
     * minutes.
     *
     * <p><b>Minutes, not seconds, and rounded here.</b> The frontend's reach tiers, the leave-by
     * line and the drive chips are all minute-grained, and {@link
     * com.gregochr.goldenhour.model.ReachEntry} already serves per-user drive times that way — so
     * serving seconds would make the origin-move swap the units under every one of those consumers
     * at once. Rounded by the entity's own accessor, which is the same expression the per-user
     * table rounds with.
     *
     * <p>Empty for a region with no stored rows, which is the ordinary state before the first
     * nightly sweep and immediately after a base moves. The client reads an absent drive time as
     * <em>unknown</em>, never as far.
     *
     * @return region id to (location id to drive minutes)
     */
    public Map<Long, Map<Long, Integer>> getMatrix() {
        Map<Long, Map<Long, Integer>> matrix = new HashMap<>();
        for (RegionDriveTimeEntity row : driveTimeRepository.findAll()) {
            matrix.computeIfAbsent(row.getRegionId(), key -> new HashMap<>())
                    .put(row.getLocationId(), row.getDriveMinutes());
        }
        return matrix;
    }
}
