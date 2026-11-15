-- Read-only, PRODUCTION. Could the free weather triage stand down more places before they reach Claude?
-- Answers "would a stricter triage threshold save real money without hiding good skies?"
-- (claude-cost-ledger.md, "Triage headroom"). Run with:
--   docker exec -i goldenhour-db psql -U goldenhour -d goldenhour < docs/engineering/cost-queries/triage-headroom.sql
--
-- Today's triage (WeatherTriageEvaluator) stands a slot down, free, when ANY of:
--   rule 1  solar-horizon low cloud > 80%   (directional; observer low cloud when no directional data)
--   rule 2  precipitation > 2 mm
--   rule 3  visibility < 5,000 m            (not consulted for a mist-prone LIKELY slot)
-- So every rated row below already passed all three. This query asks what happens just inside each line.
--
-- Each scored forecast_evaluation row is one paid request. Its cycle and cost come from its batch
-- request (api_call_log -> forecast_batch -> pipeline_run), as in poor-stays-poor.sql; a row with no
-- batch request has cycle 'UNKNOWN' and no cost (counted, but adds nothing to the dollars).
--
-- Two kinds of harm, both reported:
--   hidden_ge_4       requests in the band that came back >= 4 stars (a good rating that would have been
--                     a stand-down at that look)
--   final_ge_4_hidden slots whose LAST rating before the event (the one the reader acts on) was >= 4 AND
--                     sat in the band, so the stricter rule would have stood it down at the final look
-- The noise floor is about 8.5% moving 1 star on identical inputs (noise-floor.sql), so judge on >= 4.
--
-- DECISION RULE (set before running, same bar as Phase 2b): adopt a candidate threshold only if
-- final_ge_4_hidden is under 1% of skippable_requests, over at least 30 days that include both settled
-- and unsettled spells. Re-read the "Rejected or shelved" entries in the ledger before proposing it.

-- 1. Star outcome by solar-horizon low cloud band, for rows that passed today's triage.
--    Read the 50-80 bands: if >= 4 is rare there, rule 1 has headroom.
WITH cyc AS (
    SELECT DISTINCT ON (substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint)
           substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint AS eval_id,
           p.cycle_type, a.cost_micro_dollars
    FROM api_call_log a
    JOIN forecast_batch b ON b.anthropic_batch_id = a.batch_id
    JOIN pipeline_run p   ON p.id = b.pipeline_run_id
    WHERE a.is_batch AND a.succeeded AND a.custom_id LIKE 'fc-%'
      AND a.called_at >= now() - interval '45 days'
    ORDER BY substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint, a.called_at DESC
),
s AS (
    SELECT f.target_type,
           CASE WHEN f.days_ahead <= 1 THEN 'near (T+0/1)' ELSE 'far (T+2/3)' END AS horizon,
           COALESCE(f.solar_low_cloud, f.low_cloud) AS sun_low,
           COALESCE(c.cost_micro_dollars, 0) AS cost_micro,
           COALESCE(f.sky_rating, f.rating) AS stars
    FROM forecast_evaluation f
    LEFT JOIN cyc c ON c.eval_id = f.id
    WHERE f.forecast_run_at >= now() - interval '45 days'
      AND f.target_type IN ('SUNRISE', 'SUNSET')
      AND COALESCE(f.sky_rating, f.rating) IS NOT NULL
)
SELECT horizon, target_type,
       CASE WHEN sun_low IS NULL THEN 'no data'
            WHEN sun_low <= 20 THEN '00-20' WHEN sun_low <= 40 THEN '21-40'
            WHEN sun_low <= 50 THEN '41-50' WHEN sun_low <= 60 THEN '51-60'
            WHEN sun_low <= 70 THEN '61-70' ELSE '71-80' END       AS sun_low_band,
       count(*)                                                    AS requests,
       round(sum(cost_micro) / 1e6, 2)                             AS usd,
       round(avg(stars), 2)                                        AS mean_stars,
       round(100.0 * count(*) FILTER (WHERE stars <= 2) / count(*), 1) AS pct_le_2,
       round(100.0 * count(*) FILTER (WHERE stars = 3)  / count(*), 1) AS pct_3,
       round(100.0 * count(*) FILTER (WHERE stars >= 4) / count(*), 1) AS pct_ge_4,
       count(*) FILTER (WHERE stars >= 4)                          AS n_ge_4
FROM s
GROUP BY 1, 2, 3
ORDER BY 1, 2, 3;

