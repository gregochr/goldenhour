-- Durable state for the per-cycle location auto-disable settle (LocationFailureService).
--
-- disposition_job_run_id -- the job run the cycle's forecast_run_disposition rows were persisted
--     onto: the first submitted batch's job run, or, when the cycle submitted no batch at all, a
--     disposition-only anchor job run that nothing else ties to the pipeline run. The settle reads
--     the cycle's evidence from it. Until now that link lived in memory and was lost on a restart,
--     so a batchless cycle resumed after a restart could not recover its successful triage and a
--     place already at two failures missed its reset.
--
-- failures_settled_at -- when location failures were settled for this cycle, NULL until then. The
--     settle claims the cycle with a conditional update (SET ... WHERE failures_settled_at IS NULL)
--     in the same transaction as its counter writes, so a cycle is counted exactly once, and a run
--     that the process stopped before it settled is settled when it resumes. The newest settled
--     trigger_time is read from it to refuse an older cycle after a newer one.
--
-- Both are nullable: every existing row keeps NULL, and a historic run is never replayed because
-- only RUNNING runs are resumed. Neither column is written by whole-entity saves
-- (updatable = false on PipelineRunEntity); only column-scoped updates on PipelineRunRepository.
ALTER TABLE pipeline_run ADD COLUMN disposition_job_run_id BIGINT;
ALTER TABLE pipeline_run ADD COLUMN failures_settled_at TIMESTAMP WITH TIME ZONE;
