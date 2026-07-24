-- Supporting index for ForecastEvaluationRepository.findLatestRunPerSlotByLocationIds
-- (GET /api/forecast — the map view's primary, uncached endpoint).
--
-- That query resolves the latest evaluation run per slot with a correlated
-- MAX(forecast_run_at) subquery filtered by (location_id, target_date, target_type).
-- forecast_evaluation is insert-only and never pruned, so every run appends a row and
-- the table grows without bound. Without a supporting index the subquery degrades to a
-- per-outer-row sequential scan on Postgres that worsens as the table ages. The prior
-- index (idx_forecast_eval_location_date on location_name) was dropped in V47 when the
-- location_name column was removed, and nothing replaced it for location_id.
--
-- Column order: the (location_id, target_date, target_type) prefix serves both the outer
-- date-range/location filter and the subquery's equality predicates; the trailing
-- forecast_run_at lets Postgres resolve MAX() as an index scan rather than a heap scan.
--
-- NB: on a very large existing table consider CREATE INDEX CONCURRENTLY (run outside a
-- transaction) to avoid blocking writes while the index builds.
CREATE INDEX IF NOT EXISTS idx_forecast_eval_latest_run
    ON forecast_evaluation (location_id, target_date, target_type, forecast_run_at);
