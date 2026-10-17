-- V173: move the daily briefing model comparison from 05:00 to 06:30 UTC (solar-relative-refresh-plan P4).
--
-- The job reads the briefing the morning forecast cycle built. Once that cycle fires at sunrise minus
-- 255 minutes it starts as late as 04:15 UTC in midwinter, which left about 45 minutes. 06:30 clears
-- every other seeded schedule. The update is guarded on the value V79 seeded: production's schedules
-- were hand-edited, and a changed cron is the operator's choice and is left alone. Changes this one
-- row's cron_expression and nothing else.
UPDATE scheduler_job_config
SET cron_expression = '0 30 6 * * *'
WHERE job_key = 'briefing_model_comparison'
  AND cron_expression = '0 0 5 * * *';
