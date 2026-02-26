ALTER TABLE forecast_evaluation ADD COLUMN humidity INTEGER;
ALTER TABLE forecast_evaluation ADD COLUMN weather_code INTEGER;
ALTER TABLE forecast_evaluation ADD COLUMN boundary_layer_height INTEGER;
ALTER TABLE forecast_evaluation ADD COLUMN shortwave_radiation DECIMAL(7,2);
ALTER TABLE forecast_evaluation ADD COLUMN pm2_5 DECIMAL(7,2);
ALTER TABLE forecast_evaluation ADD COLUMN dust DECIMAL(7,2);
ALTER TABLE forecast_evaluation ADD COLUMN aerosol_optical_depth DECIMAL(5,3);
