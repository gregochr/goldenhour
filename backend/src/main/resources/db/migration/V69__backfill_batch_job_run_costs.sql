-- Backfill total_cost_micro_dollars on job_run from forecast_batch.estimated_cost_usd
-- for SCHEDULED_BATCH runs that currently show £0 cost.
UPDATE job_run jr
SET total_cost_micro_dollars = (
    SELECT CAST(fb.estimated_cost_usd * 1000000 AS BIGINT)
    FROM forecast_batch fb
    WHERE fb.job_run_id = jr.id
      AND fb.estimated_cost_usd IS NOT NULL
)
WHERE jr.run_type = 'SCHEDULED_BATCH'
  AND (jr.total_cost_micro_dollars IS NULL OR jr.total_cost_micro_dollars = 0)
  AND EXISTS (
    SELECT 1 FROM forecast_batch fb
    WHERE fb.job_run_id = jr.id AND fb.estimated_cost_usd IS NOT NULL
  );
