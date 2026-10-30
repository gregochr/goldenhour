-- Per-job time zone for CRON scheduler jobs. NULL means UTC, so every existing job is unchanged.
ALTER TABLE scheduler_job_config ADD COLUMN time_zone VARCHAR(40);

-- The evening sunrise refresh is meant to fire at 21:00 UK time all year; a UTC cron cannot say so.
-- Status is not touched: it stays whatever the owner set.
UPDATE scheduler_job_config
SET time_zone = 'Europe/London',
    cron_expression = '0 0 21 * * *',
    description = 'Re-evaluates tomorrow''s sunrise at 21:00 UK time all year, so the bedtime decision rests on a fresh look'
WHERE job_key = 'evening_sunrise_refresh';
