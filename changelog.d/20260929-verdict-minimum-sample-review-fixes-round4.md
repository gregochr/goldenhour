### Fixed — a recombined rating could silently lose its force-evaluation mark, and the best-bet advisor could still crown an insufficient region

A Codex review of the #943/round-3 work (72e7b612/26044705) found two more P1s.

**Forced provenance dropped on recombination.** `BriefingEvaluationResult.forced` is stamped once,
at write time, by `ForecastResultHandler#buildResult` — but that is only the FIRST write. An
OPEN_FELL candidate's paired bluebell task can arrive and be averaged into a prior sky rating by
`BriefingEvaluationService.recombineBluebell`, and the averaged result was being built through a
10-arg compatibility constructor that defaults `forced = false` unconditionally, silently ending an
exemption either source actually carried. An audit of every `new BriefingEvaluationResult(` and
`with…` call site in `backend/src/main/java` classified each as fresh-from-evaluation (carries its
own task's `forced` already — `ForecastResultHandler`, `writeFromBatch`, `mergeFromBatch`,
`mergeWoodlandFromBatch`), a rebuild-or-combine site (must carry the mark explicitly —
`recombineBluebell` was the only one that didn't), or not-a-rating (retraction, triage — `forced`
stays `false` by construction). `BriefingEvaluationResult.withForcedFromCombination(newlyArrived)`
is the one combination rule every rebuild-or-combine site now uses: the combined result takes the
newly-arrived side's own mark. That single formula is provably correct for both cases a combination
can face — within one cycle's OPEN_FELL pair, sky and bluebell always carry an identical `forced`
flag by construction (`ForecastTaskCollector` submits both from the same loop iteration reading the
same local variable), so reading either side already equals "either forced"; across cycles, the
side passed in is always the one that was just produced, so reading its flag alone is "the newer
write's mark wins" — a later ordinary evaluation correctly ends an earlier forced exemption, and a
later forced evaluation correctly grants one an earlier ordinary rating never had.

**The best-bet advisor could still crown a region the Plan tab would refuse a verdict.**
`BriefingRegionEvaluationRollup` computes `sampleSufficient`/`forcedSample` for every region, and
`PlanWindowProjector` already withholds a rating-derived verdict from an insufficient, non-exempt
region — but `BriefingBestBetAdvisor`'s rollup builder and `BestBetRanker` recomputed rating
coverage independently and never consulted that flag, so such a region could still be named in
`bestBets`, `pipeline_run_pick`, and the advisor's model-comparison surfaces. The eligibility test
moved onto the record itself as `BriefingRegion.verdictEligible()` (moved out of
`PlanWindowProjector`'s private static, which now calls the shared method), and
`BestBetRanker.dropIneligiblePicks` validates Claude's OWN returned picks against it — after the
existing zero-coverage drop, before the coverage-aware ranking — reading `CandidateCoverage`'s new
`verdictEligible` field, which `BriefingRollupBuilder.appendRegionNode` now populates from the
region it already holds a reference to. This is deliberately model-OUTPUT validation, never prompt
shaping: the rollup JSON sent to Claude carries no new field, so no `BestBetAuroraPromptRegressionTest`
fixture moved. An all-ineligible response degrades to `SUCCESS_NO_PICKS`, the same honest decline the
zero-coverage drop already produces. `BestBetFallbackService.findFreshFallback` now takes the current
briefing's days and re-checks a stored pick's region against the same test before resurrecting it as
a stale fallback — a region eligible when the pick was persisted can go ineligible by the time a
later FAILED cycle serves it back, and `BriefingHonestyFilter`'s own withdrawal only catches a
zero-coverage ("blanked") region, not this narrower insufficient-but-nonzero case. The reconstructed
`CandidateCoverage` the advisor's replay harness builds from a stored rollup JSON carries
`verdictEligible = false` as a documented placeholder — that field was never part of the JSON schema
sent to Claude, and neither `replayWithPrompt` nor the model-comparison path calls the new drop at
all, so the placeholder is provably never consulted on that path.

New and updated tests: `BriefingEvaluationResultTest` (new — `withRating`/`withEvaluatedAt` preserve
or clear `forced` correctly, `retracted()` never carries it, `withForcedFromCombination`'s own
behaviour including the null-input and unrated-combined-result guards),
`BriefingEvaluationServiceTest` (`recombineBluebell` forced/forced, forced-bluebell/ordinary-sky,
ordinary/ordinary, and both cross-cycle exemption-ends/exemption-granted orderings),
`BriefingBestBetAdvisorTest` (a new `advise drops picks naming a verdict-ineligible region` nested
class: an insufficient non-exempt region is dropped even when Claude names it, a forced-sample
region is allowed, a sufficient region is allowed, a legacy null-eligibility payload is not eligible,
and an all-dropped response matches the existing `SUCCESS_NO_PICKS` outcome — the shared `region()`
test fixture now defaults `sampleSufficient = true` so every pre-existing happy-path test is
unaffected by the new gate), and `BestBetFallbackServiceTest` (a stored pick whose region has since
gone ineligible is dropped, a still-eligible one is served, and a region no longer present in the
current briefing at all is treated as unknown rather than ineligible).
