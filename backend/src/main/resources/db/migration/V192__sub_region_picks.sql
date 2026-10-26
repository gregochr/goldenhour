-- V192: Lite readers' sub-region picks -- sub-regions-plan S2 (D4, D5).
--
--   app_user_sub_region     the sub-regions a reader has picked. Both foreign keys cascade: deleting the
--                           account (either path) or a sub-region area removes the picks with it.
--   app_user.sub_regions_grace_until
--                           the last day (UK civil date, inclusive) of the reader's free-change period; set
--                           once, by the first choice. Null until then.
--   app_user.sub_regions_changed_at
--                           when the reader last made a change that starts the lock (UTC wall clock, like
--                           created_at). Null when nothing is locked. Written only by scoped repository
--                           updates (the app_user column-scoped-writes rule).
--   sub_region_policy       one row, id = 1: the Lite pick limit, an admin setting (default 3).

CREATE TABLE app_user_sub_region (
    user_id    BIGINT    NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    area_id    BIGINT    NOT NULL REFERENCES area (id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, area_id)
);

CREATE INDEX idx_app_user_sub_region_area ON app_user_sub_region (area_id);

ALTER TABLE app_user
    ADD COLUMN sub_regions_grace_until DATE,
    ADD COLUMN sub_regions_changed_at  TIMESTAMP;

CREATE TABLE sub_region_policy (
    id         SMALLINT  NOT NULL PRIMARY KEY CHECK (id = 1),
    lite_limit SMALLINT  NOT NULL DEFAULT 3 CHECK (lite_limit BETWEEN 1 AND 24),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(50)
);

INSERT INTO sub_region_policy (id, lite_limit, updated_by) VALUES (1, 3, 'migration');
