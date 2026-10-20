-- V184: register the evening sunrise refresh as a dynamic scheduler job.
--
-- Seeded PAUSED so merging the feature runs nothing until the owner deliberately resumes it from
-- the Scheduler screen. When resumed it fires at 20:00 UTC (21:00 UK in summer, 20:00 in winter)
-- and re-evaluates tomorrow's sunrise alone, so the bedtime decision rests on a fresh look.
--
-- The runnable is registered in code by PipelineOrchestrator via registerJobTarget
-- ("evening_sunrise_refresh", ...); this row is the config DynamicSchedulerService reads.
-- CRON only: no solar_event / solar_offset_minutes (V171 columns stay null).
INSERT INTO scheduler_job_config (job_key, display_name, description, schedule_type, cron_expression, status)
VALUES ('evening_sunrise_refresh', 'Evening Sunrise Refresh',
        'Re-evaluates tomorrow''s sunrise at 20:00 UTC (21:00 UK in summer), so the bedtime decision rests on a fresh look',
        'CRON', '0 0 20 * * *', 'PAUSED');
