-- V169: Ten more northern bluebell woods — the rest of the spring 2026 list V84 started.
--
-- V84 (2026-04-14) seeded a hand-picked first sixteen and the follow-up never happened. This adds
-- every site from the owner's list (Woodland Trust / National Trust / wildlife-trust picks for the
-- North East, Yorkshire and Cumbria) that the roster still lacked, as of a production read on
-- 2026-10-08 showing 19 BLUEBELL locations and none of these.
--
-- LEFT OUT, by owner decision (2026-10-08): the four Lancashire / Greater Manchester sites (Aughton
-- Woods, Brockholes, Warton Crag, Styal Woods). No region covers that part of the country and Styal
-- is far south of everything else on the roster; adding a region for four seasonal woods was not
-- wanted.
--
-- ---------------------------------------------------------------------------------------------
-- THE MODEL — every row here is an enclosed wood, typed exactly as V132/V134 left V84's woods:
-- ---------------------------------------------------------------------------------------------
--   * location_type BLUEBELL + WOODLAND, and NOT LANDSCAPE. None has an open horizon to forecast a
--     sky for (V132's rule); WOODLAND puts them on the woodland product year-round (V134), and
--     BLUEBELL adds the seasonal bluebell lane.
--   * bluebell_exposure = 'WOODLAND'. ForecastTaskCollector keys the bluebell-wood lane on this
--     column, and a BLUEBELL site with it NULL is logged and routed down the woodland lane anyway —
--     so it is set explicitly rather than left to that fallback.
--   * SUNRISE and SUNSET, as every V84 wood carries.
--
-- NEWTON WOOD is a separate row from Roseberry Topping, by owner decision. Roseberry (V109) is the
-- OPEN_FELL hilltop scored for both sky and bluebells; Newton Wood is the enclosed wood at its
-- foot, and the woodland lane asks a different question of it (mist, soft light, stillness).
--
-- JESMOND DENE is likewise a separate row from V50's 'Jesmond Dene Waterfall'. Folding BLUEBELL
-- into the waterfall row would be wrong twice over: an exposure of WOODLAND would pull the
-- waterfall's sky evaluation for the whole bloom (the gate V143's header describes), and
-- OPEN_FELL would score enclosed flowers with open-fell weighting. Two rows here answer two
-- different questions — sky colour at the falls, and the wood under the canopy — so they do not
-- duplicate each other the way two sky rows in one cell would.
--
-- ---------------------------------------------------------------------------------------------
-- COORDINATES. Verified, except where marked.
-- ---------------------------------------------------------------------------------------------
-- Eight come from the OpenStreetMap wood or park polygon of that name (Nominatim, 2026-10-08).
-- Irthing Gorge has no OSM polygon of that name; it is the SSSI grid reference NY 635 685
-- converted with the same Airy 1830 inverse transverse Mercator + OSGB36->WGS84 Helmert that V143
-- used (the converter reproduces V143's Penshaw pair exactly). Washingwell Wood is an ESTIMATE:
-- it has no named polygon either, so it sits between Watergate Forest Park (whose far end the wood
-- is reached through) and Washingwell Lane — well inside the same ~2 km Open-Meteo cell either
-- way. Recorded to 4 dp, the precision the grid-cell key truncates to.
--
-- bortle_class values are ESTIMATES in the manner of V84, V138 and V143 — refine via
-- lightpollutionmap.info. elevation_m is left NULL as V84 left it: InversionScoreCalculator's
-- 200 m gate and overlooks_water = FALSE make it inert for every row here.
--
-- grid_lat / grid_lng are left NULL; V146's nightly grid_cell_backfill fills them (or trigger
-- POST /api/admin/scheduler/jobs/grid_cell_backfill/trigger after deploying). Home drive times are
-- picked up by drive_time_refresh's "roster grew since" rule, and region-base drive times by the
-- nightly region_drive_time_refresh, so nothing needs a manual pass.
--
-- REGIONS are resolved by name with the same fallbacks V138/V143 use, because V137 renamed two of
-- them and the name a given database holds depends on which steps it received.

