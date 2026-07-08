-- V121: make the batched sky-rating eval restart-safe.
--
-- The weekly multi-model eval submits one Anthropic message batch and previously
-- awaited + finalised it on a fire-and-forget in-memory virtual thread, holding the
-- batch id only on that thread's stack. A backend restart (deploy/crash) between
-- submit and finish killed the thread, orphaning the RUNNING run rows forever: no
-- persisted batch id meant nothing could ever reconcile them.
--
-- Persisting batch_id lets a scheduled reconciler (V122's sky_rating_eval_batch_poll
-- job) reload in-flight batches after a restart and finalise their runs — the same
-- DB-backed pattern the forecast pipeline's batch_result_polling job already uses.

ALTER TABLE sky_rating_eval_run ADD COLUMN batch_id VARCHAR(255);

-- One reconciler query per tick is "RUNNING runs" (optionally with a batch id), so
-- index status to keep it cheap even though it usually returns nothing.
CREATE INDEX idx_srun_status ON sky_rating_eval_run(status);

-- One-time reclaim of the pre-durable-poller orphans. Any run still RUNNING at this
-- migration is by definition abandoned: the app is restarting to apply this schema,
-- so no processing thread is alive to finish it, and these rows predate batch_id
-- (all NULL) so none can be reconciled. Fail them so the admin UI stops showing a
-- perpetual "Running…" and the rows settle to a terminal state.
UPDATE sky_rating_eval_run
   SET status = 'FAILED',
       error_message = 'Reclaimed on upgrade: batch processing thread was lost before durable polling existed (pre-V121 orphan)',
       completed_at = CURRENT_TIMESTAMP
 WHERE status = 'RUNNING';
