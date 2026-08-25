-- Stage 4 calibration for docs/engineering/heat-scale-unification-plan.md
-- Produces the lo/hi pair per metric for HeatField.rampPct(v, lo, hi).
--
-- Run on the PRODUCTION host — local H2 has no representative distribution.
-- Container / user / db from docker-compose.yml: goldenhour-db / goldenhour / goldenhour.
-- Read-only: three SELECTs. No writes, no locks beyond a shared read.

\echo '=== 1. COVERAGE — which store actually holds the data? ==='
-- cached_evaluation is what the UI displays (CLAUDE.md, "Where a rating lives");
-- forecast_evaluation is the SYNC engine, ~3/4 null since the batch pipeline took over.
SELECT 'forecast_evaluation'      AS store,
       count(*)                   AS rows,
       count(fiery_sky_potential) AS fiery_n,
       count(golden_hour_potential) AS golden_n,
       max(forecast_run_at)::date AS newest
FROM forecast_evaluation
UNION ALL
SELECT 'cached_evaluation',
       count(*), NULL, NULL, max(evaluated_at)::date
FROM cached_evaluation;

\echo ''
\echo '=== 2. THE PAIR — from cached_evaluation, the store the UI reads ==='
-- results_json is a JSON ARRAY of BriefingEvaluationResult; potentials are camelCase.
-- The typeof guard keeps one malformed row from aborting the whole query.
WITH vals AS (
    SELECT (e ->> 'fierySkyPotential')::int   AS fiery,
           (e ->> 'goldenHourPotential')::int AS golden
    FROM cached_evaluation ce
    CROSS JOIN LATERAL jsonb_array_elements(ce.results_json::jsonb) AS e
    WHERE jsonb_typeof(ce.results_json::jsonb) = 'array'
)
SELECT count(fiery)  AS fiery_n,
       round(percentile_cont(0.05) WITHIN GROUP (ORDER BY fiery)::numeric,  1) AS fiery_lo,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY fiery)::numeric,  1) AS fiery_hi,
       count(golden) AS golden_n,
       round(percentile_cont(0.05) WITHIN GROUP (ORDER BY golden)::numeric, 1) AS golden_lo,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY golden)::numeric, 1) AS golden_hi
FROM vals;

\echo ''
\echo '=== 3. THE SHAPE — so p05/p95 is a decision, not a default ==='
-- If the spread is tight, a narrower pair discriminates better. If it is bimodal,
-- percentiles are the wrong tool and that must be known BEFORE shipping the mapping.
WITH vals AS (
    SELECT (e ->> 'fierySkyPotential')::int   AS fiery,
           (e ->> 'goldenHourPotential')::int AS golden
    FROM cached_evaluation ce
    CROSS JOIN LATERAL jsonb_array_elements(ce.results_json::jsonb) AS e
    WHERE jsonb_typeof(ce.results_json::jsonb) = 'array'
)
SELECT 'fiery' AS metric,
       min(fiery) AS min,
       round(percentile_cont(0.10) WITHIN GROUP (ORDER BY fiery)::numeric, 1) AS p10,
       round(percentile_cont(0.25) WITHIN GROUP (ORDER BY fiery)::numeric, 1) AS p25,
       round(percentile_cont(0.50) WITHIN GROUP (ORDER BY fiery)::numeric, 1) AS median,
       round(percentile_cont(0.75) WITHIN GROUP (ORDER BY fiery)::numeric, 1) AS p75,
       round(percentile_cont(0.90) WITHIN GROUP (ORDER BY fiery)::numeric, 1) AS p90,
       max(fiery) AS max
FROM vals
UNION ALL
SELECT 'golden',
       min(golden),
       round(percentile_cont(0.10) WITHIN GROUP (ORDER BY golden)::numeric, 1),
       round(percentile_cont(0.25) WITHIN GROUP (ORDER BY golden)::numeric, 1),
       round(percentile_cont(0.50) WITHIN GROUP (ORDER BY golden)::numeric, 1),
       round(percentile_cont(0.75) WITHIN GROUP (ORDER BY golden)::numeric, 1),
       round(percentile_cont(0.90) WITHIN GROUP (ORDER BY golden)::numeric, 1),
       max(golden)
FROM vals;
