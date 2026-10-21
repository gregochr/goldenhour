-- V186: valley mist star (valley-mist-plan M2) -- Claude's own reading of a mist-prone site.
--
-- forecast_evaluation.mist_potential is the band Claude returns in "mist_potential" for a
-- mist_prone site (NONE/POSSIBLE/LIKELY/FOGBOUND), the inversion_potential convention: a nullable
-- VARCHAR(10), the enum NAME, mapped as a plain String. NULL for every other site and every row
-- written before this migration. It feeds the map badge alone; the hot topic and Coming up read
-- the calculator's slot_atmosphere.mist_potential (V185) and fall back to this echo only for a slot
-- the calculator never reached.
--
-- forecast_type row 8 (MIST) is the one-seed-row fold-in V107 anticipated, so ForecastScoreWriter
-- can upsert the echo into forecast_score as a MIST component: the band rides the row's summary
-- (as INVERSION's does) and the integer score is the band's midpoint on the 0-10 scale. The id,
-- code, display_name and scale_max must mirror ForecastType.MIST -- ForecastTypeSeedDriftTest
-- enforces the bijection.
ALTER TABLE forecast_evaluation ADD COLUMN mist_potential VARCHAR(10);

INSERT INTO forecast_type (id, code, display_name, scale_max) VALUES
    (8, 'MIST', 'Valley Mist Forecast', 10);
