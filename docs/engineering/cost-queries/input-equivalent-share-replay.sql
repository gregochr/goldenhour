-- Read-only, PRODUCTION. Phase 0 of input-equivalent-share-plan.md (revised after the 2026-11-16 review):
-- would one request per group of places whose prompts are equal after a ladder of masks have been SAFE
-- and WORTH IT? Uses forecast_evaluation_prompt (V164, the exact user message, kept 30 days).
-- Run on or after 2026-11-29, so the 30-day window is mostly post-P1 (the 60% triage line, 2026-11-15):
--   docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 \
--     < docs/engineering/cost-queries/input-equivalent-share-replay.sql
--
-- Population: Sonnet 5.5, T+0/1, SUNRISE/SUNSET sky requests in scheduled cycles (NIGHTLY, INTRADAY,
-- EVENING), retry batches excluded, and only rows P1 would still submit (sun-side low cloud <= 60%, or a
-- thin strip: solar low >= 50 and solar low - far low >= 30). The grouping unit is one forecast batch (one
-- submitForecast call), the only scope production could share within.
--
-- Ladder (cumulative; every level keeps the date, event, model, system-prompt hash and every bracketed
-- rule label). Masks replace numbers or place geometry only:
--   L0  "Location: <name>. " prefix removed (identical prompts apart from the name: the CONTROL)
--   L1  + event time cut to the minute
--   L2  + event time removed (no rule reads the clock time)
--   L3  + upwind sample geometry "(43km along 244° WSW)" removed; current=/at-event= values kept
--   L4  + approach-trend history slots T-3h..T-1h masked; event slot and [BUILDING]/[CLEARING] kept
--   L5  REFERENCE ONLY, never adoptable: + mist/visibility trend numbers masked (rules read them directly)
--
-- Representative of a group: lowest md5(location_id || date || event) (a deterministic rotation, the same
-- rule Phase 1 must use). A pair is (representative, sibling); pairs = calls saved. A pair is MARGINAL at
-- level k when representative and sibling had different keys at level k-1 (new at this level).
-- Metrics per level, cumulative and marginal, with the L0 pairs as the control:
--   D1 = share of pairs whose stars differ by >= 1;  D2 = by >= 2
--   X4 = share of pairs with exactly one side >= 4 stars (crosses the "worth going" line)
--   B  = mean(representative stars - sibling stars)
-- Section 4 bootstraps calendar days (1,000 resamples, fixed seed) for the differences from the control.
-- Nothing persists: TEMP views and tables are session-local.

SELECT setseed(0.20261116);

CREATE TEMP TABLE ies_base AS
WITH cyc AS (
    SELECT DISTINCT ON (substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint)
           substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint AS eval_id,
           b.id AS batch_id, p.cycle_type, a.cost_micro_dollars
    FROM api_call_log a
    JOIN forecast_batch b ON b.anthropic_batch_id = a.batch_id
    JOIN pipeline_run p   ON p.id = b.pipeline_run_id
    WHERE a.is_batch AND a.succeeded AND a.custom_id LIKE 'fc-%' AND a.custom_id NOT LIKE '%-f'
      AND NOT b.is_retry
      AND p.cycle_type IN ('NIGHTLY', 'INTRADAY', 'EVENING')
      AND a.called_at >= now() - interval '30 days'
    ORDER BY substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint, a.called_at DESC
),
r AS (
    SELECT f.id, f.location_id, f.target_date, f.target_type, c.batch_id, c.cycle_type,
           c.cost_micro_dollars AS cost_micro,
           COALESCE(f.sky_rating, f.rating) AS stars,
           md5(f.location_id::text || '|' || f.target_date || '|' || f.target_type) AS rep_order,
           md5(concat_ws('|', f.target_date, f.target_type, f.evaluation_model, f.prompt_hash)) AS fixed,
           CASE WHEN starts_with(pr.user_message, 'Location: ' || l.name || '. ')
                THEN substr(pr.user_message, length('Location: ' || l.name || '. ') + 1) END AS b0
    FROM forecast_evaluation f
    JOIN forecast_evaluation_prompt pr ON pr.evaluation_id = f.id
    JOIN locations l ON l.id = f.location_id
    JOIN cyc c ON c.eval_id = f.id
    WHERE f.days_ahead <= 1
      AND f.target_type IN ('SUNRISE', 'SUNSET')
      AND f.evaluation_model = 'SONNET_55'
      AND NOT (COALESCE(f.solar_low_cloud, f.low_cloud) > 60
               AND NOT (f.far_solar_low_cloud IS NOT NULL AND f.solar_low_cloud >= 50
                        AND f.solar_low_cloud - f.far_solar_low_cloud >= 30))
),
m1 AS (SELECT r.*,
    regexp_replace(b0,
        '^(SUNRISE|SUNSET): ([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2})(:[0-9]{2}(\.[0-9]+)?)? UTC\.',
        '\1: \2 UTC.') AS b1,
    regexp_replace(b0, '^(SUNRISE|SUNSET): [^\n]*? UTC\.', '\1.') AS b2 FROM r),
