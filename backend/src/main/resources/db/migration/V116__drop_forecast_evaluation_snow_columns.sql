-- V116: drop the orphaned snow columns from forecast_evaluation.
--
-- These (snowfall_cm, snow_depth_m, freezing_level_m, added V113) only ever fed the
-- SNOW_FRESH / SNOW_TOPS hot-topic detectors via findSnowFreshDays / findSnowTopsDays.
-- Those detectors now read snow depth, freezing level and humidity from the survivor
-- surface (survivor_atmosphere, V115), and the two queries have been removed. The columns
-- are written by ForecastService.buildEntity but read by nothing (not the serving DTO, not
-- the prompt — those use the live AtmosphericData/WeatherData, which is unchanged), so they
-- are dead weight on every forecast_evaluation row. Drop them.
--
-- Unlike the other Group-B atmospheric columns (dust, aerosol_optical_depth, pm2_5,
-- surge_*, inversion_*), these are NOT surfaced by ForecastDtoMapper, so dropping them is
-- self-contained — no DTO/serving change. The remaining dead detector queries' columns are
-- kept precisely because the serving path still reads them.

ALTER TABLE forecast_evaluation DROP COLUMN snowfall_cm;
ALTER TABLE forecast_evaluation DROP COLUMN snow_depth_m;
ALTER TABLE forecast_evaluation DROP COLUMN freezing_level_m;
