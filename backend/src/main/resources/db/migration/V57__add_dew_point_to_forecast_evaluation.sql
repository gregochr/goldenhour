-- V57: Add dew_point_celsius to forecast_evaluation for mist/fog potential scoring.
-- Captures dew point at 2 m above ground at the solar event slot.
-- The gap between temperature_celsius and this value indicates fog/mist likelihood.
-- NULL for historical rows fetched before this migration.

ALTER TABLE forecast_evaluation ADD COLUMN IF NOT EXISTS dew_point_celsius DOUBLE;
