-- Pending-registration lifecycle: remind never-verified sign-ups, then purge them
-- (docs/engineering/pending-registration-lifecycle-plan.md).
--
-- verification_reminders_sent counts the reminders the sweep has sent (0, 1 or 2);
-- final_reminder_sent_at is when the final one went, which the purge waits on. Zone-less TIMESTAMP on
-- purpose, like created_at (V10) and the verification token's expires_at: all are written in the JVM
-- default zone and every comparison in this feature stays in that one frame.
ALTER TABLE app_user ADD COLUMN verification_reminders_sent INTEGER NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN final_reminder_sent_at TIMESTAMP;

-- The backlog is never emailed (owner, 2026-10-24): every account pending NOW is marked as already
-- reminded, as if its final reminder had gone out at created_at + 144 h, so the sweep sends it nothing and
-- purges it once it is 7 days old. The 144 h is a one-off literal tied to the defaults at deploy
-- (168 h hold - 24 h final reminder); later config changes do not touch it.
UPDATE app_user SET verification_reminders_sent = 2,
       final_reminder_sent_at = created_at + INTERVAL '144 hours'
WHERE enabled = false AND password = '' AND role <> 'ADMIN';

-- Hourly at :20 UTC. job_key is NOT NULL UNIQUE, so the insert adds exactly one row.
INSERT INTO scheduler_job_config (job_key, display_name, description, schedule_type,
                                  cron_expression, status)
VALUES ('pending_registration_sweep',
        'Pending Registration Sweep',
        'Reminds never-verified sign-ups 3 days and 1 day before their place lapses, and deletes them '
            || 'after the final reminder',
        'CRON',
        '0 20 * * * *',
        'ACTIVE');
