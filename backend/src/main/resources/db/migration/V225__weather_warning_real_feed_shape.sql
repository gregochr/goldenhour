-- V225: align weather_warning_version with the real NSWWS v1.1 feed (weather-warnings-plan W1c, section 11).
--
--   warning_version  INTEGER -> NUMERIC(10,2): the feed sends a decimal string ("3.0"). Ordering is numeric, so
--                    "10.0" sorts after "9.0". The (warning_id, warning_version) unique key and the
--                    (warning_id, warning_version DESC) index are rebuilt by Postgres on the type change.
--   likelihood, impact  VARCHAR -> INTEGER: the feed sends raw integers (1..4); they are served raw, never as words.
--   update_description  the feed's warningUpdateDescription (shown on an updated warning).
--   what_should_i_do    the feed's whatShouldIDo advice text, kept in full.
--   source_url          dropped: the feed has no per-warning URL; the Met Office link is configured
--                       (photocast.warnings.public-url).
--   status              stays ACTIVE | CANCELLED. The feed's ISSUED maps to ACTIVE; its CANCELLED and EXPIRED both
--                       mean the warning has ended and map to CANCELLED; source_status keeps the feed's own word, so
--                       EXPIRED stays distinguishable without widening the CHECK constraint.
ALTER TABLE weather_warning_version
    ALTER COLUMN warning_version TYPE NUMERIC(10, 2) USING warning_version::NUMERIC(10, 2);

ALTER TABLE weather_warning_version
    ALTER COLUMN likelihood TYPE INTEGER
        USING NULLIF(regexp_replace(likelihood, '[^0-9]', '', 'g'), '')::INTEGER;

ALTER TABLE weather_warning_version
    ALTER COLUMN impact TYPE INTEGER
        USING NULLIF(regexp_replace(impact, '[^0-9]', '', 'g'), '')::INTEGER;

ALTER TABLE weather_warning_version ADD COLUMN update_description TEXT;
ALTER TABLE weather_warning_version ADD COLUMN what_should_i_do TEXT;
ALTER TABLE weather_warning_version DROP COLUMN source_url;
