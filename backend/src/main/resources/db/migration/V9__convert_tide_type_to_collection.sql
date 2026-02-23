CREATE TABLE location_tide_type (
    location_id BIGINT      NOT NULL REFERENCES locations(id),
    tide_type   VARCHAR(20) NOT NULL,
    PRIMARY KEY (location_id, tide_type)
);

-- Migrate existing values; skip NOT_COASTAL (empty set is the new "not coastal")
INSERT INTO location_tide_type (location_id, tide_type)
SELECT id, tide_type FROM locations WHERE tide_type <> 'NOT_COASTAL';

ALTER TABLE locations DROP COLUMN tide_type;
