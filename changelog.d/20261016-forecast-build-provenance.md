### Added — build and prompt provenance on forecasts and ratings, and per-build and per-prompt calibration

The calibration gate could pin a drift in how readers' ratings compare with the forecast to a model, but not to a
release or to a prompt edit: the model is recorded per forecast row, the app build was recorded nowhere on one, and nothing
identified the prompt a row was sent with. V182 adds three nullable columns, with no backfill: `forecast_evaluation.app_build`,
`forecast_evaluation.prompt_hash` and `outcome_rating.app_build`.

`app_build` is the release the deploy workflow bakes in from the git tag (the same value `job_run.app_version` already records),
stamped where a forecast row gets its rating or triage reason and where a reader's rating is written. `prompt_hash` is the
SHA-256 of the sky system prompt a batch request carried, stamped when the batch is submitted. It is the system prompt and not
the user message because the user message holds each slot's weather and would differ on every row.

`GET /api/admin/calibration` gains `bucketsByBuild` and `bucketsByPromptHash` beside the existing per-horizon and per-model
buckets. A forecast row from before V182 reads `UNKNOWN`. A reader's rating is bucketed by the build and prompt of the forecast
it was shown.
