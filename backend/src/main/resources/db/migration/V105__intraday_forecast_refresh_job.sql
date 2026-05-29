-- V105: Seed the intraday forecast refresh scheduler job.
--
-- The nightly cycle (near_term_batch_evaluation, ~01:00 UTC in production) sets
-- the day's baseline. The intraday refresh runs mid-afternoon to catch
-- within-day forecast movement on the decision-window events (tonight's sunset,
-- tomorrow's sunrise + sunset) while there is still time to act — a plan-change
-- detector, not a generic cache refresh.
--
-- A distinct job_key (not co-opting near_term_batch_evaluation's seed-only 15:00
-- slot) so the two cycles are independently schedulable and observable. Fired at
-- 14:00 UTC: a fixed, predictable time with no BST/GMT drift, well separated from
-- the nightly fire so the shared Claude @Bulkhead is never contended.
--
-- DynamicSchedulerService picks this row up via its DB-backed registration; the
-- runnable target ('intraday_forecast_refresh' -> PipelineOrchestrator.runIntradayCycle)
-- is registered in @PostConstruct. Candidate selection is event-window-aware, so
-- moving to event-relative scheduling later is a trigger change, not a logic rework.

INSERT INTO scheduler_job_config (job_key, display_name, description, schedule_type, cron_expression, status)
VALUES ('intraday_forecast_refresh',
        'Intraday Forecast Refresh',
        'Re-evaluates the decision-window events (T sunset, T+1 sunrise, T+1 sunset) mid-afternoon, gated to TRANSITIONAL/UNSETTLED locations; settled locations are skipped (SKIPPED_NO_REFRESH_NEEDED). Re-runs the briefing afterwards.',
        'CRON', '0 0 14 * * *', 'ACTIVE');
