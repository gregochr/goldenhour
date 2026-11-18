package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.comingup.ComingUpResponse;
import com.gregochr.goldenhour.service.AlmanacService;
import com.gregochr.goldenhour.service.comingup.ComingUpRoleShaper;
import com.gregochr.goldenhour.service.comingup.ComingUpWarningMerger;
import com.gregochr.goldenhour.util.Authorities;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the "Coming up" almanac feed.
 *
 * <p><strong>Bearer, with no role gate — stated rather than left to be inferred.</strong>
 * {@code SecurityConfig} gates {@code /api/**} at {@code .authenticated()} and this path is not in
 * the {@code permitAll} set, so authentication comes by inheritance and nothing needed adding. No
 * {@code @PreAuthorize} is present and none should be: this is ephemeris — tide runs, meteor peaks,
 * solstices — already visible in the forecast UI, and carries no tide heights at the light. The one
 * thing it holds back is the tide relative to the light (see below).
 * ({@code GET /api/tides} is no precedent: it became Pro/Admin on 2026-10-26 because it serves tide
 * heights.) This project has already documented one endpoint as ADMIN that the code never
 * enforced, so the intent is written down here.
 *
 * <p><strong>The body is role-shaped, not personal.</strong> Where high water falls against sunrise
 * or sunset is a Pro and Admin feature (docs/engineering/tide-light-pro-plan.md §3 B2), so a reader
 * who is not positively PRO_USER or ADMIN gets the feed with the sunrise/sunset high-water rows and
 * the coincidence alignment clock removed ({@link ComingUpRoleShaper}); the check is fail closed. The
 * shaping happens only here: {@link AlmanacService}'s cached feed stays role-free (Ask reads it
 * unshaped). The feed holds no per-user data, and the ETag is hashed from the rendered body, so the
 * two variants never share a validator.
 *
 * <p><strong>Warning entries are merged here, per request, after the day cache</strong>
 * ({@link ComingUpWarningMerger}): Met Office warnings in force that reach past the scored days, the same for every
 * role (Lite parity), from the stored versions evaluated against the injected clock (rewound for a rewound admin
 * GET), never a simulated row. Because the ETag is hashed from the rendered body, a warning that arrives, changes or
 * is cancelled changes the validator, and the response is {@code private, no-cache}, so a reader never holds a stale
 * warning set beyond one revalidation. Nothing personal is added.
 */
@RestController
@RequestMapping("/api/almanac")
public class AlmanacController {

    private final AlmanacService almanacService;
    private final ComingUpRoleShaper roleShaper;
    private final ComingUpWarningMerger warningMerger;

    /**
     * Constructs an {@code AlmanacController}.
     *
     * @param almanacService assembles and caches the feed
     * @param roleShaper     removes the tide-relative-to-the-light rows for a Lite reader
     * @param warningMerger  adds the Met Office warning entries to the served feed, per request
     */
    public AlmanacController(AlmanacService almanacService, ComingUpRoleShaper roleShaper,
            ComingUpWarningMerger warningMerger) {
        this.almanacService = almanacService;
        this.roleShaper = roleShaper;
        this.warningMerger = warningMerger;
    }

    /**
     * Returns the almanac feed starting today.
     *
     * <p>Entries may start before today or end after the requested horizon: a season or a tide run
     * that straddles an edge is reported with its true span rather than clipped, because "this
     * season began three weeks ago" is the useful reading and "starts today" would be false. Only
     * entries eligible under Plan's boundary are included — see {@link AlmanacService}.
     *
     * <p>{@code days} is clamped rather than rejected. A caller asking for 0 or 10,000 has made a
     * client-side mistake, and a feed is more useful than a 400 — there is no destructive effect to
     * guard against and nothing a validation error would let them fix that the clamp does not.
     *
     * @param days feed length in days, clamped to {@value AlmanacService#MIN_DAYS}..{@value
     *             AlmanacService#MAX_DAYS}; defaults to {@value AlmanacService#DEFAULT_DAYS}
     * @param auth the caller's authentication
     * @return the wrapped feed, shaped for the caller's role — see
     *         {@code docs/engineering/coming-up-plan.md} §13
     */
    @GetMapping
    public ComingUpResponse getAlmanac(
            @RequestParam(defaultValue = "" + AlmanacService.DEFAULT_DAYS) int days,
            Authentication auth) {
        ComingUpResponse feed = almanacService.getFeed(days);
        boolean pro = Authorities.hasRole(auth, UserRole.PRO_USER)
                || Authorities.hasRole(auth, UserRole.ADMIN);
        return warningMerger.merge(pro ? feed : roleShaper.forLite(feed), days);
    }
}
