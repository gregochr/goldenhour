package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.OpenRouteServiceClient;
import com.gregochr.goldenhour.config.OrsProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.UserDriveTimeEntity;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.UserDriveTimeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * Computes per-user drive durations from the user's home location to all forecast
 * locations via the OpenRouteService matrix API.
 *
 * <p>Results are stored in the {@code user_drive_time} table, keyed by user + location.
 * A semaphore limits concurrent ORS calls to avoid rate-limiting.
 */
@Service
public class DriveDurationService {

    private static final Logger LOG = LoggerFactory.getLogger(DriveDurationService.class);

    /** Limits concurrent ORS matrix calls across all users. */
    private static final Semaphore ORS_SEMAPHORE = new Semaphore(2);

    private final OpenRouteServiceClient orsClient;
    private final OrsProperties orsProperties;
    private final LocationRepository locationRepository;
    private final UserDriveTimeRepository userDriveTimeRepository;

    /**
     * Constructs a {@code DriveDurationService}.
     *
     * @param orsClient              client for the ORS matrix API
     * @param orsProperties          ORS configuration (API key, enabled flag)
     * @param locationRepository     JPA repository for locations
     * @param userDriveTimeRepository JPA repository for per-user drive times
     */
    public DriveDurationService(OpenRouteServiceClient orsClient, OrsProperties orsProperties,
            LocationRepository locationRepository,
            UserDriveTimeRepository userDriveTimeRepository) {
        this.orsClient = orsClient;
        this.orsProperties = orsProperties;
        this.locationRepository = locationRepository;
        this.userDriveTimeRepository = userDriveTimeRepository;
    }

    /**
     * Calculates drive times from the given origin to all locations, storing
     * results in the {@code user_drive_time} table.
     *
     * <p>Existing drive times for this user are deleted and replaced. A semaphore
     * limits concurrent ORS calls to 2.
     *
     * @param userId    the user's primary key
     * @param originLat the origin latitude (user's home)
     * @param originLon the origin longitude (user's home)
     * @return the number of locations with valid drive times
     */
    @Transactional
    public int refreshForUser(Long userId, double originLat, double originLon) {
        if (!orsProperties.isConfigured()) {
            LOG.warn("ORS not configured — skipping drive time refresh for user {}", userId);
            return 0;
        }

        try {
            ORS_SEMAPHORE.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Drive time calculation interrupted", e);
        }

        try {
            return doRefreshForUser(userId, originLat, originLon);
        } finally {
            ORS_SEMAPHORE.release();
        }
    }

    private int doRefreshForUser(Long userId, double originLat, double originLon) {
        List<LocationEntity> locations = locationRepository.findAll();
        if (locations.isEmpty()) {
            return 0;
        }

        List<double[]> destinations = locations.stream()
                .map(loc -> new double[]{loc.getLat(), loc.getLon()})
                .toList();

        LOG.info("Refreshing drive times for user {} from ({}, {}) to {} locations via ORS",
                userId, originLat, originLon, destinations.size());

        List<Double> durations = orsClient.fetchDurations(originLat, originLon, destinations);
        if (durations.isEmpty()) {
            LOG.warn("ORS returned empty durations for user {}", userId);
            return 0;
        }

        userDriveTimeRepository.deleteAllByUserId(userId);

        List<UserDriveTimeEntity> driveTimes = new ArrayList<>();
        for (int i = 0; i < Math.min(locations.size(), durations.size()); i++) {
            Double seconds = durations.get(i);
            if (seconds != null && seconds >= 0) {
                driveTimes.add(new UserDriveTimeEntity(
                        userId, locations.get(i).getId(), (int) Math.round(seconds)));
            }
        }

        userDriveTimeRepository.saveAll(driveTimes);
        LOG.info("Drive time refresh complete for user {} — {} locations updated",
                userId, driveTimes.size());
        return driveTimes.size();
    }
}
