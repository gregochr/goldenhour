-- Read-only, PRODUCTION. Where batch sky-rating money goes, and how often a re-rating changes nothing.
-- Paste into: docker exec -i goldenhour-db psql -U goldenhour -d goldenhour <<'SQL' ... SQL

-- 1. Requests and cost by cycle type, days ahead and model (last 30 days, pipeline batches only).
WITH r AS (
    SELECT p.cycle_type, f.days_ahead, a.evaluation_model AS model, a.cost_micro_dollars
    FROM api_call_log a
    JOIN forecast_batch b  ON b.anthropic_batch_id = a.batch_id
    JOIN pipeline_run p    ON p.id = b.pipeline_run_id
    JOIN forecast_evaluation f
      ON f.id = substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint
    WHERE a.is_batch AND a.succeeded AND a.custom_id LIKE 'fc-%'
      AND a.called_at >= now() - interval '30 days'
)
SELECT cycle_type, days_ahead, model, count(*) AS requests,
       round(sum(cost_micro_dollars) / 1e6, 2) AS usd
FROM r GROUP BY 1, 2, 3 ORDER BY usd DESC;

-- 2. Re-rating churn: for each slot, every scored rating after the first, compared with the one before.
WITH s AS (
    SELECT location_id, target_date, target_type, days_ahead, forecast_run_at,
           COALESCE(sky_rating, rating) AS stars,
           LAG(COALESCE(sky_rating, rating)) OVER w AS prev_stars,
           ROW_NUMBER() OVER w AS nth
    FROM forecast_evaluation
    WHERE forecast_run_at >= now() - interval '30 days'
      AND COALESCE(sky_rating, rating) IS NOT NULL
    WINDOW w AS (PARTITION BY location_id, target_date, target_type ORDER BY forecast_run_at)
)
SELECT days_ahead,
       count(*) FILTER (WHERE nth = 1)                              AS first_ratings,
       count(*) FILTER (WHERE nth > 1)                              AS re_ratings,
       count(*) FILTER (WHERE nth > 1 AND stars = prev_stars)       AS same_stars,
       count(*) FILTER (WHERE nth > 1 AND abs(stars - prev_stars) = 1) AS moved_1,
       count(*) FILTER (WHERE nth > 1 AND abs(stars - prev_stars) >= 2) AS moved_2_plus,
       round(100.0 * count(*) FILTER (WHERE nth > 1 AND stars = prev_stars)
             / NULLIF(count(*) FILTER (WHERE nth > 1), 0), 1)       AS pct_same
FROM s GROUP BY days_ahead ORDER BY days_ahead;

-- 3. Ratings per slot before its event (how many times one sunrise/sunset is rated).
WITH c AS (
    SELECT location_id, target_date, target_type, count(*) AS n
    FROM forecast_evaluation
    WHERE forecast_run_at >= now() - interval '30 days'
      AND COALESCE(sky_rating, rating) IS NOT NULL
    GROUP BY 1, 2, 3
)
SELECT n AS ratings_per_slot, count(*) AS slots FROM c GROUP BY n ORDER BY n;
