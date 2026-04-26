-- ============================================================
-- Bluebell seasonal support
-- ============================================================

-- 1. Add bluebell_exposure column to locations
ALTER TABLE locations ADD COLUMN bluebell_exposure VARCHAR(20);

-- ============================================================
-- 2. Update existing bluebell locations with exposure type
-- ============================================================

UPDATE locations SET bluebell_exposure = 'WOODLAND' WHERE id = 40;  -- Allen Banks
UPDATE locations SET bluebell_exposure = 'WOODLAND' WHERE id = 87;  -- Roseberry Topping

-- Add BLUEBELL type to existing locations
-- Conditional: skip cleanly if location is absent (e.g. fresh test database).
-- In production these locations exist (id=40 Allen Banks, id=87 Roseberry Topping).
INSERT INTO location_location_type (location_id, location_type)
SELECT 40, 'BLUEBELL'
WHERE EXISTS (SELECT 1 FROM locations WHERE id = 40)
  AND NOT EXISTS (
    SELECT 1 FROM location_location_type
    WHERE location_id = 40 AND location_type = 'BLUEBELL'
  );

INSERT INTO location_location_type (location_id, location_type)
SELECT 87, 'BLUEBELL'
WHERE EXISTS (SELECT 1 FROM locations WHERE id = 87)
  AND NOT EXISTS (
    SELECT 1 FROM location_location_type
    WHERE location_id = 87 AND location_type = 'BLUEBELL'
  );

-- ============================================================
-- 3. Insert new bluebell locations
--    Coordinates are approximate viewpoint/car park locations.
--    bortle_class values are estimates — refine via
--    lightpollutionmap.info QueryRaster API later.
--    All new locations: enabled = true, is_coastal_tidal = false
-- ============================================================

-- NORTHUMBERLAND (region_id = 5)
INSERT INTO locations (name, lat, lon, region_id, bortle_class, enabled, is_coastal_tidal,
    overlooks_water, consecutive_failures, bluebell_exposure, created_at)
VALUES ('Plessey Woods', 55.1283, -1.6197, 5, 4, true, false, false, 0, 'WOODLAND', NOW());

INSERT INTO locations (name, lat, lon, region_id, bortle_class, enabled, is_coastal_tidal,
    overlooks_water, consecutive_failures, bluebell_exposure, created_at)
VALUES ('Bluebell Wood, Morpeth', 55.1680, -1.6890, 5, 4, true, false, false, 0, 'WOODLAND', NOW());

-- TYNE AND WEAR (region_id = 1)
INSERT INTO locations (name, lat, lon, region_id, bortle_class, enabled, is_coastal_tidal,
    overlooks_water, consecutive_failures, bluebell_exposure, created_at)
VALUES ('Houghall Woods', 54.7560, -1.5630, 1, 5, true, false, false, 0, 'WOODLAND', NOW());

-- TEESDALE (region_id = 6)
INSERT INTO locations (name, lat, lon, region_id, bortle_class, enabled, is_coastal_tidal,
    overlooks_water, consecutive_failures, bluebell_exposure, created_at)
VALUES ('Hardwick Hall Country Park', 54.6890, -1.3120, 6, 5, true, false, false, 0, 'WOODLAND', NOW());

-- NORTH YORK MOORS (region_id = 7)
INSERT INTO locations (name, lat, lon, region_id, bortle_class, enabled, is_coastal_tidal,
    overlooks_water, consecutive_failures, bluebell_exposure, created_at)
VALUES ('Doctors Wood, Grosmont', 54.4380, -0.7250, 7, 4, true, false, false, 0, 'WOODLAND', NOW());

-- YORKSHIRE DALES (region_id = 4)
INSERT INTO locations (name, lat, lon, region_id, bortle_class, enabled, is_coastal_tidal,
    overlooks_water, consecutive_failures, bluebell_exposure, created_at)
VALUES ('Hardcastle Crags', 53.7560, -2.0170, 4, 4, true, false, false, 0, 'WOODLAND', NOW());

INSERT INTO locations (name, lat, lon, region_id, bortle_class, enabled, is_coastal_tidal,
    overlooks_water, consecutive_failures, bluebell_exposure, created_at)
VALUES ('Hackfall Wood', 54.2030, -1.6020, 4, 3, true, false, false, 0, 'WOODLAND', NOW());

INSERT INTO locations (name, lat, lon, region_id, bortle_class, enabled, is_coastal_tidal,
    overlooks_water, consecutive_failures, bluebell_exposure, created_at)
