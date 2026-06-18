-- Snow plumbing for the snow trio hot topics (SNOW_FRESH, SNOW_TOPS, SNOW_MIST).
-- Sampled at the hour nearest the solar event, persisted alongside the other weather columns.
-- All nullable: snow columns read null/0 outside winter, freezing level is populated year-round.
ALTER TABLE forecast_evaluation ADD COLUMN snowfall_cm DOUBLE PRECISION;
ALTER TABLE forecast_evaluation ADD COLUMN snow_depth_m DOUBLE PRECISION;
ALTER TABLE forecast_evaluation ADD COLUMN freezing_level_m DOUBLE PRECISION;
