-- Directional cloud cover from solar/antisolar horizon sampling (50 km offset points)
ALTER TABLE forecast_evaluation ADD COLUMN solar_low_cloud INT;
ALTER TABLE forecast_evaluation ADD COLUMN solar_mid_cloud INT;
ALTER TABLE forecast_evaluation ADD COLUMN solar_high_cloud INT;
ALTER TABLE forecast_evaluation ADD COLUMN antisolar_low_cloud INT;
ALTER TABLE forecast_evaluation ADD COLUMN antisolar_mid_cloud INT;
ALTER TABLE forecast_evaluation ADD COLUMN antisolar_high_cloud INT;

-- Basic-tier scores (without directional data) for LITE users
ALTER TABLE forecast_evaluation ADD COLUMN basic_fiery_sky_potential INT;
ALTER TABLE forecast_evaluation ADD COLUMN basic_golden_hour_potential INT;
ALTER TABLE forecast_evaluation ADD COLUMN basic_summary TEXT;
