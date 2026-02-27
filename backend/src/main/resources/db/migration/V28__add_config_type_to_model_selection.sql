-- Add config_type column to model_selection for per-run-type model configuration.
-- Migrate from singleton row to one row per config type.

ALTER TABLE model_selection ADD COLUMN config_type VARCHAR(20) NOT NULL DEFAULT 'SHORT_TERM';

-- Remove old singleton row(s) and seed one row per config type, all defaulting to HAIKU.
DELETE FROM model_selection;

INSERT INTO model_selection (active_model, config_type, updated_at) VALUES ('HAIKU', 'VERY_SHORT_TERM', CURRENT_TIMESTAMP);
INSERT INTO model_selection (active_model, config_type, updated_at) VALUES ('HAIKU', 'SHORT_TERM', CURRENT_TIMESTAMP);
INSERT INTO model_selection (active_model, config_type, updated_at) VALUES ('HAIKU', 'LONG_TERM', CURRENT_TIMESTAMP);
