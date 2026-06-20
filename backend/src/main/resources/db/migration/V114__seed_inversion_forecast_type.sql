-- V114: seed the INVERSION forecast_type — the one-seed-row fold-in V107 anticipated.
--
-- Completes the survivor-surface fix (Stage A) for the cloud-inversion hot topic:
-- the nightly batch dual-write (ForecastScoreWriter) now records the Claude-evaluated
-- inversion score on every scored survivor, so the detector can read it off
-- forecast_score (the survivor surface) instead of forecast_evaluation (which nightly
-- holds only the triaged-out rejects).
--
-- scale_max = 10: inversion is a 0–10 likelihood, NOT a 1–5 combiner peer and NOT a
-- 0–100 display product. It never folds into the headline rating; it is a standalone
-- signal the inversion detector thresholds at the STRONG band (score >= 9, mirroring
-- PromptBuilder.InversionPotential.fromScore). The classification string rides the
-- component's summary.
--
-- The id (6) and code/display_name/scale_max must mirror the ForecastType enum constant
-- added in the same change — ForecastTypeSeedDriftTest enforces the bijection.

INSERT INTO forecast_type (id, code, display_name, scale_max) VALUES
    (6, 'INVERSION', 'Cloud Inversion Forecast', 10);
