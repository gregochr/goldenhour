-- V118: register the sky-rating eval as a dynamic scheduler job.
--
-- Seeded PAUSED so merging the feature does not start spending Claude budget on a
-- cadence until an admin deliberately resumes it from the Scheduler UI. Weekly is
-- the right cadence for CALIBRATION drift (slow-moving) — distinct from the daily
-- real-api smoke, which catches faster-moving response/schema drift. When resumed,
-- it runs Monday 03:00 UTC (off-peak vs the 04:00 briefing and 19:00 batch).
--
-- The runnable is registered in code by SkyRatingEvalService via registerJobTarget
-- ("sky_rating_eval", ...); this row is the config the DynamicSchedulerService reads.
INSERT INTO scheduler_job_config (job_key, display_name, description, schedule_type, cron_expression, status)
VALUES ('sky_rating_eval', 'Sky Rating Eval',
        'Runs the pass^k sky-rating calibration eval (frozen fixtures through the real scorer) and persists results for drift graphs',
        'CRON', '0 0 3 * * MON', 'PAUSED');
