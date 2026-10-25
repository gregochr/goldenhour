package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.DriveTimeRefreshResponse;
import com.gregochr.goldenhour.model.MapColourPreferencesRequest;
import com.gregochr.goldenhour.model.MapTideModeRequest;
import com.gregochr.goldenhour.model.PostcodeLookupRequest;
import com.gregochr.goldenhour.model.PostcodeLookupResult;
import com.gregochr.goldenhour.model.ReverseLookupRequest;
import com.gregochr.goldenhour.model.SaveHomeRequest;
import com.gregochr.goldenhour.model.TodaysLightResponse;
import com.gregochr.goldenhour.model.UserSettingsResponse;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.model.ReachEntry;
import com.gregochr.goldenhour.service.ReachService;
import com.gregochr.goldenhour.service.TodaysLightService;
import com.gregochr.goldenhour.service.UserSettingsService;
import com.gregochr.goldenhour.service.ask.AskService;
import com.gregochr.goldenhour.service.ask.AskSettingsResponse;
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
    private final AskService askService;

    /**
     * Constructs a {@code UserSettingsController}.
     *
     * @param settingsService    the user settings service
     * @param driveTimeResolver  the drive time resolver for fetching per-user drive times
     * @param reachService       the caller's reach over the whole roster
     * @param todaysLightService today's light at the caller's home, for the masthead
     * @param askService         the caller's Ask allowance
     */
    public UserSettingsController(UserSettingsService settingsService,
            DriveTimeResolver driveTimeResolver,
            ReachService reachService,
            TodaysLightService todaysLightService,
            AskService askService) {
        this.settingsService = settingsService;
        this.driveTimeResolver = driveTimeResolver;
        this.reachService = reachService;
        this.todaysLightService = todaysLightService;
        this.askService = askService;
    }

    /**
     * The caller's Ask allowance today and whether typed questions are available right now (plan
     * §2.9), so the empty state can show the count before any question is asked. <b>Always 200</b>:
     * with Ask switched off it is {@code enabled: false} and zeros. Personal (it names the caller's
     * own count), so it lives under this prefix, which {@code HttpCachingConfig} never filters.
     *
     * @param auth the current authentication context
     * @return the allowance
     */
    @GetMapping("/ask")
    public AskSettingsResponse getAskSettings(Authentication auth) {
        return askService.settings(auth);
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
     * Finds the nearest UK postcode to a point ("Use my location") without persisting anything.
     *
     * <p>Bearer, with no role gate and no rate limit of its own: a LITE account may save a home
     * postcode, so it may find one, and the lookup is as cheap as {@code /home/lookup}. Never ETag'd:
     * it sits under {@code /api/user/settings}, which {@code HttpCachingConfig} never filters.
     *
     * <p>400 {@code {error}} for a missing, non-finite or out-of-range value, and for a point with no
     * UK postcode near it; 502 {@code {error}} when postcodes.io is down. Neither echoes the upstream.
     *
     * @param request the point to look up
     * @return the nearest postcode with its coordinates and place name
     */
    @PostMapping("/home/reverse")
    public PostcodeLookupResult reverseLookupPostcode(@RequestBody ReverseLookupRequest request) {
        return settingsService.reverseLookupPostcode(request.latitude(), request.longitude());
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
     * Saves the caller's Map tab tide mode — Auto, Always or Off (map-mobile-sheet-plan.md M4).
     *
     * @param request the chosen mode
     * @param auth    the current authentication context
     * @return the updated user settings
     */
    @PutMapping("/map-tide-mode")
    public UserSettingsResponse saveMapTideMode(
            @RequestBody MapTideModeRequest request, Authentication auth) {
        return settingsService.saveMapTideMode(auth, request);
    }

    /**
     * Records that the caller has just looked at the "Coming up" tab — the badge's only write.
     *
     * <p>Its own endpoint rather than fields on {@code saveHome}, matching {@code map-colours}'
     * recorded reasoning: the last-seen instant is not home-derived, and a partial {@code /home}
     * body would deserialise the home fields to null and wipe a saved postcode. No request body:
     * "now" is taken from the server's clock.
     *
     * @param auth the current authentication context
     * @return the updated user settings
     */
    @PutMapping("/coming-up-seen")
    public UserSettingsResponse markComingUpSeen(Authentication auth) {
        return settingsService.markComingUpSeen(auth);
    }

    /**
     * Records that the caller dismissed the one-time sign-in postcode prompt ("Not now") and answers
     * the settings, whose {@code postcodePromptDue} is then false.
     *
     * <p>Its own endpoint for the reason {@code /coming-up-seen} is: a partial {@code /home} body
     * would wipe a saved postcode. No request body — "now" is the server's clock — and idempotent:
     * a second dismissal overwrites the instant. Personal state, so under the prefix
     * {@code HttpCachingConfig} never filters.
     *
     * @param auth the current authentication context
     * @return the updated user settings
     */
    @PutMapping("/postcode-prompt-dismissed")
    public UserSettingsResponse markPostcodePromptDismissed(Authentication auth) {
        return settingsService.markPostcodePromptDismissed(auth);
    }

    /**
     * Recalculates drive times from the user's home to all locations.
     * Rate limited: max once per 30 minutes (enforced in service). Pro and Admin only: drive times
     * from home are a Pro feature, so a Lite account gets 403.
     *
     * @param auth the current authentication context
     * @return the refresh result with count and timestamp
     */
    @PostMapping("/drive-times/refresh")
    @PreAuthorize("hasAnyRole('PRO_USER','ADMIN')")
    public DriveTimeRefreshResponse refreshDriveTimes(Authentication auth) {
        return settingsService.refreshDriveTimes(auth);
    }

    /**
     * Returns the current user's per-location drive times in minutes. Pro and Admin only: a Lite
     * account gets 403.
     *
     * @param auth the current authentication context
     * @return map of location ID to drive time in minutes
     */
    @GetMapping("/drive-times")
    @PreAuthorize("hasAnyRole('PRO_USER','ADMIN')")
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
     * <p>Pro and Admin only: reach is drive time from home, a Pro feature, so a Lite account gets
     * 403 (the client already shows Lite the locked reach lens and never asks).
     *
     * @param auth the current authentication context
     * @return one entry per enabled location, in id order
     */
    @GetMapping("/reach")
    @PreAuthorize("hasAnyRole('PRO_USER','ADMIN')")
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
