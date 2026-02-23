CREATE TABLE location_location_type (
    location_id   BIGINT      NOT NULL REFERENCES locations(id),
    location_type VARCHAR(20) NOT NULL,
    PRIMARY KEY (location_id, location_type)
);
