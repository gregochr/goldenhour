-- Build and prompt provenance for the calibration gate (forecast-build-provenance).
--
-- A drift in how readers' ratings compare with the forecast can be pinned to a model change from
-- forecast_evaluation.evaluation_model, but not to a release or to a prompt edit: the app build was
-- recorded nowhere on a forecast row, and nothing identified the prompt a row was sent with.
--
--   forecast_evaluation.app_build    the release (APP_VERSION, e.g. v2.28.0) running when the row GOT its
--                                    rating or triage reason - the same instant scored_at stamps.
--   forecast_evaluation.prompt_hash  SHA-256 (hex) of the SKY system prompt the row's batch request
--                                    carried. The system prompt, not the user message: the user message
--                                    embeds each slot's weather, so its hash would differ on every row and
--                                    group nothing. Two hashes exist at a time (inland and coastal).
--   outcome_rating.app_build         the release running when the reader's rating was written or replaced.
--
-- All nullable, no backfill, no guess: a row written before this migration has no recorded build, and
-- the calibration report reads it as UNKNOWN.
ALTER TABLE forecast_evaluation ADD COLUMN app_build VARCHAR(40);
ALTER TABLE forecast_evaluation ADD COLUMN prompt_hash VARCHAR(64);
ALTER TABLE outcome_rating ADD COLUMN app_build VARCHAR(40);
