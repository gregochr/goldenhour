-- Far-point cloud capture (far-cloud-lid-plan P2, T2).
--
-- The forecast reads low, mid and high cloud at the 226 km far-solar point but has stored only the low
-- layer, so a later analysis cannot tell a lid from a canvas out there. This records, as DATA ONLY
-- (the prompt, triage and every rating are unchanged):
--   * forecast_evaluation: mid and high cloud at the far point at the event's hour, plus low, mid and
--     high at the far point's dark-side hour (the hour after a sunset, or before a sunrise);
--   * cloud_verification: the matching ERA5 readings, filled by the verification backfill.
--
-- All columns are nullable INTEGER with no default and no backfill: a row written before this
-- migration, or a read that found no far point, stays null. One statement per table, so each takes
-- its lock once. Deliberately NOT added to CloudVerificationRepository.deleteIncompleteVerifications,
-- so existing verifications are not re-walked.
ALTER TABLE forecast_evaluation
    ADD COLUMN far_solar_mid_cloud INTEGER,
    ADD COLUMN far_solar_high_cloud INTEGER,
    ADD COLUMN far_solar_low_cloud_dark INTEGER,
    ADD COLUMN far_solar_mid_cloud_dark INTEGER,
    ADD COLUMN far_solar_high_cloud_dark INTEGER;

ALTER TABLE cloud_verification
    ADD COLUMN far_mid_cloud INTEGER,
    ADD COLUMN far_high_cloud INTEGER,
    ADD COLUMN far_low_cloud_dark INTEGER,
    ADD COLUMN far_mid_cloud_dark INTEGER,
    ADD COLUMN far_high_cloud_dark INTEGER;
