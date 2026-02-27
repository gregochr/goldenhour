-- Track evaluation model, target date, and event type for forecast evaluation API calls
ALTER TABLE api_call_log ADD COLUMN evaluation_model VARCHAR(10);
ALTER TABLE api_call_log ADD COLUMN target_date DATE;
ALTER TABLE api_call_log ADD COLUMN target_type VARCHAR(10);