VALUES ('Middleton Woods', 53.8950, -1.8380, 4, 5, true, false, false, 0, 'WOODLAND', NOW());

-- THE LAKE DISTRICT (region_id = 3)
INSERT INTO locations (name, lat, lon, region_id, bortle_class, enabled, is_coastal_tidal,
    overlooks_water, consecutive_failures, bluebell_exposure, created_at)
VALUES ('Rannerdale Knotts', 54.5560, -3.2920, 3, 3, true, false, false, 0, 'OPEN_FELL', NOW());

INSERT INTO locations (name, lat, lon, region_id, bortle_class, enabled, is_coastal_tidal,
    overlooks_water, consecutive_failures, bluebell_exposure, created_at)
VALUES ('Skelghyll Woods', 54.4310, -2.9530, 3, 4, true, false, false, 0, 'WOODLAND', NOW());

INSERT INTO locations (name, lat, lon, region_id, bortle_class, enabled, is_coastal_tidal,
    overlooks_water, consecutive_failures, bluebell_exposure, created_at)
VALUES ('Brandelhow Woods', 54.5730, -3.1540, 3, 3, true, false, false, 0, 'WOODLAND', NOW());

INSERT INTO locations (name, lat, lon, region_id, bortle_class, enabled, is_coastal_tidal,
    overlooks_water, consecutive_failures, bluebell_exposure, created_at)
VALUES ('White Moss Common', 54.4520, -3.0210, 3, 4, true, false, false, 0, 'WOODLAND', NOW());

INSERT INTO locations (name, lat, lon, region_id, bortle_class, enabled, is_coastal_tidal,
    overlooks_water, consecutive_failures, bluebell_exposure, created_at)
VALUES ('Loughrigg Terrace', 54.4440, -3.0190, 3, 4, true, false, false, 0, 'OPEN_FELL', NOW());

INSERT INTO locations (name, lat, lon, region_id, bortle_class, enabled, is_coastal_tidal,
    overlooks_water, consecutive_failures, bluebell_exposure, created_at)
VALUES ('Low Wood, Wasdale', 54.4340, -3.3640, 3, 2, true, false, false, 0, 'WOODLAND', NOW());

INSERT INTO locations (name, lat, lon, region_id, bortle_class, enabled, is_coastal_tidal,
    overlooks_water, consecutive_failures, bluebell_exposure, created_at)
VALUES ('Holme Wood, Loweswater', 54.5720, -3.3510, 3, 3, true, false, false, 0, 'WOODLAND', NOW());

INSERT INTO locations (name, lat, lon, region_id, bortle_class, enabled, is_coastal_tidal,
    overlooks_water, consecutive_failures, bluebell_exposure, created_at)
VALUES ('Muncaster Castle Gardens', 54.3520, -3.3230, 3, 3, true, false, false, 0, 'WOODLAND', NOW());

-- ============================================================
-- 4. Add location types for new locations
--    Each gets both LANDSCAPE and BLUEBELL.
--    Uses subquery on bluebell_exposure to target only new rows
--    (Allen Banks id=40 and Roseberry Topping id=87 already have LANDSCAPE).
-- ============================================================

INSERT INTO location_location_type (location_id, location_type)
SELECT id, 'LANDSCAPE' FROM locations
WHERE bluebell_exposure IS NOT NULL AND id NOT IN (40, 87);

INSERT INTO location_location_type (location_id, location_type)
SELECT id, 'BLUEBELL' FROM locations
WHERE bluebell_exposure IS NOT NULL AND id NOT IN (40, 87);

-- ============================================================
-- 5. Add solar event types for new locations
--    All bluebell locations support both SUNRISE and SUNSET.
--    Allen Banks (40) and Roseberry Topping (87) already have these.
-- ============================================================

INSERT INTO location_solar_event_type (location_id, solar_event_type)
SELECT id, 'SUNRISE' FROM locations
WHERE bluebell_exposure IS NOT NULL AND id NOT IN (40, 87);

INSERT INTO location_solar_event_type (location_id, solar_event_type)
SELECT id, 'SUNSET' FROM locations
WHERE bluebell_exposure IS NOT NULL AND id NOT IN (40, 87);

-- ============================================================
-- 6. Add bluebell forecast columns on forecast_evaluation
-- ============================================================

ALTER TABLE forecast_evaluation ADD COLUMN bluebell_score INTEGER;
ALTER TABLE forecast_evaluation ADD COLUMN bluebell_summary TEXT;
