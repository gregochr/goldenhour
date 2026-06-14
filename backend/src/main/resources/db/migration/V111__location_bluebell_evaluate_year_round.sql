-- V111: Per-site flag controlling whether a bluebell location is evaluated
-- outside bluebell season (OQ4 from the Pass 3 bluebell extraction).
--
-- TRUE (the default, and the value every existing row receives) preserves the
-- current behaviour: a bluebell site stays a year-round colour-forecast
-- candidate — sky out of season, the bluebell display in season. FALSE removes
-- a site from out-of-season candidacy, for a wood worth photographing only
-- during the bloom.
--
-- Ships with ZERO sites flagged for removal: candidacy is unchanged at launch.
-- The collector gate reads this flag once the bluebell mini-batch lands; with
-- every row TRUE it is a no-op today.

ALTER TABLE locations
    ADD COLUMN bluebell_evaluate_year_round BOOLEAN NOT NULL DEFAULT TRUE;
