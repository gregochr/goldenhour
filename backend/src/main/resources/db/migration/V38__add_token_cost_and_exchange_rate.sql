-- V38: Token-based cost tracking with micro-dollar precision and exchange rate snapshots

-- api_call_log: token counts + micro-dollar cost + batch flag
ALTER TABLE api_call_log ADD COLUMN input_tokens BIGINT;
ALTER TABLE api_call_log ADD COLUMN output_tokens BIGINT;
ALTER TABLE api_call_log ADD COLUMN cache_creation_input_tokens BIGINT;
ALTER TABLE api_call_log ADD COLUMN cache_read_input_tokens BIGINT;
ALTER TABLE api_call_log ADD COLUMN is_batch BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE api_call_log ADD COLUMN cost_micro_dollars BIGINT;

-- model_test_result: token counts + micro-dollar cost
ALTER TABLE model_test_result ADD COLUMN input_tokens BIGINT;
ALTER TABLE model_test_result ADD COLUMN output_tokens BIGINT;
ALTER TABLE model_test_result ADD COLUMN cache_creation_input_tokens BIGINT;
ALTER TABLE model_test_result ADD COLUMN cache_read_input_tokens BIGINT;
ALTER TABLE model_test_result ADD COLUMN cost_micro_dollars BIGINT;

-- job_run: micro-dollar total + exchange rate snapshot
ALTER TABLE job_run ADD COLUMN total_cost_micro_dollars BIGINT;
ALTER TABLE job_run ADD COLUMN exchange_rate_gbp_per_usd DOUBLE;

-- model_test_run: micro-dollar total + exchange rate snapshot
ALTER TABLE model_test_run ADD COLUMN total_cost_micro_dollars BIGINT;
ALTER TABLE model_test_run ADD COLUMN exchange_rate_gbp_per_usd DOUBLE;

-- exchange_rate table: daily rate cache
CREATE TABLE exchange_rate (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rate_date DATE NOT NULL UNIQUE,
    gbp_per_usd DOUBLE NOT NULL,
    fetched_at TIMESTAMP NOT NULL
);