m3 AS (SELECT m1.*, regexp_replace(b2, 'Upwind sample \([^)]*\)', 'Upwind sample', 'g') AS b3 FROM m1),
m4 AS (SELECT m3.*, regexp_replace(b3, 'T-[0-9]h=[^ \n]*', 'T-h=*', 'g') AS b4 FROM m3),
m5 AS (SELECT m4.*, regexp_replace(b4,
    'vis=[0-9,]+m temp=-?[0-9.]+°C dew=-?[0-9.]+°C \(gap=-?[0-9.]+°C\)', 'vis=*', 'g') AS b5 FROM m4)
SELECT id, location_id, target_date, target_type, batch_id, cycle_type, cost_micro, stars, rep_order,
       (b0 IS NOT NULL) AS prefix_matched,
       -- mask health: did each mask change anything, and were any bracketed labels lost?
       (b1 IS DISTINCT FROM b0) AS c1, (b2 IS DISTINCT FROM b1) AS c2, (b3 IS DISTINCT FROM b2) AS c3,
       (b4 IS DISTINCT FROM b3) AS c4, (b5 IS DISTINCT FROM b4) AS c5,
       (length(b0) - length(replace(b0, '[', ''))) - (length(b5) - length(replace(b5, '[', ''))) AS labels_lost,
       CASE WHEN b0 IS NULL THEN 'u' || id ELSE md5(fixed || b0) END AS k0,
       CASE WHEN b0 IS NULL THEN 'u' || id ELSE md5(fixed || b1) END AS k1,
       CASE WHEN b0 IS NULL THEN 'u' || id ELSE md5(fixed || b2) END AS k2,
       CASE WHEN b0 IS NULL THEN 'u' || id ELSE md5(fixed || b3) END AS k3,
       CASE WHEN b0 IS NULL THEN 'u' || id ELSE md5(fixed || b4) END AS k4,
       CASE WHEN b0 IS NULL THEN 'u' || id ELSE md5(fixed || b5) END AS k5
FROM m5;

-- Long form: one row per (level, row) with the level's key and the previous level's key.
CREATE TEMP TABLE ies_long AS
SELECT x.*, v.lvl, v.k, v.kprev
FROM ies_base x
CROSS JOIN LATERAL (VALUES (0, x.k0, x.k0), (1, x.k1, x.k0), (2, x.k2, x.k1),
                           (3, x.k3, x.k2), (4, x.k4, x.k3), (5, x.k5, x.k4)) AS v(lvl, k, kprev)
WHERE x.stars IS NOT NULL;