-- 2. Candidate thresholds, one row each: what a stricter rule would have skipped and hidden, last 45 days,
--    dollars also scaled to 30 days. Candidates are independent (each row alone added to today's rules).
WITH cyc AS (
    SELECT DISTINCT ON (substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint)
           substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint AS eval_id,
           p.cycle_type, a.cost_micro_dollars
    FROM api_call_log a
    JOIN forecast_batch b ON b.anthropic_batch_id = a.batch_id
    JOIN pipeline_run p   ON p.id = b.pipeline_run_id
    WHERE a.is_batch AND a.succeeded AND a.custom_id LIKE 'fc-%'
      AND a.called_at >= now() - interval '45 days'
    ORDER BY substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint, a.called_at DESC
),
s AS (
    SELECT f.location_id, f.target_date, f.target_type, f.forecast_run_at,
           COALESCE(f.solar_low_cloud, f.low_cloud) AS sun_low,
           f.precipitation, f.visibility,
           COALESCE(c.cost_micro_dollars, 0) AS cost_micro,
           COALESCE(f.sky_rating, f.rating) AS stars,
           ROW_NUMBER() OVER (PARTITION BY f.location_id, f.target_date, f.target_type
                              ORDER BY f.forecast_run_at DESC) AS rn_desc
    FROM forecast_evaluation f
    LEFT JOIN cyc c ON c.eval_id = f.id
    WHERE f.forecast_run_at >= now() - interval '45 days'
      AND f.target_type IN ('SUNRISE', 'SUNSET')
      AND COALESCE(f.sky_rating, f.rating) IS NOT NULL
      AND f.target_date < current_date  -- past events only, so the last rating really was the final one
),
cand AS (
    SELECT 'sun low cloud > 50'  AS candidate, * FROM s WHERE sun_low > 50
    UNION ALL SELECT 'sun low cloud > 60', * FROM s WHERE sun_low > 60
    UNION ALL SELECT 'sun low cloud > 70', * FROM s WHERE sun_low > 70
    UNION ALL SELECT 'precipitation > 1 mm', * FROM s WHERE precipitation > 1.0
    UNION ALL SELECT 'precipitation > 0.5 mm', * FROM s WHERE precipitation > 0.5
    -- 5-10 km only: below 5 km is already rule 3, or a mist slot rule 3 deliberately lets through.
    UNION ALL SELECT 'visibility 5-10 km', * FROM s WHERE visibility >= 5000 AND visibility < 10000
),
totals AS (
    SELECT count(*) AS all_requests, sum(cost_micro) AS all_micro,
           count(DISTINCT forecast_run_at::date) AS days
    FROM s
)
SELECT c.candidate,
       count(*)                                                        AS skippable_requests,
       round(100.0 * count(*) / t.all_requests, 1)                     AS pct_of_requests,
       round(sum(c.cost_micro) / 1e6, 2)                               AS usd_45d,
       round(sum(c.cost_micro) / 1e6 * 30.0 / NULLIF(t.days, 0), 2)    AS usd_per_30d,
       count(*) FILTER (WHERE c.stars >= 4)                            AS hidden_ge_4,
       round(100.0 * count(*) FILTER (WHERE c.stars >= 4) / count(*), 2) AS pct_hidden_ge_4,
       count(*) FILTER (WHERE c.stars = 3)                             AS hidden_3,
       count(*) FILTER (WHERE c.rn_desc = 1 AND c.stars >= 4)          AS final_ge_4_hidden,
       round(100.0 * count(*) FILTER (WHERE c.rn_desc = 1 AND c.stars >= 4) / count(*), 2)
                                                                       AS pct_final_ge_4_hidden
FROM cand c CROSS JOIN totals t
GROUP BY c.candidate, t.all_requests, t.days
ORDER BY c.candidate;

-- 3. Where the saving would land: the best candidate from section 2 split by cycle.
--    Edit the WHERE to the candidate you are judging (default: sun low cloud > 60).
WITH cyc AS (
    SELECT DISTINCT ON (substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint)
           substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint AS eval_id,
           p.cycle_type, a.cost_micro_dollars
    FROM api_call_log a
    JOIN forecast_batch b ON b.anthropic_batch_id = a.batch_id
    JOIN pipeline_run p   ON p.id = b.pipeline_run_id
    WHERE a.is_batch AND a.succeeded AND a.custom_id LIKE 'fc-%'
      AND a.called_at >= now() - interval '45 days'
    ORDER BY substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint, a.called_at DESC
)
SELECT COALESCE(c.cycle_type, 'UNKNOWN')                         AS cycle_type,
       CASE WHEN f.days_ahead <= 1 THEN 'near' ELSE 'far' END    AS horizon,
       count(*)                                                  AS skippable_requests,
       round(sum(COALESCE(c.cost_micro_dollars, 0)) / 1e6, 2)    AS usd_45d,
       count(*) FILTER (WHERE COALESCE(f.sky_rating, f.rating) >= 4) AS hidden_ge_4
FROM forecast_evaluation f
LEFT JOIN cyc c ON c.eval_id = f.id
WHERE f.forecast_run_at >= now() - interval '45 days'
  AND f.target_type IN ('SUNRISE', 'SUNSET')
  AND COALESCE(f.sky_rating, f.rating) IS NOT NULL
  AND COALESCE(f.solar_low_cloud, f.low_cloud) > 60
GROUP BY 1, 2
ORDER BY 1, 2;
