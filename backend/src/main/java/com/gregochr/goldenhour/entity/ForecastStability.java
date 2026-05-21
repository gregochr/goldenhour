package com.gregochr.goldenhour.entity;

/**
 * Synoptic-scale forecast stability classification for a grid cell.
 *
 * <p>Classification is well-calibrated against four signals (pressure
 * tendency, precipitation probability/variance, active-weather codes
 * and wind gust variance) and is consumed by:
 * <ul>
 *   <li>the freshness resolver, which picks a per-stability cache TTL,</li>
 *   <li>the briefing best-bet advisor, which surfaces the worst-case
 *       stability per region on the Plan tab, and</li>
 *   <li>the admin stability summary endpoint.</li>
 * </ul>
 *
 * <p><b>Note for maintainers:</b> classification is independent of the
 * policy that decides which (location, daysAhead) tuples enter the batch
 * for Claude evaluation. That policy lives in
 * {@code ForecastTaskCollector#resolveEligibility} and is expressed
 * explicitly per {@code daysAhead}, so the enum no longer encodes any
 * batch-eligibility rule.
 */
public enum ForecastStability {

    /**
     * Synoptically settled — high pressure dominant, low precipitation
     * probability, no frontal signals.
     */
    SETTLED,

    /**
     * Mixed signals — some instability or frontal approach but core
     * pattern holding.
     */
    TRANSITIONAL,

    /**
     * Active weather — frontal system present or approaching, high
     * precipitation probability variance, falling pressure.
     */
    UNSETTLED;

    /**
     * Display-only depth hint, in days, for the admin stability summary
     * UI. Larger numbers correspond to more settled conditions (SETTLED→3,
     * TRANSITIONAL→1, UNSETTLED→0).
     *
     * <p><b>Not authoritative for batch evaluation eligibility.</b> The
     * scheduled batch eligibility policy lives in
     * {@code ForecastTaskCollector#resolveEligibility} and is expressed
     * explicitly per {@code daysAhead}. Callers wanting to know "should
     * this task be evaluated?" must consult the collector, never this
     * field.
     *
     * <p>The legacy {@code ForecastCommand} (admin "Run Forecast")
     * non-batch path in {@code ForecastCommandExecutor.applyStabilityFilter}
     * still reads this field as a policy proxy until that path is
     * unified into the batch flow. See CLAUDE.md for the open
     * inconsistency.
     *
     * @return display-only depth hint in days
     */
    public int evaluationWindowDays() {
        return switch (this) {
            case SETTLED      -> 3;
            case TRANSITIONAL -> 1;
            case UNSETTLED    -> 0;
        };
    }
}
