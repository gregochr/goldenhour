-- Read-only, PRODUCTION. Why did the overnight cycle and Ask ready answers cost more from late October?
-- Days are UTC dates of api_call_log.called_at. Costs in USD.

-- 1. Overnight (NIGHTLY) pipeline batch calls per day: runs, requests and cost, near-term vs far-term.
WITH r AS (
    SELECT a.called_at::date AS day, p.id AS run_id, f.days_ahead, a.evaluation_model AS model,
           a.cost_micro_dollars AS c, a.input_tokens, a.output_tokens,
           a.cache_read_input_tokens, a.cache_creation_input_tokens
    FROM api_call_log a
    JOIN forecast_batch b  ON b.anthropic_batch_id = a.batch_id
    JOIN pipeline_run p    ON p.id = b.pipeline_run_id
    LEFT JOIN forecast_evaluation f
      ON f.id = substring(a.custom_id FROM '-r([0-9]+)(-f)?$')::bigint
    WHERE a.is_batch AND a.succeeded AND a.custom_id LIKE 'fc-%'
      AND p.cycle_type = 'NIGHTLY'
      AND a.called_at >= now() - interval '40 days'
)
SELECT day,
       count(DISTINCT run_id)                                   AS runs,
       count(*) FILTER (WHERE days_ahead <= 1)                  AS near_req,
       count(*) FILTER (WHERE days_ahead >= 2)                  AS far_req,
       count(*) FILTER (WHERE days_ahead IS NULL)               AS unmatched_req,
       round(sum(c) FILTER (WHERE days_ahead <= 1) / 1e6, 2)    AS near_usd,
       round(sum(c) FILTER (WHERE days_ahead >= 2) / 1e6, 2)    AS far_usd,
       round(sum(c) / 1e6, 2)                                   AS total_usd,
       string_agg(DISTINCT model, ',')                          AS models,
       round(avg(input_tokens))                                 AS avg_in,
       round(avg(cache_read_input_tokens))                      AS avg_cache_read,
       round(avg(cache_creation_input_tokens))                  AS avg_cache_write,
       round(avg(output_tokens))                                AS avg_out
FROM r GROUP BY day ORDER BY day;

-- 2. Ask ready answers per day: precompute runs, calls, cost per run, tokens.
SELECT a.called_at::date                                        AS day,
       count(DISTINCT a.job_run_id)                             AS runs,
       count(*)                                                 AS calls,
       round(sum(a.cost_micro_dollars) / 1e6, 2)                AS usd,
       round(sum(a.cost_micro_dollars)::numeric / NULLIF(count(DISTINCT a.job_run_id), 0) / 1e6, 3) AS usd_per_run,
       string_agg(DISTINCT a.evaluation_model, ',')             AS models,
       round(avg(a.input_tokens))                               AS avg_in,
       round(avg(a.cache_read_input_tokens))                    AS avg_cache_read,
       round(avg(a.cache_creation_input_tokens))                AS avg_cache_write,
       round(avg(a.output_tokens))                              AS avg_out
FROM api_call_log a
JOIN job_run j ON j.id = a.job_run_id
WHERE j.run_type = 'ASK_READY' AND a.cost_micro_dollars IS NOT NULL AND a.succeeded
  AND a.called_at >= now() - interval '40 days'
GROUP BY 1 ORDER BY 1;

-- 3. Ask ready runs by hour of day (UTC), last 14 days: which cycle triggers them, and any extras.
SELECT extract(hour FROM j.started_at)::int AS hour_utc,
       count(*)                              AS runs,
       round(avg(x.usd), 3)                  AS avg_usd_per_run
FROM job_run j
JOIN (SELECT job_run_id, sum(cost_micro_dollars) / 1e6 AS usd
      FROM api_call_log WHERE cost_micro_dollars IS NOT NULL GROUP BY job_run_id) x ON x.job_run_id = j.id
WHERE j.run_type = 'ASK_READY' AND j.started_at >= now() - interval '14 days'
GROUP BY 1 ORDER BY 1;
