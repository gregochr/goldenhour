-- Remembers the status a job held before a config flag disabled it, so re-enabling the flag
-- restores that status instead of promoting every job to ACTIVE.
--
-- Without this, generalising the aurora.enabled gate beyond aurora_polling would start jobs
-- nobody asked for: aurora_batch_evaluation is deliberately seeded PAUSED (V73), so the old
-- "DISABLED_BY_CONFIG -> ACTIVE" restore would silently promote it to ACTIVE on the first
-- aurora.enabled off->on toggle, and it would begin submitting Anthropic Batch work nightly.
--
-- Null means "never config-disabled"; the restore falls back to ACTIVE, matching the previous
-- behaviour for aurora_polling (the only job the gate reached before).
ALTER TABLE scheduler_job_config
    ADD COLUMN status_before_config_disable VARCHAR(30);
