-- V134: WOODLAND becomes a location type, and enclosed woods return to the briefing year-round —
-- but judged by woodland rules rather than sky rules.
--
-- THIS PARTLY REVERSES V132, DELIBERATELY. V132's reasoning was:
--
--     "an enclosed wood has no horizon, so outside the bloom there is nothing for a sunrise
--      forecast to be about"
--
-- The first half is right and stands: there is no horizon, so there is nothing for a *sky*
-- forecast to be about. The second half was too broad. A wood has plenty to forecast — mist,
-- diffuse light, stillness, wet ground — it simply is not the thing BriefingVerdictEvaluator
-- measures. V132 removed woods from the sky product, which was correct. This adds them back to a
-- different product.
--
-- Concretely, four sky rules fire on exactly the morning a woodland photographer wants:
--   low cloud > 80  -> STANDDOWN "Heavy cloud"      (even light, no blown highlights)
--   mid cloud >= 80 -> STANDDOWN "Overcast"         (the softbox you wanted)
--   visibility < 5km-> STANDDOWN "Poor visibility"  (mist, the reason to be there)
--   humidity > 90   -> flag "Mist risk"             (hoped for, not risked)
-- So this is not a threshold disagreement, it is an inverted polarity, and it gets its own
-- evaluator rather than a branch inside the class that defines sky polarity.
--
-- SEEDED FROM bluebell_exposure, which is an already-curated, twice-reviewed split: V84 seeded it
-- and V109 corrected Roseberry Topping. The 15 WOODLAND rows are exactly the enclosed sites. The 3
-- OPEN_FELL rows (Roseberry Topping, Loughrigg Terrace, Rannerdale Knotts) are NOT woods and are
-- deliberately excluded — they keep their LANDSCAPE typing from V132 and stay on the sky product.
--
-- bluebell_exposure is retained for now as the seed source and the OPEN_FELL marker. Retiring it
-- (WOODLAND becomes derivable from location_type) is a later change, after a full season.
--
-- Note the two types are orthogonal and both are kept: BLUEBELL is a seasonal subject, WOODLAND is
-- a structural fact about the site. An enclosed bluebell wood is both.

INSERT INTO location_location_type (location_id, location_type)
SELECT id, 'WOODLAND'
FROM locations
WHERE bluebell_exposure = 'WOODLAND'
  AND NOT EXISTS (
      SELECT 1 FROM location_location_type existing
      WHERE existing.location_id = locations.id
        AND existing.location_type = 'WOODLAND'
  );
