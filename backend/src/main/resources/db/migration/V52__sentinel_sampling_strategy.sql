-- V52: Add SENTINEL_SAMPLING as a configurable optimisation strategy
-- and clean up stale REQUIRE_PRIOR rows (functionality merged into SKIP_LOW_RATED)

-- Add SENTINEL_SAMPLING strategy for each run type (default: enabled with threshold 2)
INSERT INTO optimisation_strategy (run_type, strategy_type, enabled, param_value, updated_at)
VALUES ('VERY_SHORT_TERM', 'SENTINEL_SAMPLING', TRUE, 2, CURRENT_TIMESTAMP);
INSERT INTO optimisation_strategy (run_type, strategy_type, enabled, param_value, updated_at)
VALUES ('SHORT_TERM', 'SENTINEL_SAMPLING', TRUE, 2, CURRENT_TIMESTAMP);
INSERT INTO optimisation_strategy (run_type, strategy_type, enabled, param_value, updated_at)
VALUES ('LONG_TERM', 'SENTINEL_SAMPLING', TRUE, 2, CURRENT_TIMESTAMP);

-- Remove stale REQUIRE_PRIOR rows — this logic was folded into SKIP_LOW_RATED
DELETE FROM optimisation_strategy WHERE strategy_type = 'REQUIRE_PRIOR';
