-- V39: Determinism re-run support — atmospheric data storage and run lineage

-- Store atmospheric data as JSON for exact replay
ALTER TABLE model_test_result ADD COLUMN atmospheric_data_json CLOB;

-- Run lineage tracking
ALTER TABLE model_test_run ADD COLUMN parent_run_id BIGINT;
ALTER TABLE model_test_run ADD COLUMN rerun_type VARCHAR(20);
ALTER TABLE model_test_run ADD CONSTRAINT fk_mtr_parent
    FOREIGN KEY (parent_run_id) REFERENCES model_test_run(id);

-- Structured atmospheric fields on model_test_result (for future comparison grid)
ALTER TABLE model_test_result ADD COLUMN low_cloud_percent INT;
ALTER TABLE model_test_result ADD COLUMN mid_cloud_percent INT;
ALTER TABLE model_test_result ADD COLUMN high_cloud_percent INT;
ALTER TABLE model_test_result ADD COLUMN visibility_metres INT;
ALTER TABLE model_test_result ADD COLUMN wind_speed_ms DECIMAL(5,2);
ALTER TABLE model_test_result ADD COLUMN wind_direction_degrees INT;
ALTER TABLE model_test_result ADD COLUMN precipitation_mm DECIMAL(5,2);
ALTER TABLE model_test_result ADD COLUMN humidity_percent INT;
ALTER TABLE model_test_result ADD COLUMN weather_code INT;
ALTER TABLE model_test_result ADD COLUMN pm25 DECIMAL(6,2);
ALTER TABLE model_test_result ADD COLUMN dust_ugm3 DECIMAL(6,2);
ALTER TABLE model_test_result ADD COLUMN aerosol_optical_depth DECIMAL(5,3);
ALTER TABLE model_test_result ADD COLUMN temperature_celsius DOUBLE;
ALTER TABLE model_test_result ADD COLUMN apparent_temperature_celsius DOUBLE;
ALTER TABLE model_test_result ADD COLUMN precipitation_probability INT;
ALTER TABLE model_test_result ADD COLUMN tide_state VARCHAR(20);
ALTER TABLE model_test_result ADD COLUMN tide_aligned BOOLEAN;
