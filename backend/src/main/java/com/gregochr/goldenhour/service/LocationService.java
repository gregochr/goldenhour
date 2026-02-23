package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.ForecastProperties;
import com.gregochr.goldenhour.entity.GoldenHourType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.repository.LocationRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Manages the persisted set of forecast locations.
 *
 * <p>On startup, locations defined in {@code application.yml} are seeded into the
 * {@code locations} table if they are not already present. Locations added at runtime
 * via {@link #add} persist across restarts.
 */
@Service
public class LocationService {

    private static final Logger LOG = LoggerFactory.getLogger(LocationService.class);

    private static final double MIN_LAT = -90.0;
    private static final double MAX_LAT = 90.0;
    private static final double MIN_LON = -180.0;
    private static final double MAX_LON = 180.0;

    private final LocationRepository locationRepository;
    private final ForecastProperties forecastProperties;

    /**
     * Constructs a {@code LocationService}.
     *
     * @param locationRepository repository for {@link LocationEntity}
     * @param forecastProperties YAML-configured seed locations
     */
    public LocationService(LocationRepository locationRepository,
            ForecastProperties forecastProperties) {
        this.locationRepository = locationRepository;
        this.forecastProperties = forecastProperties;
    }

    /**
     * Seeds and synchronises the {@code locations} table from {@code application.yml} on startup.
     *
     * <p>New locations are inserted. Existing locations (matched by name) have their
     * {@code goldenHourType}, {@code tideType}, and {@code locationType} updated to
     * match the current YAML config — so the YAML remains the source of truth for metadata.
     * Coordinates are not updated for existing rows to preserve any manual corrections.
     */
    @PostConstruct
    public void seedFromProperties() {
        int seeded = 0;
        int updated = 0;
        for (ForecastProperties.Location loc : forecastProperties.getLocations()) {
            Optional<LocationEntity> existing = locationRepository.findByName(loc.getName());
            if (existing.isEmpty()) {
                locationRepository.save(LocationEntity.builder()
                        .name(loc.getName())
                        .lat(loc.getLat())
                        .lon(loc.getLon())
                        .goldenHourType(loc.getGoldenHourType())
                        .tideType(loc.getTideType())
                        .locationType(loc.getLocationType())
                        .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                        .build());
                seeded++;
            } else {
                LocationEntity entity = existing.get();
                boolean changed = !entity.getGoldenHourType().equals(loc.getGoldenHourType())
                        || !entity.getTideType().equals(loc.getTideType())
                        || !entity.getLocationType().equals(loc.getLocationType());
                if (changed) {
                    entity.setGoldenHourType(loc.getGoldenHourType());
                    entity.setTideType(loc.getTideType());
                    entity.setLocationType(loc.getLocationType());
                    locationRepository.save(entity);
                    updated++;
                }
            }
        }
        LOG.info("Seeded {} location(s), updated metadata for {} location(s) from config", seeded, updated);
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
     * Adds a new location and persists it to the database.
     *
     * @param name human-readable identifier (e.g. "Durham UK")
     * @param lat  latitude in decimal degrees (−90 to 90)
     * @param lon  longitude in decimal degrees (−180 to 180)
     * @return the saved {@link LocationEntity}
     * @throws IllegalArgumentException if name is blank, lat/lon are out of range,
     *                                  or a location with the same name already exists
     */
    public LocationEntity add(String name, double lat, double lon) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Location name must not be blank");
        }
        if (lat < MIN_LAT || lat > MAX_LAT) {
            throw new IllegalArgumentException("Latitude must be between -90 and 90");
        }
        if (lon < MIN_LON || lon > MAX_LON) {
            throw new IllegalArgumentException("Longitude must be between -180 and 180");
        }
        if (locationRepository.existsByName(name)) {
            throw new IllegalArgumentException("A location named '" + name + "' already exists");
        }
        LocationEntity entity = LocationEntity.builder()
                .name(name)
                .lat(lat)
                .lon(lon)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
        return locationRepository.save(entity);
    }

    /**
     * Returns {@code true} if a sunrise evaluation should be run for this location.
     *
     * @param location the location to check
     * @return {@code true} for {@code SUNRISE}, {@code BOTH_TIMES}, and {@code ANYTIME}
     */
    public boolean shouldEvaluateSunrise(LocationEntity location) {
        GoldenHourType type = location.getGoldenHourType();
        return type == GoldenHourType.SUNRISE
                || type == GoldenHourType.BOTH_TIMES
                || type == GoldenHourType.ANYTIME;
    }

    /**
     * Returns {@code true} if a sunset evaluation should be run for this location.
     *
     * @param location the location to check
     * @return {@code true} for {@code SUNSET}, {@code BOTH_TIMES}, and {@code ANYTIME}
     */
    public boolean shouldEvaluateSunset(LocationEntity location) {
        GoldenHourType type = location.getGoldenHourType();
        return type == GoldenHourType.SUNSET
                || type == GoldenHourType.BOTH_TIMES
                || type == GoldenHourType.ANYTIME;
    }

    /**
     * Returns {@code true} if this location is coastal and tide data should be fetched.
     *
     * <p>A location is coastal when its tide type set contains at least one value other
     * than {@link TideType#NOT_COASTAL}. An empty set means the location is inland.
     *
     * @param location the location to check
     * @return {@code true} if the tide type set contains any coastal preference
     */
    public boolean isCoastal(LocationEntity location) {
        return location.getTideType().stream().anyMatch(t -> t != TideType.NOT_COASTAL);
    }

    /**
     * Returns {@code true} if this location has the {@link LocationType#SEASCAPE} tag.
     *
     * <p>Seascape locations are coastal spots where tide state is directly relevant to
     * photographic composition — rock pools, foreshore textures, reflections, etc.
     *
     * @param location the location to check
     * @return {@code true} if the location's type set contains {@code SEASCAPE}
     */
    public boolean isSeascape(LocationEntity location) {
        return location.getLocationType().contains(LocationType.SEASCAPE);
    }
}
