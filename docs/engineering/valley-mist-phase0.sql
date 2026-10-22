-- Phase M0 of docs/engineering/valley-mist-plan.md — measure before building.
--
-- Run on the PRODUCTION host (local H2 has no representative data).
-- Container / user / db from docker-compose.yml: goldenhour-db / goldenhour / goldenhour.
-- Read-only: four SELECTs. No writes.
--
-- forecast_evaluation holds a row for EVERY slot whose weather was fetched — triaged slots included
-- (ForecastService.fetchWeatherAndTriage saves the stand-down row) — so its predictor columns are the
-- population, not only the scored rows. HOURLY rows (wildlife comfort) are excluded throughout.
--
-- ⚠️ One row per RUN, not per morning. A slot is forecast by the nightly cycle, the intraday cycle and
-- any hand-started or retry run, so a single morning can hold several rows whose predictions evolve.
-- Every count below therefore reads ONE representative row per (location, date, event): the LATEST
-- run made BEFORE the event, which is the forecast a reader could have acted on. Both instants are
-- naive UTC (forecast_run_at is stamped LocalDateTime.now(UTC); solar_event_time is the UTC event),
-- so they compare directly. A slot with no pre-event row is not counted anywhere.

\echo '=== 1. LOW_VISIBILITY stand-downs, 180 days, and the share with the photographable signature ==='
-- signature = clear above (low cloud < 30), calm (wind <= 3 m/s), near-saturated (temp - dew < 2 C).
-- These are the mornings the pipeline refused that the sky prompt's own MIST guidance describes.
-- Each morning counts ONCE: its latest pre-event run decides whether it was triaged and why.
WITH pre_event AS (
    SELECT DISTINCT ON (fe.location_id, fe.target_date, fe.target_type)
           fe.location_id, fe.target_date, fe.target_type, fe.triage_reason,
           fe.low_cloud, fe.wind_speed, fe.visibility,
           (fe.temperature_celsius - fe.dew_point_celsius) AS gap
    FROM forecast_evaluation fe
    WHERE fe.target_type IN ('SUNRISE', 'SUNSET')
      AND fe.target_date >= current_date - INTERVAL '180 days'
      AND fe.solar_event_time IS NOT NULL
      AND fe.forecast_run_at < fe.solar_event_time
    ORDER BY fe.location_id, fe.target_date, fe.target_type, fe.forecast_run_at DESC
)
SELECT l.name, p.target_type,
       count(*)                                                                   AS triaged_mornings,
       count(*) FILTER (WHERE p.low_cloud < 30 AND p.wind_speed <= 3 AND p.gap < 2) AS photographable,
       round(avg(p.visibility))                                                    AS mean_vis_m,
       round(avg(p.gap)::numeric, 1)                                               AS mean_gap_c
FROM pre_event p
JOIN locations l ON l.id = p.location_id
WHERE p.triage_reason = 'LOW_VISIBILITY'
GROUP BY l.name, p.target_type
ORDER BY photographable DESC, triaged_mornings DESC
LIMIT 60;

\echo ''
\echo '=== 1b. The signature over EVERY LOW_VISIBILITY morning (whole population, no LIMIT) ==='
-- Query 1 is sorted by the photographable count and cut at 60 groups, so its total is an enriched slice
-- and a FLOOR, never an estimate of the whole (a Codex review of #1268). This is the whole.
WITH pre_event AS (
    SELECT DISTINCT ON (fe.location_id, fe.target_date, fe.target_type)
           fe.target_type, fe.triage_reason, fe.low_cloud, fe.wind_speed,
           (fe.temperature_celsius - fe.dew_point_celsius) AS gap
    FROM forecast_evaluation fe
    WHERE fe.target_type IN ('SUNRISE', 'SUNSET')
      AND fe.target_date >= current_date - INTERVAL '180 days'
      AND fe.solar_event_time IS NOT NULL
      AND fe.forecast_run_at < fe.solar_event_time
    ORDER BY fe.location_id, fe.target_date, fe.target_type, fe.forecast_run_at DESC
)
SELECT target_type,
       count(*)                                                                   AS triaged_low_vis,
       count(*) FILTER (WHERE low_cloud < 30 AND wind_speed <= 3 AND gap < 2)      AS photographable,
       count(*) FILTER (WHERE low_cloud < 30 AND wind_speed <= 3)                  AS clear_and_calm
FROM pre_event
WHERE triage_reason = 'LOW_VISIBILITY'
GROUP BY target_type;

\echo ''
\echo '=== 2. The same signature on mornings that were NOT triaged (what the prompt already sees) ==='
-- The baseline is the population that actually REACHED the sky prompt: triage_reason IS NULL. A row
-- stood down for precipitation or horizon cloud never did, whatever its visibility, so it is not in
-- the haze-band figure. The triaged_low_vis column is the complement, for the ratio.
WITH pre_event AS (
    SELECT DISTINCT ON (fe.location_id, fe.target_date, fe.target_type)
           fe.target_type, fe.triage_reason, fe.visibility, fe.low_cloud, fe.wind_speed,
           (fe.temperature_celsius - fe.dew_point_celsius) AS gap
    FROM forecast_evaluation fe
    WHERE fe.target_type IN ('SUNRISE', 'SUNSET')
      AND fe.target_date >= current_date - INTERVAL '180 days'
      AND fe.solar_event_time IS NOT NULL
      AND fe.forecast_run_at < fe.solar_event_time
    ORDER BY fe.location_id, fe.target_date, fe.target_type, fe.forecast_run_at DESC
)
SELECT target_type,
       count(*)                                                                   AS mornings,
       count(*) FILTER (WHERE triage_reason IS NULL)                               AS reached_prompt,
       count(*) FILTER (WHERE triage_reason IS NULL
                          AND visibility BETWEEN 5000 AND 10000
                          AND low_cloud < 30 AND wind_speed <= 3 AND gap < 2)     AS haze_band_signature,
       count(*) FILTER (WHERE triage_reason = 'LOW_VISIBILITY')                    AS triaged_low_vis
FROM pre_event
GROUP BY target_type;

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
\echo '=== 4. 17 Oct 2026 SUNRISE — what the model said, every roster location (latest run BEFORE the event) ==='
-- The cutoff is the point: a run written after sunrise knows the morning, and the comparison against
-- the photograph is only honest for the forecast a reader could have had the night before.
SELECT DISTINCT ON (fe.location_id)
       l.name, fe.forecast_run_at, fe.solar_event_time, fe.days_ahead, fe.triage_reason, fe.rating,
       fe.visibility, fe.low_cloud, fe.mid_cloud, fe.high_cloud, fe.wind_speed, fe.humidity,
       round((fe.temperature_celsius - fe.dew_point_celsius)::numeric, 1) AS gap_c
FROM forecast_evaluation fe
JOIN locations l ON l.id = fe.location_id
WHERE fe.target_date = DATE '2026-10-17' AND fe.target_type = 'SUNRISE'
  AND fe.solar_event_time IS NOT NULL
  AND fe.forecast_run_at < fe.solar_event_time
ORDER BY fe.location_id, fe.forecast_run_at DESC;
