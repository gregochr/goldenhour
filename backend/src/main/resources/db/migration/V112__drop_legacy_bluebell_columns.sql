-- V112: Drop the legacy bluebell columns from forecast_evaluation.
--
-- Pass 3 extracted bluebell into its own Claude prompt and persists the result as
-- BLUEBELL rows in forecast_score (1-5). Every reader has been migrated:
--   * the bluebell hot topic reads forecast_score BLUEBELL rows (C4);
--   * the forecast DTO / map popup reads the Claude BLUEBELL rating from
--     forecast_score (C5);
--   * the dead findBluebellEvaluations query and the always-null
--     SunsetEvaluation/entity bluebell fields are removed.
--
-- These columns only ever held the deterministic 0-10 condition score (and latterly
-- nothing, since the standard prompt stopped emitting a bluebell score in C1), so the
-- drop loses no live product data — the normalised forecast_score rows are the record.

ALTER TABLE forecast_evaluation DROP COLUMN bluebell_score;
ALTER TABLE forecast_evaluation DROP COLUMN bluebell_summary;
