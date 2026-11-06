-- V221: a named drive point for a location OpenRouteService cannot route to (reach-scoped-card-plan R1c).
--
-- A summit such as Helvellyn is not on a road, so ORS returns no route to its pin. The drive point is the nearest
-- place a driver can actually reach (a village or car park, e.g. Glenridding): drive times are measured to it
-- instead of to the pin. It has the same shape as a region base (V145): a name a reader recognises, plus the point
-- ORS routes to. Straight-line miles and Ask's home radius keep reading the location's own coordinates.
--
-- Nullable: most places are routable and carry none. The three columns move together, all set or all null; the
-- service enforces that (LocationService / DrivePoint), and it is not a SQL CHECK for the reason V145 gives for the
-- region base. Written only by LocationRepository.updateDrivePoint and the insert (updatable = false on the entity).
ALTER TABLE locations ADD COLUMN drive_point_name VARCHAR(120);
ALTER TABLE locations ADD COLUMN drive_point_lat  DOUBLE PRECISION;
ALTER TABLE locations ADD COLUMN drive_point_lon  DOUBLE PRECISION;
