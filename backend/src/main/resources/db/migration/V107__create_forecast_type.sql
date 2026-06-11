-- V107: forecast_type lookup — the component-score taxonomy.
--
-- First schema pass of the forecast-score re-architecture (Pass 1 of the
-- plan in docs/engineering/forecast-score-schema-investigation.md). One row
-- per score PRODUCT the system emits — not one per prompt. The standard
-- sky call yields up to three products (SKY, FIERY_SKY, GOLDEN_HOUR);
-- TIDAL is deterministic (TideVisitor, no Claude call); BLUEBELL gets its
-- own prompt in Pass 3.
--
-- scale_max is the native scale of each product:
--   * 1–5 types (SKY, TIDAL, BLUEBELL) are combiner peers — the values
--     RatingCombiner averages into the headline rating.
--   * 0–100 types (FIERY_SKY, GOLDEN_HOUR) are display products with
--     deliberately finer granularity. They are NEVER combiner inputs.
--
-- SKY stores the PRE-COMBINE sky visitor score. For coastal locations the
-- pure sky score is currently lost after RatingCombiner folds it into the
-- combined rating; the SKY row is the first place it is ever recorded.
-- The combined rating itself remains a serving-path product and has no row
-- here.
--
-- Explicit ids, not identity: the ForecastType Java enum mirrors (id, code,
-- scale_max) exactly and the pairing is load-bearing — see
-- ForecastTypeSeedDriftTest, which fails if either side drifts. The table
-- exists for FK integrity and reporting joins; the enum is the source of
-- truth in code.
--
-- AURORA and INVERSION are deliberately NOT seeded. They fold in via their
-- own future work; a future type is one seed row + one enum constant + a
-- writer, so nothing here precludes them. BASIC_* tier variants get no
-- rows either — they are a tier variant of FIERY_SKY/GOLDEN_HOUR, not a
-- type (product decision deferred to Pass 4).

CREATE TABLE forecast_type (
    id           BIGINT      PRIMARY KEY,
    code         VARCHAR(30) NOT NULL,
    display_name VARCHAR(60) NOT NULL,
    scale_max    INTEGER     NOT NULL,
    CONSTRAINT uq_forecast_type_code UNIQUE (code)
);

INSERT INTO forecast_type (id, code, display_name, scale_max) VALUES
    (1, 'SKY',         'Sky Forecast',         5),
    (2, 'FIERY_SKY',   'Fiery Sky Forecast',   100),
    (3, 'GOLDEN_HOUR', 'Golden Hour Forecast', 100),
    (4, 'TIDAL',       'Tidal Forecast',       5),
    (5, 'BLUEBELL',    'Bluebell Forecast',    5);
