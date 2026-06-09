-- V106: Mark forecast_batch rows that are retries of transient failures.
--
-- After a cycle's forecast batches reach terminal status, a capped RETRY_FAILED
-- phase re-submits the genuinely-failed requests (parse failures / API errors —
-- requests that were sent to the model and came back unusable). Deliberate skips
-- (SKIPPED_*) are never retried: they have no api_call_log failure row, so they
-- cannot enter the retry set.
--
-- is_retry distinguishes a retry batch from its precursor(s). Both carry the same
-- pipeline_run_id, so the cycle's "Batches in this cycle" view can show
--   precursor (324/325, 1 failed) -> retry (1/1 recovered).
-- A boolean (not a precursor FK) because a cycle has up to four forecast batches
-- (near/far x inland/coastal) and a single retry batch may aggregate failures
-- across several of them — one FK cannot represent that. The precursor set is
-- "the is_retry = FALSE forecast batches sharing this pipeline_run_id".
--
-- Failure SELECTION reads only is_retry = FALSE batches, and submission is guarded
-- so at most one retry batch exists per cycle — together these make the
-- single-retry guarantee structural (a retry's own failures can never be retried).

ALTER TABLE forecast_batch
    ADD COLUMN is_retry BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_forecast_batch_pipeline_run_retry
    ON forecast_batch(pipeline_run_id, is_retry);
