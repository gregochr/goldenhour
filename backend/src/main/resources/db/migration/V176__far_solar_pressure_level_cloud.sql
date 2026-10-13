-- Pressure-level cloud at the far point (far-cloud-lid-plan P2, S0, T7b).
--
-- Cloud cover at 850, 700, 600, 500, 400 and 300 hPa at the 226 km far-solar point, at the event's
-- hour, so a later analysis can tell a lid from a canvas by height (far-cloud-lid-plan H5). Recorded
-- as DATA ONLY: the prompt, triage and every rating are unchanged. Forecast side only: the ERA5
-- archive returns null for these variables, so cloud_verification gets nothing.
--
-- All columns are nullable INTEGER with no default and no backfill: a row written before this
-- migration, or one whose separate pressure-level request failed, stays null. A single statement,
-- so the table lock is taken once.
ALTER TABLE forecast_evaluation
    ADD COLUMN far_solar_cloud_850hpa INTEGER,
    ADD COLUMN far_solar_cloud_700hpa INTEGER,
    ADD COLUMN far_solar_cloud_600hpa INTEGER,
    ADD COLUMN far_solar_cloud_500hpa INTEGER,
    ADD COLUMN far_solar_cloud_400hpa INTEGER,
    ADD COLUMN far_solar_cloud_300hpa INTEGER;
