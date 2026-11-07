-- Read-only, PRODUCTION. Is prompt caching paying for itself on Ask ready answers? (ask-ready-cost-plan.md R0)
-- Compares the logged cost with what the same tokens would have cost with no caching at all
-- (every cached token billed as plain input). Failed turns that returned usage are billed, so they are
-- kept. Rates are Haiku 4.5's, so rows are limited to that model. Haiku 4.5 rates from application-example.yml:
-- input $1.00/M, output $5.00/M, cache write (5 min) $1.25/M, cache read $0.10/M.

-- 1. Per day since caching began: actual vs no-cache cost, and how often a call reads the cache.
SELECT a.called_at::date                                                      AS day,
       count(DISTINCT a.job_run_id)                                           AS runs,
       count(*)                                                               AS calls,
       count(*) FILTER (WHERE NOT a.succeeded)                                AS failed_calls,
       round(count(*)::numeric / NULLIF(count(DISTINCT a.job_run_id), 0), 1)  AS calls_per_run,
       round(sum(a.cost_micro_dollars) / 1e6, 3)                              AS actual_usd,
       round(sum(  coalesce(a.input_tokens, 0)
                 + coalesce(a.cache_creation_input_tokens, 0)
                 + coalesce(a.cache_read_input_tokens, 0)) / 1e6
             + sum(coalesce(a.output_tokens, 0)) * 5.0 / 1e6, 3)              AS no_cache_usd,
       round(100.0 * count(*) FILTER (WHERE a.cache_read_input_tokens > 0) / count(*), 1) AS pct_calls_reading,
       round(100.0 * count(*) FILTER (WHERE a.cache_creation_input_tokens > 0) / count(*), 1) AS pct_calls_writing,
       round(avg(a.cache_read_input_tokens) FILTER (WHERE a.cache_read_input_tokens > 0)) AS avg_read_when_reading,
       round(avg(a.cache_creation_input_tokens) FILTER (WHERE a.cache_creation_input_tokens > 0)) AS avg_write_when_writing
FROM api_call_log a
JOIN job_run j ON j.id = a.job_run_id
WHERE j.run_type = 'ASK_READY' AND a.cost_micro_dollars IS NOT NULL
  AND a.evaluation_model = 'HAIKU'
  AND a.called_at >= timestamp '2026-10-30'
GROUP BY 1 ORDER BY 1;

-- 2. The shape of one recent run, call by call: does the shared prefix ever get read across tasks?
WITH last_run AS (
    SELECT j.id FROM job_run j
    WHERE j.run_type = 'ASK_READY' AND j.started_at >= now() - interval '2 days'
      AND j.pipeline_run_id IS NOT NULL AND j.completed_at IS NOT NULL   -- a full cycle run, not astro
    ORDER BY j.started_at DESC LIMIT 1
)
SELECT row_number() OVER (ORDER BY a.called_at) AS n,
       to_char(a.called_at, 'HH24:MI:SS')       AS at,
       a.input_tokens, a.cache_creation_input_tokens AS cache_write,
       a.cache_read_input_tokens AS cache_read, a.output_tokens,
       round(a.cost_micro_dollars / 1e6, 5)     AS usd
FROM api_call_log a JOIN last_run r ON r.id = a.job_run_id
WHERE a.cost_micro_dollars IS NOT NULL
ORDER BY a.called_at;
