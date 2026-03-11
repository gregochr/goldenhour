-- Add NEXT_EVENT_ONLY optimisation strategy for all forecast run types (disabled by default).
INSERT INTO optimisation_strategy (run_type, strategy_type, enabled, param_value, updated_at)
VALUES ('VERY_SHORT_TERM', 'NEXT_EVENT_ONLY', FALSE, NULL, CURRENT_TIMESTAMP);

INSERT INTO optimisation_strategy (run_type, strategy_type, enabled, param_value, updated_at)
VALUES ('SHORT_TERM', 'NEXT_EVENT_ONLY', FALSE, NULL, CURRENT_TIMESTAMP);

INSERT INTO optimisation_strategy (run_type, strategy_type, enabled, param_value, updated_at)
VALUES ('LONG_TERM', 'NEXT_EVENT_ONLY', FALSE, NULL, CURRENT_TIMESTAMP);
