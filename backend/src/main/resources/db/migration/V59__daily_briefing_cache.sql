-- Persists the last generated daily briefing so it survives backend restarts.
-- Single-row table (id = 1 always); upserted on every briefing refresh.
CREATE TABLE daily_briefing_cache (
    id          INTEGER      PRIMARY KEY,
    generated_at TIMESTAMP   NOT NULL,
    payload     TEXT         NOT NULL
);
