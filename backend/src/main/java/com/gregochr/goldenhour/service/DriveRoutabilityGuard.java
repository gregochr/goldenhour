package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.OpenRouteServiceClient;
import com.gregochr.goldenhour.client.OpenRouteServiceClient.OrsUnroutablePointException;
import com.gregochr.goldenhour.client.OpenRouteServiceClient.RouteProbe;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.exception.NoRoadRouteException;
import com.gregochr.goldenhour.model.AddLocationRequest;
import com.gregochr.goldenhour.model.DrivePoint;
import com.gregochr.goldenhour.model.UpdateLocationRequest;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.util.GeoUtils;
import com.gregochr.goldenhour.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Refuses an admin save that would leave a location with a point no road route reaches (reach-scoped-card-plan R1c,
 * owner requirement), by asking OpenRouteService once, <em>before</em> the save's transaction opens.
 *
 * <h2>When it refuses</h2>
 *
 * <p>With a 422 ({@link NoRoadRouteException}) on exactly three triggers, and never otherwise:
 * <ul>
 *   <li><b>create</b>: the new place's routing point (its drive point, else its pin) has no route;</li>
 *   <li><b>a coordinate move</b> on a place with no drive point: the new pin has no route;</li>
 *   <li><b>clearing a drive point</b>: the pin it falls back to has no route.</li>
 * </ul>
 * Setting or changing a drive point also checks that point itself, because a drive point that cannot be routed is
 * worse than none. Every other edit (sky, mist, tide, names, region) is never refused, and neither is a place that
 * already exists and is unroutable: that is only flagged (R1b).
 *
 * <h2>Why it fails open</h2>
 *
 * <p>An outage must never stop an admin saving. So "no answer" of any kind is {@link Routability#UNAVAILABLE} and the
 * save goes ahead: ORS not configured, no region base to route from, no permit within {@link #PERMIT_TIMEOUT}, an ORS
 * error, a short or empty answer, or a source that itself cannot be routed from (the control destination, a point
 * known to be on a road, comes back null: an all-null row means the <em>source</em> is bad, not the target). Only a
 * clear "this point has no route" refuses.
 *
 * <h2>Why before the transaction</h2>
 *
 * <p>The ORS call waits for a rate-limit permit and an HTTP round trip. {@code LocationService.add} and {@code update}
 * are transactional and the same rule that keeps {@code DriveDurationService} out of one applies here: no database
 * connection is pinned across a slow external call. The controller calls this first, then the service.
 */
@Service
public class DriveRoutabilityGuard {

    private static final Logger LOG = LoggerFactory.getLogger(DriveRoutabilityGuard.class);

    /**
     * The longest a save waits for an ORS permit. The nightly sweeps hold both permits for a whole matrix call, so
     * a save during one would otherwise wait on it; a few seconds is the longest an admin should wait before being
     * told nothing, and the save is then allowed.
     */
    static final Duration PERMIT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * A point known to be on a road, routed alongside the target (central Birmingham, the Bull Ring). If ORS cannot
     * route even to this from the source, the source is the problem (a base on water or a ferry-only island), not
     * the target, and the answer is "unavailable".
     */
    static final double[] CONTROL_DESTINATION = {52.4776, -1.8932};

    /** The index of the target in the request's {@code locations} array: source 0, control 1, target 2. */
    private static final int TARGET_COORDINATE_INDEX = 2;

    /** Shown when the pin itself has no route. */
    static final String PIN_MESSAGE =
            "No road route reaches this point. Add a drive point, e.g. the nearest village or car park.";

    /** Shown when the drive point has no route. */
    static final String DRIVE_POINT_MESSAGE =
            "No road route reaches that drive point. Choose a point on a road, e.g. the village centre or a car park.";

    /** What the check found. */
    enum Routability {
        /** ORS routed to the target. */
        ROUTABLE,
        /** ORS answered, and the target has no road route. */
        UNROUTABLE,
        /** No usable answer; the save is allowed. */
        UNAVAILABLE
    }

    private final OpenRouteServiceClient orsClient;
    private final RegionRepository regionRepository;
    private final LocationService locationService;

    /**
     * Constructs the guard.
     *
     * @param orsClient        the ORS client, whose probe gives up rather than waits
     * @param regionRepository supplies the region bases routed from
     * @param locationService  supplies the stored location an update is compared with
     */
    public DriveRoutabilityGuard(OpenRouteServiceClient orsClient, RegionRepository regionRepository,
            LocationService locationService) {
        this.orsClient = orsClient;
        this.regionRepository = regionRepository;
        this.locationService = locationService;
    }

    /**
     * Checks a create request, before it is saved.
     *
     * @param request the new location
     * @throws NoRoadRouteException     if the point drive times would be routed to has no road route
     * @throws IllegalArgumentException if the drive point is partial or out of range (HTTP 400)
     */
    public void requireRoutableOnAdd(AddLocationRequest request) {
        DrivePoint drivePoint = DrivePoint.parse(
                request.drivePointName(), request.drivePointLat(), request.drivePointLon());
        if (drivePoint != null) {
            require(drivePoint.lat(), drivePoint.lon(), request.regionId(), DRIVE_POINT_MESSAGE);
        } else {
            require(request.lat(), request.lon(), request.regionId(), PIN_MESSAGE);
        }
    }

    /**
     * Checks an update request against the stored location, before it is saved. Only the three triggers refuse;
     * every other edit returns without calling ORS.
     *
     * @param id      the location being updated
     * @param request the update
     * @throws NoRoadRouteException             if the update leaves the routing point with no road route
     * @throws IllegalArgumentException         if the drive point is partial, out of range, or set and cleared
     * @throws java.util.NoSuchElementException if no location has that id (HTTP 404)
     */
    public void requireRoutableOnUpdate(Long id, UpdateLocationRequest request) {
        DrivePoint requested = DrivePoint.parse(
                request.drivePointName(), request.drivePointLat(), request.drivePointLon());
        boolean clear = Boolean.TRUE.equals(request.clearDrivePoint());
        if (clear && requested != null) {
            throw new IllegalArgumentException("Clear the drive point or set it, not both");
        }
        if (requested == null && !clear && request.lat() == null && request.lon() == null) {
            return;
        }
        LocationEntity stored = locationService.findById(id);
        Long regionId = request.regionId() != null ? request.regionId()
                : (stored.getRegion() == null ? null : stored.getRegion().getId());

        if (requested != null) {
            boolean unchanged = stored.hasDrivePoint()
                    && requested.equals(new DrivePoint(stored.getDrivePointName(), stored.getDrivePointLat(),
                            stored.getDrivePointLon()));
            if (!unchanged) {
                require(requested.lat(), requested.lon(), regionId, DRIVE_POINT_MESSAGE);
            }
            return;
        }
        double pinLat = request.lat() != null ? request.lat() : stored.getLat();
        double pinLon = request.lon() != null ? request.lon() : stored.getLon();
        boolean moved = Double.compare(pinLat, stored.getLat()) != 0 || Double.compare(pinLon, stored.getLon()) != 0;
        if (clear && stored.hasDrivePoint()) {
            // The pin becomes the routing point again, wherever this same request leaves it.
            require(pinLat, pinLon, regionId, PIN_MESSAGE);
        } else if (moved && !stored.hasDrivePoint()) {
            require(pinLat, pinLon, regionId, PIN_MESSAGE);
        }
        // A pin move behind a drive point leaves the routing point where it was; nothing to check.
    }

    private void require(double lat, double lon, Long regionId, String refusal) {
        if (check(lat, lon, regionId) == Routability.UNROUTABLE) {
            throw new NoRoadRouteException(refusal);
        }
    }

    /**
     * Asks ORS whether a point can be routed to. Never throws for an ORS problem.
     *
     * @param lat      the target latitude
     * @param lon      the target longitude
     * @param regionId the location's region, whose base is the source; null or baseless falls back to the nearest
     *                 base
     * @return the answer; {@link Routability#UNAVAILABLE} for anything short of a clear one
     */
    Routability check(double lat, double lon, Long regionId) {
        Optional<RegionEntity> source = sourceFor(lat, lon, regionId);
        if (source.isEmpty()) {
            return Routability.UNAVAILABLE;
        }
        RegionEntity base = source.get();
        List<double[]> destinations = List.of(CONTROL_DESTINATION, new double[] {lat, lon});
        Optional<RouteProbe> probe;
        try {
            probe = orsClient.probe(base.getBaseLat(), base.getBaseLon(), destinations, PERMIT_TIMEOUT);
        } catch (OrsUnroutablePointException e) {
            // 6010 names the coordinate it could not place: only the target (index 2) is an answer. The source or
            // the control being unplaceable says nothing about the target.
            return e.getCoordinateIndex() == TARGET_COORDINATE_INDEX
                    ? Routability.UNROUTABLE : Routability.UNAVAILABLE;
        } catch (RuntimeException e) {
            LOG.warn("Routability check could not reach ORS: {}", LogSanitizer.sanitize(e.getMessage()));
            return Routability.UNAVAILABLE;
        }
        if (probe.isEmpty() || probe.get().durations().size() < destinations.size()) {
            return Routability.UNAVAILABLE;
        }
        RouteProbe result = probe.get();
        boolean controlRouted = result.durations().get(0) != null && result.snapped().get(0);
        if (!controlRouted) {
            // An all-null row: the source is bad, so nothing was learned about the target.
            return Routability.UNAVAILABLE;
        }
        boolean targetRouted = result.durations().get(1) != null && result.snapped().get(1);
        return targetRouted ? Routability.ROUTABLE : Routability.UNROUTABLE;
    }

    /**
     * The base to route from: the location's own region's when it has one, else the nearest base of any region.
     */
    private Optional<RegionEntity> sourceFor(double lat, double lon, Long regionId) {
        if (regionId != null) {
            Optional<RegionEntity> own = regionRepository.findById(regionId)
                    .filter(RegionDriveDurationService::hasBase);
            if (own.isPresent()) {
                return own;
            }
        }
        return regionRepository.findAll().stream()
                .filter(RegionDriveDurationService::hasBase)
                .min(Comparator.comparingDouble(r -> GeoUtils.distanceKm(lat, lon, r.getBaseLat(), r.getBaseLon())));
    }
}
