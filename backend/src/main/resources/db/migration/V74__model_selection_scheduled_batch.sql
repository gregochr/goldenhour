-- Add model_selection row for scheduled batch evaluation.
-- Batch API pricing is 50% off; SONNET via batch costs roughly the same as
-- HAIKU in real-time, so SONNET is the sensible default here.
INSERT INTO model_selection (active_model, run_type, updated_at)
VALUES ('SONNET', 'SCHEDULED_BATCH', CURRENT_TIMESTAMP);
