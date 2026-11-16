-- Read-only, PRODUCTION. Replays the sun-side change gate AS SPECIFIED (sun-side-gate-plan.md) over real
-- Sonnet T+0/1 ratings, instead of comparing consecutive ratings as sun-side-change-gate.sql did.
-- Run with:
--   docker exec -i goldenhour-db psql -U goldenhour -d goldenhour < docs/engineering/cost-queries/sun-side-gate-replay.sql
--
-- Per slot (location, date, event), looks are walked in order. The first look is a rating and becomes the
-- ANCHOR. Each later look is skipped when, against the anchor (not the previous look): the four sun-side
-- cloud fields moved <= threshold, rain and visibility held, and the anchor is no older than max_age_h.
-- A skipped look serves the anchor's stars and leaves the anchor unchanged; a rated look becomes the anchor.
-- "Hidden" moves compare the stars a skip would have served against the stars that look actually got.
--
-- The pool is today's regime, not the old one: only looks from 2026-11-05 (after #1601 and #1662 left the
-- afternoon's looks at tomorrow to the evening refresh), with the afternoon's T+1 looks dropped, and with
-- looks the 60% rule (#1838) would now stand down dropped (solar low cloud over 60 and not a thin strip:
-- near >= 50 and near - far >= 30). Dollars are scaled to 30 days.
-- The TEMP VIEWs are session-local: nothing persists in the database.

CREATE TEMP VIEW replay_cyc AS
SELECT DISTINCT ON (substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint)
       substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint AS eval_id,
       p.cycle_type, a.cost_micro_dollars
FROM api_call_log a
JOIN forecast_batch b ON b.anthropic_batch_id = a.batch_id
JOIN pipeline_run p   ON p.id = b.pipeline_run_id
WHERE a.is_batch AND a.succeeded AND a.custom_id LIKE 'fc-%'
  AND a.called_at >= timestamp '2026-11-05'
ORDER BY substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint, a.called_at DESC;

CREATE TEMP VIEW replay_looks AS
SELECT f.location_id, f.target_date, f.target_type,
       COALESCE(c.cycle_type, 'UNKNOWN') AS cycle_type,
       COALESCE(c.cost_micro_dollars, 0) AS cost_micro,
       COALESCE(f.sky_rating, f.rating) AS stars,
       f.solar_low_cloud AS sl, f.solar_mid_cloud AS sm, f.solar_high_cloud AS sh,
       f.far_solar_low_cloud AS fl, f.precipitation AS pr, f.visibility AS vis,
       f.forecast_run_at AS run_at,
       row_number() OVER (PARTITION BY f.location_id, f.target_date, f.target_type
                          ORDER BY f.forecast_run_at) AS rn
FROM forecast_evaluation f
LEFT JOIN replay_cyc c ON c.eval_id = f.id
WHERE f.forecast_run_at >= timestamp '2026-11-05'
  AND f.target_type IN ('SUNRISE', 'SUNSET')
  AND f.days_ahead <= 1
  AND f.evaluation_model = 'SONNET_55'
  AND f.batch_state = 'SCORED'
  AND COALESCE(f.sky_rating, f.rating) IS NOT NULL
  AND NOT (COALESCE(c.cycle_type, '') = 'INTRADAY' AND f.days_ahead = 1)
  AND NOT (COALESCE(f.solar_low_cloud, 0) > 60
           AND NOT (f.solar_low_cloud >= 50 AND f.far_solar_low_cloud IS NOT NULL
                    AND f.solar_low_cloud - f.far_solar_low_cloud >= 30));

