-- Add model_selection rows for briefing best-bet advisor and aurora evaluation.
-- Briefing defaults to HAIKU (was hardcoded to OPUS; resetting to cost-effective default).
-- Aurora defaults to HAIKU (was hardcoded to Haiku).

INSERT INTO model_selection (active_model, run_type, updated_at) VALUES ('HAIKU', 'BRIEFING_BEST_BET', CURRENT_TIMESTAMP);
INSERT INTO model_selection (active_model, run_type, updated_at) VALUES ('HAIKU', 'AURORA_EVALUATION', CURRENT_TIMESTAMP);
