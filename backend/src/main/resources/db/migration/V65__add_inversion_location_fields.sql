-- Cloud inversion support: elevation and water-overlook flag on locations
ALTER TABLE locations ADD COLUMN elevation_m INTEGER;
ALTER TABLE locations ADD COLUMN overlooks_water BOOLEAN NOT NULL DEFAULT FALSE;
