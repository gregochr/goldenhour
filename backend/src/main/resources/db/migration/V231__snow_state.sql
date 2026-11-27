-- V231: the point snowpack, recorded per place and per summit (snow-topics-truth-plan S2).
--
--   snow_state   one row per (point, date, event): the modelled snow water equivalent at the sunrise or sunset hour,
--                the snow added in the 24 hours before it, and the low cloud at that hour. point_key is
--                'loc:<location id>' or 'summit:<key>' (a text key, not a foreign key: a summit has no row to point
--                at, and a deleted location's rows simply age out under the 7-day prune).
--
-- Written by SnowStateRefreshJob, read by SnowStateReader. Nothing reads it for a topic yet (S3 switches the
-- detectors); slot_atmosphere.snow_depth_m, the 9 km grid value, keeps being written untouched.
--
-- Plain unique key only: no foreign key, no CHECK, no partial index, so the local H2 schema (ddl-auto: update)
-- matches and the entity's validate boot has nothing to disagree with. The unique constraint's index also serves
-- the point lookups; the date index serves the 7-day prune and the per-date reads.
CREATE TABLE snow_state (
    id              BIGSERIAL PRIMARY KEY,
    point_key       VARCHAR(64)      NOT NULL,
    evaluation_date DATE             NOT NULL,
    event_type      VARCHAR(10)      NOT NULL,
    snow_water_mm   DOUBLE PRECISION,
    snowfall_24h_cm DOUBLE PRECISION,
    low_cloud_pct   INTEGER,
    computed_at     TIMESTAMP        NOT NULL,
    CONSTRAINT uq_snow_state_point_date_event UNIQUE (point_key, evaluation_date, event_type)
);

CREATE INDEX idx_snow_state_evaluation_date ON snow_state (evaluation_date);

-- Three runs a day, UTC (time_zone NULL means UTC since V204): 06:30 after the overnight cycle, 14:30 thirty
-- minutes after the afternoon refresh (a shared Open-Meteo rate limit, the catalogue's staggered-after edge), and
-- 21:30 after the evening refresh. 05:30/17:30 wildlife comfort and 15:30 astro conditions are an hour clear.
-- No Claude call. job_key is NOT NULL UNIQUE, so the insert adds exactly one row.
INSERT INTO scheduler_job_config (job_key, display_name, description, schedule_type,
                                  cron_expression, status)
VALUES ('snow_state_refresh',
        'Snow State',
        'Recomputes each place''s and each summit''s modelled snowpack from its own recent weather '
            || '(seven days of temperature, humidity, precipitation, wind and sun) for today and the '
            || 'next four sunrises and sunsets. One batched Open-Meteo request set, no Claude call. '
            || 'A place that could not be fetched keeps its previous reading.',
        'CRON',
        '0 30 6,14,21 * * *',
        'ACTIVE');
