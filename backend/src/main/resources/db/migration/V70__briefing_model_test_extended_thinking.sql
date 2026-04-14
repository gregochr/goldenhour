-- Widen evaluation_model column to accommodate SONNET_ET (9 chars) and OPUS_ET (7 chars).
-- Previous length was VARCHAR(10) which already fits both, but this migration documents intent.
-- Add thinking_text column for storing the extended thinking chain from ET variants.

ALTER TABLE briefing_model_test_result ADD COLUMN thinking_text TEXT;
