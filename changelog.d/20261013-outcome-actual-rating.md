### Fix — `POST /api/outcome` can record the 1–5 star rating the calibration gate needs

`ActualOutcome` gained an optional `actualRating` (1–5, validated; out of range answers 400) which `OutcomeService.record` now writes to `actual_outcome.actual_rating`. The calibration report pairs forecasts only with outcomes that have this rating, and the API previously had no way to supply it, so no outcome recorded through the API could ever be calibrated. No migration: the column already existed.
