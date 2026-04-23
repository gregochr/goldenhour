-- V99: Batch observability — add custom_id, error_type, batch_id to api_call_log
-- and widen error_message for Anthropic batch error payloads.

ALTER TABLE api_call_log ADD COLUMN custom_id VARCHAR(64);

ALTER TABLE api_call_log ADD COLUMN error_type VARCHAR(100);

ALTER TABLE api_call_log ADD COLUMN batch_id VARCHAR(100);

ALTER TABLE api_call_log ALTER COLUMN error_message TYPE TEXT;

CREATE INDEX idx_api_call_log_batch ON api_call_log(is_batch, called_at DESC)
    WHERE is_batch = true;

CREATE INDEX idx_api_call_log_custom_id ON api_call_log(custom_id)
    WHERE custom_id IS NOT NULL;
