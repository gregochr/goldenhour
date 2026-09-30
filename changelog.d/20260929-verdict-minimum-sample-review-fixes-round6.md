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

## Round 14 — round 13's own fix was wrong, tested against production, and corrected

A further Codex review tested round 13's discriminator against production (read-only) and found it
does not hold, on two counts.

**The evidence.** Pipeline run 249 (INTRADAY, 2026-09-29, trigger 14:00:00 UTC): all three Anthropic
batch submissions failed with HTTP 500. `ScheduledBatchEvaluationService#persistCycleDispositions`
anchored the cycle's 589 dispositions — 510 `EVALUATED`, 76 `SKIPPED_TRIAGED`, 3
`SKIPPED_UNKNOWN_LOCATION` — to a disposition-only "anchor run" job_run with **no `forecast_batch`
row at all**: `forecast_batch` holds zero rows for that job_run_id, and over five days, 589 of 13,120
disposition rows join to no batch at all. Two consequences, both wrong in round 13:

1. Round 13's three-entity join through `forecast_batch` (`d.jobRunId = b.jobRunId AND
   b.pipelineRunId = p.id`) cannot see ANY disposition from an anchor run, in either direction — a
   genuine `SKIPPED_TRIAGED` decision from a failed cycle was invisible to it, exactly the shape
   this whole feature exists to catch.
2. Round 13's disposition filter (`d.disposition <> 'SKIPPED_CACHED'`) let `EVALUATED`/
   `FORCE_EVALUATED` supersede a result. Both categories record only that a candidate was INCLUDED
   for submission, never that a result was produced — the 510 `EVALUATED` rows on the failed cycle
   above produced exactly zero results. Had a `forecast_batch` row existed for them (the ordinary,
   non-anchor case), round 13 would have rejected a perfectly good older rating in favour of
   nothing, leaving the slot with no rating at all where, before this whole PR, the stale-but-real
   prior rating would have kept serving.

**Correction 1 — the disposition allow-list is now explicit, not "everything except".** Every
`DispositionCategory` value was individually re-argued: `SKIPPED_STABILITY` (the original #940
retraction category — a positive decision to decline re-scoring) and `SKIPPED_TRIAGED` (the weather
stand-down — a positive decision the slot was looked at and rejected) are IN; `EVALUATED` and
`FORCE_EVALUATED` (inclusion records, not results — the production proof above) are OUT;
`SKIPPED_CACHED` (a region-level reuse, not a per-slot decision), `SKIPPED_PAST_DATE`,
`SKIPPED_TRAVEL_DAY`, `SKIPPED_UNKNOWN_LOCATION`, `SKIPPED_ERROR` and `SKIPPED_NO_REFRESH_NEEDED`
("a later look is already guaranteed" — the opposite of a decision against) are all OUT; and
`SKIPPED_HARD_CONSTRAINT`/`SKIPPED_NO_PROMPT` are OUT too, per the explicit two-item allow-list,
though the former is arguably a candidate for a future round since it IS a genuine stand-down. The
new JPQL queries (`ForecastRunDispositionRepository#findSupersedingDispositions`/
`#existsSupersedingDisposition`) filter `disposition IN ('SKIPPED_STABILITY', 'SKIPPED_TRIAGED')`
literally — an allow-list, never a NOT-IN exclusion.

