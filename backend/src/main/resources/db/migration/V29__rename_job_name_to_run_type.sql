-- V29: Rename job_name → run_type on job_run, config_type → run_type on model_selection
-- Also add evaluation_model column to job_run

-- 1. job_run: add run_type and evaluation_model columns (IF NOT EXISTS for idempotency)
ALTER TABLE job_run ADD COLUMN IF NOT EXISTS run_type VARCHAR(20);
ALTER TABLE job_run ADD COLUMN IF NOT EXISTS evaluation_model VARCHAR(10);

-- 2. Populate run_type and evaluation_model from legacy job_name (safe if already done)
UPDATE job_run SET run_type = 'SHORT_TERM', evaluation_model = 'HAIKU' WHERE run_type IS NULL AND job_name = 'HAIKU';
UPDATE job_run SET run_type = 'SHORT_TERM', evaluation_model = 'SONNET' WHERE run_type IS NULL AND job_name = 'SONNET';
UPDATE job_run SET run_type = 'VERY_SHORT_TERM', evaluation_model = 'OPUS' WHERE run_type IS NULL AND job_name = 'OPUS';
UPDATE job_run SET run_type = 'WEATHER' WHERE run_type IS NULL AND job_name = 'WEATHER';
UPDATE job_run SET run_type = 'TIDE' WHERE run_type IS NULL AND job_name = 'TIDE';

-- 3. Make run_type NOT NULL now that all rows are populated
ALTER TABLE job_run ALTER COLUMN run_type SET NOT NULL;

-- 4. Drop index referencing job_name, then drop the column
DROP INDEX IF EXISTS idx_job_run_name_started;
ALTER TABLE job_run DROP COLUMN IF EXISTS job_name;

-- 5. model_selection: rename config_type → run_type
ALTER TABLE model_selection RENAME COLUMN config_type TO run_type;
