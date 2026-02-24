-- Add tide-related columns to forecast_evaluation table
ALTER TABLE forecast_evaluation ADD COLUMN tide_state VARCHAR(10) DEFAULT NULL;
ALTER TABLE forecast_evaluation ADD COLUMN next_high_tide_time TIMESTAMP DEFAULT NULL;
ALTER TABLE forecast_evaluation ADD COLUMN next_high_tide_height_m DECIMAL(5, 2) DEFAULT NULL;
ALTER TABLE forecast_evaluation ADD COLUMN next_low_tide_time TIMESTAMP DEFAULT NULL;
ALTER TABLE forecast_evaluation ADD COLUMN next_low_tide_height_m DECIMAL(5, 2) DEFAULT NULL;
ALTER TABLE forecast_evaluation ADD COLUMN tide_aligned BOOLEAN DEFAULT NULL;
