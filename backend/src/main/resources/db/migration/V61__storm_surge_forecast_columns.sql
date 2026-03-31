-- Storm surge forecast columns on forecast_evaluation.
-- Stores the breakdown of weather-driven tide amplification for each evaluation.

ALTER TABLE forecast_evaluation ADD COLUMN surge_total_metres        DOUBLE PRECISION;
ALTER TABLE forecast_evaluation ADD COLUMN surge_pressure_metres     DOUBLE PRECISION;
ALTER TABLE forecast_evaluation ADD COLUMN surge_wind_metres         DOUBLE PRECISION;
ALTER TABLE forecast_evaluation ADD COLUMN surge_risk_level          VARCHAR(10);
ALTER TABLE forecast_evaluation ADD COLUMN surge_adjusted_range_metres    DOUBLE PRECISION;
ALTER TABLE forecast_evaluation ADD COLUMN surge_astronomical_range_metres DOUBLE PRECISION;
