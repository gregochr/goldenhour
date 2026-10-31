package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.SolarEventType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.AddLocationRequest;
import com.gregochr.goldenhour.model.UpdateLocationRequest;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.util.GeoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages the persisted set of forecast locations.
 *
 * <p>Locations are managed exclusively via the REST API. The database is the sole
 * source of truth for location metadata. Disabled locations are excluded from
 * forecast runs.
 */
@Service
public class LocationService {

    private static final Logger LOG = LoggerFactory.getLogger(LocationService.class);

    /** Lowest accepted elevation or valley-floor height in metres. */
    private static final int MIN_HEIGHT_M = -50;

    /** Highest accepted elevation or valley-floor height in metres. */
    private static final int MAX_HEIGHT_M = 1500;

    /** The valley floor must lie within this distance of the location, in kilometres. */
    private static final double MAX_FLOOR_DISTANCE_KM = 10.0;

    /**
     * Furthest a tide point may sit from the location's pin. A tide point is a neighbouring coastal point a few
     * kilometres away that WorldTides models better than the pin's own cell; 25 km is generous for that and
     * rejects a mistyped coordinate or a point on another stretch of coast.
     */
    private static final double MAX_TIDE_POINT_DISTANCE_KM = 25.0;

    private static final double MIN_LAT = -90.0;
    private static final double MAX_LAT = 90.0;
    private static final double MIN_LON = -180.0;
    private static final double MAX_LON = 180.0;

    private final LocationRepository locationRepository;
    private final RegionRepository regionRepository;
    private final TideService tideService;
    private final LocationEnrichmentService locationEnrichmentService;
    private final NameNamespaceGuard nameGuard;
    private final SubRegionMembershipService subRegionMembership;
    private final ServedBriefingEpoch servedEpoch;

    /**
     * Constructs a {@code LocationService}.
     *
     * @param locationRepository repository for {@link LocationEntity}
     * @param regionRepository   repository for {@link RegionEntity}
     * @param tideService        used to fetch tide extremes for coastal locations
     * @param locationEnrichmentService supplies the Open-Meteo snapped grid cell when a location
     *                                  is saved without one, or is moved
     * @param nameGuard          the shared name rules: a location name may not shadow a named area or an alias
     * @param subRegionMembership the only writer of which sub-region a location belongs to
     * @param servedEpoch bumped after commit on every location save, enable and failure reset
     */
    public LocationService(LocationRepository locationRepository,
                           RegionRepository regionRepository,
                           TideService tideService,
                           LocationEnrichmentService locationEnrichmentService,
                           NameNamespaceGuard nameGuard,
                           SubRegionMembershipService subRegionMembership,
                           ServedBriefingEpoch servedEpoch) {
        this.nameGuard = nameGuard;
        this.subRegionMembership = subRegionMembership;
        this.servedEpoch = servedEpoch;
        this.locationRepository = locationRepository;
        this.regionRepository = regionRepository;
        this.tideService = tideService;
        this.locationEnrichmentService = locationEnrichmentService;
    }

    /**
     * Returns all persisted locations ordered alphabetically by name.
     *
     * @return list of location entities
     */
    public List<LocationEntity> findAll() {
        return locationRepository.findAllByOrderByNameAsc();
    }

    /**
     * Returns all enabled locations ordered alphabetically by name.
     *
     * @return list of enabled location entities
     */
    public List<LocationEntity> findAllEnabled() {
        return locationRepository.findAllByEnabledTrueOrderByNameAsc();
    }

    /**
     * Returns a location by its exact name.
     *
     * @param name the location name to look up
     * @return the matching {@link LocationEntity}
     * @throws java.util.NoSuchElementException if no location with that name exists
     */
    public LocationEntity findByName(String name) {
        return locationRepository.findByName(name)
                .orElseThrow(() -> new java.util.NoSuchElementException("No location named '" + name + "'"));
    }

