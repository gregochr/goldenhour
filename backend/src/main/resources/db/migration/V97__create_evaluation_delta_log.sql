-- Captures score deltas when a cache entry is refreshed, enabling
-- empirical refinement of stability-driven freshness thresholds.
CREATE TABLE evaluation_delta_log (
    id                   BIGSERIAL PRIMARY KEY,
    cache_key            VARCHAR(255) NOT NULL,
    location_name        VARCHAR(255) NOT NULL,
    evaluation_date      DATE NOT NULL,
    target_type          VARCHAR(10) NOT NULL,
    stability_level      VARCHAR(20) NOT NULL,
    old_evaluated_at     TIMESTAMPTZ NOT NULL,
    new_evaluated_at     TIMESTAMPTZ NOT NULL,
    age_hours            NUMERIC(6, 2) NOT NULL,
    old_rating           INTEGER,
    new_rating           INTEGER,
    rating_delta         NUMERIC(4, 2),
    threshold_used_hours NUMERIC(5, 2) NOT NULL,
    logged_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_eval_delta_stability_age
    ON evaluation_delta_log(stability_level, age_hours);
