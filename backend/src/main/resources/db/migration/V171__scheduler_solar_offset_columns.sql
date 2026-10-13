-- Columns for the SOLAR_OFFSET schedule type (solar-relative-refresh-plan A3, P2a).
--
-- A SOLAR_OFFSET job fires a fixed number of minutes before sunrise or sunset at the scheduler's
-- reference point. It keeps its cron_expression as the fallback and the revert target, so these two
-- columns are purely additive and nullable: every existing row keeps reading exactly as it did.
--
-- COLUMNS ONLY. This migration deliberately updates and inserts no row. Production's cron values
-- were hand-edited and differ from their seeds (near_term_batch_evaluation is seeded
-- '0 0 3,15 * * *' PAUSED by V73 and runs at 01:00 in production), so nothing here may assume one.
-- A job is switched to SOLAR_OFFSET only by an admin, through the schedule-type endpoint.
--
-- The CHECKs hold the values the application already validates: the event is one of two words and
-- the offset is 0..720 minutes before it. NULL passes both (a CRON or FIXED_DELAY row).

ALTER TABLE scheduler_job_config ADD COLUMN solar_event VARCHAR(10);
ALTER TABLE scheduler_job_config ADD COLUMN solar_offset_minutes INTEGER;

ALTER TABLE scheduler_job_config
    ADD CONSTRAINT ck_scheduler_job_config_solar_event
        CHECK (solar_event IS NULL OR solar_event IN ('SUNRISE', 'SUNSET'));

ALTER TABLE scheduler_job_config
    ADD CONSTRAINT ck_scheduler_job_config_solar_offset
        CHECK (solar_offset_minutes IS NULL OR (solar_offset_minutes BETWEEN 0 AND 720));
