-- V143: Penshaw Monument joins the roster — a year-round sky viewpoint that is also a bluebell site.
--
-- ONE ROW, NOT TWO. The monument stands on the summit of Penshaw Hill; the bluebells carpet
-- Penshaw Wood at the foot of it, a few hundred metres west and downhill. That is two subjects and
-- one place to check the weather for, which is exactly how Roseberry Topping and Allen Banks are
-- already modelled: one location carrying both LANDSCAPE and BLUEBELL. Splitting it would put two
-- rows in the same Open-Meteo grid cell, double the evaluation cost, and give the Plan tab two
-- entries that can only ever agree.
--
-- COORDINATES ARE VERIFIED, unlike V138's. Wikidata gives NZ 33403 54376 and 54°52'59.2"N
-- 1°28'51.2"W for the monument; converting the grid reference through the Airy 1830 inverse
-- transverse Mercator and the OSGB36->WGS84 Helmert lands on 54.88302, -1.48088, agreeing with the
-- stated decimal pair to about ten metres. Recorded to 4 dp, which is the precision the grid-cell
-- key already truncates to.
--
-- ---------------------------------------------------------------------------------------------
-- THE ONE REAL DECISION HERE: bluebell_exposure = OPEN_FELL, and it is NOT a description of the
-- wood.
-- ---------------------------------------------------------------------------------------------
-- Penshaw Wood is genuinely closed canopy — carpets of bluebells with wild garlic and wood
-- anemone under trees — so as a description of the flowers, WOODLAND is the truer word, and
-- BluebellConditionService's woodland weighting (soft overcast, mist, wind less critical) is the
-- weighting those flowers actually want.
--
-- WOODLAND is nevertheless wrong here, because this column is not only a scoring weight. It is the
-- switch ForecastTaskCollector reads to decide whether the location gets a SKY task at all in
-- season:
--
--     boolean bluebellWoodInSeason = bluebellScored
--             && candidate.location().getBluebellExposure() == BluebellExposure.WOODLAND;
--     ...
--     if (bluebellWoodInSeason) { bluebell.add(...); continue; }   // no sky task, no colour bucket
--
-- Note what that gate does NOT test: isWoodlandOnly(). It is keyed on the exposure column alone,
-- so it fires on a LANDSCAPE hilltop just as readily as on an enclosed gorge. Setting WOODLAND
-- here would therefore take Penshaw Monument out of the sunrise/sunset product for the whole of
-- the configured bluebell window — the back half of April and most of May — and it would do it
-- silently, because there is no error, just an absence: the monument would stop appearing in
-- evening briefings during the best six weeks of the spring for standing on it.
--
-- OPEN_FELL is the value that means "score this place for BOTH", and its effects are the ones
-- wanted:
--   * ForecastTaskCollector pairs a bluebell task alongside the sky task (the openFellPaired arm),
--     so the hill keeps its sunrise and sunset evaluation right through the bloom.
--   * BriefingEvaluationService.recombineBluebell averages the two ratings onto the sky narrative,
--     so the card still reads as a sky forecast with the bluebell display folded into the stars.
-- The cost, stated plainly rather than hidden: through those weeks the bluebell half of Penshaw's
-- rating is scored with the open-fell weighting (calm wind critical, golden-hour light rewarded)
-- when the flowers are in fact under a canopy that shelters them and diffuses that light. That is
-- a wrong emphasis on half of one seasonal score. Losing the sunsets is a wrong answer to the
-- question the location exists to answer, all season. If the exposure column is ever split into
-- "how the flowers sit" and "does this site also have a horizon", this row is the first to revisit.
--
-- No WOODLAND location_type either. It would be inert today — isWoodlandOnly() requires the
-- absence of LANDSCAPE, which this row has — but it is a structural claim about the viewpoint, and
-- the viewpoint is a bare hilltop with a 360-degree horizon.
--
-- ---------------------------------------------------------------------------------------------
-- The remaining metadata, and why each is what it is.
-- ---------------------------------------------------------------------------------------------
--   elevation_m = 136   Published summit height (Wikidata, Wikipedia; PeakVisor says 137). Recorded
--                       as a fact, not as a trigger: InversionScoreCalculator.MIN_ELEVATION_METRES
--                       is 200, so ForecastDataAugmentor.augmentWithInversionScore returns early
--                       for this row whatever overlooks_water says. A Wear-valley inversion seen
--                       from up there is real, but this schema cannot express it and a fabricated
--                       height would be the wrong way to buy it.
--   overlooks_water     FALSE. The hill looks down on the Wear, but V86's convention is
--                       line-of-sight to a lake, tarn, reservoir or sea, and a river in a valley
--                       is not that. Inert below the 200 m gate in any case.
--   bortle_class = 6    An ESTIMATE, in the manner of V84 and V138 — refine via
--                       lightpollutionmap.info. Penshaw sits inside the Sunderland / Washington /
--                       Houghton triangle with the A19 at its foot, so it belongs with Roker Pier
--                       (6) rather than the Durham city fringe (Houghall Woods, 5).
--   is_coastal_tidal    FALSE, and no location_tide_type rows. Six miles inland.
--
-- ⚠️ grid_lat / grid_lng are left NULL, as they are for every location inserted by raw SQL —
-- V138's ten included. They are populated only by the enrichment call behind the admin Add-location
-- form, so this row is skipped by GridCellStabilityService and falls back to its own key in
-- BriefingService rather than being deduplicated against neighbours sharing its Open-Meteo cell.
-- Nothing breaks; the cell simply is not shared. To close it after deploying, re-save the location
-- through the admin UI, which runs the enrichment.

