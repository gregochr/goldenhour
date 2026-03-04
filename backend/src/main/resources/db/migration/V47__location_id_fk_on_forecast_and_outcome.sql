-- V47: Replace location_name VARCHAR FK with location_id BIGINT FK
-- on forecast_evaluation and actual_outcome tables.

-- 1. Add location_id column (nullable initially)
ALTER TABLE forecast_evaluation ADD COLUMN location_id BIGINT;
ALTER TABLE actual_outcome ADD COLUMN location_id BIGINT;

-- 2. Backfill from locations table
UPDATE forecast_evaluation fe SET location_id = (SELECT id FROM locations WHERE name = fe.location_name);
UPDATE actual_outcome ao SET location_id = (SELECT id FROM locations WHERE name = ao.location_name);

-- 3. Make NOT NULL
ALTER TABLE forecast_evaluation ALTER COLUMN location_id SET NOT NULL;
ALTER TABLE actual_outcome ALTER COLUMN location_id SET NOT NULL;

-- 4. Add FK constraints
ALTER TABLE forecast_evaluation ADD CONSTRAINT fk_forecast_eval_location_id
    FOREIGN KEY (location_id) REFERENCES locations(id);
ALTER TABLE actual_outcome ADD CONSTRAINT fk_actual_outcome_location_id
    FOREIGN KEY (location_id) REFERENCES locations(id);

-- 5. Drop old string FK constraints (V15)
ALTER TABLE forecast_evaluation DROP CONSTRAINT fk_forecast_eval_location;
ALTER TABLE actual_outcome DROP CONSTRAINT fk_actual_outcome_location;

-- 6. Drop index referencing location_name (H2 requires this before column drop)
DROP INDEX IF EXISTS idx_forecast_eval_location_date;

-- 7. Drop location_name column
ALTER TABLE forecast_evaluation DROP COLUMN location_name;
ALTER TABLE actual_outcome DROP COLUMN location_name;
