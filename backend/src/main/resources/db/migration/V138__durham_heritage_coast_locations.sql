-- V138: the Durham Heritage Coast enters the roster.
--
-- ⚠️  COORDINATES ARE UNVERIFIED CANDIDATES. Every lat/lon below is an estimate of the shooting
--     position (car park or clifftop viewpoint), not a surveyed value. Check them on a map before
--     applying — a wrong coordinate does not fail loudly, it silently briefs the wrong grid cell.
--     A GeoJSON of these ten points ships alongside this migration for exactly that check.
--
-- WHY. The roster runs down the coast to Marsden Rock, Trow Rocks and Souter Lighthouse, then
-- stops until Redcar and South Gare — about thirty miles of unrepresented shoreline through
-- Sunderland, Seaham, Easington, Blackhall and Hartlepool. It is east-facing, so it is sunrise
-- coast, and the app currently cannot brief on any of it.
--
-- This is a coverage fix, not a statistical one. It does NOT make a standalone County Durham
-- region viable, and it is worth writing down why so the question is not reopened: nine of these
-- ten are County Durham (Roker is Sunderland), which with Wharton Park would give a Durham region
-- a voting roster of 10. rollUpVerdict degenerates at five or fewer VIABLE slots, so a 10-location
-- region is carried by one GO the moment half of it stands down. Better than Teesdale's four, but
-- still the smallest region by a wide margin and still inside the failure mode V137 removed. The
-- coast from Souter to Seaham is six miles of continuous shoreline with no weather boundary in it,
-- so these join the region that already owns the coast to the north.
--
-- Storm-surge parameters follow the V60 convention and interpolate between its neighbours rather
-- than aiming at absolute accuracy: the Tyne and Wear entries directly north (Trow, Marsden,
-- Souter) use bearing 90 / fetch 240 km / shelf 27-28 m, and Redcar to the south sits at 400 km /
-- 30 m only because it faces north into Tees Bay. Consistency with the adjacent calibrated
-- stretch matters more here than a better guess in isolation.
--
-- bortle_class values are estimates in the manner of V84 — refine via lightpollutionmap.info.
-- elevation_m is deliberately left NULL rather than guessed; InversionScoreCalculator reads it,
-- and a fabricated clifftop height is worse than a known absence.
--
-- ⚠️ TRIGGER THE TIDE REFRESH IMMEDIATELY AFTER DEPLOYING THIS. It is not a nicety.
-- These rows arrive by raw SQL, which bypasses the eager fetch in LocationService.addLocation
-- ("if (isCoastal(saved) && !tideService.hasStoredExtremes(...))"), so they carry tide PREFERENCES
-- with no tide_extreme DATA until the weekly WorldTides job runs (Mondays 02:00 UTC by default).
-- Nothing BREAKS in that window; the evaluation is just worse than it looks. The briefing guards
-- its coastal demotion on "tideState() != null" (BriefingSlotBuilder), and the batch pipeline is
-- self-consistent: ForecastTaskCollector buckets on "atmosphericData().tide() != null" and
-- BatchRequestFactory.selectBuilder picks the prompt on the SAME predicate, so the batch stays
-- homogeneous and each task gets a prompt matching the data it carries. The cost is that all ten
-- are evaluated by the INLAND prompt — no tide, no surge — for up to six days, which for a
-- seascape roster is the whole reason they were added.
--     POST /api/admin/scheduler/jobs/tide_refresh/trigger

-- ---------------------------------------------------------------------------------------------
-- 0. Pre-flight.
-- ---------------------------------------------------------------------------------------------
-- The destination region's name depends on whether V137's section 5 rename was kept, so resolve
-- across all three possibilities and assert exactly one exists. Without this, a missed rename
-- would insert ten locations with region_id NULL.
DO $$
DECLARE found INT;
BEGIN
    SELECT COUNT(*) INTO found FROM regions
    WHERE name IN ('Northumberland & Tyneside', 'Northumberland', 'North East England');
    IF found <> 1 THEN
        RAISE EXCEPTION 'V138: expected exactly 1 destination region, found %.', found;
    END IF;
