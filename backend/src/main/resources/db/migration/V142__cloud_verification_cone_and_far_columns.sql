-- Measurement pass for the directional sampling geometry (the cone aggregation and the
-- canvas-height gate questions). Three new analysed readings per verification:
--
--   horizon_low_min / horizon_low_max — the extremes of ERA5 low cloud across the three solar-cone
--   bearings. The forecast persists only the cone MEAN, which cannot distinguish a uniform 60%
--   deck from a solid wall with a clear third (90/0/90). The spread (max - min) measures how often
--   the horizon really is a wall-with-gap, which is the case the mean hides.
--
--   far_low_cloud — ERA5 low cloud at the 226 km far-solar point. The 113 km gate assumes a
--   mid-level canvas; a ray underlighting an 8 km cirrus canvas grazes low cloud 206-432 km out,
--   so near-vs-far divergence measures how often the current single-distance gate looks at the
--   wrong part of the corridor.
--
-- Nullable like every other observed column: a row records the attempt even when the archive had
-- no data. Rows verified before this migration lack all three and are deliberately re-verified
-- (see CloudVerificationRepository.deleteIncompleteVerifications).
ALTER TABLE cloud_verification ADD COLUMN horizon_low_min INTEGER;
ALTER TABLE cloud_verification ADD COLUMN horizon_low_max INTEGER;
ALTER TABLE cloud_verification ADD COLUMN far_low_cloud INTEGER;
