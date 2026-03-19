-- Horizon cloud structure: low cloud at 226 km along the solar azimuth.
-- Comparing to solar_low_cloud (113 km) reveals strip vs blanket.
ALTER TABLE forecast_evaluation ADD COLUMN far_solar_low_cloud INT;
