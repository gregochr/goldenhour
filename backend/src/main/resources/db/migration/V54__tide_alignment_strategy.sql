-- V54: Add TIDE_ALIGNMENT as a configurable optimisation strategy
-- Enabled by default for all colour forecast run types (no param_value required)

INSERT INTO optimisation_strategy (run_type, strategy_type, enabled, param_value, updated_at)
VALUES ('VERY_SHORT_TERM', 'TIDE_ALIGNMENT', TRUE, NULL, CURRENT_TIMESTAMP);
INSERT INTO optimisation_strategy (run_type, strategy_type, enabled, param_value, updated_at)
VALUES ('SHORT_TERM', 'TIDE_ALIGNMENT', TRUE, NULL, CURRENT_TIMESTAMP);
INSERT INTO optimisation_strategy (run_type, strategy_type, enabled, param_value, updated_at)
VALUES ('LONG_TERM', 'TIDE_ALIGNMENT', TRUE, NULL, CURRENT_TIMESTAMP);
