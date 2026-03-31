-- Add SQM (Sky Quality Meter) value alongside the existing Bortle class.
-- SQM is a continuous value (magnitudes per square arcsecond) providing finer
-- granularity than the discrete Bortle 1–8 scale.
ALTER TABLE locations ADD COLUMN IF NOT EXISTS sky_brightness_sqm DOUBLE PRECISION;