CREATE TEMP VIEW replay_sim AS
WITH RECURSIVE sim AS (
    SELECT g.threshold, g.max_age_h, l.location_id, l.target_date, l.target_type, l.rn, l.cycle_type,
           l.cost_micro, l.stars, false AS skipped, l.stars AS served, 0::numeric AS anchor_age_h,
           l.stars AS a_stars, l.sl AS a_sl, l.sm AS a_sm, l.sh AS a_sh, l.fl AS a_fl,
           l.pr AS a_pr, l.vis AS a_vis, l.run_at AS a_at
    FROM replay_looks l
    CROSS JOIN (VALUES (10, 18), (15, 18), (10, 12)) AS g(threshold, max_age_h)
    WHERE l.rn = 1
    UNION ALL
    SELECT s.threshold, s.max_age_h, n.location_id, n.target_date, n.target_type, n.rn, n.cycle_type,
           n.cost_micro, n.stars, k.skip,
           CASE WHEN k.skip THEN s.a_stars ELSE n.stars END,
           (EXTRACT(EPOCH FROM (n.run_at - s.a_at)) / 3600)::numeric,
           CASE WHEN k.skip THEN s.a_stars ELSE n.stars END,
           CASE WHEN k.skip THEN s.a_sl ELSE n.sl END,
           CASE WHEN k.skip THEN s.a_sm ELSE n.sm END,
           CASE WHEN k.skip THEN s.a_sh ELSE n.sh END,
           CASE WHEN k.skip THEN s.a_fl ELSE n.fl END,
           CASE WHEN k.skip THEN s.a_pr ELSE n.pr END,
           CASE WHEN k.skip THEN s.a_vis ELSE n.vis END,
           CASE WHEN k.skip THEN s.a_at ELSE n.run_at END
    FROM sim s
    JOIN replay_looks n ON n.location_id = s.location_id AND n.target_date = s.target_date
                       AND n.target_type = s.target_type AND n.rn = s.rn + 1
    CROSS JOIN LATERAL (
        SELECT COALESCE(
            n.sl IS NOT NULL AND n.sm IS NOT NULL AND n.sh IS NOT NULL AND n.fl IS NOT NULL
            AND s.a_sl IS NOT NULL AND s.a_sm IS NOT NULL AND s.a_sh IS NOT NULL AND s.a_fl IS NOT NULL
            AND GREATEST(abs(n.sl - s.a_sl), abs(n.sm - s.a_sm), abs(n.sh - s.a_sh), abs(n.fl - s.a_fl))
                <= s.threshold
            AND ((COALESCE(n.pr, 0) < 0.2 AND COALESCE(s.a_pr, 0) < 0.2)
                 OR abs(COALESCE(n.pr, 0) - COALESCE(s.a_pr, 0)) < 0.2)
            AND n.vis IS NOT NULL AND s.a_vis IS NOT NULL
            AND ((n.vis >= 10000 AND s.a_vis >= 10000) OR abs(n.vis - s.a_vis) < 2000)
            AND n.run_at - s.a_at <= make_interval(hours => s.max_age_h),
            false) AS skip
    ) k
)
SELECT * FROM sim;

-- 1. The gate as specified: re-ratings in today's pool, what it skips, what it saves per 30 days, and what
--    the skipped looks would have hidden (served anchor stars against the stars the look actually got).
SELECT threshold, max_age_h, COALESCE(cycle_type, 'ALL')                         AS cycle_type,
       count(*)                                                                  AS rerates,
       count(*) FILTER (WHERE skipped)                                           AS skipped,
       round(100.0 * count(*) FILTER (WHERE skipped) / count(*), 1)              AS pct_skipped,
       round(sum(cost_micro) FILTER (WHERE skipped) / 1e6
             * 30 / (EXTRACT(EPOCH FROM (now() - timestamp '2026-11-05')) / 86400), 2) AS usd_per_30d,
       round(100.0 * count(*) FILTER (WHERE skipped AND abs(stars - served) = 1)
             / NULLIF(count(*) FILTER (WHERE skipped), 0), 1)                    AS pct_hidden_1,
       round(100.0 * count(*) FILTER (WHERE skipped AND abs(stars - served) >= 2)
             / NULLIF(count(*) FILTER (WHERE skipped), 0), 2)                    AS pct_hidden_2plus,
       count(*) FILTER (WHERE skipped AND (stars >= 4) <> (served >= 4))         AS hidden_crossed_4
FROM replay_sim
WHERE rn > 1
GROUP BY GROUPING SETS ((threshold, max_age_h, cycle_type), (threshold, max_age_h))
ORDER BY threshold, max_age_h, cycle_type NULLS LAST;

-- 2. How old the kept rating is when a look is skipped (hours since the anchor rating), per setting.
SELECT threshold, max_age_h, count(*) AS skipped,
       round(avg(anchor_age_h), 1)                                               AS avg_age_h,
       round(percentile_cont(0.5) WITHIN GROUP (ORDER BY anchor_age_h)::numeric, 1) AS p50_age_h,
       round(percentile_cont(0.9) WITHIN GROUP (ORDER BY anchor_age_h)::numeric, 1) AS p90_age_h,
       count(*) FILTER (WHERE target_date = (now() AT TIME ZONE 'Europe/London')::date) AS on_today_dates
FROM replay_sim
WHERE rn > 1 AND skipped
GROUP BY 1, 2
ORDER BY 1, 2;
