-- V122: register the sky-rating eval batch reconciler as a dynamic scheduler job.
--
-- Seeded ACTIVE (unlike the weekly eval job in V118, which is PAUSED). The reconciler
-- spends no Claude budget — it only reloads RUNNING eval runs that carry a batch id
-- and finalises the ones whose Anthropic batch has ENDED — so it is safe to run
-- continuously, and it MUST be active before the weekly eval is resumed so the
-- batches that job submits get reconciled without a second manual resume.
--
-- When there are no in-flight batches (the common case, since the weekly job ships
-- PAUSED) each tick is a single indexed "status = RUNNING" query that returns nothing.
-- FIXED_DELAY 60 s mirrors the forecast pipeline's batch_result_polling cadence; the
-- 45 s initial delay lets startup settle before the first poll.
--
-- The runnable is registered in code by SkyRatingEvalBatchService via
-- registerJobTarget("sky_rating_eval_batch_poll", ...); this row is the config the
-- DynamicSchedulerService reads.
INSERT INTO scheduler_job_config (job_key, display_name, description, schedule_type, fixed_delay_ms, initial_delay_ms, status)
VALUES ('sky_rating_eval_batch_poll', 'Sky Rating Eval Batch Poll',
        'Polls the Anthropic Batch API for completed sky-rating eval batches and finalises their runs (restart-safe reconciler for the weekly calibration eval)',
        'FIXED_DELAY', 60000, 45000, 'ACTIVE');
