-- V135: seed the WOODLAND forecast_type — the second one-seed-row fold-in V107 anticipated.
--
-- Gives a canopy site its own Claude-evaluated 1-5 rating, year-round. Until now a wood out
-- of bluebell season reached DispositionCategory.SKIPPED_NO_PROMPT: it passed triage, but the
-- sky prompt cannot say anything true about a location with no sky and no woodland prompt
-- existed. It therefore showed a deterministic verdict and no rating at all.
--
-- scale_max = 5: WOODLAND is a combiner PEER, like SKY, TIDAL and BLUEBELL — not a 0-100
-- display product and not a standalone signal like INVERSION. For a woodland-only location it
-- is typically the sole peer present, because such a site produces no sky score by design
-- (LocationEntity.hasColourTypes() excludes WOODLAND); the combined rating is then simply the
-- woodland rating, which is the intent.
--
-- The id (7) and code/display_name/scale_max must mirror the ForecastType enum constant added
-- in the same change — ForecastTypeSeedDriftTest enforces the bijection.

INSERT INTO forecast_type (id, code, display_name, scale_max) VALUES
    (7, 'WOODLAND', 'Woodland Forecast', 5);
