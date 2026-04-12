-- V69: Add briefing model comparison as a scheduled job (daily at 05:00 UTC)

INSERT INTO scheduler_job_config (job_key, display_name, description, schedule_type, cron_expression, status)
VALUES (
    'briefing_model_comparison',
    'Briefing Model Comparison',
    'Daily comparison of Haiku, Sonnet and Opus best-bet picks against live briefing data.',
    'CRON',
    '0 0 5 * * *',
    'ACTIVE'
);
