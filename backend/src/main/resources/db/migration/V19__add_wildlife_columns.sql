ALTER TABLE forecast_evaluation ADD COLUMN temperature_celsius FLOAT DEFAULT NULL;
ALTER TABLE forecast_evaluation ADD COLUMN apparent_temperature_celsius FLOAT DEFAULT NULL;
ALTER TABLE forecast_evaluation ADD COLUMN precipitation_probability_percent INT DEFAULT NULL;
