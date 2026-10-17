-- Phase M0 of docs/engineering/valley-mist-plan.md — measure before building.
--
-- Run on the PRODUCTION host (local H2 has no representative data).
-- Container / user / db from docker-compose.yml: goldenhour-db / goldenhour / goldenhour.
-- Read-only: four SELECTs. No writes.
--
-- forecast_evaluation holds a row for EVERY slot whose weather was fetched — triaged slots included
-- (ForecastService.fetchWeatherAndTriage saves the stand-down row) — so its predictor columns are the
-- population, not only the scored rows. HOURLY rows (wildlife comfort) are excluded throughout.

\echo '=== 1. LOW_VISIBILITY stand-downs, 180 days, and the share with the photographable signature ==='
-- signature = clear above (low cloud < 30), calm (wind <= 3 m/s), near-saturated (temp - dew < 2 C).
-- These are the mornings the pipeline refused that the sky prompt's own MIST guidance describes.
WITH rows AS (
    SELECT fe.location_id, l.name, fe.target_type, fe.target_date,
           fe.low_cloud, fe.wind_speed, fe.visibility,
           (fe.temperature_celsius - fe.dew_point_celsius) AS gap
    FROM forecast_evaluation fe
    JOIN locations l ON l.id = fe.location_id
    WHERE fe.triage_reason = 'LOW_VISIBILITY'
      AND fe.target_type IN ('SUNRISE', 'SUNSET')
      AND fe.target_date >= current_date - INTERVAL '180 days'
)
SELECT name, target_type,
       count(*)                                                                   AS triaged,
       count(*) FILTER (WHERE low_cloud < 30 AND wind_speed <= 3 AND gap < 2)      AS photographable,
       count(DISTINCT target_date)                                                 AS days,
       round(avg(visibility))                                                      AS mean_vis_m,
       round(avg(gap)::numeric, 1)                                                 AS mean_gap_c
FROM rows
GROUP BY name, target_type
ORDER BY photographable DESC, triaged DESC
LIMIT 60;

\echo ''
\echo '=== 2. The same signature on rows that were NOT triaged (what the prompt already sees) ==='
SELECT fe.target_type,
       count(*)                                                                   AS fetched,
       count(*) FILTER (WHERE fe.visibility < 5000)                                AS under_5km,
       count(*) FILTER (WHERE fe.visibility BETWEEN 5000 AND 10000
                          AND fe.low_cloud < 30 AND fe.wind_speed <= 3
                          AND (fe.temperature_celsius - fe.dew_point_celsius) < 2) AS haze_band_signature,
       count(*) FILTER (WHERE fe.triage_reason = 'LOW_VISIBILITY')                 AS triaged_low_vis
FROM forecast_evaluation fe
WHERE fe.target_type IN ('SUNRISE', 'SUNSET')
  AND fe.target_date >= current_date - INTERVAL '180 days'
GROUP BY fe.target_type;

\echo ''
\echo '=== 3. Shortlist of plausibly mist-prone sites from stored metadata (for the owner to tick, not a derivation) ==='
SELECT l.id, l.name, r.name AS region, l.elevation_m, l.is_coastal_tidal,
       string_agg(lt.location_type, ',' ORDER BY lt.location_type) AS types
FROM locations l
LEFT JOIN regions r ON r.id = l.region_id
LEFT JOIN location_location_type lt ON lt.location_id = l.id
WHERE l.enabled
  AND COALESCE(l.elevation_m, 0) <= 150
  AND NOT l.is_coastal_tidal
GROUP BY l.id, l.name, r.name, l.elevation_m, l.is_coastal_tidal
HAVING string_agg(lt.location_type, ',') NOT LIKE '%WILDLIFE%'
   AND string_agg(lt.location_type, ',') NOT LIKE '%WOODLAND%'
ORDER BY l.elevation_m NULLS LAST, l.name;

\echo ''
\echo '=== 4. 17 Oct 2026 SUNRISE — what the model said, every roster location (nearest run before the event) ==='
SELECT DISTINCT ON (fe.location_id)
       l.name, fe.forecast_run_at, fe.days_ahead, fe.triage_reason, fe.rating,
       fe.visibility, fe.low_cloud, fe.mid_cloud, fe.high_cloud, fe.wind_speed, fe.humidity,
       round((fe.temperature_celsius - fe.dew_point_celsius)::numeric, 1) AS gap_c
FROM forecast_evaluation fe
JOIN locations l ON l.id = fe.location_id
WHERE fe.target_date = DATE '2026-10-17' AND fe.target_type = 'SUNRISE'
ORDER BY fe.location_id, fe.forecast_run_at DESC;
