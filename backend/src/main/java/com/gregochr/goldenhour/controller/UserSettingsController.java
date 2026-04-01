package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.DriveTimeRefreshResponse;
import com.gregochr.goldenhour.model.PostcodeLookupRequest;
import com.gregochr.goldenhour.model.PostcodeLookupResult;
import com.gregochr.goldenhour.model.SaveHomeRequest;
import com.gregochr.goldenhour.model.UserSettingsResponse;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.UserSettingsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller for user profile settings — home location and per-user drive times.
 */
@RestController
@RequestMapping("/api/user/settings")
@PreAuthorize("isAuthenticated()")
public class UserSettingsController {

    private final UserSettingsService settingsService;
    private final DriveTimeResolver driveTimeResolver;

    /**
     * Constructs a {@code UserSettingsController}.
     *
     * @param settingsService    the user settings service
     * @param driveTimeResolver  the drive time resolver for fetching per-user drive times
     */
    public UserSettingsController(UserSettingsService settingsService,
            DriveTimeResolver driveTimeResolver) {
        this.settingsService = settingsService;
        this.driveTimeResolver = driveTimeResolver;
    }

    /**
     * Returns the current user's profile and settings.
     *
     * @param auth the current authentication context
     * @return the user settings response
     */
    @GetMapping
    public UserSettingsResponse getSettings(Authentication auth) {
        return settingsService.getSettings(auth);
    }

    /**
     * Validates and geocodes a UK postcode without persisting.
     *
     * @param request the postcode to look up
     * @return the resolved coordinates and place name
     */
    @PostMapping("/home/lookup")
    public PostcodeLookupResult lookupPostcode(@RequestBody PostcodeLookupRequest request) {
        return settingsService.lookupPostcode(request.postcode());
    }

    /**
     * Saves the confirmed home location on the user entity.
     *
     * @param request the confirmed postcode and coordinates
     * @param auth    the current authentication context
     * @return the updated user settings
     */
    @PutMapping("/home")
    public UserSettingsResponse saveHome(@RequestBody SaveHomeRequest request,
            Authentication auth) {
        return settingsService.saveHome(auth, request);
    }

    /**
     * Recalculates drive times from the user's home to all locations.
     * Rate limited: max once per 30 minutes (enforced in service).
     *
     * @param auth the current authentication context
     * @return the refresh result with count and timestamp
     */
    @PostMapping("/drive-times/refresh")
    public DriveTimeRefreshResponse refreshDriveTimes(Authentication auth) {
        return settingsService.refreshDriveTimes(auth);
    }

    /**
     * Returns the current user's per-location drive times in minutes.
     *
     * @param auth the current authentication context
     * @return map of location ID to drive time in minutes
     */
    @GetMapping("/drive-times")
    public Map<Long, Integer> getDriveTimes(Authentication auth) {
        Long userId = settingsService.getUserId(auth);
        return driveTimeResolver.getAllMinutes(userId);
    }
}
