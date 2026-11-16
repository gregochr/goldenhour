-- Read-only, PRODUCTION. Could a T+0/1 re-rating be skipped when the SUN-SIDE weather has barely moved
-- since the previous Sonnet rating of the same slot? (claude-cost-ledger.md, "Sun-side change gate".)
-- Run with:
--   docker exec -i goldenhour-db psql -U goldenhour -d goldenhour < docs/engineering/cost-queries/sun-side-change-gate.sql
--
-- The shelved Phase 2 gate (input-delta.sql) took the largest change across EVERY cloud field, overhead and
-- antisolar included, and found the inputs almost never hold still. This gate looks only at what a rating
-- mostly turns on: solar-horizon low/mid/high cloud, far-solar low cloud (thin strip), rain and visibility.
--
-- A pair = a Sonnet T+0/1 rating and the previous Sonnet rating of the same (location, date, event). A
-- skipped look would have served the previous stars, so "moved" is what skipping would have hidden.
-- sun_delta = largest change, in points, across the four sun-side cloud fields; a field missing on either
-- side makes the pair ineligible (never skipped). Rain counts as unchanged when both are under 0.2 mm or the
-- change is under 0.2 mm; visibility when both are 10 km or more or the change is under 2 km.
--
-- DECISION RULE (set before running): a threshold is worth building only if, over its skippable pairs,
-- moved 2 stars or more is under 1%, and crossings of the 4-star line (either way) are no more than the
-- 1-star noise floor allows (noise-floor.sql: ~8.5% of repeats move 1 star, so crossings well under that).
-- The TEMP VIEW is session-local: nothing persists in the database.

CREATE TEMP VIEW gate_cyc AS
SELECT DISTINCT ON (substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint)
       substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint AS eval_id,
       p.cycle_type, a.cost_micro_dollars
FROM api_call_log a
JOIN forecast_batch b ON b.anthropic_batch_id = a.batch_id
JOIN pipeline_run p   ON p.id = b.pipeline_run_id
WHERE a.is_batch AND a.succeeded AND a.custom_id LIKE 'fc-%'
  AND a.called_at >= now() - interval '30 days'
ORDER BY substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint, a.called_at DESC;

CREATE TEMP VIEW gate_pairs AS
WITH s AS (
    SELECT f.id, f.target_type, f.days_ahead,
           COALESCE(c.cycle_type, 'UNKNOWN') AS cycle_type,
           COALESCE(c.cost_micro_dollars, 0) AS cost_micro,
           COALESCE(f.sky_rating, f.rating) AS stars,
           f.solar_low_cloud AS sl, f.solar_mid_cloud AS sm, f.solar_high_cloud AS sh,
           f.far_solar_low_cloud AS fl, f.precipitation AS pr, f.visibility AS vis,
           f.forecast_run_at,
           LAG(COALESCE(f.sky_rating, f.rating)) OVER w AS p_stars,
           LAG(f.solar_low_cloud) OVER w AS p_sl, LAG(f.solar_mid_cloud) OVER w AS p_sm,
           LAG(f.solar_high_cloud) OVER w AS p_sh, LAG(f.far_solar_low_cloud) OVER w AS p_fl,
           LAG(f.precipitation) OVER w AS p_pr, LAG(f.visibility) OVER w AS p_vis,
           LAG(f.forecast_run_at) OVER w AS p_run_at,
           LEAD(COALESCE(f.sky_rating, f.rating)) OVER w AS n_stars
    FROM forecast_evaluation f
    LEFT JOIN gate_cyc c ON c.eval_id = f.id
    WHERE f.forecast_run_at >= now() - interval '30 days'
      AND f.target_type IN ('SUNRISE', 'SUNSET')
      AND f.days_ahead <= 1
      AND f.evaluation_model = 'SONNET_55'
      AND COALESCE(f.sky_rating, f.rating) IS NOT NULL
    WINDOW w AS (PARTITION BY f.location_id, f.target_date, f.target_type ORDER BY f.forecast_run_at)
)
SELECT *,
       CASE WHEN sl IS NULL OR sm IS NULL OR sh IS NULL OR fl IS NULL
                 OR p_sl IS NULL OR p_sm IS NULL OR p_sh IS NULL OR p_fl IS NULL THEN NULL
            ELSE GREATEST(abs(sl - p_sl), abs(sm - p_sm), abs(sh - p_sh), abs(fl - p_fl)) END AS sun_delta,
       ((COALESCE(pr, 0) < 0.2 AND COALESCE(p_pr, 0) < 0.2)
         OR abs(COALESCE(pr, 0) - COALESCE(p_pr, 0)) < 0.2)                                  AS rain_same,
       ((vis >= 10000 AND p_vis >= 10000) OR abs(vis - p_vis) < 2000)                        AS vis_same,
       ((stars >= 4) <> (p_stars >= 4))                                                      AS crossed_4
