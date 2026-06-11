-- V109: Reclassify Roseberry Topping WOODLAND -> OPEN_FELL.
--
-- V84 seeded Roseberry Topping (id 87) as WOODLAND, but the bluebell
-- displays there carpet the open hillside below the summit, not a closed
-- canopy — OPEN_FELL is the exposure that matches how it photographs
-- (golden-hour light raking across the slope, calm wind for sharp stems).
--
-- Effects, both intended and both dormant out of season:
--   * BluebellConditionService switches Roseberry to the OPEN_FELL
--     weighting (calm +3, golden-hour light +2) instead of the woodland
--     soft-light weighting.
--   * The UI exposure label changes from "Woodland" to "Open fell".
--
-- The exposure split becomes 15 WOODLAND / 3 OPEN_FELL.
--
-- Both id AND name in the WHERE clause: if id 87 is not Roseberry Topping
-- on the target database, this updates zero rows rather than silently
-- reclassifying the wrong location.

UPDATE locations
SET bluebell_exposure = 'OPEN_FELL'
WHERE id = 87
  AND name = 'Roseberry Topping';
