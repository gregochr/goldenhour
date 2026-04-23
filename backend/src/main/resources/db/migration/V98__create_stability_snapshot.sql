-- Persists the in-memory stability snapshot so it survives container restarts.
-- One row per Open-Meteo grid cell; upserted on each scheduled classification.
CREATE TABLE stability_snapshot (
    id              BIGSERIAL       PRIMARY KEY,
    grid_cell_key   VARCHAR(30)     NOT NULL,
    grid_lat        DOUBLE PRECISION NOT NULL,
    grid_lng        DOUBLE PRECISION NOT NULL,
    stability_level VARCHAR(20)     NOT NULL,
    reason          VARCHAR(500)    NOT NULL,
    evaluation_window_days INT      NOT NULL,
    location_names  VARCHAR(2000)   NOT NULL,
    classified_at   TIMESTAMPTZ     NOT NULL,
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_stability_snapshot_cell ON stability_snapshot(grid_cell_key);
CREATE INDEX idx_stability_snapshot_classified ON stability_snapshot(classified_at);
