package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.comingup.ComingUpResponse;
import com.gregochr.goldenhour.service.AlmanacService;
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
 * solstices — already visible in the forecast UI, and {@code GET /api/tides} is the standing
 * precedent for almanac data being Bearer rather than ADMIN. This project has already documented
 * one endpoint as ADMIN that the code never enforced, so the intent is written down here.
 *
 * <p>The feed carries no per-user data, which is what lets it be ETag-revalidated and shared.
 */
@RestController
@RequestMapping("/api/almanac")
public class AlmanacController {

    private final AlmanacService almanacService;

    /**
     * Constructs an {@code AlmanacController}.
     *
     * @param almanacService assembles and caches the feed
     */
    public AlmanacController(AlmanacService almanacService) {
        this.almanacService = almanacService;
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
     * @return the wrapped feed — see {@code docs/engineering/coming-up-plan.md} §13
     */
    @GetMapping
    public ComingUpResponse getAlmanac(
            @RequestParam(defaultValue = "" + AlmanacService.DEFAULT_DAYS) int days) {
        return almanacService.getFeed(days);
    }
}
