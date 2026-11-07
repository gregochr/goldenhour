package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.OpenRouteServiceClient;
import com.gregochr.goldenhour.config.OrsProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.UserDriveTimeEntity;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Measures per-user drive durations from a user's home location to all forecast locations via
 * the OpenRouteService matrix API.
 *
 * <p>It only measures; it stores nothing. The caller stores the result through
 * {@link UserDriveTimeWriter#storeIfHomeUnchanged}, which writes it only if the home is still the
 * one measured from: routing takes seconds, and a save can move the home while it runs. Storing
 * from in here, as this class once did, wrote the rows unconditionally and left each caller to
 * stamp them afterwards in a separate write.
 *
 * <p>The account-wide concurrency limit on ORS lives in {@link
 * com.gregochr.goldenhour.client.OrsRateLimiter} and is applied inside the client, so this class
 * and the shared region matrix (plan P7) queue on the same two permits rather than on one each.
 *
 * <p>Deliberately not transactional: the semaphore wait and the ORS HTTP call run outside any
 * transaction so no database connection is pinned across them.
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
     * Measures drive times from the given origin to all locations, without storing them.
     *
     * <p>Two kinds of nothing are kept apart, because the callers treat them differently. No answer
     * at all — ORS unconfigured, no locations, or an empty response — is an empty {@code Optional}:
     * nothing was learned, and stored drive times should stay. An answer that carries no valid
     * duration (every destination null or negative) is a present, empty list: ORS answered, and
     * storing it clears the stored ones.
     *
     * <p>A short answer (fewer durations than locations) is also "no answer": the caller stamps what it stores
     * as measured in full, so storing part of the roster would let an unmeasured place read as unroutable.
     *
     * <p>The ORS call queues on the shared two-permit limiter inside the client, and an exception
     * from it propagates.
     *
     * @param userId    the user's primary key, stamped on each measured row
     * @param originLat the origin latitude (user's home)
     * @param originLon the origin longitude (user's home)
     * @return one row per location with a valid duration, possibly none; empty if ORS gave no
     *         answer
     */
    public Optional<List<UserDriveTimeEntity>> measureForUser(Long userId, double originLat,
            double originLon) {
        if (!orsProperties.isConfigured()) {
            LOG.warn("ORS not configured — skipping drive time refresh for user {}", userId);
            return Optional.empty();
        }

        List<LocationEntity> locations = locationRepository.findAll();
        if (locations.isEmpty()) {
            return Optional.empty();
        }

        // ORS routes to the drive point when a place has one (reach-scoped-card-plan R1c) and to the pin otherwise.
        // Only ORS does: straight-line miles stay on the pin.
        List<double[]> destinations = locations.stream()
                .map(loc -> new double[]{loc.routingLat(), loc.routingLon()})
                .toList();

        LOG.info("Measuring drive times for user {} from ({}, {}) to {} locations via ORS",
                userId, originLat, originLon, destinations.size());

        List<Double> durations = orsClient.fetchDurations(originLat, originLon, destinations);
        if (durations.isEmpty()) {
            LOG.warn("ORS returned empty durations for user {}", userId);
            return Optional.empty();
        }
        if (durations.size() < locations.size()) {
            // A short answer is not a full measurement, and the caller stamps what it stores as "measured in
            // full" (reach-scoped-card-plan D1b): a destination the answer never covered would then read
            // UNROUTABLE instead of PENDING. Treated as no answer, so nothing is stored and no stamp moves.
            // OpenRouteServiceClient already refuses a short row, so this is a guard against a future client.
            LOG.warn("ORS returned {} durations for {} locations for user {} — treating as no answer",
                    durations.size(), locations.size(), userId);
            return Optional.empty();
        }

        List<UserDriveTimeEntity> driveTimes = new ArrayList<>();
        for (int i = 0; i < locations.size(); i++) {
            Double seconds = durations.get(i);
            if (seconds != null && seconds >= 0) {
                driveTimes.add(new UserDriveTimeEntity(
                        userId, locations.get(i).getId(), (int) Math.round(seconds)));
            }
        }

        LOG.info("Drive times measured for user {} — {} locations", userId, driveTimes.size());
        return Optional.of(driveTimes);
    }
}
