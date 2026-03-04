package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.GoldenHourType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.AddLocationRequest;
import com.gregochr.goldenhour.model.UpdateLocationRequest;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private static final double MIN_LAT = -90.0;
    private static final double MAX_LAT = 90.0;
    private static final double MIN_LON = -180.0;
    private static final double MAX_LON = 180.0;

    private final LocationRepository locationRepository;
    private final RegionRepository regionRepository;
    private final TideService tideService;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Constructs a {@code LocationService}.
     *
     * @param locationRepository repository for {@link LocationEntity}
     * @param regionRepository   repository for {@link RegionEntity}
     * @param tideService        used to fetch tide extremes for coastal locations
     * @param jdbcTemplate       used for cascading name updates across FK tables
     */
    public LocationService(LocationRepository locationRepository,
                           RegionRepository regionRepository,
                           TideService tideService,
                           JdbcTemplate jdbcTemplate) {
        this.locationRepository = locationRepository;
        this.regionRepository = regionRepository;
        this.tideService = tideService;
        this.jdbcTemplate = jdbcTemplate;
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

        GoldenHourType goldenHourType = request.goldenHourType() != null
                ? request.goldenHourType() : GoldenHourType.BOTH_TIMES;
        LocationType locationType = request.locationType() != null
                ? request.locationType() : LocationType.LANDSCAPE;
        Set<TideType> tideTypes = request.tideTypes() != null
                ? new HashSet<>(request.tideTypes()) : new HashSet<>();

        // Force empty set when not SEASCAPE
        if (locationType != LocationType.SEASCAPE) {
            tideTypes = new HashSet<>();
        }

        RegionEntity region = resolveRegion(request.regionId());

        LocationEntity entity = LocationEntity.builder()
                .name(request.name())
                .lat(request.lat())
                .lon(request.lon())
                .goldenHourType(goldenHourType)
                .locationType(new HashSet<>(Set.of(locationType)))
                .tideType(tideTypes)
                .region(region)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
        LocationEntity saved = locationRepository.save(entity);

        if (isCoastal(saved) && !tideService.hasStoredExtremes(saved.getId())) {
            tideService.fetchAndStoreTideExtremes(saved);
        }

        LOG.info("Added location '{}' ({}, {}) — type={}, solar={}, tide={}",
                saved.getName(), saved.getLat(), saved.getLon(),
                locationType, goldenHourType, tideTypes);
        return saved;
    }

    /**
     * Updates metadata for an existing location.
     *
     * <p>Name and coordinates are immutable. If the location type changes to SEASCAPE,
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

        // Handle name change — cascade to FK tables
        if (request.name() != null && !request.name().isBlank()
                && !request.name().equals(location.getName())) {
            String newName = request.name().trim();
            if (locationRepository.existsByName(newName)) {
                throw new IllegalArgumentException(
                        "A location named '" + newName + "' already exists.");
            }
            String oldName = location.getName();
            jdbcTemplate.update(
                    "UPDATE forecast_evaluation SET location_name = ? WHERE location_name = ?",
                    newName, oldName);
            jdbcTemplate.update(
                    "UPDATE actual_outcome SET location_name = ? WHERE location_name = ?",
                    newName, oldName);
            location.setName(newName);
            LOG.info("Renamed location '{}' → '{}'", oldName, newName);
        }

        if (request.goldenHourType() != null) {
            location.setGoldenHourType(request.goldenHourType());
        }

        if (request.locationType() != null) {
            location.setLocationType(new HashSet<>(Set.of(request.locationType())));
        }

        if (request.tideTypes() != null) {
            // Force empty set when not SEASCAPE
            if (!location.getLocationType().contains(LocationType.SEASCAPE)) {
                location.setTideType(new HashSet<>());
            } else {
                location.setTideType(new HashSet<>(request.tideTypes()));
            }
        }

        if (request.regionId() != null) {
            location.setRegion(resolveRegion(request.regionId()));
        }

        LocationEntity saved = locationRepository.save(location);

        if (isCoastal(saved) && !tideService.hasStoredExtremes(saved.getId())) {
            tideService.fetchAndStoreTideExtremes(saved);
        }

        LOG.info("Updated location '{}' — type={}, solar={}, tide={}",
                saved.getName(), saved.getLocationType(), saved.getGoldenHourType(),
                saved.getTideType());
        return saved;
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
    public LocationEntity setEnabled(Long id, boolean enabled) {
        LocationEntity location = findById(id);
        location.setEnabled(enabled);
        if (enabled) {
            location.setConsecutiveFailures(0);
            location.setDisabledReason(null);
            location.setLastFailureAt(null);
        }
        LocationEntity saved = locationRepository.save(location);
        LOG.info("Location '{}' {}", saved.getName(), enabled ? "enabled" : "disabled");
        return saved;
    }

    /**
     * Returns {@code true} if a sunrise evaluation should be run for this location.
     *
     * <p>Always returns {@code true} — {@code goldenHourType} is photographer preference
     * metadata, not an evaluation filter. Both sunrise and sunset are always evaluated so
     * photographers can visit any location at either time.
     *
     * @param location the location to check
     * @return always {@code true}
     */
    public boolean shouldEvaluateSunrise(LocationEntity location) {
        return true;
    }

    /**
     * Returns {@code true} if a sunset evaluation should be run for this location.
     *
     * <p>Always returns {@code true} — {@code goldenHourType} is photographer preference
     * metadata, not an evaluation filter. Both sunrise and sunset are always evaluated so
     * photographers can visit any location at either time.
     *
     * @param location the location to check
     * @return always {@code true}
     */
    public boolean shouldEvaluateSunset(LocationEntity location) {
        return true;
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
     * Returns {@code true} if this location has the {@link LocationType#SEASCAPE} tag.
     *
     * @param location the location to check
     * @return {@code true} if the location's type set contains {@code SEASCAPE}
     */
    public boolean isSeascape(LocationEntity location) {
        return location.getLocationType().contains(LocationType.SEASCAPE);
    }

    /**
     * Resets the consecutive failure counter and disabled reason for a location.
     *
     * @param name the location name to reset
     * @return the updated {@link LocationEntity}
     * @throws java.util.NoSuchElementException if no location with that name exists
     */
    public LocationEntity resetFailures(String name) {
        LocationEntity location = findByName(name);
        location.setConsecutiveFailures(0);
        location.setDisabledReason(null);
        location.setLastFailureAt(null);
        return locationRepository.save(location);
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
