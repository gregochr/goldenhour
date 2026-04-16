-- Adds token usage and cost tracking columns to forecast_batch.
-- All nullable — existing batch rows retain nulls.

ALTER TABLE forecast_batch
    ADD COLUMN total_input_tokens BIGINT;

ALTER TABLE forecast_batch
    ADD COLUMN total_output_tokens BIGINT;

ALTER TABLE forecast_batch
    ADD COLUMN total_cache_read_tokens BIGINT;

ALTER TABLE forecast_batch
    ADD COLUMN total_cache_creation_tokens BIGINT;

ALTER TABLE forecast_batch
    ADD COLUMN estimated_cost_usd NUMERIC(10, 6);

COMMENT ON COLUMN forecast_batch.total_input_tokens IS
    'Sum of input tokens across all requests in batch';
COMMENT ON COLUMN forecast_batch.total_output_tokens IS
    'Sum of output tokens generated';
COMMENT ON COLUMN forecast_batch.total_cache_read_tokens IS
    'Sum of cached input tokens read (90% cost saving applies)';
COMMENT ON COLUMN forecast_batch.total_cache_creation_tokens IS
    'Sum of tokens written to cache (1.25x base rate applies)';
COMMENT ON COLUMN forecast_batch.estimated_cost_usd IS
    'Estimated USD cost for the entire batch (post-discounts)';