END $$;

-- ---------------------------------------------------------------------------------------------
-- 1. The locations, north to south.
-- ---------------------------------------------------------------------------------------------
INSERT INTO locations (name, lat, lon, region_id, bortle_class, enabled, is_coastal_tidal,
                       overlooks_water, consecutive_failures, created_at,
                       shore_normal_bearing_degrees, effective_fetch_metres,
                       avg_shelf_depth_metres)
SELECT v.name, v.lat, v.lon,
       (SELECT id FROM regions
        WHERE name IN ('Northumberland & Tyneside', 'Northumberland', 'North East England')),
       v.bortle, TRUE, TRUE, v.overlooks, 0, NOW(), v.bearing, v.fetch, v.shelf
FROM (VALUES
    -- name                              lat        lon      bortle  overlooks  bearing  fetch   shelf
    ('Roker Pier & Lighthouse',        54.9210, -1.3620,  6,     FALSE,      90,  240000,  27),
    ('Seaham North Pier & Lighthouse', 54.8400, -1.3210,  5,     FALSE,      95,  245000,  27),
    ('Seaham Chemical Beach',          54.8290, -1.3290,  5,     FALSE,      95,  245000,  27),
    ('Nose''s Point & Blast Beach',    54.8180, -1.3230,  5,     TRUE,       95,  245000,  27),
    ('Hawthorn Hive',                  54.7940, -1.3180,  5,     TRUE,       95,  245000,  27),
    ('Shippersea Bay, Easington',      54.7830, -1.3070,  5,     TRUE,       95,  245000,  28),
    ('Horden Beach',                   54.7590, -1.2870,  5,     FALSE,     100,  245000,  28),
    ('Blackhall Rocks',                54.7400, -1.2660,  5,     TRUE,      100,  245000,  28),
    ('Crimdon Beach',                  54.7180, -1.2540,  5,     FALSE,     100,  250000,  28),
    -- Hartlepool Headland is the contested one: it is County Durham and north of the Tees, but it
    -- is also five miles across the estuary from the Tees-mouth cluster (South Gare, Redcar,
    -- Saltholme, Infinity Bridge) that sits in North York Moors & Coast. Deliberately kept with
    -- the Heritage Coast — it is the southern end of that shoreline and shares its aspect and
    -- weather, where the Tees cluster faces north into the bay.
    ('Hartlepool Headland',            54.6930, -1.1770,  6,     FALSE,     110,  250000,  28)
) AS v(name, lat, lon, bortle, overlooks, bearing, fetch, shelf);

-- ---------------------------------------------------------------------------------------------
-- 2. Types. SEASCAPE for all ten — they are the sea, not a landscape that happens to face it.
-- ---------------------------------------------------------------------------------------------
INSERT INTO location_location_type (location_id, location_type)
SELECT id, 'SEASCAPE' FROM locations WHERE name IN (
    'Roker Pier & Lighthouse', 'Seaham North Pier & Lighthouse', 'Seaham Chemical Beach',
    'Nose''s Point & Blast Beach', 'Hawthorn Hive', 'Shippersea Bay, Easington',
    'Horden Beach', 'Blackhall Rocks', 'Crimdon Beach', 'Hartlepool Headland');

