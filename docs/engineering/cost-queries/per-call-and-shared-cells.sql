-- Read-only, PRODUCTION. Two levers on the cost of rating good days that the ledger has not measured
-- (claude-cost-ledger.md, "Open: cost per call and shared cells"). Run with:
--   docker exec -i goldenhour-db psql -U goldenhour -d goldenhour < docs/engineering/cost-queries/per-call-and-shared-cells.sql

-- 1. What one rating call is made of, by model and cycle, last 30 days. Sonnet 5.5 runs with adaptive
--    thinking at effort low, so output tokens include thinking: a long p90 tail means thinking, not the
--    answer, is a large part of the bill.
WITH cyc AS (
    SELECT DISTINCT ON (a.id) a.id, p.cycle_type
    FROM api_call_log a
    JOIN forecast_batch b ON b.anthropic_batch_id = a.batch_id
    JOIN pipeline_run p   ON p.id = b.pipeline_run_id
    WHERE a.is_batch AND a.succeeded AND a.custom_id LIKE 'fc-%'
      AND a.called_at >= now() - interval '30 days'
)
SELECT a.evaluation_model, c.cycle_type,
       count(*)                                                        AS requests,
       round(sum(a.cost_micro_dollars) / 1e6, 2)                       AS usd,
       round(avg(a.cost_micro_dollars))                                AS micro_usd_per_call,
       round(avg(a.input_tokens))                                      AS avg_input,
       round(avg(a.cache_read_input_tokens))                           AS avg_cache_read,
       round(avg(a.cache_creation_input_tokens))                       AS avg_cache_write,
       round(avg(a.output_tokens))                                     AS avg_output,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY a.output_tokens)    AS p50_output,
       percentile_cont(0.9) WITHIN GROUP (ORDER BY a.output_tokens)    AS p90_output,
       max(a.output_tokens)                                            AS max_output
FROM api_call_log a
JOIN cyc c ON c.id = a.id
GROUP BY 1, 2
ORDER BY usd DESC;

-- 2. Places that share an Open-Meteo grid cell (~2 km), so they read the same weather.
SELECT count(*)                                              AS enabled_locations,
       count(DISTINCT (grid_lat, grid_lng))                  AS distinct_cells,
       count(*) - count(DISTINCT (grid_lat, grid_lng))       AS extra_locations_in_shared_cells
FROM locations
WHERE enabled AND grid_lat IS NOT NULL;

-- 3. Within one run, how often do two places in the same cell get the same sun-side inputs, and do
--    they get the same stars? If the inputs match and the stars match, one call could serve both.
WITH r AS (
    SELECT f.forecast_run_at, f.target_date, f.target_type, l.grid_lat, l.grid_lng,
           f.solar_low_cloud, f.solar_mid_cloud, f.solar_high_cloud, f.low_cloud, f.mid_cloud,
           f.high_cloud, f.precipitation, f.visibility,
           COALESCE(f.sky_rating, f.rating) AS stars
    FROM forecast_evaluation f
    JOIN locations l ON l.id = f.location_id
    WHERE f.forecast_run_at >= now() - interval '30 days'
      AND f.target_type IN ('SUNRISE', 'SUNSET') AND f.days_ahead <= 1
      AND COALESCE(f.sky_rating, f.rating) IS NOT NULL
      AND l.grid_lat IS NOT NULL
),
g AS (
    SELECT grid_lat, grid_lng, target_date, target_type, date_trunc('hour', forecast_run_at) AS run_hour,
           count(*) AS places,
           count(DISTINCT (solar_low_cloud, solar_mid_cloud, solar_high_cloud, low_cloud, mid_cloud,
                           high_cloud, precipitation, visibility)) AS distinct_inputs,
           max(stars) - min(stars) AS star_spread
    FROM r
    GROUP BY 1, 2, 3, 4, 5
    HAVING count(*) > 1
)
SELECT count(*)                                                  AS shared_cell_groups,
       sum(places)                                               AS rated_places_in_them,
       sum(places - 1)                                           AS calls_one_per_cell_would_save,
       count(*) FILTER (WHERE distinct_inputs = 1)               AS groups_with_identical_inputs,
       count(*) FILTER (WHERE star_spread = 0)                   AS groups_same_stars,
       count(*) FILTER (WHERE star_spread >= 2)                  AS groups_spread_2_plus
FROM g;
