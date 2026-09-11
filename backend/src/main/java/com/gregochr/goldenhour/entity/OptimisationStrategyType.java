package com.gregochr.goldenhour.entity;

/**
 * Types of cost optimisation strategy that can be toggled per {@link RunType}.
 *
 * <p>⚠️ <b>Both apply only to the synchronous engine</b> ({@code ForecastCommandExecutor}), which is
 * reached solely through five admin endpoints in {@code ForecastController} — the hand-started runs:
 * Very-Short-Term, Short-Term and Long-Term on Job Runs, and Run Forecast on a map location. The
 * batch pipeline reads neither: its calls into {@code ForecastService.fetchWeatherAndTriage} pass
 * {@code tideAlignmentEnabled = false}, and it has no sentinel phase. A hand-started run's rows are
 * still served by {@code GET /api/forecast}, so what these change can reach readers.
 *
 * <p>Seven more types used to live here and were retired (V153). Six — {@code SKIP_LOW_RATED},
 * {@code SKIP_EXISTING}, {@code FORCE_IMMINENT}, {@code FORCE_STALE}, {@code EVALUATE_ALL},
 * {@code NEXT_EVENT_ONLY} — did act at first, skipping slots on hand-started runs; v2.7.2
 * (2026-04-06) confined them behind {@code !triggeredManually} because {@code SKIP_LOW_RATED} was
 * dropping locations from an explicit Run Forecast. Every caller passes {@code manual = true}, and
 * the engine's scheduled triggers had been retired on 2026-02-27, before these types existed — so
 * from v2.7.2 nothing could evaluate them. The seventh, {@code BATCH_API}, was a placeholder that
 * was never built. Do not reintroduce a type without a path that actually evaluates it.
 */
public enum OptimisationStrategyType {
    /** Enable sentinel sampling — evaluate geographic representatives per region first;
     *  skip remainder if all sentinels rate at or below the configured threshold (default 2). */
    SENTINEL_SAMPLING,
    /** Apply tide alignment triage for SEASCAPE locations — skip Claude when no preferred
     *  tide type falls within the golden/blue hour window around the solar event. Gates the tide
     *  check only: weather triage (cloud, precipitation, visibility) runs regardless. */
    TIDE_ALIGNMENT
}
