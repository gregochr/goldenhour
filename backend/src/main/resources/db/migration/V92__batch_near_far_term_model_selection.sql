-- Split SCHEDULED_BATCH into BATCH_NEAR_TERM and BATCH_FAR_TERM model_selection rows.
-- Near-term (T+0, T+1) inherits the existing SONNET setting from SCHEDULED_BATCH.
-- Far-term (T+2, T+3) defaults to HAIKU — lower-confidence forecasts cost less.
-- The old SCHEDULED_BATCH row is retained for backward compatibility with existing
-- job_run records but will no longer be used by the model selection service.

INSERT INTO model_selection (active_model, run_type, updated_at)
VALUES ('SONNET', 'BATCH_NEAR_TERM', CURRENT_TIMESTAMP);

INSERT INTO model_selection (active_model, run_type, updated_at)
VALUES ('HAIKU', 'BATCH_FAR_TERM', CURRENT_TIMESTAMP);
