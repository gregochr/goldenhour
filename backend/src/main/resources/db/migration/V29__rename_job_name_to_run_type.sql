-- V29: Rename job_name → run_type on job_run, config_type → run_type on model_selection
-- Also add evaluation_model column to job_run

-- 1. job_run: add run_type and evaluation_model columns
ALTER TABLE job_run ADD COLUMN run_type VARCHAR(20);
ALTER TABLE job_run ADD COLUMN evaluation_model VARCHAR(10);

-- 2. Populate run_type and evaluation_model from legacy job_name
-- HAIKU/SONNET/OPUS → SHORT_TERM (best guess; the original run type wasn't tracked)
UPDATE job_run SET run_type = 'SHORT_TERM', evaluation_model = 'HAIKU' WHERE job_name = 'HAIKU';
UPDATE job_run SET run_type = 'SHORT_TERM', evaluation_model = 'SONNET' WHERE job_name = 'SONNET';
UPDATE job_run SET run_type = 'VERY_SHORT_TERM', evaluation_model = 'OPUS' WHERE job_name = 'OPUS';
UPDATE job_run SET run_type = 'WEATHER' WHERE job_name = 'WEATHER';
UPDATE job_run SET run_type = 'TIDE' WHERE job_name = 'TIDE';

-- 3. Make run_type NOT NULL now that all rows are populated
ALTER TABLE job_run ALTER COLUMN run_type SET NOT NULL;

-- 4. Drop legacy job_name column
ALTER TABLE job_run DROP COLUMN job_name;

-- 5. model_selection: rename config_type → run_type
ALTER TABLE model_selection ALTER COLUMN config_type RENAME TO run_type;
