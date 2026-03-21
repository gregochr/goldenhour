package com.gregochr.goldenhour.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Response from the aurora forecast run endpoint, summarising per-night outcomes.
 *
 * @param nights           per-night results, one entry per requested date
 * @param totalClaudeCalls number of Claude API calls made across all nights
 * @param estimatedCost    rough cost estimate in USD (e.g. "~$0.02")
 */
public record AuroraForecastRunResponse(
        List<NightResult> nights,
        int totalClaudeCalls,
        String estimatedCost) {

    /** Compact constructor that defensively copies the mutable nights list. */
    public AuroraForecastRunResponse {
        nights = nights == null ? List.of() : List.copyOf(nights);
    }


    /**
     * Outcome for a single forecast night.
     *
     * @param date              the calendar date this night covers
     * @param status            one of: "scored" (Claude ran for some locations),
     *                          "all_triaged" (all locations overcast — no Claude call),
     *                          "no_eligible_locations" (Bortle filter excluded all locations),
     *                          "no_activity" (Kp too low to warrant a forecast)
     * @param locationsScored   number of locations scored by Claude
     * @param locationsTriaged  number of locations auto-assigned 1★ due to overcast conditions
     * @param maxForecastKp     highest Kp value in the dark window
     * @param summary           one-line result summary (e.g. "Embleton Bay ★★★★★")
     */
    public record NightResult(
            LocalDate date,
            String status,
            int locationsScored,
            int locationsTriaged,
            double maxForecastKp,
            String summary) {}
}
