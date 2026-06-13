-- V110: Record the best-bet advisor's outcome per pipeline cycle.
--
-- The whole-run `status` column (RUNNING/COMPLETED/FAILED) describes the
-- orchestrator's lifecycle, not the best-bet advisor's outcome — a run can be
-- COMPLETED while the advisor failed (e.g. a truncated response). Without a
-- dedicated column the absence of pipeline_run_pick rows is ambiguous: it could
-- mean an honest "nothing stood out" decline OR a failure that lost a good pick.
--
-- best_bet_status disambiguates the three outcomes per run, so the run history
-- and the cross-run "did Plan A change?" comparison can tell "flat week" from
-- "advisor broke", and the fail-safe fallback can find the last successful run.
--
-- Values: SUCCESS_WITH_PICKS / SUCCESS_NO_PICKS / FAILED. Null on runs that
-- predate this column or whose briefing was served stale (carried-forward
-- last-known-good, not this cycle's own outcome).

ALTER TABLE pipeline_run
    ADD COLUMN best_bet_status VARCHAR(20);
