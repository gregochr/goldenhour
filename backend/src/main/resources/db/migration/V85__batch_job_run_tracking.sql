-- ============================================================
-- Batch job run tracking
-- ============================================================

-- 1. Add notes column to job_run (used to store Anthropic batch IDs)
ALTER TABLE job_run ADD COLUMN notes VARCHAR(500);

-- 2. Link forecast_batch rows to their corresponding job_run record
ALTER TABLE forecast_batch ADD COLUMN job_run_id BIGINT;
