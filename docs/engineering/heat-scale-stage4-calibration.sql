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
\echo '=== 3. THE SHAPE — a histogram, because percentiles cannot see modality ==='
-- Six percentiles and min/max CANNOT distinguish unimodal from bimodal: both populations can
-- share every one of those summary values. The pre-ship question is specifically whether there
-- is a mass of stood-down slots near zero SEPARATE from a hump of real forecasts — if so, p05 is
-- measuring the floor of the wrong population and `lo` belongs at the upper mode's foot.
-- Read the counts: one hump = unimodal, two humps with a trough between = raise `lo` to the
-- trough. Also reports the zero-inflation probe, which is the specific shape suspected here.
WITH vals AS (
    SELECT (e ->> 'fierySkyPotential')::int   AS fiery,
           (e ->> 'goldenHourPotential')::int AS golden
    FROM cached_evaluation ce
    CROSS JOIN LATERAL jsonb_array_elements(ce.results_json::jsonb) AS e
    WHERE jsonb_typeof(ce.results_json::jsonb) = 'array'
)
SELECT g.b * 10 AS bucket_from,
       g.b * 10 + 9 AS bucket_to,
       count(*) FILTER (WHERE least(vals.fiery  / 10, 9) = g.b) AS fiery_n,
       count(*) FILTER (WHERE least(vals.golden / 10, 9) = g.b) AS golden_n
FROM vals
CROSS JOIN generate_series(0, 9) AS g(b)
GROUP BY g.b
ORDER BY g.b;

\echo ''
\echo '=== 3b. ZERO-INFLATION PROBE — is there a spike AT the bottom? ==='
-- A bucket histogram hides a spike sitting entirely inside bucket 0. If exact-zero or <=5
-- counts dominate that bucket, the low mass is stand-downs rather than a tail, and p05 is
-- calibrating against slots that were never scored as sky at all.
WITH vals AS (
    SELECT (e ->> 'fierySkyPotential')::int   AS fiery,
           (e ->> 'goldenHourPotential')::int AS golden
    FROM cached_evaluation ce
    CROSS JOIN LATERAL jsonb_array_elements(ce.results_json::jsonb) AS e
    WHERE jsonb_typeof(ce.results_json::jsonb) = 'array'
)
SELECT count(*) FILTER (WHERE fiery  = 0)  AS fiery_exactly_0,
       count(*) FILTER (WHERE fiery  <= 5) AS fiery_le_5,
       count(*) FILTER (WHERE fiery  < 10) AS fiery_lt_10,
       count(*) FILTER (WHERE golden = 0)  AS golden_exactly_0,
       count(*) FILTER (WHERE golden <= 5) AS golden_le_5,
       count(*) FILTER (WHERE golden < 10) AS golden_lt_10
FROM vals;
