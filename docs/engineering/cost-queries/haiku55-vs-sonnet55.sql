-- Read-only, PRODUCTION. Sonnet 5.5 (production near-term) vs Haiku 5.5 on the Sky-Rating Eval fixtures.
-- Completed runs in the last 14 days only.

-- 1. Accuracy, noise and cost per model.
WITH runs AS (
    SELECT * FROM sky_rating_eval_run
    WHERE model IN ('SONNET_55', 'HAIKU_55') AND status = 'COMPLETED'
      AND started_at >= now() - interval '14 days'
),
r AS (
    SELECT run.model, res.rating,
           LAG(res.rating) OVER (PARTITION BY res.run_id, res.fixture_name ORDER BY res.run_index) AS prev
    FROM sky_rating_eval_result res JOIN runs run ON run.id = res.run_id
    WHERE res.rating IS NOT NULL
)
SELECT runs.model, count(DISTINCT runs.id) AS eval_runs, sum(total_runs) AS ratings,
       round(100.0 * sum(total_passes) / NULLIF(sum(total_runs), 0), 1) AS pass_pct,
       sum(below_misses) AS below, sum(above_misses) AS above,
       round(sum(cost_micro_dollars) / 1e6 / NULLIF(sum(total_runs), 0), 5) AS usd_per_rating,
       (SELECT round(100.0 * count(*) FILTER (WHERE rating = prev) / NULLIF(count(*) FILTER (WHERE prev IS NOT NULL), 0), 1)
          FROM r WHERE r.model = runs.model) AS pct_same_on_repeat
FROM runs GROUP BY runs.model ORDER BY runs.model;

-- 2. Per fixture: mean stars and spread for each model, and the gap between them.
WITH x AS (
    SELECT res.fixture_name, run.model, res.rating
    FROM sky_rating_eval_result res
    JOIN sky_rating_eval_run run ON run.id = res.run_id
    WHERE run.model IN ('SONNET_55', 'HAIKU_55') AND run.status = 'COMPLETED'
      AND run.started_at >= now() - interval '14 days' AND res.rating IS NOT NULL
)
SELECT fixture_name,
       round(avg(rating) FILTER (WHERE model = 'SONNET_55'), 2) AS sonnet55_mean,
       min(rating) FILTER (WHERE model = 'SONNET_55')           AS s_min,
       max(rating) FILTER (WHERE model = 'SONNET_55')           AS s_max,
       round(avg(rating) FILTER (WHERE model = 'HAIKU_55'), 2)  AS haiku55_mean,
       min(rating) FILTER (WHERE model = 'HAIKU_55')            AS h_min,
       max(rating) FILTER (WHERE model = 'HAIKU_55')            AS h_max,
       round(avg(rating) FILTER (WHERE model = 'HAIKU_55')
             - avg(rating) FILTER (WHERE model = 'SONNET_55'), 2) AS haiku_minus_sonnet
FROM x GROUP BY fixture_name ORDER BY fixture_name;
