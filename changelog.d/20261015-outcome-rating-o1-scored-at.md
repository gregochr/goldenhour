### Changed — `forecast_evaluation` rows now carry the instant they got their rating

`forecast_evaluation` gains a nullable `scored_at` (V178): when a row acquired something to say. The
synchronous engine and the triage and sentinel-skip paths stamp it at insert, from the clock, when the
row carries a rating or a triage reason; a batch's `PENDING` row is stamped when its result scores it
in place, and only when the star survived validation. A row still `PENDING`, an `ABANDONED` row, a
wildlife comfort row and every row written before this deploy keep it null — no backfill, no guess.
`forecast_run_at` is untouched: it is a submission instant (a batch row is stamped before its batch
is even submitted, and the Batch API may land the result a day later), so it cannot say what existed
at a given moment; `scored_at` can. Nothing reads it for serving yet; the outcome rating's provenance
uses it as the availability bound, and a row without it is never taken as the forecast a reader saw.
`ForecastResultHandler` now takes the application `Clock`.
