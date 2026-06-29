package com.gregochr.goldenhour.entity;

import java.util.Optional;

/**
 * Per-candidate outcome of a forecast batch cycle's task collection.
 *
 * <p>Recorded into {@code forecast_run_disposition} so the Job Run detail UI
 * can reconcile every candidate the collector considered — submitted to Claude
 * or skipped with reason — without resorting to {@code grep [BATCH DIAG]} on
 * production logs.
 *
 * <p>The column type in the schema is {@code VARCHAR(40)} (not a native enum),
 * so adding a new category later (notably the reserved
 * {@link #SKIPPED_NO_REFRESH_NEEDED} for the future intraday refresh path)
 * needs only a code change here, not a migration. Reads tolerate unknown
 * stored values via {@link #fromString(String)} returning {@link Optional#empty()}.
 */
public enum DispositionCategory {

    /** Candidate was submitted to Claude as part of one of the batch buckets. */
    EVALUATED,

    /**
     * Candidate would have been gated out by the Gate 4 stability policy but was
     * force-evaluated anyway because it was a top GO + tide-aligned far-out
     * headline contender for the best-bet pick. Capped per cycle (see
     * {@code photocast.batch.force-eval-cap}) so "targeted" never becomes
     * "blanket". Submitted through the same far-term bucket as every other
     * evaluation — it is a rule within the one eligibility policy, not a
     * parallel path.
     */
    FORCE_EVALUATED,

    /**
     * STANDDOWN slot whose reason is a hard physical constraint (currently only
     * tide mismatch on a coastal location whose tide preference does not match
     * the event-time tide state). Cannot be revised by Claude, so never reaches it.
     */
    SKIPPED_HARD_CONSTRAINT,

    /**
     * Candidate reached the triage loop and was rejected by
     * {@code ForecastService.fetchWeatherAndTriage} — typically heavy cloud,
     * rain, or sun blocked at the horizon. The reason string is captured in
     * the disposition's {@code detail} field.
     */
    SKIPPED_TRIAGED,

    /**
     * Region had a fresh cached evaluation within the stability-derived freshness
     * threshold, so the slot was not re-evaluated this cycle.
     */
    SKIPPED_CACHED,

    /**
     * Briefing day's date is before today (Europe/London) — already past, no
     * point evaluating.
     */
    SKIPPED_PAST_DATE,

    /**
     * The candidate's target date falls inside a declared travel range (the
     * operator is away — typically commuting to London — and cannot shoot). The
     * whole day's slots are skipped so the batch spends nothing on forecasts that
     * will never be acted on. Managed via Manage → Operations → Travel Days.
     */
    SKIPPED_TRAVEL_DAY,

    /**
     * Candidate passed triage but was gated out by the Gate 4 stability policy
     * (e.g. T+3 with TRANSITIONAL stability, T+4+ entirely). The skip reason
     * from the policy is captured in {@code detail}.
     */
    SKIPPED_STABILITY,

    /**
     * Slot's location name did not resolve to a known enabled {@code LocationEntity}.
     * Should never happen in production; surfaced as a category rather than
     * swallowed silently so misconfiguration is visible.
     */
    SKIPPED_UNKNOWN_LOCATION,

    /**
     * An exception was thrown during data assembly for this candidate (e.g.
     * Open-Meteo extraction failure, cloud cache miss). The message is captured
     * in {@code detail}.
     */
    SKIPPED_ERROR,

    /**
     * Reserved for the future intraday refresh path. Stability-classified
     * SETTLED candidates whose cached evaluation is still within threshold for
     * the intraday refresh would be tagged with this — not used yet.
     */
    SKIPPED_NO_REFRESH_NEEDED;

    /**
     * Parses a stored disposition string back to the enum. Returns
     * {@link Optional#empty()} for unknown values so old code can iterate over
     * rows written by a newer schema-compatible deployment without crashing.
     *
     * @param value the stored disposition string, may be null
     * @return the matching enum value, or empty if null/unknown
     */
    public static Optional<DispositionCategory> fromString(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (DispositionCategory category : values()) {
            if (category.name().equals(value)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }
}
