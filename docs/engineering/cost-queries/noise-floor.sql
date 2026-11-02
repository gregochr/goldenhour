-- Read-only, PRODUCTION. The model's own noise floor: identical inputs rated repeatedly by the
-- weekly Sky-Rating Eval (8 repeats per fixture per model). No API calls; costs nothing.
WITH r AS (
    SELECT run.model, res.run_id, res.fixture_name, res.run_index, res.rating,
           LAG(res.rating) OVER (PARTITION BY res.run_id, res.fixture_name ORDER BY res.run_index) AS prev
    FROM sky_rating_eval_result res
    JOIN sky_rating_eval_run run ON run.id = res.run_id
    WHERE run.started_at >= now() - interval '60 days'
      AND res.rating IS NOT NULL
)
SELECT model,
       count(DISTINCT run_id)                                                      AS eval_runs,
       count(*) FILTER (WHERE prev IS NOT NULL)                                    AS repeat_pairs,
       round(100.0 * count(*) FILTER (WHERE rating = prev)
             / NULLIF(count(*) FILTER (WHERE prev IS NOT NULL), 0), 1)            AS pct_same,
       round(100.0 * count(*) FILTER (WHERE abs(rating - prev) = 1)
             / NULLIF(count(*) FILTER (WHERE prev IS NOT NULL), 0), 1)            AS pct_moved_1,
       round(100.0 * count(*) FILTER (WHERE abs(rating - prev) >= 2)
             / NULLIF(count(*) FILTER (WHERE prev IS NOT NULL), 0), 1)            AS pct_moved_2plus
FROM r GROUP BY model ORDER BY model;

-- Accuracy per model over the same window (pass = rating inside the fixture's expected range).
SELECT model, count(*) AS eval_runs, sum(total_runs) AS ratings,
       round(100.0 * sum(total_passes) / NULLIF(sum(total_runs), 0), 1) AS pass_pct,
       sum(below_misses) AS below, sum(above_misses) AS above,
       round(sum(cost_micro_dollars) / 1e6, 2) AS usd
FROM sky_rating_eval_run
WHERE started_at >= now() - interval '60 days'
GROUP BY model ORDER BY model;