-- Pairs: every non-representative member against its group's representative.
CREATE TEMP TABLE ies_pairs AS
WITH g AS (
    SELECT l.*,
           first_value(id) OVER w AS rep_id
    FROM ies_long l
    WINDOW w AS (PARTITION BY lvl, batch_id, k ORDER BY rep_order, id)
)
SELECT s.lvl, s.target_date AS day, s.cost_micro,
       rep.stars AS rep_stars, s.stars AS sib_stars,
       (s.kprev IS DISTINCT FROM rep.kprev) AS marginal,
       least(rep.location_id, s.location_id) AS loc_a, greatest(rep.location_id, s.location_id) AS loc_b
FROM g s
JOIN ies_long rep ON rep.id = s.rep_id AND rep.lvl = s.lvl
WHERE s.id <> s.rep_id;

-- 0. Mask health. Every c_k should be > 0 (a mask that never fires reads as "no gain"); labels_lost must be 0.
SELECT count(*) AS requests, count(*) FILTER (WHERE prefix_matched) AS prefix_matched,
       count(*) FILTER (WHERE stars IS NULL) AS null_stars,
       count(*) FILTER (WHERE c1) AS l1_changed, count(*) FILTER (WHERE c2) AS l2_changed,
       count(*) FILTER (WHERE c3) AS l3_changed, count(*) FILTER (WHERE c4) AS l4_changed,
       count(*) FILTER (WHERE c5) AS l5_changed,
       count(*) FILTER (WHERE labels_lost <> 0) AS rows_losing_labels,
       round(sum(cost_micro) / 1e6, 2) AS usd_30d
FROM ies_base;

-- 1. The ladder: saving and point safety statistics, cumulative and marginal, per level.
WITH tot AS (SELECT count(*) AS n FROM ies_base WHERE stars IS NOT NULL)
SELECT p.lvl, CASE WHEN p.lvl = 5 THEN 'reference only' ELSE 'adoptable' END AS status,
       count(*)                                                      AS pairs_saved,
       round(100.0 * count(*) / tot.n, 2)                            AS pct_saved,
       round(sum(p.cost_micro) / 1e6, 2)                             AS usd_saved_30d,
       count(*) FILTER (WHERE p.marginal)                            AS marginal_pairs,
       round(100.0 * avg((abs(rep_stars - sib_stars) >= 1)::int), 2) AS d1_pct,
       round(100.0 * avg((abs(rep_stars - sib_stars) >= 2)::int), 2) AS d2_pct,
       round(100.0 * avg(((rep_stars >= 4) <> (sib_stars >= 4))::int), 2) AS x4_pct,
       round(avg(rep_stars - sib_stars), 3)                          AS b_mean,
       round(100.0 * avg((abs(rep_stars - sib_stars) >= 1)::int) FILTER (WHERE p.marginal), 2) AS d1_marg_pct,
       round(100.0 * avg((abs(rep_stars - sib_stars) >= 2)::int) FILTER (WHERE p.marginal), 2) AS d2_marg_pct,
       round(100.0 * avg(((rep_stars >= 4) <> (sib_stars >= 4))::int) FILTER (WHERE p.marginal), 2) AS x4_marg_pct
FROM ies_pairs p CROSS JOIN tot
GROUP BY p.lvl, tot.n
ORDER BY p.lvl;

-- 2. By the representative's star band (wobble lives in 2-4 stars; a level full of unanimous 1-star
--    overcast groups would look safe on average).
SELECT lvl, least(rep_stars, 4) AS rep_band, count(*) AS pairs,
       round(100.0 * avg((abs(rep_stars - sib_stars) >= 1)::int), 2) AS d1_pct,
       round(100.0 * avg(((rep_stars >= 4) <> (sib_stars >= 4))::int), 2) AS x4_pct
FROM ies_pairs GROUP BY 1, 2 ORDER BY 1, 2;

