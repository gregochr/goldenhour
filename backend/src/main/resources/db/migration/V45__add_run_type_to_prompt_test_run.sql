-- V45: Add run_type column to prompt_test_run for date-range selection
ALTER TABLE prompt_test_run ADD COLUMN run_type VARCHAR(20);
