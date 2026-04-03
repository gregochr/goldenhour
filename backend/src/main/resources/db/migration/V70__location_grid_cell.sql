ALTER TABLE locations ADD COLUMN grid_lat DOUBLE PRECISION;
ALTER TABLE locations ADD COLUMN grid_lng DOUBLE PRECISION;
CREATE INDEX idx_location_grid_cell ON locations(grid_lat, grid_lng);
