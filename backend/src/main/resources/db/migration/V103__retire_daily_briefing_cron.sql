-- V103: Retire the daily_briefing cron — the briefing is now invoked by the
-- nightly pipeline orchestrator (V102) on actual batch completion, not on a
-- ~3h time buffer hoped to be late enough.
--
-- The briefing service itself is unchanged; only the cron entry is removed.
-- BriefingService.refreshBriefing() is now called as the orchestrator's
-- BRIEFING phase. The @PostConstruct registration of the daily_briefing job
-- target in ScheduledForecastService is retained — it becomes a no-op when
-- the row below is gone (DynamicSchedulerService logs a warn and skips).
-- That keeps a one-line revert path open if we ever need to fall back to the
-- cron model while debugging.

DELETE FROM scheduler_job_config WHERE job_key = 'daily_briefing';