-- ---------------------------------------------------------------------------------------------
-- 0. Pre-flight. Same three-name resolution as V138: V137 folded 'Tyne and Wear' into
--    'Northumberland' and then renamed it, so the destination's name depends on which of those
--    steps a given database received. Assert exactly one match rather than inserting a NULL region.
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE found INT;
BEGIN
    SELECT COUNT(*) INTO found FROM regions
    WHERE name IN ('Northumberland & Tyneside', 'Northumberland', 'North East England');
    IF found <> 1 THEN
        RAISE EXCEPTION 'V143: expected exactly 1 destination region, found %.', found;
    END IF;
END $$;

-- ---------------------------------------------------------------------------------------------
-- 1. The location. Guarded on name because locations.name is UNIQUE (V5) and production locations
--    are admin-managed — if someone has already added it by hand, this migration must adopt that
--    row rather than abort Flyway and brick every restart behind it.
-- ---------------------------------------------------------------------------------------------
INSERT INTO locations (name, lat, lon, region_id, bortle_class, elevation_m, enabled,
                       is_coastal_tidal, overlooks_water, consecutive_failures,
                       bluebell_exposure, created_at)
SELECT 'Penshaw Monument', 54.8830, -1.4809,
       (SELECT id FROM regions
        WHERE name IN ('Northumberland & Tyneside', 'Northumberland', 'North East England')),
       6, 136, TRUE, FALSE, FALSE, 0, 'OPEN_FELL', NOW()
WHERE NOT EXISTS (SELECT 1 FROM locations WHERE name = 'Penshaw Monument');

-- 1b. Adoption path. If the row was already there, section 1 inserted nothing, so fill in only the
--     fields that are ABSENT. Never overwrite a value someone chose: an admin who deliberately set
--     this site's exposure to WOODLAND has made the call this file's header argues against, and a
--     migration is the wrong place to overrule them silently. The one field that must not stay
--     NULL is bluebell_exposure — ForecastTaskCollector logs a BATCH DIAG warning and routes a
--     BLUEBELL site with no exposure down the woodland lane in the middle of May.
UPDATE locations
SET bluebell_exposure = COALESCE(bluebell_exposure, 'OPEN_FELL'),
    elevation_m       = COALESCE(elevation_m, 136),
    region_id         = COALESCE(region_id,
                            (SELECT id FROM regions WHERE name IN
                                ('Northumberland & Tyneside', 'Northumberland',
                                 'North East England')))
WHERE name = 'Penshaw Monument'
  AND (bluebell_exposure IS NULL OR elevation_m IS NULL OR region_id IS NULL);

-- ---------------------------------------------------------------------------------------------
-- 2. Types: LANDSCAPE (the hilltop, year-round) and BLUEBELL (the wood, in season).
-- ---------------------------------------------------------------------------------------------
INSERT INTO location_location_type (location_id, location_type)
SELECT l.id, t.loc_type
FROM locations l
CROSS JOIN (VALUES ('LANDSCAPE'), ('BLUEBELL')) AS t(loc_type)
WHERE l.name = 'Penshaw Monument'
  AND NOT EXISTS (
      SELECT 1 FROM location_location_type existing
      WHERE existing.location_id = l.id AND existing.location_type = t.loc_type);

-- ---------------------------------------------------------------------------------------------
-- 3. Solar events: both. The evening is the celebrated one — the columns silhouette against a
--    western sky and the monument is floodlit after dark — but the summit is open on every side
--    with a clear eastern horizon over the coast, so the sunrise is a real forecast and not a
--    doubled cost for a sun rising behind the subject (V138's reason for withholding SUNSET from
--    the Heritage Coast).
-- ---------------------------------------------------------------------------------------------
INSERT INTO location_solar_event_type (location_id, solar_event_type)
SELECT l.id, e.solar_event
FROM locations l
CROSS JOIN (VALUES ('SUNRISE'), ('SUNSET')) AS e(solar_event)
WHERE l.name = 'Penshaw Monument'
  AND NOT EXISTS (
      SELECT 1 FROM location_solar_event_type existing
      WHERE existing.location_id = l.id AND existing.solar_event_type = e.solar_event);

-- ---------------------------------------------------------------------------------------------
-- 4. Post-flight. Scoped to this row only — asserting anything about locations at large would let
--    unrelated pre-existing data abort the migration (V138 section 5 has the full reasoning).
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE types INT; events INT; regioned INT;
BEGIN
    SELECT COUNT(*) INTO regioned FROM locations
    -- NOT "exposure = 'OPEN_FELL'". Section 1b deliberately leaves an existing deliberate value
    -- alone, so asserting this file's preferred value would abort Flyway on exactly the database
    -- section 1's guard exists to tolerate. What must hold is that the column is SET at all.
    WHERE name = 'Penshaw Monument' AND region_id IS NOT NULL AND bluebell_exposure IS NOT NULL;
    IF regioned <> 1 THEN
        RAISE EXCEPTION 'V143: expected 1 Penshaw Monument with a region and an exposure, found %',
            regioned;
    END IF;

    SELECT COUNT(*) INTO types FROM location_location_type t
    JOIN locations l ON l.id = t.location_id
    WHERE l.name = 'Penshaw Monument' AND t.location_type IN ('LANDSCAPE', 'BLUEBELL');
    IF types <> 2 THEN
        RAISE EXCEPTION 'V143: expected LANDSCAPE and BLUEBELL on Penshaw Monument, found %', types;
    END IF;

    SELECT COUNT(*) INTO events FROM location_solar_event_type e
    JOIN locations l ON l.id = e.location_id
    WHERE l.name = 'Penshaw Monument' AND e.solar_event_type IN ('SUNRISE', 'SUNSET');
    IF events <> 2 THEN
        RAISE EXCEPTION 'V143: expected SUNRISE and SUNSET on Penshaw Monument, found %', events;
    END IF;
END $$;
