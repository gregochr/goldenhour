### Fixed — the verdict sample gate's "examined" count still missed the production shape it exists to protect

A Codex review of 72e7b612 (the first #943 P1-A/P1-B fix) found one P1: the round-1 fix made the
rule wrong in the MAIN case it was built to protect.

`VerdictSampleGate.examinedCount` was fixed to stop reading the briefing's own weather-triage
`Verdict` and instead read a `triagedByBatch` marker synthesised onto the score resolver's map — but
that marker was only attached when the resolver had NOTHING else to return for a slot. In
production, a batch triage always ALSO writes a real `forecast_evaluation` triage row alongside its
`SKIPPED_TRIAGED` disposition (`ForecastService#fetchWeatherAndTriage`), so `resolveForEnrichment`
resolves a genuine triage result instead — the marker's one branch almost never fired, and
`examinedCount` silently collapsed to rated-only. A region with 35 batch-triaged and 15 rated
voting slots out of 50 — the poor-weather near-window case the original brief explicitly said must
keep today's verdict — read 15-of-50, under half, and lost it.

The fix removes the flag from `BriefingEvaluationResult` entirely (it had already been attached
wrongly once) and hands `BriefingRegionEvaluationRollup` the disposition-sourced evidence through a
genuinely separate channel: a new `TriagedByBatchResolver` functional interface, resolved for the
same region/date/event key as the existing `RegionScoreResolver` but answering independently —
`EvaluationViewService.getTriagedByBatchLocationNames`/`getTriagedByBatchLocationNamesBulk`, sourced
from the same `loadTriagedByBatch` disposition read as before. `BriefingScoreEnricher.enrich` grew
a third parameter for it, with a default 2-arg overload so every caller that has no triaged evidence
to supply keeps compiling and reads as not-examined-by-triage (safe under-counting).

A slot can be examined via triage through EITHER of two independent channels, unioned by the
rollup: the disposition table (a batch triage), or a resolved `triageReason() != null` read directly
off whatever the score resolver already returned for the slot — needed because a hand-started or
synchronous-engine run can triage a slot (a real `forecast_evaluation` row) without ever writing a
`forecast_run_disposition` row at all. `VerdictSampleGate.examinedCount`'s own
`claudeRating() == null` guard, unchanged, still prevents double-counting a slot that is both rated
and named by either channel. A stability-skipped slot is excluded from both channels: its latest
disposition is `SKIPPED_STABILITY`, not `SKIPPED_TRIAGED`, and a recorded stability skip retracts
whatever the score resolver would otherwise return to a `retracted()` marker with a null
`triageReason`.

A false comment from the round-1 fix (`EvaluationViewService.java`, both the single-key and bulk
enrichment methods) claiming "`SKIPPED_TRIAGED` writes only a disposition row, no
`forecast_evaluation` row" is corrected — it does not, and the round-1 tests' own fixtures never
modelled the real row, which is why the bug survived a green suite once already.

New and corrected tests, all exercising real production shapes: `BriefingRegionEvaluationRollupTest`
gained a real-triage-result variant of the near-window case, a channel-B (hand-started, no
disposition) case, and a rated-and-triaged double-count guard; `EvaluationViewServiceTest` gained an
end-to-end nested class driving the real `EvaluationViewService` (mocked repositories) through a
real `BriefingRegionEvaluationRollup`, proving the bulk and single-key resolver shapes agree and
that the near-window and far-window cases resolve correctly. Two tests are mechanically proven (via
a non-destructive `git worktree` checkout of 72e7b612) to fail against the code they replace.