-- ---------------------------------------------------------------------------------------------
-- 3. Solar events. East-facing coast, so SUNRISE — with one exception.
-- ---------------------------------------------------------------------------------------------
-- Deliberately not both by default. An empty set means "supports everything" per
-- supportsTargetType, and listing SUNSET everywhere would put ten locations into every evening
-- batch to photograph a sun setting behind them. Published guides for this coast are consistent:
-- Seaham (Blast Beach, Nose's Point, the harbour) and Blackhall Rocks are named for sunrise, and
-- the one Chemical Beach location guide that discusses timing gives sunrise only.
INSERT INTO location_solar_event_type (location_id, solar_event_type)
SELECT id, 'SUNRISE' FROM locations WHERE name IN (
    'Roker Pier & Lighthouse', 'Seaham North Pier & Lighthouse', 'Seaham Chemical Beach',
    'Nose''s Point & Blast Beach', 'Hawthorn Hive', 'Shippersea Bay, Easington',
    'Horden Beach', 'Blackhall Rocks', 'Crimdon Beach', 'Hartlepool Headland');

-- Hartlepool Headland is the exception: it is surrounded by the North Sea on three sides, so
-- unlike the rest of this shoreline it has a genuine westward aspect across the bay and earns the
-- doubled evaluation cost. Nowhere else here does.
INSERT INTO location_solar_event_type (location_id, solar_event_type)
SELECT id, 'SUNSET' FROM locations WHERE name = 'Hartlepool Headland';

-- ---------------------------------------------------------------------------------------------
-- 4. Tide preferences. Now sourced from location guides rather than inferred from site type.
-- ---------------------------------------------------------------------------------------------
-- LOW only, because the subject or the access does not survive a rising tide:
--   Chemical Beach — the chaldron wheels ARE the shot and they surface only at low water; one
--     published guide quotes a 1.2 m tide height, and the single entrance is cut off at high.
--   Shippersea Bay — reached from Hawthorn Hive around Beacon Point on a low tide. This is an
--     access constraint rather than a preference, which is why MID is not listed.
INSERT INTO location_tide_type (location_id, tide_type)
SELECT id, 'LOW' FROM locations
WHERE name IN ('Seaham Chemical Beach', 'Shippersea Bay, Easington');

-- LOW and MID, for the beaches and rock platforms where falling water exposes the foreground —
-- the stacks and tide pools at Blackhall, the exposed rock at Blast Beach, the stacks between
-- Hawthorn Hive and Seaham. Seaham North Pier joins them rather than the HIGH group below: the
-- compositional element every published frame uses is Featherbed Rocks in the foreground, and
-- that platform needs the tide off it.
INSERT INTO location_tide_type (location_id, tide_type)
SELECT id, t.tide FROM locations
CROSS JOIN (VALUES ('LOW'), ('MID')) AS t(tide)
WHERE name IN ('Seaham North Pier & Lighthouse', 'Nose''s Point & Blast Beach', 'Hawthorn Hive',
               'Horden Beach', 'Blackhall Rocks', 'Crimdon Beach');

-- HIGH, for the two structures photographed with water against them. Roker is the better
-- evidenced of the pair — guides note that a high tide lets you frame more of the pier — while
-- Hartlepool Headland is still an inference from site type, not a sourced claim.
INSERT INTO location_tide_type (location_id, tide_type)
SELECT id, 'HIGH' FROM locations WHERE name IN (
    'Roker Pier & Lighthouse', 'Hartlepool Headland');

-- ---------------------------------------------------------------------------------------------
-- 5. Post-flight. Scoped to the ten rows this migration inserts.
-- ---------------------------------------------------------------------------------------------
-- NOT "no location anywhere has a NULL region_id" — that was here until review caught it. A NULL
-- region is a legal, supported state (nullable since V32; LocationService.resolveRegion(null)
-- returns null; the admin Add-location form defaults the region select to "— None —"), so a
-- pre-existing region-less row this migration never touches would abort Flyway, fail the Spring
-- context, and brick every restart after it. Assert only what section 1 claims to have done.
DO $$
DECLARE landed INT;
BEGIN
    SELECT COUNT(*) INTO landed FROM locations
    WHERE region_id IS NOT NULL
      AND name IN ('Roker Pier & Lighthouse', 'Seaham North Pier & Lighthouse',
                   'Seaham Chemical Beach', 'Nose''s Point & Blast Beach', 'Hawthorn Hive',
                   'Shippersea Bay, Easington', 'Horden Beach', 'Blackhall Rocks',
                   'Crimdon Beach', 'Hartlepool Headland');
    IF landed <> 10 THEN
        RAISE EXCEPTION 'V138: expected 10 Heritage Coast locations with a region, found %', landed;
    END IF;
END $$;
