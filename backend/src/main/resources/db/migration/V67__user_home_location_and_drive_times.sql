-- V67: Per-user home location and drive times
-- Replaces the single drive_duration_minutes column on location with
-- per-user drive times in a dedicated table.

-- (a) Add home location columns to the user table
ALTER TABLE app_user ADD COLUMN home_postcode VARCHAR(10);
ALTER TABLE app_user ADD COLUMN home_latitude DOUBLE PRECISION;
ALTER TABLE app_user ADD COLUMN home_longitude DOUBLE PRECISION;
ALTER TABLE app_user ADD COLUMN drive_times_calculated_at TIMESTAMP WITH TIME ZONE;

-- (b) Create the per-user drive time table (seconds, not minutes)
CREATE TABLE user_drive_time (
    user_id     BIGINT NOT NULL REFERENCES app_user(id),
    location_id BIGINT NOT NULL REFERENCES locations(id),
    drive_duration_seconds INTEGER NOT NULL,
    PRIMARY KEY (user_id, location_id)
);

CREATE INDEX idx_user_drive_time_user ON user_drive_time(user_id);

-- (c) Drop the legacy single-user column
ALTER TABLE locations DROP COLUMN drive_duration_minutes;
