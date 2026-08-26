package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.DriveTimeRefreshResponse;
import com.gregochr.goldenhour.model.MapColourPreferencesRequest;
import com.gregochr.goldenhour.model.PostcodeLookupRequest;
import com.gregochr.goldenhour.model.PostcodeLookupResult;
import com.gregochr.goldenhour.model.SaveHomeRequest;
import com.gregochr.goldenhour.model.TodaysLightResponse;
import com.gregochr.goldenhour.model.UserSettingsResponse;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.model.ReachEntry;
import com.gregochr.goldenhour.service.ReachService;
import com.gregochr.goldenhour.service.TodaysLightService;
import com.gregochr.goldenhour.service.UserSettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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
    private final ReachService reachService;
    private final TodaysLightService todaysLightService;

    /**
     * Constructs a {@code UserSettingsController}.
     *
     * @param settingsService    the user settings service
     * @param driveTimeResolver  the drive time resolver for fetching per-user drive times
     * @param reachService       the caller's reach over the whole roster
     * @param todaysLightService today's light at the caller's home, for the masthead
     */
    public UserSettingsController(UserSettingsService settingsService,
            DriveTimeResolver driveTimeResolver,
            ReachService reachService,
            TodaysLightService todaysLightService) {
        this.settingsService = settingsService;
        this.driveTimeResolver = driveTimeResolver;
        this.reachService = reachService;
        this.todaysLightService = todaysLightService;
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
     * Saves the caller's map colour preferences — which ramp paints the map, and whether markers
     * follow it.
     *
     * @param request the chosen scale and whether markers follow it
     * @param auth    the current authentication context
     * @return the updated user settings
     */
    @PutMapping("/map-colours")
    public UserSettingsResponse saveMapColourPreferences(
            @RequestBody MapColourPreferencesRequest request, Authentication auth) {
        return settingsService.saveMapColourPreferences(auth, request);
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

    /**
     * Returns the caller's reach — drive minutes and distance — for every enabled location.
     *
     * <p>Deliberately under {@code /api/user/settings}, and not merely for tidiness:
     * {@code HttpCachingConfig}'s revalidatable set is an exact-match allow-list, so a path here is
     * excluded from ETag caching by construction. Reach is home-derived personal data, and ETag
     * revalidation would persist it to a browser HTTP cache JavaScript cannot evict on logout.
     *
     * <p>Bearer, with no role gate: this is the caller's own data, and gating it would break the
     * reach lens for the very users it exists to serve.
     *
     * @param auth the current authentication context
     * @return one entry per enabled location, in id order
     */
    @GetMapping("/reach")
    public List<ReachEntry> getReach(Authentication auth) {
        return reachService.getReach(auth);
    }

    /**
     * Today's light at the caller's home — the masthead's light rule and its labelled time row.
     *
     * <p>Under {@code /api/user/settings} for the same reason {@code /reach} is: the payload names
     * the caller's home postcode, and {@code HttpCachingConfig}'s revalidatable set is an
     * exact-match allow-list, so a path here can never pick up the {@code Cache-Control: private,
     * no-cache} that would persist it to a browser HTTP cache JavaScript cannot evict on logout.
     *
     * <p>Bearer, with no role gate. A LITE account may save a home postcode — light times are free,
     * drive times are Pro — so gating this would leave the rule permanently dim for the users the
     * masthead's "set a postcode" nudge is aimed at.
     *
     * <p>{@code 204 No Content} when no postcode is saved. That is the masthead's empty state, not
     * an error: it renders a dim rule and the nudge.
     *
     * @param auth the current authentication context
     * @return today's light, or 204 when the caller has saved no home postcode
     */
    @GetMapping("/light")
    public ResponseEntity<TodaysLightResponse> getTodaysLight(Authentication auth) {
        TodaysLightResponse light = todaysLightService.getTodaysLight(auth);
        return light == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(light);
    }
}
