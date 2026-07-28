-- V132: Correct the location typing V84 applied wholesale, and drop the flag that was added
-- to work around it.
--
-- WHAT WENT WRONG
-- V84 introduced 16 new bluebell sites and typed them with a single blanket statement:
--
--     INSERT INTO location_location_type (location_id, location_type)
--     SELECT id, 'LANDSCAPE' FROM locations
--     WHERE bluebell_exposure IS NOT NULL AND id NOT IN (40, 87);
--
-- with the comment "Each gets both LANDSCAPE and BLUEBELL". No per-site judgement was made.
-- BriefingService.isColourLocation admits anything that is not WILDLIFE, so every one of those
-- woods became a year-round sunrise/sunset candidate — which is why an enclosed wood like
-- Houghall was being briefed in July, when there is neither a bluebell nor a horizon.
--
-- V111 then added bluebell_evaluate_year_round to subtract what V84 had over-added, and said so:
-- "OQ4 from the Pass 3 bluebell extraction ... The collector gate reads this flag once the
-- bluebell mini-batch lands". That gate was never built, so the column has never had a reader.
-- Rather than finally build a gate on a flag that exists to undo a mis-typing, this migration
-- fixes the typing and removes the flag.
--
-- THE MODEL AFTERWARDS — three concepts, each with one home:
--   * what a location IS          -> location_type   (LANDSCAPE means a year-round colour spot)
--   * WHEN bluebells matter       -> SeasonalWindow  (config: photocast.season.bluebell.start/end)
--   * whether a wood is worth an  -> no longer a separate flag; it is exactly "does it also
--     out-of-season trip                              carry LANDSCAPE"
--
-- WHICH SITES KEEP LANDSCAPE, AND WHY
-- Retained for five sites whose photographic draw is a genuine open sightline, with bluebells
-- incidental. Three are corroborated by this schema's own independent classification: V84 seeded
-- bluebell_exposure = 'OPEN_FELL' for Rannerdale Knotts and Loughrigg Terrace, and V109
-- reclassified Roseberry Topping. The other two are lakeshore vantages (Brandelhow sits on Low
-- Brandlehow jetty; Low Wood is the south-west shore of Wast Water).
--
-- Allen Banks (id 40) and Roseberry Topping (id 87) are deliberately untouched below: V84 skipped
-- them because they were LANDSCAPE locations before bluebell support existed, so their typing was
-- a considered judgement rather than a blanket insert.
--
-- Removing LANDSCAPE does NOT remove these sites from bluebell scoring or the bluebell hot topic:
-- LocationRepository selects them by LocationType.BLUEBELL, not by LANDSCAPE.

-- 1. Remove the blanket LANDSCAPE typing from woods with no open horizon.
DELETE FROM location_location_type
WHERE location_type = 'LANDSCAPE'
  AND location_id IN (
      SELECT id FROM locations WHERE name IN (
          'Hackfall Wood',              -- wooded gorge; the Mowbray Point panorama has grown in
          'Hardcastle Crags',           -- closed-canopy valley floor; mill and waterfall subjects
          'Muncaster Castle Gardens',   -- ticketed 10:30-17:00; never contains a golden hour
          'Hardwick Hall Country Park', -- gated designed estate; daytime amenity park
          'Skelghyll Woods',            -- Jenkin Crag is a framed gap, not an open aspect
          'Holme Wood, Loweswater',     -- enclosed NT wood; Loweswater is photographed elsewhere
          'White Moss Common',          -- no vista on site; the shoreline is Rydal Water's
          'Middleton Woods',            -- enclosed ancient woodland; Ilkley's sky spot is the Cow and Calf
          'Houghall Woods',             -- sheltered city-fringe woodland, no open horizon
          'Plessey Woods',              -- enclosed river valley; the draw is wildlife
          'Bluebell Wood, Morpeth',     -- named for the bloom and described by nothing else
          'Doctors Wood, Grosmont'      -- enclosed valley-side wood; the vistas are elsewhere
      )
  );

-- 2. Drop the workaround. Never read by any code: V111 shipped the column for a gate that was
--    never written, so this removes a concept rather than a behaviour.
ALTER TABLE locations DROP COLUMN bluebell_evaluate_year_round;
