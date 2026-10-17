-- Backfill forecast_evaluation.scored_at for the rows scored BEFORE V178 (docs/engineering/
-- outcome-rating-plan.md section 4 #32 and the O7 row, owner decision 2026-10-17).
--
-- V178 chose "no backfill, no guess": a row written before the column has no availability instant, and
-- the as-of-event resolver (AsOfEventResolver) treats a NULL scored_at as never a candidate. That was
-- right in principle and wrong in practice. The Map tab serves a passed slot from the resolver, so every
-- sunrise and sunset whose rows were all scored before the v2.28.0 deploy (Wednesday's and Thursday's,
-- the first Saturday after it) resolved to NO row, was served as no row at all, and could not be
-- rated; the reader stepping back past yesterday found nothing to step to. Left alone that holds for
-- the whole first week, until the legacy rows age out of the rating window.
--
-- This is a deliberate, BOUNDED departure from V178, not a reversal of it:
--
--   * The stamp is forecast_run_at, the SUBMISSION instant. For a synchronous or triage row that is
--     also the instant it got its rating or reason, so the stamp is exact. For a batch row whose result
--     landed after its event (the Batch API allows up to 24 hours) it OVERSTATES availability, and the
--     resolver may offer that row as "what stood at the event". Accepted for this one population of
--     about a week of rows, which ages out of the 24-day rating window by mid-November; every row
--     scored after V178 carries a real stamp and is never touched here.
--   * The bound is V178's own install instant. A row submitted before V178 but scored after it was
--     already stamped by ForecastResultHandler.scoreEvaluationRow and is excluded by scored_at IS NULL;
--     a row submitted after V178 is excluded by the date test. If V178 is not in the history (it cannot
--     be, this runs after it) the subquery is NULL, the comparison is NULL and nothing is updated.
--   * Only rows with something to say are stamped (rating or triage_reason, the hasSomethingToSay
--     rule). A PENDING or ABANDONED row has nothing to say, keeps NULL, and the resolver would skip it
--     anyway.
--
-- Time-zone assumption: forecast_run_at is a naive UTC TIMESTAMP (ForecastService.buildEntity stamps
-- LocalDateTime.now(ZoneOffset.UTC)), and flyway_schema_history.installed_on is a TIMESTAMP written in
-- the DB session's zone. The stamp is written explicitly as UTC, whatever the session; the BOUND compares
-- the two naive values as they are, which is exact because the production container runs UTC (a session
-- in another zone would shift the bound by that offset: a few rows at the edge, nothing else).
-- Idempotent: a stamped row no longer matches scored_at IS NULL.
UPDATE forecast_evaluation
   SET scored_at = forecast_run_at AT TIME ZONE 'UTC'
 WHERE scored_at IS NULL
   AND (rating IS NOT NULL OR triage_reason IS NOT NULL)
   AND forecast_run_at < (SELECT installed_on FROM flyway_schema_history WHERE version = '178');
