-- What moving the FAR bucket (T+2, T+3) from Haiku to Sonnet 5.5 would cost.
-- FAR calls per UK day on Haiku, and the per-call cost Sonnet 5.5 is already paying for the same
-- sky prompt at T+0/T+1. Horizon here is target_date minus the call's date (api_call_log has no
-- days_ahead column); the nightly batch runs at ~01:07 UTC, so UTC and UK dates agree for it.
-- Run standalone (it does not need cell-groups.sql). :since as in README.md.
SELECT evaluation_model AS model, target_date - CAST(called_at AS DATE) AS t,
       COUNT(*) AS calls,
       COUNT(DISTINCT CAST(called_at AS DATE)) AS call_days,
       ROUND(AVG(cost_micro_dollars)) AS avg_cost_micro_usd,
       ROUND(AVG(input_tokens)) AS avg_in,
       ROUND(AVG(cache_read_input_tokens)) AS avg_cache_read,
       ROUND(AVG(output_tokens)) AS avg_out,
       ROUND(SUM(cost_micro_dollars) / 1000000.0, 3) AS usd
FROM api_call_log
WHERE service = 'ANTHROPIC' AND succeeded AND is_batch
  AND custom_id LIKE 'fc-%'
  AND called_at >= :'since'
GROUP BY evaluation_model, target_date - CAST(called_at AS DATE)
ORDER BY evaluation_model, t;
