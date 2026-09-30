### Changed — a region's ratings need a large enough sample before they may set its verdict

A single rated location, however it got its rating, was enough to set a fifty-location region's
verdict, crown it a BEST BET or ALSO GOOD pick, and outrank a region every one of whose locations
was actually scored. Production evidence, 2026-09-29: for Fri 2 Oct sunrise the whole 253-location
catalogue held six ratings, all 4★, all force-evaluated far-horizon headline candidates — and that
window read "Worth it" and took ALSO GOOD. The owner's words: a single good rating "isn't really in
the spirit" of the app — a good sunrise needs a statistically significant number of 3/4/5★
forecasts, not a blip.

`VerdictSampleGate` now gates a region's rated `displayVerdict`, pick eligibility and ranking on
whether its sample is large enough: at least 5 rated voting slots, and at least half the voting
roster *examined* — rated, or a resolved weather stand-down the pipeline actually triaged; an
unrated, un-triaged slot beyond Gate 4's horizon is no evidence at all. A region that clears both
tests behaves exactly as before. On a poor-weather near window, where most of the region is triaged
and only the viable remainder is rated, the region has been examined in full and its ratings keep
deciding its verdict unchanged — the gate bites only the far-horizon, stability-gated shape the
production example above shows. Below the gate a region falls back to the same triage-derived
verdict an unrated region already gets, cannot be a pick, ranks below every region that clears the
gate, and is floored at `Confidence.LOW`. The star is never touched: `meanRating` and `bestRating`
keep serving the rated average and maximum exactly as before, whatever the sample size — only the
verdict word, the pick and the ranking change.

**The exemption — an owner decision dated 2026-09-29, the same day, added once the interaction was raised:**
`ForceEvalHeadlineSelector` force-evaluates a capped handful of far-out headline candidates a night
specifically so a clear far-out day can be crowned with real Claude evidence — a handful of forced
ratings can never reach the sample gate's own threshold, so a gate with no exemption would have left
that spend buying stars no verdict could use. A region with at least one voting slot whose CURRENT
rating was written by a force evaluation is exempt from the sample gate outright: its verdict, pick
eligibility, ranking and confidence follow the rated average exactly as they did before this change.
"Current" is answered per slot by the new `EvaluationViewService.loadForcedFlags` — one bulk query
per serve, bounded to the served window, reading each slot's most recent `EVALUATED`/
`FORCE_EVALUATED` `forecast_run_disposition` row — so a later ordinary evaluation ends the exemption
for that slot, and the parent stability-skip retraction clears a forced rating outright the next
time a nightly cycle declines to re-look at it. A failed or empty disposition lookup reads as not
forced; unknown never grants the exemption.

Two nullable `BriefingRegion` fields ride the existing `daily_briefing_cache` JSON with no
migration: `sampleSufficient` (the raw sample-size test, independent of forcing) and `forcedSample`
(whether the exemption applies) — together they let a client eventually tell "crowned on a
sufficient sample" apart from "crowned on forced headline ratings", though nothing reads them yet.
Both are `null`, read as unknown/ineligible, on a payload cached before this change. Computed fresh
on both the build and serve paths by `BriefingRegionEvaluationRollup`, the same place the region's
mean, best rating and confidence already are.
