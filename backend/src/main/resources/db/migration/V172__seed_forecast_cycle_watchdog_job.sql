-- V172: seed the forecast-cycle watchdog as a dynamic-scheduler job (solar-relative-refresh-plan B2).
--
-- Every ten minutes it checks the two forecast cycle jobs and mails the admins, once per occurrence,
-- when a cycle did not start (its scheduled fire passed more than 30 minutes ago with no pipeline run
-- since) or had not finished close to the sunrise or sunset it serves. It only reads: it never starts,
-- stops or retries anything. The first check is ten minutes after startup so a deploy does not
-- alert about a cycle that is simply still coming up. job_key is NOT NULL UNIQUE, so the insert adds
-- exactly one row and changes no existing schedule.
INSERT INTO scheduler_job_config (job_key, display_name, description, schedule_type, fixed_delay_ms, initial_delay_ms, status)
VALUES ('forecast_cycle_watchdog', 'Forecast Cycle Watchdog', 'Mails the admins, once per occurrence, when a forecast cycle did not start within 30 minutes of its scheduled fire or had not finished 20 minutes before the sunrise or sunset it serves. Read-only.', 'FIXED_DELAY', 600000, 600000, 'ACTIVE');
