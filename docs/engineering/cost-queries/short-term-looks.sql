-- Read-only, PRODUCTION. Sizes the next forecast-cost idea (owner, 2026-11-04): the overnight run does
-- far-term only, and a short-term series refreshes the next couple of windows, gated on stability.
-- Run:  docker exec -i goldenhour-db psql -U goldenhour -d goldenhour < docs/engineering/cost-queries/short-term-looks.sql
--
-- Four sections, each standalone:
--   1. How much each look moves the rating it replaces, by event, cycle pair and days ahead. The rows
--      that matter: SUNRISE  EVENING -> NIGHTLY at days_ahead 0 (what dropping the overnight sunrise
--      look would hide) and SUNSET  NIGHTLY -> INTRADAY at 0 (the overnight sunset look, plan §8).
--   2. The same moves split by the stability label at the time of the new rating.
--   3. Ground truth: for past events a Pro or Admin reader rated ("How was it?"), was the 21:00 rating
--      or the last rating before the event closer to what they saw?
--   4. Cost per 30 days by cycle, event, days ahead and model, from a date you set (the release that
--      shipped #1662), so savings are sized on the new baseline.
--
-- Noise floor: identical inputs move 1 star about 8.5% of the time (noise-floor.sql). So read a 1-star
-- move as mostly noise, a 2-star move as signal, and a flip across 3 stars (<=2 to >=4, or back) as a
-- change that would alter a decision to go.
-- A rating's cycle comes from its batch request (api_call_log -> forecast_batch -> pipeline_run), as in
-- rerating-waste.sql; a rating with no batch request (JFDI, manual) is cycle 'UNKNOWN'.

-- 1. Moves by look ----------------------------------------------------------------------------------
WITH cyc AS (
    SELECT DISTINCT ON (substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint)
           substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint AS eval_id,
           p.cycle_type
    FROM api_call_log a
    JOIN forecast_batch b ON b.anthropic_batch_id = a.batch_id
    JOIN pipeline_run p   ON p.id = b.pipeline_run_id
    WHERE a.is_batch AND a.succeeded AND a.custom_id LIKE 'fc-%'
      AND a.called_at >= now() - interval '45 days'
    ORDER BY substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint, a.called_at DESC
),
s AS (
    SELECT f.target_type, f.days_ahead,
           COALESCE(c.cycle_type, 'UNKNOWN') AS cycle_type,
           COALESCE(f.sky_rating, f.rating) AS stars,
           LAG(COALESCE(f.sky_rating, f.rating)) OVER w AS prev_stars,
           LAG(COALESCE(c.cycle_type, 'UNKNOWN')) OVER w AS prev_cycle,
           f.forecast_run_at - LAG(f.forecast_run_at) OVER w AS gap
    FROM forecast_evaluation f
    LEFT JOIN cyc c ON c.eval_id = f.id
    WHERE f.forecast_run_at >= now() - interval '45 days'
      AND COALESCE(f.sky_rating, f.rating) IS NOT NULL
    WINDOW w AS (PARTITION BY f.location_id, f.target_date, f.target_type ORDER BY f.forecast_run_at)
)
SELECT target_type                                                        AS event,
       prev_cycle || ' -> ' || cycle_type                                 AS look,
       days_ahead                                                         AS new_days_ahead,
       count(*)                                                           AS pairs,
       round(avg(extract(epoch FROM gap) / 3600)::numeric, 1)             AS mean_gap_h,
       round(100.0 * count(*) FILTER (WHERE stars = prev_stars) / count(*), 1)          AS pct_same,
       round(100.0 * count(*) FILTER (WHERE abs(stars - prev_stars) = 1) / count(*), 1) AS pct_1,
       round(100.0 * count(*) FILTER (WHERE abs(stars - prev_stars) >= 2) / count(*), 1) AS pct_ge_2,
       count(*) FILTER (WHERE prev_stars <= 2 AND stars >= 4)             AS flips_up,
       count(*) FILTER (WHERE prev_stars >= 4 AND stars <= 2)             AS flips_down
FROM s
WHERE prev_stars IS NOT NULL AND days_ahead IN (0, 1)
GROUP BY 1, 2, 3
HAVING count(*) >= 20
ORDER BY 1, 3, 2;

-- 2. The same moves by stability ---------------------------------------------------------------------
-- From evaluation_delta_log, which records the stability label (the latest classification at write
-- time) beside each re-rating. Only rows whose age is this location's own previous write (V140
-- age_basis 'LOCATION'). Each write's cycle is the pipeline run whose trigger..completion window holds
-- it; 'UNMATCHED' means none did (a JFDI or manual write). From late May to late July the afternoon and
-- evening runs can overlap, so the latest-triggered run is taken.
WITH runs AS (
    SELECT cycle_type, trigger_time,
           COALESCE(completed_at, trigger_time + interval '4 hours') + interval '5 minutes' AS ended
    FROM pipeline_run
    WHERE trigger_time >= now() - interval '50 days'
),
d AS (
    SELECT l.target_type, l.stability_level, l.old_rating, l.new_rating,
           l.evaluation_date - (l.new_evaluated_at AT TIME ZONE 'Europe/London')::date AS days_ahead,
           COALESCE((SELECT r.cycle_type FROM runs r
                     WHERE l.old_evaluated_at BETWEEN r.trigger_time AND r.ended
                     ORDER BY r.trigger_time DESC LIMIT 1), 'UNMATCHED') AS old_cycle,
           COALESCE((SELECT r.cycle_type FROM runs r
                     WHERE l.new_evaluated_at BETWEEN r.trigger_time AND r.ended
                     ORDER BY r.trigger_time DESC LIMIT 1), 'UNMATCHED') AS new_cycle
    FROM evaluation_delta_log l
    WHERE l.logged_at >= now() - interval '45 days'
      AND l.age_basis = 'LOCATION'
      AND l.old_rating IS NOT NULL AND l.new_rating IS NOT NULL
)
SELECT target_type                                                        AS event,
       old_cycle || ' -> ' || new_cycle                                   AS look,
       days_ahead                                                         AS new_days_ahead,
       stability_level,
       count(*)                                                           AS pairs,
       round(100.0 * count(*) FILTER (WHERE new_rating = old_rating) / count(*), 1)          AS pct_same,
       round(100.0 * count(*) FILTER (WHERE abs(new_rating - old_rating) >= 2) / count(*), 1) AS pct_ge_2,
       count(*) FILTER (WHERE old_rating <= 2 AND new_rating >= 4)        AS flips_up,
       count(*) FILTER (WHERE old_rating >= 4 AND new_rating <= 2)        AS flips_down