-- 3. Concentration: do a few fixed place pairs carry the saving? (If so, a static "rate X as a proxy of
--    Y" setting may beat the general fan-out.) Top 20 place pairs per adoptable level from L2.
WITH c AS (
    SELECT lvl, loc_a, loc_b, count(*) AS n,
           row_number() OVER (PARTITION BY lvl ORDER BY count(*) DESC) AS rk,
           sum(count(*)) OVER (PARTITION BY lvl) AS lvl_total
    FROM ies_pairs WHERE lvl BETWEEN 2 AND 4 GROUP BY lvl, loc_a, loc_b
)
SELECT lvl, count(*) AS place_pairs,
       sum(n) FILTER (WHERE rk <= 20) AS top20_pairs_saved,
       round(100.0 * sum(n) FILTER (WHERE rk <= 20) / max(lvl_total), 1) AS top20_share_pct
FROM c GROUP BY lvl ORDER BY lvl;

-- 4. Day-block bootstrap of the differences from the L0 control (1,000 resamples of calendar days, the
--    level and the control on the same resampled days). Upper bounds for D1, D2 and X4 differences; a 95%
--    interval for B. Scope 'cum' is all pairs at the level; 'marg' is the marginal pairs only.
CREATE TEMP TABLE ies_daily AS
SELECT lvl, scope, day, count(*) AS n,
       sum((abs(rep_stars - sib_stars) >= 1)::int) AS d1,
       sum((abs(rep_stars - sib_stars) >= 2)::int) AS d2,
       sum(((rep_stars >= 4) <> (sib_stars >= 4))::int) AS x4,
       sum(rep_stars - sib_stars) AS sdiff
FROM (SELECT p.*, 'cum' AS scope FROM ies_pairs p
      UNION ALL SELECT p.*, 'marg' FROM ies_pairs p WHERE p.marginal AND p.lvl > 0) z
GROUP BY lvl, scope, day;

WITH days AS (SELECT array_agg(DISTINCT day ORDER BY day) AS d FROM ies_daily),
draws AS (
    SELECT s.b, (SELECT d FROM days)[1 + floor(random() * cardinality((SELECT d FROM days)))::int] AS day
    FROM generate_series(1, 1000) AS s(b), generate_series(1, (SELECT cardinality(d) FROM days)) AS i
),
agg AS (
    SELECT dr.b, dd.lvl, dd.scope,
           sum(dd.n) AS n, sum(dd.d1) AS d1, sum(dd.d2) AS d2, sum(dd.x4) AS x4, sum(dd.sdiff) AS sdiff
    FROM draws dr JOIN ies_daily dd ON dd.day = dr.day
    GROUP BY dr.b, dd.lvl, dd.scope
),
ctl AS (SELECT b, n, d1, d2, x4 FROM agg WHERE lvl = 0 AND scope = 'cum'),
diffs AS (
    SELECT a.lvl, a.scope, a.b,
           a.d1::numeric / a.n - c.d1::numeric / NULLIF(c.n, 0) AS d1_diff,
           a.d2::numeric / a.n - c.d2::numeric / NULLIF(c.n, 0) AS d2_diff,
           a.x4::numeric / a.n - c.x4::numeric / NULLIF(c.n, 0) AS x4_diff,
           a.sdiff::numeric / a.n AS b_mean,
           a.n
    FROM agg a JOIN ctl c ON c.b = a.b
    WHERE a.lvl > 0 AND a.n > 0
)
SELECT lvl, scope,
       round(avg(n))                                                             AS avg_pairs_per_draw,
       round(100 * percentile_cont(0.975) WITHIN GROUP (ORDER BY d1_diff)::numeric, 2) AS d1_diff_upper_pp,
       round(100 * percentile_cont(0.975) WITHIN GROUP (ORDER BY d2_diff)::numeric, 2) AS d2_diff_upper_pp,
       round(100 * percentile_cont(0.975) WITHIN GROUP (ORDER BY x4_diff)::numeric, 2) AS x4_diff_upper_pp,
       round(percentile_cont(0.025) WITHIN GROUP (ORDER BY b_mean)::numeric, 3)        AS b_lower,
       round(percentile_cont(0.975) WITHIN GROUP (ORDER BY b_mean)::numeric, 3)        AS b_upper
FROM diffs
GROUP BY lvl, scope
ORDER BY lvl, scope;
