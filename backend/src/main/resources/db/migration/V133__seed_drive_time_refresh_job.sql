-- V133: Nightly drive-time refresh.
--
-- Drive times were only ever recalculated when a user pressed the button in Settings, and
-- DriveDurationService.refreshForUser replaces a user's whole set on each run. So a location added
-- after someone's last refresh had no row for them indefinitely — the UI simply rendered without a
-- drive chip, with nothing to indicate why. Observed in production: Hardwick Hall Country Park and
-- Houghall Woods had zero user_drive_time rows while their neighbours had one each, because they
-- were added after the last manual refresh.
--
-- 02:40 is deliberate: after the nightly forecast pipeline has settled and clear of the
-- top-of-hour jobs, on the quietest part of the ORS rate limit. The manual button is unchanged and
-- remains the immediate override.

INSERT INTO scheduler_job_config (job_key, display_name, description, schedule_type,
                                  cron_expression, status)
VALUES ('drive_time_refresh',
        'Drive Time Refresh',
        'Recalculates every user''s per-location drive times from their saved home postcode, so '
            || 'locations added since their last refresh stop rendering without a drive time. '
            || 'Skips users with no home location.',
        'CRON',
        '0 40 2 * * *',
        'ACTIVE');
