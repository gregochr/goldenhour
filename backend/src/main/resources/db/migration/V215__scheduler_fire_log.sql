-- V215: the scheduler fire log (scheduler-redesign-plan.md S1, decisions D1, D3 and D6).
--
-- One row per fire of a dynamically scheduled job, written by DynamicSchedulerService.wrapTarget: an insert
-- (RUNNING) when the fire starts and one scoped UPDATE when it ends. The admin Scheduler screen reads it to
-- show what happened on a day. Retention is 14 days, pruned by the scheduler_fire_log_cleanup job seeded below.
--
-- status:  RUNNING | SUCCEEDED | PARTIAL | FAILED | SKIPPED  (MISSED is served, never stored)
-- trigger: SCHEDULED | MANUAL | CATCH_UP
-- pipeline_run_id links a forecast cycle's fire to the pipeline_run it started; nullable and FK-less (the row
-- is pruned long before the pipeline run, and a cycle that did nothing has none).
CREATE TABLE scheduler_fire_log (
    id              BIGSERIAL    PRIMARY KEY,
    job_key         VARCHAR(50)  NOT NULL,
    fired_at        TIMESTAMPTZ  NOT NULL,
    completed_at    TIMESTAMPTZ,
    status          VARCHAR(12)  NOT NULL,
    summary         VARCHAR(200),
    trigger         VARCHAR(12)  NOT NULL,
    pipeline_run_id BIGINT
);

-- "The fires of this job, newest first" (the job panel, the day view).
CREATE INDEX idx_scheduler_fire_log_job_fired ON scheduler_fire_log (job_key, fired_at DESC);

-- "Everything that fired in this window" (the day view) and the retention prune.
CREATE INDEX idx_scheduler_fire_log_fired_at ON scheduler_fire_log (fired_at);

-- When a job's schedule last changed (schedule edit, type switch, pause, resume). There is no schedule
-- history, so the day view only reports a fire as MISSED after this instant. Null = never changed since this
-- column existed. Written only by a scoped UPDATE (the entity maps it updatable = false).
ALTER TABLE scheduler_job_config ADD COLUMN schedule_changed_at TIMESTAMPTZ;

-- The nightly prune of the fire log. 03:57 UTC is free in every scheduler seed (03:55 is
-- ask_log_cleanup, V167). Same shape as V167's seed. job_key is NOT NULL UNIQUE, so the insert adds
-- exactly one row.
INSERT INTO scheduler_job_config (job_key, display_name, description, schedule_type,
                                  cron_expression, status)
VALUES ('scheduler_fire_log_cleanup',
        'Scheduler Fire Log Cleanup',
        'Prunes scheduler fire-log rows older than 14 days, so the Scheduler screen''s history '
            || 'stays a fortnight deep and the table stays small',
        'CRON',
        '0 57 3 * * *',
        'ACTIVE');
