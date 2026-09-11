-- Retire the optimisation strategies that can no longer act, and the placeholder that was never built.
--
-- SKIP_LOW_RATED, SKIP_EXISTING, FORCE_IMMINENT, FORCE_STALE, EVALUATE_ALL and NEXT_EVENT_ONLY were
-- evaluated only by OptimisationSkipEvaluator.shouldSkip. They acted at first, on hand-started runs;
-- v2.7.2 (2026-04-06) guarded that call with !triggeredManually because SKIP_LOW_RATED was dropping
-- locations from an explicit Run Forecast. Every caller of that engine passes manual = true (the five
-- ForecastController endpoints that reach it), and its scheduled triggers had been retired on
-- 2026-02-27, before these strategies existed — so from v2.7.2 none of the six could act. The batch
-- pipeline never read this table at all. BATCH_API was a placeholder, hidden and read by nothing.
--
-- SENTINEL_SAMPLING and TIDE_ALIGNMENT stay: both are read straight from the enabled set, outside
-- that guard, so they do act on hand-started runs.
--
-- The enum values go in the same change, and strategy_type maps through @Enumerated(STRING), so any
-- row left behind would make Hibernate throw on read. Same shape as V42's REQUIRE_PRIOR removal.
DELETE FROM optimisation_strategy
WHERE strategy_type IN (
    'SKIP_LOW_RATED',
    'SKIP_EXISTING',
    'FORCE_IMMINENT',
    'FORCE_STALE',
    'EVALUATE_ALL',
    'NEXT_EVENT_ONLY',
    'BATCH_API'
);
