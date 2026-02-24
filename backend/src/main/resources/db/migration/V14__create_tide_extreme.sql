-- Stores tide extremes (high/low tide times and heights) fetched from WorldTides.
-- Refreshed weekly per coastal location. Used by ForecastService to classify
-- tide state at the solar event time without calling the external API on every run.
CREATE TABLE tide_extreme (
    id            BIGSERIAL     PRIMARY KEY,
    location_id   BIGINT        NOT NULL REFERENCES locations(id) ON DELETE CASCADE,
    event_time    TIMESTAMP     NOT NULL,
    height_metres DECIMAL(6, 3) NOT NULL,
    type          VARCHAR(4)    NOT NULL,
    fetched_at    TIMESTAMP     NOT NULL,
    CONSTRAINT uq_tide_extreme UNIQUE (location_id, event_time)
);

CREATE INDEX idx_tide_extreme_location_time ON tide_extreme (location_id, event_time);
