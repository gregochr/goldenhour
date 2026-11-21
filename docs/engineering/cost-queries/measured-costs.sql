-- Measured Claude cost per call, per run, and runs per day for each Run Config tab, last 30 days.
-- Run on PRODUCTION (read-only):
--
--   docker exec -i goldenhour-db psql -U goldenhour -d goldenhour < measured-costs.sql
--
-- Only Anthropic calls (cost_micro_dollars set) are counted; Open-Meteo and tide calls carry none.
-- The retired best-bet advisor (2026-11-21) logged under the BRIEFING job run, never a job run of its own, with
-- api_call_log.request_url = 'briefing-best-bet'. Use the second query at the foot of this file to see its older
-- rows inside the 30-day window; it should read zero for calls after the deploy. Run Config no longer profiles it.
WITH calls AS (
    SELECT j.run_type, a.evaluation_model AS model, a.job_run_id, a.is_batch,
           a.cost_micro_dollars, a.input_tokens, a.output_tokens,
           a.cache_read_input_tokens, a.called_at
    FROM api_call_log a
    JOIN job_run j ON j.id = a.job_run_id
    WHERE a.cost_micro_dollars IS NOT NULL
      AND a.succeeded
      AND a.called_at >= now() - interval '30 days'
      AND j.run_type IN ('BRIEFING', 'AURORA_EVALUATION',
                         'BATCH_NEAR_TERM', 'BATCH_FAR_TERM', 'SCHEDULED_BATCH')
)
SELECT run_type, model, is_batch,
       count(*)                                              AS calls,
       count(DISTINCT job_run_id)                            AS runs,
       round(count(DISTINCT job_run_id) / 30.0, 1)           AS runs_per_day,
       round(count(*)::numeric / count(DISTINCT job_run_id), 1) AS calls_per_run,
       round(avg(cost_micro_dollars) / 1e6, 5)               AS usd_per_call,
       round(sum(cost_micro_dollars)::numeric / count(DISTINCT job_run_id) / 1e6, 4) AS usd_per_run,
       round(avg(input_tokens))                              AS avg_in,
       round(avg(cache_read_input_tokens))                   AS avg_cache_read,
       round(avg(output_tokens))                             AS avg_out
FROM calls
GROUP BY run_type, model, is_batch
ORDER BY run_type, model, is_batch;

-- The retired best-bet advisor's calls per day (it shares the BRIEFING job run; select by request_url).
SELECT a.called_at::date                                     AS day,
       count(*)                                              AS calls,
       round(sum(a.cost_micro_dollars)::numeric / 1e6, 4)    AS usd
FROM api_call_log a
WHERE a.request_url = 'briefing-best-bet'
  AND a.cost_micro_dollars IS NOT NULL
  AND a.called_at >= now() - interval '30 days'
GROUP BY a.called_at::date
ORDER BY day;
