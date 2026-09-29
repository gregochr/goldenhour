### Fixed — a delayed batch could overwrite a newer rating, not just a newer force-evaluation mark

A Codex review of 9761f365 found that round 10's `forced`-provenance fix (P1-A) rested on an
incomplete premise: recording `BriefingEvaluationResult.forced` at write time assumes write order
matches evaluation order, and it does not. `BatchPollingService` polls every still-`SUBMITTED`
batch independently, and the Anthropic Batch API allows up to 24 hours to complete, so a batch can
outlive its pipeline's internal safety timeout and finish AFTER a newer cycle's batch for the same
slot has already written it. The delayed, older batch's `forced = true` would then land on top of a
rating a newer, ordinary evaluation should have ended the exemption for.

Investigating the class of bug (not just the one instance) confirmed it is real and general: every
write into `cached_evaluation` — `mergeFromBatch`, `mergeWoodlandFromBatch`, and the OPEN_FELL
recombination in `recombineBluebell` — compared nothing about submission order before writing or
combining. Arrival order, which `evaluatedAt` records, is not evaluation order; the delayed batch's
RATING, not only its forced mark, could silently overwrite a newer one. `forecast_score` and
`survivor_atmosphere` share the identical weakness (both upsert unconditionally on their natural
key) and are left unaddressed as a named follow-up, out of this commit's scope; the PENDING
`forecast_evaluation` row-scoring seam is not exposed the same way, since it scores a row by primary
key rather than by natural-key overwrite.

The fix required no schema migration. `ForecastBatchEntity.pipelineRunId` and
`PipelineRunEntity.triggerTime` already existed — a cycle's own trigger time, set once at cycle
start and shared identically by every batch (including a retry) the cycle submits, is the reliable
submission-order key; a batch outside any orchestrated cycle falls back to its own `submittedAt`.
`BriefingEvaluationResult.submittedAt` (nullable `Instant`, `@JsonInclude(NON_NULL)`, rides
`results_json` exactly as `forced` does) carries this instant onto every result.
`BatchResultProcessor.resolveSubmissionInstant` resolves it once per batch and threads it through
the (also new) `ResultContext.submissionInstant`, down to `ForecastResultHandler`'s three
`buildXxx` methods and the synchronous path (`EvaluationServiceImpl.evaluateNowForecast`, which now
takes an injected `Clock`). `BriefingEvaluationService`'s three live merge methods compare the
incoming result's `submittedAt` against the stored result's for that location before writing: an
incoming result strictly OLDER is rejected as stale and logged once at INFO; a `null` on either
side (a legacy row, or a result with no instant to report) is never stale, matching every other
"unknown is safe" convention `forced` already established.

A second, more subtle finding fell out of writing the tests for this: sky and bluebell are
submitted as genuinely separate Anthropic batches, so their own `ForecastBatchEntity.submittedAt`
values almost never coincide even within one cycle — comparing those directly would have broken
OPEN_FELL recombination almost every time. Stamping every result with the shared CYCLE instant
instead of the individual batch's own timestamp is what makes a same-cycle pair's two sides carry
an EQUAL `submittedAt` by construction, so `recombineBluebell` now requires that equality (not
merely "not stale") before averaging; a bluebell newer than a stored sky entry but from a different
cycle is written but stands alone, the same "sky hasn't arrived yet" race the class already
tolerated.

With the ordering fixed generally, `forced` needed no combination-specific reasoning left at all —
except that writing the round-12 tests exposed a genuine bug in round 10's own combination method.
`BriefingEvaluationResult.withForcedFromCombination` used to take one argument (the "newly-arrived"
side) and read only its flag, reasoning that a same-cycle OPEN_FELL pair's two tasks always agree
on `forced` by construction (`ForecastTaskCollector` submits both from one loop iteration reading
one local variable) — true in production, but a synthetic same-cycle pair with the two flags
genuinely differing exposed that the method itself never enforced that invariant, and produced the
wrong answer for it. Both `withForcedFromCombination` and the new `withSubmittedAtFromCombination`
now take two explicit arguments and compute a genuine, commutative result — a plain OR for forced,
"prefer the newer side, fall back to the other" for the instant — removing the arrival-order
reasoning the coordinator asked to see gone.

New and updated tests: `BriefingEvaluationServiceTest` gained a `SubmissionOrderStaleness` nested
class covering Codex's case and its mirror (both pinned to fail against 9761f365), same-cycle
combination in both force-holding configurations, a cross-cycle bluebell standing alone rather than
combining, the wider defect pinned generally for sky/woodland/bluebell-only sites, a synchronous-
vs-slow-batch race, and both legacy/unknown-instant fallback directions; two pre-existing round-10
tests were given real, differing submission instants so their "cycle N vs cycle N+1" names describe
what they actually exercise, rather than relying on argument position with no timestamps at all — a
gap round 12 closed. `BriefingEvaluationResultTest` was rewritten for the two-argument combination
methods, including the exact case (only the older side forced) that exposed the original one-argument
shape's bug. `BatchResultProcessorTest` gained three tests pinning the pipeline-run lookup, the
retry-shares-precursor's-instant behaviour, and the ad-hoc fallback (all three confirmed to fail
when the lookup is disabled). `CachePayloadGoldenMasterTest` gained the byte-identical/round-trip/
legacy-null coverage for the new field, mirroring `forced`'s own three tests exactly.
