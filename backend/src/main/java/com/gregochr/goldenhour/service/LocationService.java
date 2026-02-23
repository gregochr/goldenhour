package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.ForecastProperties;
import com.gregochr.goldenhour.entity.GoldenHourType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.repository.LocationRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

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
     * Seeds the {@code locations} table from {@code application.yml} on startup.
     *
     * <p>Any location already present (matched by name) is skipped. New locations are
     * inserted with the current UTC timestamp as {@code created_at}.
     */
    @PostConstruct
    public void seedFromProperties() {
        int seeded = 0;
        for (ForecastProperties.Location loc : forecastProperties.getLocations()) {
            if (!locationRepository.existsByName(loc.getName())) {
                locationRepository.save(LocationEntity.builder()
                        .name(loc.getName())
                        .lat(loc.getLat())
                        .lon(loc.getLon())
                        .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                        .build());
                seeded++;
            }
        }
        LOG.info("Seeded {} location(s) from config", seeded);
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
     * @param location the location to check
     * @return {@code true} for any {@code TideType} other than {@code NOT_COASTAL}
     */
    public boolean isCoastal(LocationEntity location) {
        return location.getTideType() != TideType.NOT_COASTAL;
    }
}
