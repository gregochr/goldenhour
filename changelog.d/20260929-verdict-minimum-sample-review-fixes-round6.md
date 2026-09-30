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

## Round 13 — the comparison still missed a DECISION with no competing result, and a second sink

A further Codex review found round 12's fix incomplete on two fronts, both closed here without a
schema migration.

**Gap 1.** Round 12 only ever compares an incoming result against whatever is currently STORED in
`cached_evaluation`. But a later cycle's Gate 4 stability skip or weather-triage stand-down writes
NO cache entry to compare against — only a `forecast_run_disposition` row — so a batch delayed past
that later decision had nothing to lose a staleness comparison against at all, and its
chronologically stale rating went straight into the cache: exactly the arrival-order bug the
serve-time stability-skip retraction (`EvaluationViewService`) already exists to guard against,
reopened through a door round 12 did not close.

The obvious-looking fix — compare a disposition's own `created_at` against the incoming result's
`submittedAt` — was checked and rejected before writing any code, because it is actively wrong: a
disposition is always written a short time AFTER its own cycle starts, so a cycle's own
`EVALUATED`/`FORCE_EVALUATED`/`SKIPPED_STABILITY` record of the very slot it just decided would
always postdate that SAME cycle's trigger time (which, for an orchestrated batch, IS the result's
own `submittedAt`) — every forced T+3 rating would be retracted by its own cycle's own paperwork.
The fix instead identifies each disposition's OWNING CYCLE and compares that cycle's `trigger_time`
against the incoming result's `submittedAt`; a same-cycle disposition then compares as simultaneous
(excluded by a strict `isAfter`), never later. `ForecastRunDispositionEntity` carries no pipeline run
id of its own, but one is recoverable without a migration: every cycle's dispositions are anchored
to that cycle's FIRST job run, and exactly one `ForecastBatchEntity` row (the first bucket submitted
that cycle) shares that same `job_run_id` and already carries the real `pipeline_run_id` — a new
three-entity JPQL join (`ForecastRunDispositionRepository#findSupersedingCycleTriggerTimes`)
recovers it. Cost is two-phase: `PipelineRunRepository#existsByTriggerTimeAfter` is a cheap
existence check that answers the common case (no later cycle exists yet) with one query and no
further interaction; only when it says yes does the disposition join run, once per batch call, never
once per location. `BriefingEvaluationService.supersededByLaterRun` is the new gate, consulted first
in `mergeFromBatch`, `mergeWoodlandFromBatch` and `mergeBluebellFromBatch`, ahead of the existing
staleness/combination logic — a superseded result is written to no sink and logged once at INFO. The
PENDING `forecast_evaluation` row is untouched by this and needs no change: `scoreEvaluationRow` runs
earlier, inside `ForecastResultHandler#buildResult`, before the orchestrator ever reaches the merge
methods this gate lives in, and `forecast_run_at` is stamped at collection time (never bumped at
score time), so a genuinely later cycle's own row already wins `loadLatestForecasts`' per-slot MAX
comparison regardless of when an older cycle's Claude result happens to arrive.

**Gap 2.** `forecast_score` was named as an unaddressed follow-up in round 12 and turned out to be
fixable in this same commit after all: `ForecastScoreEntity` already stores the producing run's own
`pipeline_run_id` directly, so no disposition join is needed there — `ForecastScoreWriter#upsert` now
rejects an incoming write whose `pipelineRunId` is strictly smaller than the stored row's (logged
once at INFO), comparing ids directly rather than trigger times, because `PipelineRunEntity.id` is an
autoincrement key assigned in strict cycle-trigger order and so already IS that ordering. Equal ids
overwrite as before; a `null` on either side (a legacy row, or a sync/admin write, which this
writer's own class javadoc already documents as always `null`) proceeds exactly as before —
"unknown" can never be shown to be older. `survivor_atmosphere` remains the one genuinely
unaddressed member of round 12's follow-up list: it has no stored run id of any kind to compare
against, so closing it needs its own schema change and review, out of scope here.

New and updated tests: `BriefingEvaluationServiceTest` gained a `SupersededByLaterRun` nested class —
Codex's case and its `SKIPPED_TRIAGED` sibling (both confirmed to fail when the Phase 1 check is
short-circuited to always proceed), the same case with a forced rating (no exemption survives a
superseded write, because nothing is written), a same-cycle disposition that must NOT supersede its
own result (the regression guard for the hazard named above), a later cycle that recorded only
`SKIPPED_CACHED` (write proceeds), a retry batch sharing its precursor's cycle (write proceeds), and
a "no later run exists" case asserting the disposition repository is never touched at all
(`verifyNoInteractions`, no `any()` in the `verify()` call). `ForecastScoreWriterTest` gained four
tests for gap 2 — an earlier run rejected after a later one already landed (confirmed to fail when
the ordering check is stubbed out), equal ids overwriting, a null stored id always losing, and a null
incoming id still overwriting a known stored id. Every test from rounds 1 through 12 still passes
(9077 total after this round, up from 9059).
