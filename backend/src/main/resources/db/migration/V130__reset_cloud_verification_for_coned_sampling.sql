-- Reset cloud_verification: the horizon sample changed meaning.
--
-- V129 recorded the horizon layers from a SINGLE point at the centre solar bearing. The forecast
-- value those rows are compared against (forecast_evaluation.solar_low_cloud) is a THREE-POINT
-- CONE AVERAGE, so the comparison charged a sampling difference to forecast error.
--
-- Verification now averages the same three cone bearings the forecast used. Old and new rows are
-- therefore not comparable, and mixing them would put a systematic artefact into exactly the
-- statistic the harness exists to produce.
--
-- Safe to discard: cloud_verification is derived data, rebuilt by
-- POST /api/admin/cloud-verification/backfill. Nothing references it, and the source evaluations
-- are untouched. Candidate selection is an anti-join, so clearing the table simply returns every
-- evaluation to the pool.
--
-- This also clears the ~13.5% of rows written blank during a rate-limited run before the archive
-- error envelope was detected.

DELETE FROM cloud_verification;
