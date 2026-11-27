-- V229: a watched almanac date as a third kind of favourite (one-screen-plan D16, O2b).
--
--   almanac_key   `<lower-kebab almanac type>:<start date>` (for example `king-tide:2026-12-03`), set only on a
--                 watched-date row. The key is the type and the START date, never the feed entry's id
--                 (`type:start:end`), because a tide run's end date moves as the forecast firms up. The service
--                 validates the type and the ISO date; the database only guarantees one target per row and one
--                 watch per key per reader.
--
-- The one-target CHECK now reads "exactly one of location_id, area_id, almanac_key". Existing rows each set exactly
-- one of the first two, so they satisfy it. The 25-per-reader cap (places, areas and dates together) and the deletion
-- of long-expired date rows are the service's, under the reader's row lock.

ALTER TABLE user_favourite ADD COLUMN almanac_key VARCHAR(64);

ALTER TABLE user_favourite DROP CONSTRAINT ck_user_favourite_one_target;
ALTER TABLE user_favourite ADD CONSTRAINT ck_user_favourite_one_target
    CHECK (num_nonnulls(location_id, area_id, almanac_key) = 1);

CREATE UNIQUE INDEX uq_user_favourite_date ON user_favourite (user_id, almanac_key)
    WHERE almanac_key IS NOT NULL;