-- ---------------------------------------------------------------------------------------------
-- 0. The rows, once. A temp table so the pre-flight, the insert, the adoption pass and the
--    post-flight all read one list rather than four copies that can drift. Dropped explicitly at
--    the end rather than ON COMMIT DROP, which would empty them at once if this ever ran outside
--    a transaction.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE v169_woods (
    name        VARCHAR(255) NOT NULL,
    lat         DOUBLE PRECISION NOT NULL,
    lon         DOUBLE PRECISION NOT NULL,
    region_key  VARCHAR(16) NOT NULL,
    bortle      INTEGER NOT NULL
);

INSERT INTO v169_woods (name, lat, lon, region_key, bortle) VALUES
    -- North York Moors & Coast
    ('Newton Wood, Roseberry Topping',  54.5030, -1.1134, 'NYM',   4),  -- OSM wood
    -- The Yorkshire Dales (where V84 put Hackfall and Middleton)
    ('Nidd Gorge',                      54.0169, -1.4982, 'DALES', 5),  -- OSM forestry
    -- Northumberland & Tyneside
    ('Letah Wood',                      54.9388, -2.0959, 'NE',    3),  -- OSM wood
    ('Irthing Gorge',                   55.0097, -2.5723, 'NE',    2),  -- NY 635 685 (SSSI)
    ('Jesmond Dene Woods',              55.0039, -1.6025, 'NE',    7),  -- OSM wood
    ('Denton Dene',                     54.9752, -1.6952, 'NE',    7),  -- OSM park
    ('Ragpath Wood',                    54.7711, -1.6929, 'NE',    4),  -- OSM wood
    ('Lands Wood, Winlaton Mill',       54.9451, -1.7168, 'NE',    5),  -- OSM wood
    ('Washingwell Wood',                54.9375, -1.6580, 'NE',    6),  -- ESTIMATE, see header
    -- The Lake District
    ('Dorothy Farrer''s Spring Wood',   54.3781, -2.7981, 'LAKES', 4);  -- OSM wood

CREATE TEMP TABLE v169_region_names (
    region_key  VARCHAR(16) NOT NULL,
    region_name VARCHAR(255) NOT NULL
);

INSERT INTO v169_region_names (region_key, region_name) VALUES
    ('NE',    'Northumberland & Tyneside'),
    ('NE',    'Northumberland'),
    ('NE',    'North East England'),
    ('NYM',   'North York Moors & Coast'),
    ('NYM',   'The North Yorkshire Coast'),
    ('DALES', 'The Yorkshire Dales'),
    ('LAKES', 'The Lake District');

-- ---------------------------------------------------------------------------------------------
-- 1. Pre-flight: every region key must resolve to exactly one region, or no row is inserted with a
--    NULL region.
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE k RECORD; found INT;
BEGIN
    FOR k IN SELECT DISTINCT region_key FROM v169_region_names LOOP
        SELECT COUNT(*) INTO found FROM regions r
        JOIN v169_region_names n ON n.region_name = r.name
        WHERE n.region_key = k.region_key;
        IF found <> 1 THEN
            RAISE EXCEPTION 'V169: expected exactly 1 region for %, found %', k.region_key, found;
        END IF;
    END LOOP;
END $$;

CREATE TEMP TABLE v169_region_ids AS
SELECT n.region_key, r.id AS region_id
FROM regions r JOIN v169_region_names n ON n.region_name = r.name;

-- ---------------------------------------------------------------------------------------------
-- 2. The locations. Guarded on name (locations.name is UNIQUE, V5): production locations are
--    admin-managed, so a wood someone has already added by hand is adopted, not duplicated, and
--    Flyway is not bricked behind a unique violation.
-- ---------------------------------------------------------------------------------------------
INSERT INTO locations (name, lat, lon, region_id, bortle_class, enabled, is_coastal_tidal,
                       overlooks_water, consecutive_failures, bluebell_exposure, created_at)