FROM d
WHERE days_ahead IN (0, 1)
GROUP BY 1, 2, 3, 4
HAVING count(*) >= 10
ORDER BY 1, 3, 2, 4;

-- 3. Ground truth: the 21:00 rating against the last rating before the event --------------------------
-- outcome_rating holds 1-5 stars a reader gave a past event (null = "didn't see it", excluded). For each,
-- the evening's last rating of that slot and the last rating of any cycle before the event (what was
-- served at the light). A rating counts only if it was AVAILABLE before the event (scored_at <= event
-- time, as ForecastEvaluationRepository's as-of-event query does): forecast_run_at is the submission time,
-- and a batch submitted before the light can land after it. Among those, the latest submission wins.
-- The sample is small; read the counts before the means.
WITH cyc AS (
    SELECT DISTINCT ON (substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint)
           substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint AS eval_id,
           p.cycle_type
    FROM api_call_log a
    JOIN forecast_batch b ON b.anthropic_batch_id = a.batch_id
    JOIN pipeline_run p   ON p.id = b.pipeline_run_id
    WHERE a.is_batch AND a.succeeded AND a.custom_id LIKE 'fc-%'
      AND a.called_at >= now() - interval '90 days'
    ORDER BY substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint, a.called_at DESC
),
ev AS (
    SELECT f.location_id, f.target_date, f.target_type, f.forecast_run_at, f.scored_at,
           COALESCE(c.cycle_type, 'UNKNOWN') AS cycle_type,
           COALESCE(f.sky_rating, f.rating) AS stars
    FROM forecast_evaluation f
    LEFT JOIN cyc c ON c.eval_id = f.id
    WHERE f.forecast_run_at >= now() - interval '90 days'
      AND COALESCE(f.sky_rating, f.rating) IS NOT NULL
),
o AS (
    SELECT o.event_type, o.rating AS observed,
           (SELECT ev.stars FROM ev
            WHERE ev.location_id = o.location_id AND ev.target_date = o.event_date
              AND ev.target_type = o.event_type AND ev.cycle_type = 'EVENING'
              AND ev.scored_at IS NOT NULL AND ev.scored_at <= o.event_time
            ORDER BY ev.forecast_run_at DESC LIMIT 1) AS evening_stars,
           (SELECT ev.stars FROM ev
            WHERE ev.location_id = o.location_id AND ev.target_date = o.event_date
              AND ev.target_type = o.event_type
              AND ev.scored_at IS NOT NULL AND ev.scored_at <= o.event_time
            ORDER BY ev.forecast_run_at DESC LIMIT 1) AS last_stars,
           (SELECT ev.cycle_type FROM ev
            WHERE ev.location_id = o.location_id AND ev.target_date = o.event_date
              AND ev.target_type = o.event_type
              AND ev.scored_at IS NOT NULL AND ev.scored_at <= o.event_time
            ORDER BY ev.forecast_run_at DESC LIMIT 1) AS last_cycle
    FROM outcome_rating o
    WHERE o.rating IS NOT NULL
      AND o.event_date >= current_date - 90
)
SELECT event_type                                                         AS event,
       last_cycle,
       count(*)                                                           AS rated_with_evening,
       round(avg(abs(evening_stars - observed)), 2)                       AS mean_err_evening,
       round(avg(abs(last_stars - observed)), 2)                          AS mean_err_last,
       count(*) FILTER (WHERE abs(evening_stars - observed) < abs(last_stars - observed)) AS evening_closer,
       count(*) FILTER (WHERE abs(last_stars - observed) < abs(evening_stars - observed)) AS last_closer,
       count(*) FILTER (WHERE abs(last_stars - observed) = abs(evening_stars - observed)) AS tied
FROM o
WHERE evening_stars IS NOT NULL
GROUP BY 1, 2
ORDER BY 1, 2;

-- 4. Cost per 30 days by cycle, event, days ahead and model, from a date ----------------------------
-- Set `since` to the day after the release that shipped #1662 (the evening takes tomorrow's sunset).
-- Counts every billed batch forecast request, retries included.
WITH params AS (
    SELECT timestamptz '2026-11-05 00:00 Europe/London' AS since
),
calls AS (
    SELECT p.cycle_type, a.evaluation_model, a.cost_micro_dollars,
           substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint AS eval_id
    FROM api_call_log a
    JOIN forecast_batch b ON b.anthropic_batch_id = a.batch_id
    JOIN pipeline_run p   ON p.id = b.pipeline_run_id
    CROSS JOIN params
    WHERE a.is_batch AND a.custom_id LIKE 'fc-%'
      AND a.cost_micro_dollars IS NOT NULL
      AND a.called_at >= params.since
),
span AS (
    SELECT greatest(1.0, extract(epoch FROM now() - since) / 86400.0) AS days FROM params
)
SELECT c.cycle_type,
       COALESCE(f.target_type, '?')                                       AS event,
       f.days_ahead,
       c.evaluation_model                                                 AS model,
       count(*)                                                           AS requests,
       round(sum(c.cost_micro_dollars) / 1e6, 2)                          AS usd,
       round((30.0 * sum(c.cost_micro_dollars) / 1e6 / span.days)::numeric, 2) AS usd_per_30_days
FROM calls c
LEFT JOIN forecast_evaluation f ON f.id = c.eval_id
CROSS JOIN span
GROUP BY c.cycle_type, f.target_type, f.days_ahead, c.evaluation_model, span.days
ORDER BY usd_per_30_days DESC;