    /**
     * Returns a location by its database ID.
     *
     * @param id the location primary key
     * @return the matching {@link LocationEntity}
     * @throws java.util.NoSuchElementException if no location with that ID exists
     */
    public LocationEntity findById(Long id) {
        return locationRepository.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("No location with id " + id));
    }

    /**
     * Adds a new location and persists it to the database.
     *
     * @param request the location details including name, coordinates, and metadata
     * @return the saved {@link LocationEntity}
     * @throws IllegalArgumentException if name is blank, lat/lon are out of range,
     *                                  or a location with the same name already exists
     */
    @Transactional
    public LocationEntity add(AddLocationRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("Location name must not be blank");
        }
        if (request.lat() < MIN_LAT || request.lat() > MAX_LAT) {
            throw new IllegalArgumentException("Latitude must be between -90 and 90");
        }
        if (request.lon() < MIN_LON || request.lon() > MAX_LON) {
            throw new IllegalArgumentException("Longitude must be between -180 and 180");
        }
        if (locationRepository.existsByName(request.name())) {
            throw new IllegalArgumentException("A location named '" + request.name() + "' already exists");
        }
        nameGuard.locationNameClash(request.name().trim(), null).ifPresent(clash -> {
            throw new IllegalArgumentException(clash);
        });

        Set<SolarEventType> solarEventTypes = request.solarEventTypes() != null
                && !request.solarEventTypes().isEmpty()
                ? new HashSet<>(request.solarEventTypes())
                : new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET));
        Set<LocationType> locationTypes = request.locationTypes() != null
                && !request.locationTypes().isEmpty()
                ? new HashSet<>(request.locationTypes())
                : new HashSet<>(Set.of(LocationType.LANDSCAPE));
        Set<TideType> tideTypes = request.tideTypes() != null
                ? new HashSet<>(request.tideTypes()) : new HashSet<>();

        // Force empty set when not SEASCAPE. Tested with contains() rather than equality:
        // a site can be coastal AND something else (a wooded clifftop is [SEASCAPE, WOODLAND]),
        // and an equality test would strip the tides off every such location.
        if (!locationTypes.contains(LocationType.SEASCAPE)) {
            tideTypes = new HashSet<>();
        }

        RegionEntity region = resolveRegion(request.regionId());
        if (request.subRegionId() != null) {
            // Refuse a sub-region of another region BEFORE the location exists, so nothing is left half-made.
            subRegionMembership.requireAssignable(request.subRegionId(), region);
        }

        LocationEntity entity = LocationEntity.builder()
                .name(request.name())
                .lat(request.lat())
                .lon(request.lon())
                .solarEventType(solarEventTypes)
                .locationType(locationTypes)
                .tideType(tideTypes)
                .region(region)
                .bortleClass(request.bortleClass())
                .skyBrightnessSqm(request.skyBrightnessSqm())
                .elevationMetres(request.elevationMetres())
                .gridLat(request.gridLat())
                .gridLng(request.gridLng())
                .overlooksWater(request.overlooksWater() != null && request.overlooksWater())
                .mistProne(request.mistProne() != null && request.mistProne())
                .coastalTidal(request.coastalTidal() != null && request.coastalTidal())
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
        LocationEntity saved = locationRepository.save(entity);
        servedEpoch.bump();
        if (request.subRegionId() != null) {
            subRegionMembership.assign(saved, request.subRegionId());
        }

        if (isCoastal(saved) && !tideService.hasStoredExtremes(saved.getId())) {
            tideService.fetchAndStoreTideExtremes(saved);
        }

        LOG.info("Added location '{}' ({}, {}) — type={}, solar={}, tide={}",
                saved.getName(), saved.getLat(), saved.getLon(),
                locationTypes, solarEventTypes, tideTypes);
        return saved;
    }

    /**
     * Updates metadata for an existing location.
     *
     * <p>If the location type changes to SEASCAPE,
     * tide data is fetched if not already stored.
     *
     * @param id      the location primary key
     * @param request the updated metadata
     * @return the updated {@link LocationEntity}
     * @throws java.util.NoSuchElementException if no location with that ID exists
     */
    @Transactional
    public LocationEntity update(Long id, UpdateLocationRequest request) {
        LocationEntity location = findById(id);
        // Read BEFORE the setters below run, or the comparison is against the new value and every
        // coordinate edit looks like a no-op.
        double previousLat = location.getLat();
        double previousLon = location.getLon();
        LocationEntity.TidePoint previousTidePoint = location.getEffectiveTidePoint();

        // Handle name change — no cascade needed, forecast_evaluation
        // references location by ID, so renaming is a single-row update.
        if (request.name() != null && !request.name().isBlank()
                && !request.name().equals(location.getName())) {
            String newName = request.name().trim();
            if (locationRepository.existsByName(newName)) {
                throw new IllegalArgumentException(
                        "A location named '" + newName + "' already exists.");
            }
            nameGuard.locationNameClash(newName, location.getId()).ifPresent(clash -> {
                throw new IllegalArgumentException(clash);
            });
            String oldName = location.getName();
            location.setName(newName);
            LOG.info("Renamed location '{}' → '{}'", oldName, newName);
        }

        if (request.lat() != null) {
            if (request.lat() < MIN_LAT || request.lat() > MAX_LAT) {
                throw new IllegalArgumentException("Latitude must be between -90 and 90");
            }
            location.setLat(request.lat());
        }
        if (request.lon() != null) {
            if (request.lon() < MIN_LON || request.lon() > MAX_LON) {
                throw new IllegalArgumentException("Longitude must be between -180 and 180");
            }
            location.setLon(request.lon());
        }

        if (request.solarEventTypes() != null) {
            location.setSolarEventType(new HashSet<>(request.solarEventTypes()));
        }

        if (request.locationTypes() != null) {
            if (request.locationTypes().isEmpty()) {
                // Reject rather than ignore. Silently keeping the old types would hide a UI that
                // had let every chip be deselected, and silently writing an empty set would drop
                // the location out of every evaluation lane at once — invisible until nothing
                // was ever forecast for it again.
                throw new IllegalArgumentException("A location must have at least one type");
            }
            location.setLocationType(new HashSet<>(request.locationTypes()));
        }

        if (request.tideTypes() != null) {
            // Force empty set when not SEASCAPE
            if (!location.getLocationType().contains(LocationType.SEASCAPE)) {
                location.setTideType(new HashSet<>());
            } else {
                location.setTideType(new HashSet<>(request.tideTypes()));
            }
        }

        // A type change ALONE must still enforce "no tides unless SEASCAPE". The block above only
        // runs when the caller supplied tideTypes, so a partial update that drops SEASCAPE without
        // mentioning tides used to leave them behind — and isCoastal() is defined as a non-empty
        // tide set, so the now-inland location stayed coastal forever: billed WorldTides fetches,
        // and BriefingSlotBuilder overriding its verdict to STANDDOWN on a tide alignment it can
        // never satisfy. Scoped to requests that actually changed the types, because running it
        // unconditionally would blank any legacy non-SEASCAPE row carrying tide preferences on an
        // edit that never touched types.
        if (request.locationTypes() != null
                && !location.getLocationType().contains(LocationType.SEASCAPE)) {
            location.setTideType(new HashSet<>());
        }

        boolean regionChanged = false;
        if (request.regionId() != null) {
            RegionEntity newRegion = resolveRegion(request.regionId());
            regionChanged = location.getRegion() == null
                    || !location.getRegion().getId().equals(newRegion.getId());
            location.setRegion(newRegion);
        }
        if (request.subRegionId() != null) {
            // Checked against the region as this same request leaves it, and before the save.
            subRegionMembership.requireAssignable(request.subRegionId(), location.getRegion());
        }

        if (request.mistProne() != null) {
            if (request.mistProne() != location.isMistProne()) {
                LOG.info("Location '{}' mist_prone {} → {}", location.getName(),
                        location.isMistProne(), request.mistProne());
            }
            location.setMistProne(request.mistProne());
        }

        applyHeights(location, request);
        applyTidePoint(location, request);

        boolean coordinatesMoved = Double.compare(previousLat, location.getLat()) != 0
                || Double.compare(previousLon, location.getLon()) != 0;
        refreshGridCell(location, coordinatesMoved);

        LocationEntity saved = locationRepository.save(location);
        servedEpoch.bump();

        // A region change strands a membership in a sub-region of the old region: drop it (and say so), then
        // honour a sub-region this same request names.
        if (regionChanged) {
            subRegionMembership.reconcileRegion(saved);
        }
        if (request.subRegionId() != null) {
            subRegionMembership.assign(saved, request.subRegionId());
        }

        if (isCoastal(saved) && !tideService.hasStoredExtremes(saved.getId())) {
            tideService.fetchAndStoreTideExtremes(saved);
        } else if (isCoastal(saved) && !previousTidePoint.equals(saved.getEffectiveTidePoint())) {
            refetchTidesAfterCommit(saved);
        }

        LOG.info("Updated location '{}' — type={}, solar={}, tide={}",
                saved.getName(), saved.getLocationType(), saved.getSolarEventType(),
                saved.getTideType());
        return saved;
    }

    /**
     * Applies the viewpoint elevation and valley-floor fields of an update (inversion v2 Phase 1-pre).
     *
     * <p>All null means "don't change". Runs after the coordinates are applied, so the distance check uses
     * the coordinates as this same request leaves them. A coordinate change touches neither height.
     * Every failure is an {@link IllegalArgumentException} (HTTP 400).
     *
     * @param location the location being updated, mutated in place
     * @param request  the update request
     */
    private void applyHeights(LocationEntity location, UpdateLocationRequest request) {
        if (request.elevationMetres() != null) {
            requireHeight(request.elevationMetres(), "Elevation");
            location.setElevationMetres(request.elevationMetres());
        }

        Integer floorMetres = request.valleyFloorMetres();
        Double floorLat = request.valleyFloorLat();
        Double floorLng = request.valleyFloorLng();
        int present = (floorMetres != null ? 1 : 0) + (floorLat != null ? 1 : 0) + (floorLng != null ? 1 : 0);
        if (Boolean.TRUE.equals(request.clearValleyFloor())) {
            if (present > 0) {
                throw new IllegalArgumentException("Clear the valley floor or set it, not both");
            }
            location.setValleyFloorMetres(null);
            location.setValleyFloorLat(null);
            location.setValleyFloorLng(null);
            return;
        }
        if (present == 0) {
            return;
        }
        if (floorMetres == null || floorLat == null || floorLng == null) {
            throw new IllegalArgumentException(
                    "A valley floor needs its height, latitude and longitude together");
        }
        boolean differs = !floorMetres.equals(location.getValleyFloorMetres())
                || !floorLat.equals(location.getValleyFloorLat())
                || !floorLng.equals(location.getValleyFloorLng());
        if (differs) {
            requireHeight(floorMetres, "Valley floor height");
            if (floorLat < MIN_LAT || floorLat > MAX_LAT || floorLng < MIN_LON || floorLng > MAX_LON) {
                throw new IllegalArgumentException("Valley floor coordinates are out of range");
            }
            double distanceKm = GeoUtils.distanceKm(location.getLat(), location.getLon(), floorLat, floorLng);
            if (distanceKm > MAX_FLOOR_DISTANCE_KM) {
                throw new IllegalArgumentException(
                        "The valley floor must be within 10 km of the location");
            }
        }
        location.setValleyFloorMetres(floorMetres);
        location.setValleyFloorLat(floorLat);
        location.setValleyFloorLng(floorLng);
    }

    /**
     * Applies the tide point of an update (tide-source-integrity-plan P1b), mirroring the valley-floor triple.
     *
     * <p>Both coordinates or neither; {@code clearTidePoint} alone nulls both and is rejected alongside either
     * value; all null means "don't change". Each coordinate is range-checked, and the point must lie within
     * {@value #MAX_TIDE_POINT_DISTANCE_KM} km of the pin as this same request leaves it. Every failure is an
     * {@link IllegalArgumentException} (HTTP 400).
     *
     * @param location the location being updated, mutated in place
     * @param request  the update request
     */
    private void applyTidePoint(LocationEntity location, UpdateLocationRequest request) {
        Double tideLat = request.tideLat();
        Double tideLng = request.tideLng();
        int present = (tideLat != null ? 1 : 0) + (tideLng != null ? 1 : 0);
        if (Boolean.TRUE.equals(request.clearTidePoint())) {
            if (present > 0) {
                throw new IllegalArgumentException("Clear the tide point or set it, not both");
            }
            location.setTideLat(null);
            location.setTideLng(null);
            return;
        }
        if (present == 0) {
            return;
        }
        if (tideLat == null || tideLng == null) {
            throw new IllegalArgumentException("A tide point needs its latitude and longitude together");
        }
        boolean differs = !tideLat.equals(location.getTideLat()) || !tideLng.equals(location.getTideLng());
        if (differs) {
            if (tideLat < MIN_LAT || tideLat > MAX_LAT || tideLng < MIN_LON || tideLng > MAX_LON) {
                throw new IllegalArgumentException("Tide point coordinates are out of range");
            }
            double distanceKm = GeoUtils.distanceKm(location.getLat(), location.getLon(), tideLat, tideLng);
            if (distanceKm > MAX_TIDE_POINT_DISTANCE_KM) {
                throw new IllegalArgumentException("The tide point must be within 25 km of the location");
            }
        }
        location.setTideLat(tideLat);
        location.setTideLng(tideLng);
    }

    /**
     * Re-reads tides for a coastal location whose effective tide point has just changed, once the edit has
     * committed, so the new point takes effect without waiting for the weekly refresh.
     *
     * <p>Runs after commit and in a transaction of its own, so a WorldTides failure can never roll back the
     * admin's edit; it is logged and the weekly refresh retries. With no transaction in progress it runs at once.
     *
     * @param saved the saved location
     */
    private void refetchTidesAfterCommit(LocationEntity saved) {
        Runnable refetch = () -> {
            try {
                tideService.fetchAndStoreTideExtremesInNewTransaction(saved);
            } catch (RuntimeException e) {
                LOG.warn("Tide re-read after a tide point change failed for '{}'; the weekly refresh will retry: {}",
                        saved.getName(), e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    refetch.run();
                }
            });
        } else {
            refetch.run();
        }
    }

    private static void requireHeight(int metres, String what) {
        if (metres < MIN_HEIGHT_M || metres > MAX_HEIGHT_M) {
            throw new IllegalArgumentException(what + " must be between -50 and 1500 metres");
        }
    }

    /**
     * Brings the stored Open-Meteo grid cell into line with the location's coordinates.
     *
     * <p>Runs on two conditions, which are different problems with the same fix:
     * <ul>
     *   <li><b>The cell is absent.</b> Every location inserted by raw SQL arrives this way —
     *       V84's sixteen bluebell sites, V138's ten Heritage Coast entries, V143's Penshaw
     *       Monument. {@code gridLat}/{@code gridLng} are written only by {@link #addLocation},
     *       from the enrichment the admin Add-location form runs client-side, so a migration
     *       cannot populate them and nothing else ever did. Such a location is skipped by
     *       {@code GridCellStabilityService} and falls back to its own key in
     *       {@code BriefingService} instead of being deduplicated against neighbours in the same
     *       ~2 km cell.</li>
     *   <li><b>The coordinates moved.</b> Editing lat/lon used to leave the old cell in place,
     *       which is worse than the absent case: the location is then grouped, classified and
     *       deduplicated against a cell it is no longer in.</li>
     * </ul>
     *
     * <p>On a lookup failure the two cases part company, and the asymmetry is the point. If the
     * coordinates moved, the stored pair is now known-wrong and is CLEARED: absent means "excluded
     * from dedup and stability", which is degraded but honest, where stale means "confidently
     * grouped with a different place's weather". If the cell was merely absent to begin with,
     * nothing is lost by leaving it absent and the next save will try again.
     *
     * <p>Never fails the save. The grid cell is an optimisation — a coordinate correction must
     * still land when Open-Meteo is unreachable.
     *
     * @param location         the location being updated, mutated in place
     * @param coordinatesMoved whether this update changed lat or lon
     */
    private void refreshGridCell(LocationEntity location, boolean coordinatesMoved) {
        if (!coordinatesMoved && location.hasGridCell()) {
            return;
        }
        double[] cell = locationEnrichmentService.fetchGridCell(location.getLat(),
                location.getLon());
        if (cell != null) {
            location.setGridLat(cell[0]);
            location.setGridLng(cell[1]);
            LOG.info("Grid cell for '{}' set to {},{}", location.getName(), cell[0], cell[1]);
            return;
        }
        if (coordinatesMoved && location.hasGridCell()) {
            location.setGridLat(null);
            location.setGridLng(null);
            LOG.warn("Grid cell lookup failed for '{}' after a coordinate change — cleared the "
                    + "previous cell rather than leaving it pointing at the old position. It will "
                    + "be refetched on the next save.", location.getName());
            return;
        }
        LOG.warn("Grid cell lookup failed for '{}'; it remains without one.", location.getName());
    }

    /**
     * Toggles the enabled state of a location.
     *
     * <p>When re-enabling, also clears failure tracking fields.
     *
     * @param id      the location primary key
     * @param enabled the new enabled state
     * @return the updated {@link LocationEntity}
     * @throws java.util.NoSuchElementException if no location with that ID exists
     */
    @Transactional
    public LocationEntity setEnabled(Long id, boolean enabled) {
        LocationEntity saved = findById(id);
        // enabled and the failure columns are not updatable through the entity (see LocationEntity):
        // write them with scoped updates, and mirror that on the in-memory copy that is returned.
        locationRepository.updateEnabled(saved.getId(), enabled);
        servedEpoch.bump();
        saved.setEnabled(enabled);
        if (enabled) {
            locationRepository.clearFailureState(saved.getId());
            saved.setConsecutiveFailures(0);
            saved.setDisabledReason(null);
            saved.setLastFailureAt(null);
        }
        LOG.info("Location '{}' {}", saved.getName(), enabled ? "enabled" : "disabled");
        return saved;
    }

    /**
     * Returns {@code true} if this location is coastal and tide data should be fetched.
     *
     * <p>A location is coastal when its tide type set is non-empty.
     *
     * @param location the location to check
     * @return {@code true} if the tide type set contains at least one preference
     */
    public boolean isCoastal(LocationEntity location) {
        return !location.getTideType().isEmpty();
    }

    /**
     * Resets the consecutive failure counter, last-failure time and disabled reason for a
     * location — and re-enables it if it was auto-disabled.
     *
     * <p>This is what the admin Locations alert's "Re-enable" button calls. A place is treated as
     * auto-disabled when it carries a {@code disabledReason}: only the scheduled-cycle auto-disable
     * (see {@link LocationFailureService}) writes one, whereas an admin's own disable through
     * {@link #setEnabled} leaves it null. So a reason means "re-enable it" and the place goes back
     * on the roster ({@code enabled = true}); a place with no reason that is disabled was switched
     * off by an admin on purpose and stays disabled, with only its counters cleared.
     *
     * @param name the location name to reset
     * @return the updated {@link LocationEntity}
     * @throws java.util.NoSuchElementException if no location with that name exists
     */
    @Transactional
    public LocationEntity resetFailures(String name) {
        LocationEntity saved = findByName(name);
        // enabled and the failure columns are not updatable through the entity (see LocationEntity):
        // write them with scoped updates, and mirror that on the in-memory copy that is returned.
        if (saved.getDisabledReason() != null) {
            locationRepository.updateEnabled(saved.getId(), true);
            saved.setEnabled(true);
        }
        servedEpoch.bump();
        locationRepository.clearFailureState(saved.getId());
        saved.setConsecutiveFailures(0);
        saved.setDisabledReason(null);
        saved.setLastFailureAt(null);
        return saved;
    }

    /**
     * Resolves a region ID to a {@link RegionEntity}, or returns null if the ID is null.
     *
     * @param regionId the region primary key, or null
     * @return the matching region, or null
     * @throws java.util.NoSuchElementException if the ID is non-null but no region exists
     */
    private RegionEntity resolveRegion(Long regionId) {
        if (regionId == null) {
            return null;
        }
        return regionRepository.findById(regionId)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "No region with id " + regionId));
    }
}
