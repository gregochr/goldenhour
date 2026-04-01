package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.PostcodeLookupException;
import com.gregochr.goldenhour.client.PostcodesIoClient;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.model.DriveTimeRefreshResponse;
import com.gregochr.goldenhour.model.PostcodeLookupResult;
import com.gregochr.goldenhour.model.SaveHomeRequest;
import com.gregochr.goldenhour.model.UserSettingsResponse;
import com.gregochr.goldenhour.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

/**
 * Service for user profile and settings — home location and drive time management.
 */
@Service
@Transactional
public class UserSettingsService {

    private static final Logger LOG = LoggerFactory.getLogger(UserSettingsService.class);

    /** Minimum interval between drive time refreshes (30 minutes). */
    private static final long REFRESH_COOLDOWN_MINUTES = 30;

    private final AppUserRepository userRepository;
    private final PostcodesIoClient postcodesIoClient;
    private final DriveDurationService driveDurationService;

    /**
     * Constructs a {@code UserSettingsService}.
     *
     * @param userRepository       the user repository
     * @param postcodesIoClient    the postcodes.io client for geocoding
     * @param driveDurationService the drive duration calculation service
     */
    public UserSettingsService(AppUserRepository userRepository,
            PostcodesIoClient postcodesIoClient,
            DriveDurationService driveDurationService) {
        this.userRepository = userRepository;
        this.postcodesIoClient = postcodesIoClient;
        this.driveDurationService = driveDurationService;
    }

    /**
     * Returns the current user's settings including profile and home location.
     *
     * @param auth the authenticated user
     * @return the user settings response
     */
    public UserSettingsResponse getSettings(Authentication auth) {
        AppUserEntity user = getUser(auth);
        String placeName = resolveHomePlaceName(user);
        return mapToResponse(user, placeName);
    }

    /**
     * Validates and geocodes a UK postcode without persisting.
     *
     * @param postcode the postcode to look up
     * @return the lookup result with coordinates and place name
     * @throws PostcodeLookupException if the postcode is invalid
     */
    public PostcodeLookupResult lookupPostcode(String postcode) {
        return postcodesIoClient.lookup(postcode);
    }

    /**
     * Persists the confirmed home location on the user entity.
     *
     * @param auth    the authenticated user
     * @param request the confirmed postcode and coordinates
     * @return the updated user settings response
     */
    public UserSettingsResponse saveHome(Authentication auth, SaveHomeRequest request) {
        AppUserEntity user = getUser(auth);
        user.setHomePostcode(request.postcode());
        user.setHomeLatitude(request.latitude());
        user.setHomeLongitude(request.longitude());
        userRepository.save(user);
        LOG.info("User '{}' saved home location: {} ({}, {})",
                user.getUsername(), request.postcode(), request.latitude(), request.longitude());
        return mapToResponse(user, null);
    }

    /**
     * Recalculates drive times from the user's home to all locations.
     *
     * @param auth the authenticated user
     * @return the refresh response with count and timestamp
     * @throws ResponseStatusException 400 if no home location set, 429 if recently refreshed
     */
    public DriveTimeRefreshResponse refreshDriveTimes(Authentication auth) {
        AppUserEntity user = getUser(auth);
        if (user.getHomeLatitude() == null || user.getHomeLongitude() == null) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Set a home location before refreshing drive times");
        }
        if (user.getDriveTimesCalculatedAt() != null
                && user.getDriveTimesCalculatedAt()
                        .isAfter(Instant.now().minus(REFRESH_COOLDOWN_MINUTES, ChronoUnit.MINUTES))) {
            throw new ResponseStatusException(TOO_MANY_REQUESTS,
                    "Drive times were refreshed recently. Please wait before trying again.");
        }

        int updated = driveDurationService.refreshForUser(
                user.getId(), user.getHomeLatitude(), user.getHomeLongitude());
        user.setDriveTimesCalculatedAt(Instant.now());
        userRepository.save(user);
        LOG.info("Drive times refreshed for user '{}': {} locations updated",
                user.getUsername(), updated);
        return new DriveTimeRefreshResponse(updated, user.getDriveTimesCalculatedAt());
    }

    /**
     * Resolves the authenticated user's database ID.
     *
     * @param auth the authentication context
     * @return the user's primary key
     */
    public Long getUserId(Authentication auth) {
        return getUser(auth).getId();
    }

    private AppUserEntity getUser(Authentication auth) {
        return userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new NoSuchElementException(
                        "User not found: " + auth.getName()));
    }

    private String resolveHomePlaceName(AppUserEntity user) {
        if (user.getHomePostcode() == null) {
            return null;
        }
        try {
            return postcodesIoClient.lookup(user.getHomePostcode()).placeName();
        } catch (Exception e) {
            LOG.debug("Could not resolve place name for postcode '{}': {}",
                    user.getHomePostcode(), e.getMessage());
            return null;
        }
    }

    private UserSettingsResponse mapToResponse(AppUserEntity user, String placeName) {
        return new UserSettingsResponse(
                user.getUsername(),
                user.getEmail(),
                user.getRole().name(),
                user.getHomePostcode(),
                user.getHomeLatitude(),
                user.getHomeLongitude(),
                placeName,
                user.getDriveTimesCalculatedAt());
    }
}
