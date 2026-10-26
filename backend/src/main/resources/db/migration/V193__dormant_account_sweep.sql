-- Dormant-account sweep: warn inactive LITE_USER accounts, then delete them
-- (docs/engineering/audit-followups-plan.md, D).
--
-- dormancy_warning_sent_at is when the sweep warned the reader. Zone-less TIMESTAMP on purpose, like created_at
-- (V10) and last_active_at: all are written in the JVM default zone and every comparison in the sweep stays in
-- that one frame. A warning counts only if it is later than the reader's last activity, so a sign-in after the
-- warning cancels the deletion. Nullable, no backfill: nobody has been warned.
ALTER TABLE app_user ADD COLUMN dormancy_warning_sent_at TIMESTAMP;

-- Daily at 10:15 UTC, seeded PAUSED (as V184 does): merging this runs nothing until the owner resumes the job
-- from the Scheduler screen. Even then photocast.dormancy.dry-run defaults to true, so the first runs only log
-- what they would warn and delete. "Run now" runs a PAUSED job, which dry-run also covers.
-- job_key is NOT NULL UNIQUE, so the insert adds exactly one row.
INSERT INTO scheduler_job_config (job_key, display_name, description, schedule_type,
                                  cron_expression, status)
VALUES ('dormant_account_sweep',
        'Dormant Account Sweep',
        'Warns Lite accounts that have been unused for a long while, then deletes them after a grace period if they have not signed in. '
            || 'Logs only while photocast.dormancy.dry-run is true (the default)',
        'CRON',
        '0 15 10 * * *',
        'PAUSED');
