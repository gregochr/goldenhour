-- Correct the tide_refresh scheduler description.
--
-- Two things were wrong with the V68 seed text, and only one of them is new:
--
--   1. "14 days" — the WorldTides fetch horizon is now 97 days (a 90-day almanac horizon for
--      the Coming-up feed, plus a week of slack so coverage still reaches T+90 on the day
--      before the next weekly refresh).
--   2. "for all coastal locations" — this was never true. ScheduledForecastService.refreshTideExtremes
--      requires the SEASCAPE type as well as a non-empty tide type, so a coastal LANDSCAPE or
--      WATERFALL location has never been included.
--
-- Why a migration rather than a code change: nothing in the application writes this column.
-- DynamicSchedulerService.registerJobTarget takes only (jobKey, Runnable) and holds no metadata
-- to re-seed from, and its only writes are to cron_expression, status and the fire/completion
-- timestamps. The V68 seed is therefore the permanent source of truth.
--
-- Why it matters that it is correct: SchedulerController.toDto puts this string on
-- GET /api/admin/scheduler/jobs, and SchedulerView.jsx renders it verbatim in
-- Manage -> Operations -> Scheduler. A stale value here is a false statement on a screen an
-- operator uses, not a dormant database field.
--
-- Safe if the row is absent: job_key is NOT NULL UNIQUE so this touches at most one row, and an
-- UPDATE matching nothing succeeds silently. V90 and V103 established that job rows do get
-- deleted, so that is a real case rather than a theoretical one.

UPDATE scheduler_job_config
SET description = 'Refreshes 97 days of tide extremes from WorldTides for all enabled SEASCAPE locations with a tide preference',
    updated_at  = CURRENT_TIMESTAMP
WHERE job_key = 'tide_refresh';
