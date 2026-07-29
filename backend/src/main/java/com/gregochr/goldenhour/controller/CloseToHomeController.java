package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.CloseToHomeResponse;
import com.gregochr.goldenhour.model.UserSettingsResponse;
import com.gregochr.goldenhour.service.CloseToHomeService;
import com.gregochr.goldenhour.service.UserSettingsService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the Close to home panel.
 *
 * <p><b>Its own controller, not a method on {@code BriefingController}, and that is the point.</b>
 * Every other Plan-tab panel is a view of the same shared forecast snapshot and rides one
 * {@code GET /api/briefing} payload, because two panels fetching independently can disagree about
 * the same location. This one answers a different question about <em>differently owned</em> data —
 * the caller's home postcode and their own drive times — which is the only thing that earns a
 * separate contract. See {@code docs/engineering/plan-panel-data-contracts.md}.
 *
 * <p><b>It must never be ETag-revalidated.</b> Enabling an ETag forces
 * {@code Cache-Control: private, no-cache}, which persists the response body to the browser's
 * on-disk HTTP cache — and JavaScript cannot evict that cache on logout, so one user's home-area
 * forecast would survive into the next session on a shared machine. {@code HttpCachingConfig}'s
 * whitelist is exact-match for exactly this reason, so this path is excluded by default rather
 * than by vigilance; {@code HttpCachingConfigTest} pins it so it stays that way.
 */
@RestController
@RequestMapping("/api/briefing")
public class CloseToHomeController {

    private final CloseToHomeService closeToHomeService;
    private final UserSettingsService userSettingsService;

    /**
     * Creates the controller.
     *
     * @param closeToHomeService  derives the panel
     * @param userSettingsService resolves the caller's identity and home location
     */
    public CloseToHomeController(CloseToHomeService closeToHomeService,
            UserSettingsService userSettingsService) {
        this.closeToHomeService = closeToHomeService;
        this.userSettingsService = userSettingsService;
    }

    /**
     * Returns the nearby options worth leaving the house for.
     *
     * <p>Returns an EMPTY panel rather than 404 or an error when the caller has set no home
     * postcode. That is the normal state for a new user, and a panel with nothing in it is
     * something the client can render; an error is not.
     *
     * @param auth the authenticated caller
     * @return the panel for that caller
     */
    @GetMapping("/close-to-home")
    public CloseToHomeResponse closeToHome(Authentication auth) {
        UserSettingsResponse settings = userSettingsService.getSettings(auth);
        return closeToHomeService.build(
                userSettingsService.getUserId(auth),
                settings.homeLatitude(),
                settings.homeLongitude());
    }
}