**Correction 2 — the discriminator needs no `forecast_batch` join at all.** A disposition supersedes
a result when its `created_at` is at or after the trigger time of the FIRST pipeline run triggered
after the result's own `submittedAt`. `PipelineRunRepository#findTriggerTimesAfter` answers this
purely from the `pipeline_run` table, which exists for every triggered cycle regardless of whether
that cycle ever produced a batch — so the anchor-run case is no longer a blind spot. This cannot
misclassify a same-cycle row: a cycle's own dispositions are always written minutes after its own
trigger and hours before the next cycle's, so a same-cycle disposition's `created_at` always falls
strictly before "the first trigger after this result's own submittedAt" and reads as simultaneous,
never later. Checked and confirmed: two pipeline runs cannot both be in their SUBMIT phase at once —
`PipelineOrchestrator.submitPhase`'s own javadoc documents a single `AtomicBoolean` submission guard
shared by every trigger (scheduled and admin-fired); the losing trigger's run is failed outright
("Forecast batch submission dropped — another pipeline run already holds the submission guard"), so
this rule never has to reason about two overlapping submissions for the same cycle. Results in one
merge call may carry different `submittedAt` values (the brief's own hazard to check): the trigger
list is loaded ONCE from the EARLIEST submission among them, and each result's own "next trigger" is
resolved from that one in-memory list — one bulk disposition query still covers every location in
the call. Query cost, corrected and now stated precisely: ONE query in the common case per MERGE
CALL for the cache-side check (`SupersedingDispositionService#supersededLocations`), two when a
later cycle exists at all; the forecast_score-side check (`#isSuperseded`) is necessarily per
RESPONSE, not per batch — see correction 3.

**Correction 3 — `forecast_score` was still reachable by a superseded response, because "written to
no sink" was false until this round.** `ForecastResultHandler` writes `forecast_score` inside
`buildResult`/`buildWoodlandResult`/`buildBluebellResult`, which run once PER ANTHROPIC RESPONSE as
`BatchResultProcessor` consumes the Batch API's streaming result reader. That reader only learns a
batch's location set as it streams, so there is no point "before any result of the batch is parsed"
at which every location it will touch is already known — a true batch-wide hoist would need to
buffer an entire batch before writing anything, which this class does not do for any sink today.
The accepted, explained fallback: `SupersedingDispositionService#isSuperseded` runs once per
response, immediately before each of the three `forecast_score` write sites, and the synchronous
path (`handleSyncResult`) carries the identical check before its own write. Cost: one query in the
common case per response, two only when a later cycle already exists for that one submission — a
real but rare per-response cost, traded for genuine correctness over a hoist that is not achievable
with the current streaming reader. The PENDING `forecast_evaluation` row is scored unconditionally
regardless (confirmed safe, unchanged from round 13's own finding), and `api_call_log` is written
unconditionally too, so cost accounting stays complete even though a superseded response's rating
reaches no sink. `survivor_atmosphere` needed no change at all: `SurvivorAtmosphereWriter.write` is
called only from `ForecastTaskCollector` (batch collection, before any batch is even submitted) and
`ForecastService` (the synchronous engine's pre-Claude-call point) — never from
`ForecastResultHandler` — so a superseded RESULT has no bearing on it.

**New shared component**: `SupersedingDispositionService` (new class,
`service/evaluation`) — both `BriefingEvaluationService.supersededByLaterRun` (bulk, merge-level)
and `ForecastResultHandler`'s three `forecast_score` sites (single-location, per-response) delegate
to it, so the discriminator and the allow-list are defined exactly once. Round 13's
`PipelineRunRepository#existsByTriggerTimeAfter` and
`ForecastRunDispositionRepository#findSupersedingCycleTriggerTimes` (the three-entity join) are
both replaced — `PipelineRunRepository#findTriggerTimesAfter` returns every later trigger ascending
(not merely whether one exists), and the disposition queries are two new allow-listed,
`forecast_batch`-free queries (`findSupersedingDispositions` bulk, `existsSupersedingDisposition`
single-location).

New and changed tests: `SupersedingDispositionServiceTest` (new, 23 tests) — the production case and
its bulk-form sibling (both must fail against 20922233's design, confirmed by direct comparison
against that commit's actual JPQL, which allowed `EVALUATED` through and required the
`forecast_batch` join), the anchor-run shape with no `forecast_batch` row for either
`SKIPPED_TRIAGED` or `SKIPPED_STABILITY` (must fail against 20922233's join), one test per excluded
disposition category (ten, all confirmed passing against the real allow-list), same-cycle safety (no
later run exists at all, so the disposition repository is never even consulted), a retry batch
sharing its precursor's cycle, two results in one call with different `submittedAt` values (one
superseded, one not, from a single shared trigger-time load), query-count tests with precise literal
`verify()` arguments (no `any()`) for both the common and later-run-exists cases, and null-
submittedAt handling (no repository interaction at all). `PipelineRunRepositoryTest` and
`ForecastRunDispositionRepositoryTest` (both new, `@DataJpaTest` on H2, no Docker) prove the actual
JPQL at the SQL level — including a literal repeat of the production case, which fails when the
allow-list is reverted to round 13's `<> 'SKIPPED_CACHED'` filter (confirmed by temporarily reverting
the query and re-running: exactly the production-case test and the ten-excluded-categories test
fail). `BriefingEvaluationServiceTest`'s `SupersededByLaterRun` nested class was rewritten to test
ONLY the wiring (does `mergeFromBatch`/`mergeWoodlandFromBatch`/`mergeBluebellFromBatch` correctly
skip whatever `SupersedingDispositionService` reports) — the algorithm itself moved to
`SupersedingDispositionServiceTest`, so the class it used to mock
(`PipelineRunRepository`/`ForecastRunDispositionRepository` directly) is now mocked one level up
(`SupersedingDispositionService`). `ForecastResultHandlerTest` gained three tests for correction 3 —
a superseded response writes no `forecast_score` row but still scores its PENDING row and still
writes `api_call_log` (confirmed to fail when the gate is removed), a non-superseded response writes
normally, and the synchronous path carries the identical gate. Every test from rounds 1 through 13
still passes (9115 total after this round, up from 9077).
