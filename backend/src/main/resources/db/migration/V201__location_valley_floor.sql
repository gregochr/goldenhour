-- V201: location valley floor (inversion-v2-plan Phase 1-pre). Nullable terrain facts, written only by the
-- admin's location editor (LocationService.update); nothing in the forecast path reads them until Phase 1b.
-- valley_floor_m is the height of the valley floor below the viewpoint; the lat/lng pair is the point it was
-- read at, so 1b can request that point's own model cell. The three are set or cleared together. Existing rows
-- stay NULL, so this migration changes no behaviour on its own.
ALTER TABLE locations ADD COLUMN valley_floor_m INTEGER;
ALTER TABLE locations ADD COLUMN valley_floor_lat DOUBLE PRECISION;
ALTER TABLE locations ADD COLUMN valley_floor_lng DOUBLE PRECISION;