FROM s
WHERE p_stars IS NOT NULL;

-- 1. Stars movement by sun-side change band (rain and visibility unchanged), with all re-ratings for scale.
SELECT CASE WHEN sun_delta IS NULL THEN 'z ineligible (missing field)'
            WHEN NOT (rain_same AND vis_same) THEN 'y rain or visibility moved'
            WHEN sun_delta = 0  THEN 'a 0'
            WHEN sun_delta <= 5 THEN 'b 1-5'
            WHEN sun_delta <= 10 THEN 'c 6-10'
            WHEN sun_delta <= 15 THEN 'd 11-15'
            WHEN sun_delta <= 25 THEN 'e 16-25'
            ELSE 'f >25' END                                                    AS sun_band,
       count(*)                                                                AS pairs,
       round(sum(cost_micro) / 1e6, 2)                                         AS usd_30d,
       round(100.0 * count(*) FILTER (WHERE stars = p_stars) / count(*), 1)    AS pct_same,
       round(100.0 * count(*) FILTER (WHERE abs(stars - p_stars) = 1) / count(*), 1) AS pct_moved_1,
       round(100.0 * count(*) FILTER (WHERE abs(stars - p_stars) >= 2) / count(*), 2) AS pct_moved_2plus,
       count(*) FILTER (WHERE crossed_4)                                       AS crossed_4,
       round(100.0 * count(*) FILTER (WHERE crossed_4) / count(*), 2)          AS pct_crossed_4
FROM gate_pairs
GROUP BY 1
ORDER BY 1;

-- 2. Candidate gates: skip when rain and visibility are unchanged and sun_delta <= N, by cycle.
SELECT g.threshold, p.cycle_type,
       count(*)                                                                AS skippable,
       round(sum(p.cost_micro) / 1e6, 2)                                       AS usd_30d,
       count(*) FILTER (WHERE abs(p.stars - p.p_stars) >= 2)                   AS moved_2plus,
       round(100.0 * count(*) FILTER (WHERE abs(p.stars - p.p_stars) >= 2) / count(*), 2) AS pct_moved_2plus,
       count(*) FILTER (WHERE p.crossed_4)                                     AS crossed_4,
       round(100.0 * count(*) FILTER (WHERE p.crossed_4) / count(*), 2)        AS pct_crossed_4,
       round(avg(EXTRACT(EPOCH FROM (p.forecast_run_at - p.p_run_at)) / 3600), 1) AS avg_gap_h
FROM (VALUES (5), (10), (15)) AS g(threshold)
JOIN gate_pairs p ON p.sun_delta IS NOT NULL AND p.rain_same AND p.vis_same AND p.sun_delta <= g.threshold
GROUP BY ROLLUP (g.threshold, p.cycle_type)
HAVING g.threshold IS NOT NULL
ORDER BY g.threshold, p.cycle_type NULLS LAST;

-- 4. Is the afternoon "just today's sunset"? What the afternoon (INTRADAY) run re-rates, what it costs, how
--    often it moves the stars, and, when it moved them, whether the NEXT look (normally the evening or the
--    next overnight) sided with the afternoon's new stars or went back to the earlier ones. A low
--    "next_kept_change" means the afternoon's moves were mostly reversed: dropping that look would have
--    cost little freshness.
SELECT CASE WHEN days_ahead <= 0 THEN 'today' ELSE 'tomorrow' END              AS horizon,
       target_type,
       count(*)                                                                AS afternoon_looks,
       round(sum(cost_micro) / 1e6, 2)                                         AS usd_30d,
       round(100.0 * count(*) FILTER (WHERE stars <> p_stars) / count(*), 1)   AS pct_moved,
       round(100.0 * count(*) FILTER (WHERE abs(stars - p_stars) >= 2) / count(*), 2) AS pct_moved_2plus,
       count(*) FILTER (WHERE crossed_4)                                       AS crossed_4,
       count(*) FILTER (WHERE stars <> p_stars AND n_stars IS NOT NULL)        AS moved_with_next,
       round(100.0 * count(*) FILTER (WHERE stars <> p_stars AND n_stars IS NOT NULL
                                        AND abs(n_stars - stars) < abs(n_stars - p_stars))
             / NULLIF(count(*) FILTER (WHERE stars <> p_stars AND n_stars IS NOT NULL), 0), 1)
                                                                               AS pct_next_kept_change
FROM gate_pairs
WHERE cycle_type = 'INTRADAY'
GROUP BY 1, 2
ORDER BY 1 DESC, 2;

-- 5. Scale: every Sonnet T+0/1 re-rating in the window, and its cost.
SELECT count(*) AS all_rerating_pairs, round(sum(cost_micro) / 1e6, 2) AS usd_30d
FROM gate_pairs;
