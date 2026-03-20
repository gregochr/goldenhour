package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.OpenRouteServiceClient;
import com.gregochr.goldenhour.config.OrsProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes and persists drive durations from a given source location to all
 * non-Home locations via the OpenRouteService matrix API.
 *
 * <p>Drive times are stored on {@link LocationEntity#getDriveDurationMinutes()} for
 * use in the map drive-time filter. The special "Home" location (case-insensitive)
 * is excluded from destination computation.
 */
@Service
public class DriveDurationService {

    private static final Logger LOG = LoggerFactory.getLogger(DriveDurationService.class);

    private final OpenRouteServiceClient orsClient;
    private final OrsProperties orsProperties;
    private final LocationRepository locationRepository;

    /**
     * Constructs a {@code DriveDurationService}.
     *
     * @param orsClient          client for the ORS matrix API
     * @param orsProperties      ORS configuration (API key, enabled flag)
     * @param locationRepository JPA repository for locations
     */
    public DriveDurationService(OpenRouteServiceClient orsClient, OrsProperties orsProperties,
            LocationRepository locationRepository) {
        this.orsClient = orsClient;
        this.orsProperties = orsProperties;
        this.locationRepository = locationRepository;
    }

    /**
     * Computes drive times from the given source point to all eligible locations,
     * persists the results, and returns a name → minutes map.
     *
     * <p>Locations named "Home" (case-insensitive) are excluded from the computation
     * and their {@code driveDurationMinutes} is set to 0.
     *
     * @param sourceLat source latitude (e.g. from browser geolocation)
     * @param sourceLon source longitude
     * @return map of location name to drive duration in minutes; empty if ORS is not configured
     */
    public Map<String, Integer> refreshDriveTimes(double sourceLat, double sourceLon) {
        if (!orsProperties.isConfigured()) {
            LOG.warn("ORS not configured — skipping drive time refresh");
            return Map.of();
        }

        List<LocationEntity> allLocations = locationRepository.findAll();

        // "Home" location gets 0 minutes; exclude from ORS destinations
        List<LocationEntity> destinations = allLocations.stream()
                .filter(loc -> !"home".equalsIgnoreCase(loc.getName()))
                .toList();

        if (destinations.isEmpty()) {
            return Map.of();
        }

        List<double[]> coords = destinations.stream()
                .map(loc -> new double[]{loc.getLat(), loc.getLon()})
                .toList();

        LOG.info("Refreshing drive times from ({}, {}) to {} locations via ORS",
                sourceLat, sourceLon, destinations.size());

        List<Double> durations = orsClient.fetchDurations(sourceLat, sourceLon, coords);

        Map<String, Integer> result = new LinkedHashMap<>();

        for (int i = 0; i < Math.min(destinations.size(), durations.size()); i++) {
            Double seconds = durations.get(i);
            LocationEntity loc = destinations.get(i);
            Integer minutes = seconds != null ? (int) Math.round(seconds / 60.0) : null;
            loc.setDriveDurationMinutes(minutes);
            result.put(loc.getName(), minutes);
        }

        // Persist and log
        locationRepository.saveAll(destinations);
        LOG.info("Drive time refresh complete — {} locations updated", result.size());

        // Add Home locations with 0
        allLocations.stream()
                .filter(loc -> "home".equalsIgnoreCase(loc.getName()))
                .forEach(loc -> {
                    loc.setDriveDurationMinutes(0);
                    locationRepository.save(loc);
                    result.put(loc.getName(), 0);
                });

        return result;
    }
}
