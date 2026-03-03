-- V40: Remove REQUIRE_PRIOR strategy (merged into SKIP_LOW_RATED)
DELETE FROM optimisation_strategy WHERE strategy_type = 'REQUIRE_PRIOR';
