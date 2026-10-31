-- Cirrus-level cloud at the far point (far-cloud-cirrus-levels-plan).
--
-- Cloud cover at 250 and 200 hPa (about 10 to 12 km) at the 226 km far-solar point, at the event's
-- hour. V176 stopped at 300 hPa, so a far point under a cirrus deck could read 0 at every stored
-- level while single-level cloud_cover_high read high; 86% of the production rows blind at every
-- stored level were high-only (2026-10-31). Recorded as DATA ONLY: the prompt, triage and every
-- rating are unchanged. Forecast side only: the ERA5 archive returns null for these variables.
--
-- Both columns are nullable INTEGER with no default and no backfill: a row written before this
-- migration, or one whose separate pressure-level request failed, stays null. A single statement,
-- so the table lock is taken once.
ALTER TABLE forecast_evaluation
    ADD COLUMN far_solar_cloud_250hpa INTEGER,
    ADD COLUMN far_solar_cloud_200hpa INTEGER;
