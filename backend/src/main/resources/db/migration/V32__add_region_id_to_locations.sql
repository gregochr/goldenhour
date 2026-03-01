ALTER TABLE locations ADD COLUMN region_id BIGINT;

ALTER TABLE locations ADD CONSTRAINT fk_locations_region
    FOREIGN KEY (region_id) REFERENCES regions(id);
