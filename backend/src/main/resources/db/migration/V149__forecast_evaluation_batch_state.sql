-- V149: batch_state column on forecast_evaluation (prompted-row persistence plan, R2)
--
-- Explicit tri-state lifecycle for a row inserted at fc- (sky lane) batch submit time:
-- PENDING until the batch result lands (SCORED) or the row is closed out by the
-- abandonment sweep (ABANDONED). Null for every existing row and for every sync-engine
-- or triage write going forward — their semantics are unchanged.
--
-- No index: V128's (location_id, target_date, target_type, forecast_run_at) already serves
-- R1's exclusion (an extra predicate on that same query). It does NOT serve the R7(b) backstop
-- sweep's bulk UPDATE (WHERE batch_state = PENDING AND forecast_run_at < :cutoff — no
-- location_id/target_date/target_type predicate, so it can't use a composite index led by
-- location_id). Left unindexed anyway per the plan's "no indexes on spec" rule: the sweep runs
-- hourly over a table in the tens of thousands of rows, a cheap scan today. Add a
-- (batch_state, forecast_run_at) index if EXPLAIN or the table's growth says otherwise.

ALTER TABLE forecast_evaluation ADD COLUMN batch_state VARCHAR(20);
