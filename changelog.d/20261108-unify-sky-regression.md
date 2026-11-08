### Changed — one sky regression class for quick and pass^k runs

The two paid sky test classes, `PromptRegressionTest` (single shot, Haiku, 8 fixtures plus a hard-coded
Angel of the North case) and `SkyRatingEvalTest` (8 runs, Sonnet 4.6, rating band only), are merged into
`eval/SkyRatingRegressionTest`. This restructure was approved by the owner on 2026-11-08; no band, bound
or other assertion value changed.

Every run of every one of the 10 registry fixtures now checks the same things: the rating band, plus the
fixture's Fiery Sky and Golden Hour bounds where it has `RegressionBounds`. `-Dsky.runs` / `SKY_RUNS`
sets the runs per fixture (default 1, quick mode: every assertion hard, monitored fixtures included;
`-Dsky.runs=8` is strict pass^k on gated fixtures, with monitored fixtures reported only). The default
model is now `SONNET_55`, the production near-term model; `-Dsky.model` / `SKY_MODEL` set it, and the
old `regression.model`, `eval.model`, `REGRESSION_MODEL` and `EVAL_MODEL` still work (conflicting legacy
settings fail fast). A null score is reported as a malformed run instead of throwing. The Angel of the
North case is retired. `RegressionBoundsRegistryTest` moved to the `eval` package and now also guards
that each fixture's bounds rating equals its band; the judgement is proven offline by
`SkyRatingRegressionJudgementTest`. Test-only change: no behaviour change in the app.
