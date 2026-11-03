-- Read-only, PRODUCTION. Does a poor sunrise ever turn good by the 21:00 evening refresh?
-- Answers "can the evening skip slots already rated <= 2 stars?" (claude-cost-ledger.md, Phase 2b).
-- Paste into: docker exec -i goldenhour-db psql -U goldenhour -d goldenhour <<'SQL' ... SQL
--
-- Each scored rating is compared with the previous scored rating of the same slot (location, date,
-- event). The cycle that produced it comes from its batch request (api_call_log -> forecast_batch ->
-- pipeline_run), as in rerating-waste.sql; a rating with no batch request is cycle 'UNKNOWN'.
-- The noise floor on identical inputs is about 8.5% moving 1 star (noise-floor.sql), so a 2-star move is
-- signal, and a crossing from <= 2 to >= 4 is the one that would change a bedtime decision.

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
    SELECT f.id, f.target_type, f.days_ahead,
           COALESCE(c.cycle_type, 'UNKNOWN') AS cycle_type,
           c.cost_micro_dollars,
           COALESCE(f.sky_rating, f.rating) AS stars,
           LAG(COALESCE(f.sky_rating, f.rating)) OVER w AS prev_stars,
           LAST_VALUE(COALESCE(f.sky_rating, f.rating)) OVER (w ROWS BETWEEN UNBOUNDED PRECEDING
                                                                  AND UNBOUNDED FOLLOWING) AS final_stars
    FROM forecast_evaluation f
    LEFT JOIN cyc c ON c.eval_id = f.id
    WHERE f.forecast_run_at >= now() - interval '45 days'
      AND COALESCE(f.sky_rating, f.rating) IS NOT NULL
    WINDOW w AS (PARTITION BY f.location_id, f.target_date, f.target_type ORDER BY f.forecast_run_at)
),
scoped AS (
    SELECT 'evening T+1 sunrise' AS scope, * FROM s
    WHERE prev_stars IS NOT NULL AND cycle_type = 'EVENING'
    UNION ALL
    SELECT 'all T+1 sunrise re-ratings', * FROM s
    WHERE prev_stars IS NOT NULL AND target_type = 'SUNRISE' AND days_ahead = 1
)

-- 1. Transition table: previous stars -> new stars, by scope. Read the prev_stars <= 2 rows.
--    final_ge_4 = slots whose LAST rating before the event (normally the overnight's) was >= 4.
SELECT scope, prev_stars,
       count(*)                                         AS pairs,
       count(*) FILTER (WHERE stars <= 2)               AS now_le_2,
       count(*) FILTER (WHERE stars = 3)                AS now_3,
       count(*) FILTER (WHERE stars >= 4)               AS now_ge_4,
       round(100.0 * count(*) FILTER (WHERE stars >= 4) / count(*), 1) AS pct_now_ge_4,
       count(*) FILTER (WHERE final_stars >= 4)         AS final_ge_4
FROM scoped
GROUP BY scope, prev_stars
ORDER BY scope, prev_stars;

-- 2. What a "skip if previously <= 2 stars" gate on the evening would have saved, and what it would
--    have hidden (evening ratings that came back >= 4 from a <= 2 start), last 45 days.
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
    SELECT f.target_date, c.cycle_type, c.cost_micro_dollars,
           COALESCE(f.sky_rating, f.rating) AS stars,
           LAG(COALESCE(f.sky_rating, f.rating)) OVER w AS prev_stars
    FROM forecast_evaluation f
    LEFT JOIN cyc c ON c.eval_id = f.id
    WHERE f.forecast_run_at >= now() - interval '45 days'
      AND COALESCE(f.sky_rating, f.rating) IS NOT NULL
    WINDOW w AS (PARTITION BY f.location_id, f.target_date, f.target_type ORDER BY f.forecast_run_at)
)
SELECT count(DISTINCT target_date)                                        AS evening_nights,
       count(*)                                                           AS evening_requests,
       round(sum(cost_micro_dollars) / 1e6, 2)                            AS evening_usd,
       count(*) FILTER (WHERE prev_stars <= 2)                            AS skippable_requests,
       round(sum(cost_micro_dollars) FILTER (WHERE prev_stars <= 2) / 1e6, 2) AS skippable_usd,
       round(30.0 * sum(cost_micro_dollars) FILTER (WHERE prev_stars <= 2) / 1e6
             / NULLIF(count(DISTINCT target_date), 0), 2)                 AS skippable_usd_per_30_nights,
       count(*) FILTER (WHERE prev_stars <= 2 AND stars >= 4)             AS hidden_jumps_to_ge_4,
       count(*) FILTER (WHERE prev_stars <= 2 AND stars = 3)              AS hidden_moves_to_3
FROM s
WHERE cycle_type = 'EVENING' AND prev_stars IS NOT NULL;
