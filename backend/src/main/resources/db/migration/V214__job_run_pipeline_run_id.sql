-- V214: link a chained job run to the pipeline run (cycle) that started it.
--
-- A cycle's BRIEFING phase and the ASK_READY precompute that follows it each write their own job_run, but
-- until now nothing on the row said which cycle they belonged to (ask_ready_answer.pipeline_run_id is an
-- upsert cache, not history). The admin Runs "Day" view reads this column to show a cycle's children
-- ("Runs after Afternoon refresh") and the cycle's "until answers ready" time.
--
-- Nullable and FK-less: every row written before this migration, every manual run and every run that has no
-- cycle (an admin briefing build, the astro job's own precompute) leaves it null. Written at insert only (the
-- entity maps it updatable = false).
ALTER TABLE job_run ADD COLUMN pipeline_run_id BIGINT;

-- Partial: most rows are null, and the reader looks a cycle's children up by id.
CREATE INDEX idx_job_run_pipeline_run_id
    ON job_run (pipeline_run_id)
    WHERE pipeline_run_id IS NOT NULL;
