-- V46: Refactor goldenHourType from single enum column to @ElementCollection Set<SolarEventType>
-- Rename GoldenHourType → SolarEventType; values: SUNRISE, SUNSET, ALLDAY
-- Migration mapping: SUNRISE→[SUNRISE], SUNSET→[SUNSET], BOTH_TIMES→[SUNRISE,SUNSET], ANYTIME→[ALLDAY]

-- 1. Create join table
CREATE TABLE location_solar_event_type (
    location_id BIGINT NOT NULL,
    solar_event_type VARCHAR(20) NOT NULL,
    PRIMARY KEY (location_id, solar_event_type),
    CONSTRAINT fk_solar_event_type_location FOREIGN KEY (location_id) REFERENCES locations(id)
);

-- 2. Migrate existing data
INSERT INTO location_solar_event_type (location_id, solar_event_type)
SELECT id, 'SUNRISE' FROM locations WHERE golden_hour_type = 'SUNRISE';

INSERT INTO location_solar_event_type (location_id, solar_event_type)
SELECT id, 'SUNSET' FROM locations WHERE golden_hour_type = 'SUNSET';

INSERT INTO location_solar_event_type (location_id, solar_event_type)
SELECT id, 'SUNRISE' FROM locations WHERE golden_hour_type = 'BOTH_TIMES';

INSERT INTO location_solar_event_type (location_id, solar_event_type)
SELECT id, 'SUNSET' FROM locations WHERE golden_hour_type = 'BOTH_TIMES';

INSERT INTO location_solar_event_type (location_id, solar_event_type)
SELECT id, 'ALLDAY' FROM locations WHERE golden_hour_type = 'ANYTIME';

-- 3. Drop old column
ALTER TABLE locations DROP COLUMN golden_hour_type;
