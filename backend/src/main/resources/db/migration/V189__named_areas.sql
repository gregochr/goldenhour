-- V189: named areas ("Hadrian's Wall") and location aliases ("the Heugh") -- ask-named-areas-plan A1.
--
-- Data only: Ask does not read these tables until A2. An area is explicit membership (area -> location
-- ids), not geometry. A location alias is another name for one location.
--
-- This file is UTF-8: the seed below contains a right single quotation mark. Do not re-save it in
-- another encoding.

CREATE TABLE area (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(80) NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uq_area_name_lower ON area (lower(name));

CREATE TABLE area_alias (
    area_id BIGINT      NOT NULL REFERENCES area (id) ON DELETE CASCADE,
    alias   VARCHAR(80) NOT NULL,
    PRIMARY KEY (area_id, alias)
);

-- Locations are never hard-deleted today (V177's note); the cascade only keeps the join honest if one is.
CREATE TABLE area_location (
    area_id     BIGINT NOT NULL REFERENCES area (id) ON DELETE CASCADE,
    location_id BIGINT NOT NULL REFERENCES locations (id) ON DELETE CASCADE,
    PRIMARY KEY (area_id, location_id)
);
CREATE INDEX idx_area_location_location ON area_location (location_id);

CREATE TABLE location_alias (
    location_id BIGINT      NOT NULL REFERENCES locations (id) ON DELETE CASCADE,
    alias       VARCHAR(80) NOT NULL,
    PRIMARY KEY (location_id, alias)
);

-- Seed: Hadrian's Wall. Area aliases are at least two words ("the Wall" alone is too loose).
INSERT INTO area (name) VALUES ('Hadrian''s Wall');

INSERT INTO area_alias (area_id, alias)
SELECT id, v.alias
FROM area
CROSS JOIN (VALUES ('Hadrian Wall'), ('Roman Wall')) AS v (alias)
WHERE name = 'Hadrian''s Wall';

-- Membership is seeded BY NAME because ids differ between environments, and the Wall's production
-- locations were added through the admin UI, not by migration. The list is candidate spellings: a
-- spelling that matches no location inserts nothing, by design. The owner checks the result and
-- corrects it in the admin UI (A3). Production's Wall locations are ids 41–49 (named in V86's comments);
-- the list covers those names plus variants. The replace() folds a curly apostrophe to a straight one.
INSERT INTO area_location (area_id, location_id)
SELECT a.id, l.id
FROM area a
JOIN locations l
  ON lower(replace(l.name, '’', '''')) IN (
       'walltown crags', 'walltown', 'cawfields', 'caw gap', 'steel rigg', 'peel craggs', 'peel crags',
       'hotbank crags', 'hotbank', 'cuddy''s crags', 'housesteads', 'sycamore gap', 'irthing gorge',
       'crammel linn', 'crammel linn waterfall', 'steel rigg & winshield crags', 'highshield crags')
WHERE a.name = 'Hadrian''s Wall';

-- Hartlepool Headland (V138) is locally "the Heugh", after the lighthouse and the battery.
INSERT INTO location_alias (location_id, alias)
SELECT l.id, v.alias
FROM locations l
CROSS JOIN (VALUES ('The Heugh'), ('Heugh Lighthouse'), ('Heugh Battery')) AS v (alias)
WHERE lower(l.name) = 'hartlepool headland';
