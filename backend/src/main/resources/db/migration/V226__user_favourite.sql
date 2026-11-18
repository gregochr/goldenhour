-- V226: a reader's favourites (find-and-favourites-plan F3, D2, D5).
--
--   user_favourite   one row per starred place OR starred area (exactly one of location_id / area_id is set).
--                    All three foreign keys cascade: deleting the account, the location or the area removes
--                    the favourite with it. Independent of app_user_sub_region (V192): a Lite reader's
--                    sub-region pick and a Pro reader's favourite never read or change each other.
--                    The 25-per-reader cap is enforced by the service under the user row's lock, not here.

CREATE TABLE user_favourite (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT    NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    location_id BIGINT    REFERENCES locations (id) ON DELETE CASCADE,
    area_id     BIGINT    REFERENCES area (id) ON DELETE CASCADE,
    created_at  TIMESTAMP NOT NULL,
    CONSTRAINT ck_user_favourite_one_target CHECK ((location_id IS NULL) <> (area_id IS NULL))
);

CREATE UNIQUE INDEX uq_user_favourite_place ON user_favourite (user_id, location_id)
    WHERE location_id IS NOT NULL;
CREATE UNIQUE INDEX uq_user_favourite_area ON user_favourite (user_id, area_id)
    WHERE area_id IS NOT NULL;
CREATE INDEX idx_user_favourite_user ON user_favourite (user_id);
CREATE INDEX idx_user_favourite_location ON user_favourite (location_id) WHERE location_id IS NOT NULL;
CREATE INDEX idx_user_favourite_area ON user_favourite (area_id) WHERE area_id IS NOT NULL;
