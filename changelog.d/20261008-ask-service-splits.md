### Changed — Ask services split at their seams; relevance and day words have one home each

A behaviour-preserving refactor of Ask PhotoCast's backend. `AskJobRunService` no longer holds two
unrelated jobs under one lock: the daily `ASK` run, the cost increments and the typed-spend sum stay
there, and the unrecorded-turn holder with its accounting latch is `UnrecordedTurnHolder` (the lock
still covers the spend memo, so a held turn and its persisted row are still never counted twice).
`AskReadyService` is split into `AskReadyPrecompute` (the pipeline dispatch, the admin endpoint and the
per-day ceiling) and `AskReadyServing` (what `GET /api/ask/ready`, the typed intent match and the `try`
suggestions read), so the code that only serves no longer constructs the ten-argument precompute bean.
What a Ready question may carry is `ReadyRelevance`, one code path for store, serve and the validator,
and `ReadyQuestion` keeps the catalogue. The relative-day words in three places are built on the shared
`DayLabels.relative`, the London `HH:mm` clock has one home (`AskClock`), and a new test round-trips the
stored Ready question text through the typed-question matcher for every weekday and both events. Nothing
on the wire moves; the prompt and tool-schema golden files are byte-identical.
