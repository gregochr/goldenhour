-- Drop the legacy pence-based cost columns now that micro-dollar token pricing is the
-- single source of truth for all cost data. Every current record carries
-- cost_micro_dollars / total_cost_micro_dollars (+ exchange rate for GBP), and the
-- frontend renders GBP from micro-dollars, falling back to pence only for pre-token rows.
-- After this migration those legacy rows show no cost (acceptable, admin-only historical data).
--
-- DROP COLUMN IF EXISTS is valid in both H2 (local/dev) and PostgreSQL (prod). Dropping a
-- column also removes its NOT NULL constraint; none of these columns has an index, FK, or
-- view dependency.

ALTER TABLE api_call_log DROP COLUMN IF EXISTS cost_pence;
ALTER TABLE job_run DROP COLUMN IF EXISTS total_cost_pence;
ALTER TABLE model_test_run DROP COLUMN IF EXISTS total_cost_pence;
ALTER TABLE model_test_result DROP COLUMN IF EXISTS cost_pence;
ALTER TABLE prompt_test_run DROP COLUMN IF EXISTS total_cost_pence;
ALTER TABLE prompt_test_result DROP COLUMN IF EXISTS cost_pence;