SELECT w.name, w.lat, w.lon, ri.region_id, w.bortle, TRUE, FALSE, FALSE, 0, 'WOODLAND', NOW()
FROM v169_woods w
JOIN v169_region_ids ri ON ri.region_key = w.region_key
WHERE NOT EXISTS (SELECT 1 FROM locations l WHERE l.name = w.name);

-- 2b. Adoption path: fill only what is ABSENT on a pre-existing row. Never overwrite a value an
--     admin chose (V143 section 1b has the reasoning).
UPDATE locations l
SET bluebell_exposure = COALESCE(l.bluebell_exposure, 'WOODLAND'),
    region_id         = COALESCE(l.region_id, ri.region_id)
FROM v169_woods w
JOIN v169_region_ids ri ON ri.region_key = w.region_key
WHERE l.name = w.name
  AND (l.bluebell_exposure IS NULL OR l.region_id IS NULL);

-- ---------------------------------------------------------------------------------------------
-- 3. Types: BLUEBELL (the seasonal subject) and WOODLAND (the structural fact). Additive only — an
--    adopted row keeps whatever else it carries.
-- ---------------------------------------------------------------------------------------------
INSERT INTO location_location_type (location_id, location_type)
SELECT l.id, t.loc_type
FROM locations l
JOIN v169_woods w ON w.name = l.name
CROSS JOIN (VALUES ('BLUEBELL'), ('WOODLAND')) AS t(loc_type)
WHERE NOT EXISTS (
    SELECT 1 FROM location_location_type existing
    WHERE existing.location_id = l.id AND existing.location_type = t.loc_type);

-- ---------------------------------------------------------------------------------------------
-- 4. Solar events: both, as every V84 wood.
-- ---------------------------------------------------------------------------------------------
INSERT INTO location_solar_event_type (location_id, solar_event_type)
SELECT l.id, e.solar_event
FROM locations l
JOIN v169_woods w ON w.name = l.name
CROSS JOIN (VALUES ('SUNRISE'), ('SUNSET')) AS e(solar_event)
WHERE NOT EXISTS (
    SELECT 1 FROM location_solar_event_type existing
    WHERE existing.location_id = l.id AND existing.solar_event_type = e.solar_event);

-- ---------------------------------------------------------------------------------------------
-- 5. Post-flight, scoped to these rows only (V138 section 5): each has a region and an exposure
--    (not necessarily this file's — an adopted row's own value stands), both types, both events.
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE expected INT; ok INT;
BEGIN
    SELECT COUNT(*) INTO expected FROM v169_woods;

    SELECT COUNT(*) INTO ok FROM locations l JOIN v169_woods w ON w.name = l.name
    WHERE l.region_id IS NOT NULL AND l.bluebell_exposure IS NOT NULL;
    IF ok <> expected THEN
        RAISE EXCEPTION 'V169: expected % woods with a region and an exposure, found %', expected, ok;
    END IF;

    SELECT COUNT(*) INTO ok FROM location_location_type t
    JOIN locations l ON l.id = t.location_id JOIN v169_woods w ON w.name = l.name
    WHERE t.location_type IN ('BLUEBELL', 'WOODLAND');
    IF ok <> expected * 2 THEN
        RAISE EXCEPTION 'V169: expected % BLUEBELL/WOODLAND type rows, found %', expected * 2, ok;
    END IF;

    SELECT COUNT(*) INTO ok FROM location_solar_event_type e
    JOIN locations l ON l.id = e.location_id JOIN v169_woods w ON w.name = l.name
    WHERE e.solar_event_type IN ('SUNRISE', 'SUNSET');
    IF ok <> expected * 2 THEN
        RAISE EXCEPTION 'V169: expected % SUNRISE/SUNSET rows, found %', expected * 2, ok;
    END IF;
END $$;

DROP TABLE v169_region_ids;
DROP TABLE v169_region_names;
DROP TABLE v169_woods;
