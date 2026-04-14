-- V81: Register the refresh-token cleanup job with the dynamic scheduler

INSERT INTO scheduler_job_config (job_key, display_name, description, schedule_type, cron_expression, status)
VALUES ('refresh_token_cleanup', 'Refresh Token Cleanup',
        'Deletes expired refresh tokens from the database', 'CRON', '0 0 3 * * *', 'ACTIVE');
