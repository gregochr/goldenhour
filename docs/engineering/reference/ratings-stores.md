# Where a rating lives: cached_evaluation, forecast_score, forecast_evaluation

> Moved verbatim from `CLAUDE.md` on 2026-10-22 to keep the always-loaded file small.
> Read the section you need; this file is not loaded automatically.

## Where a rating lives — three stores, one of them a different engine

Reach for the wrong one and you will build something that compares two engines and calls the
difference a change. This cost a day on 2026-08-03. (`slot_atmosphere` — read below via
`SlotSignalReader`/`SlotSignals` — was called `survivor_atmosphere`/`SurvivorAtmosphere*`/
`SurvivorSignal*` until V159, 2026-09-30; a log line or a changelog entry dated before that still
says "survivor".) `slot_atmosphere` is the one table here with a retention rule: 180 days by slot
date, deleted nightly by `SlotAtmosphereCleanupJob` and guarded against shortening below its longest
reader window (see the "`slot_atmosphere` keeps 180 days" bullet above); the other three stores are
not pruned by that job.

| store | per-run history | written by | read by |
|---|---|---|---|
| `cached_evaluation` (briefing evaluation cache) | **no** — overwritten each run, ⚠️ **on the basis of SUBMISSION order, never arrival order** (round 12) — see below | `BriefingEvaluationService.writeFromBatch`, from the batch pipeline | `BriefingRegionEvaluationRollup.enrichSlot` — **this is the rating the UI displays**, keyed by location *name* — ⚠️ since the tide gate lift (2026-09-18, `docs/engineering/tide-window-plan.md` §6 Q1) each entry's `results_json` also carries `skyRating` (`BriefingEvaluationResult.skyRating` → `BriefingSlot.skyRating`, no migration): the sky visitor's own component alone, with no tide averaged in, next to the combined `rating` every other reader already used. Null on a row written before the field existed. A result served from a `forecast_evaluation` row when that row wins the precedence now carries the row's `sky_rating` too (`EvaluationViewService.toEnrichmentResult`), so the two sources agree on it; a rated row with none (pre-V164, the synchronous engine) serves null |
| `forecast_score` (V108) | **no** — `uq_forecast_score_component` is UNIQUE on (forecast_type, location, date, event); latest evaluation wins, deliberately matching `cached_evaluation` semantics — ⚠️ since round 13's "gap 2", "latest" means latest by `pipeline_run_id` ordering, never by arrival order: `ForecastScoreWriter#upsert` rejects an incoming write whose `pipelineRunId` is strictly smaller than the stored row's, see the round-13 bullet below | the Pass-2 dual write in `ForecastResultHandler` | **`ForecastDtoMapper`** (the API DTO's Claude BLUEBELL rating) and **`SlotSignalReader`** (hot-topic components INVERSION, BLUEBELL and, since V186, MIST — Claude's valley mist band on the row's `summary`, read only through `SlotSignals.effectiveMistPotential()` as the stand-in for a slot whose calculator reading was never written; read by all seven slot-signal strategies and by the "Coming up" panel's dust, inversion and mist conditions) — ⚠️ *not* a proving surface any more, whatever the older comments said. ⚠️ **The two readers now apply DIFFERENT rules, on purpose (owner decision, 2026-09-29 — the two-question rule; see the "Hot topics are recomputed LIVE" bullet above).** `ForecastDtoMapper` serves this component as a RATING (the DTO's bluebell score), so it keeps the retraction rule: a component here is evidence exactly like a rating, and a nightly Gate 4 stability skip retracts it the same way, dropping (never serving) a component whose own `evaluated_at` predates the slot's most recent stability skip, via `EvaluationViewService.isRetractedByStabilitySkip` against a `loadStabilitySkips` map it loads once per `toDtoList` call and once per single-row `toDto`. `SlotSignalReader` applies **no retraction of any kind** — it briefly did, for one day (commit c6e14cc8, reverted the next day by the owner decision above), and now returns every INVERSION/BLUEBELL component exactly as stored, whatever a later stability skip or triage stand-down has since decided about the RATING for the same slot. `slot_atmosphere` readings (dust, surge, snow, since V158 cloud inversion likelihood and since V185 valley mist likelihood, `mist_score`/`mist_scored`) were never retracted on either path — measured/forecast atmospheric INPUT, never Claude's opinion. A stale `forecast_score` row hidden behind a newer TRIAGE row is therefore not a gap on the hot-topic path — nothing there is retracted by design — and remains, as before, unaddressed on the `ForecastDtoMapper`/rating side (no fresh row is ever written for a triage stand-down the way `forecast_evaluation` always gets one) |
| `forecast_evaluation` | **yes**, insert-only and never pruned — ⚠️ **one exception: `HOURLY` rows** (the wildlife comfort table) are *replaced* per place and date by `WildlifeComfortRefreshJob` twice a day, never accumulated; no scored `SUNRISE`/`SUNSET` row is ever deleted | the batch path (`ForecastService.persistPendingEvaluation` inserts the `PENDING` row, `ForecastResultHandler` scores it in place) and the triage stand-down rows `ForecastService.fetchWeatherAndTriage` writes, and (HOURLY comfort rows only) `WildlifeComfortRefreshJob`; the synchronous engine that used to add rows on admin runs was deleted in 2026-10 | `GET /api/forecast` (the map's primary endpoint), `EvaluationViewService`, `ForecastCalibrationService`; since V186 the row also carries Claude's `mist_potential` echo (a flagged site only), served as `ForecastEvaluationDto.mistPotential` for the map badge and nothing else |

⚠️ **Two diagnostic additions to `forecast_evaluation` (V164).** `forecast_evaluation.sky_rating` is Claude's own sky
component before the tide score is averaged in (`rating` stays the combined star); it is written only by the batch result
handler, and is null for the sky-not-forecast substitution, triage rows, rows scored earlier, and
is never served on a DTO. `forecast_evaluation_prompt` (side table, keyed by `evaluation_id`, `ON DELETE CASCADE`) holds the
exact user message each batch SKY request sent, stored by `ForecastPromptStore` from the one build in
`BatchRequestFactory.buildForecastRequestAndPrompt` (woodland and bluebell store nothing), for 30 days
(`photocast.forecast-prompt.retention-days`) so a production prompt can be replayed; it is never serialised to an API response.

⚠️ **Two additions from the outcome-rating series (V177, V178; `docs/engineering/outcome-rating-plan.md`).**
`outcome_rating` is not a fourth store of FORECAST ratings: it is a reader's record of how a past sunrise or sunset looked
(`rating` 1–5, NULL for "didn't see it"; and since V187 an optional `seen` tag, what they say they saw), one row per (user, location, date, type), upsert-only, never read by the Plan tab
or the briefing, and carrying the PROVENANCE of the forecast the reader had been shown (`pipeline_run_id`,
`forecast_run_at`, `forecast_evaluation_id`, `forecast_rating`). `forecast_evaluation.scored_at` (V178) is the
AVAILABILITY instant — stamped from the clock where a row gets its rating or triage reason (`ForecastService.buildEntity`
when the row has a rating, `ForecastService.standDown(...)` for the weather-triage stand-down,
`ForecastResultHandler.scoreEvaluationRow` once and only when the validated star survived). NULL for a PENDING or ABANDONED
row, any row built with no rating and no triage reason, and every row written before V178 that V183 did not stamp (V183, 2026-10-17, gave every pre-V178 row with something to say `scored_at = forecast_run_at`, a deliberate bounded departure: the submission instant overstates availability for a batch row whose result landed after its event, accepted for the one week of rows that ages out of the rating window by mid-November), and ⚠️ **a row is a candidate for "what existed at the event"
only when it is non-null**, because `forecast_run_at` is a SUBMISSION instant (a batch row is built before its batch is
submitted and the Batch API may land it a day later), so a late batch's own row precedes the event on that column and follows
it in reality. `AsOfEventResolver` (the SERVED result if it had arrived by the event, else the latest scored-by-the-event row
not retracted by a stability skip recorded at or before it; a canopy-bearing place's star is recoverable only from the served
result) is the one answer to "what had the reader been shown", used twice: it fills the rating's provenance, and
**`GET /api/forecast` serves a PASSED slot through it** (see the Forecast endpoints), so the star a past-window chip draws is
the star the record captures. A passed slot's served row is therefore the as-of-event row, no longer the slot's latest; the
latest-row-per-slot reads above still describe an UNPASSED slot.

⚠️ **Build and prompt provenance (V182, `app_build` and `prompt_hash`) — so a drift in the calibration gate can be pinned to a
release or a prompt edit, not only to a model.** `forecast_evaluation.app_build` is the release (`AppBuild`, from the
`APP_VERSION` the deploy workflow bakes in from the git tag, `dev` when unset, cut to 40 characters) running when a row GOT its
rating or triage reason: stamped at exactly the seams `scored_at` is — `ForecastService.buildEntity` (only when the row has a
rating) and `ForecastService.standDown` (the weather-triage stand-down), and `ForecastResultHandler.scoreEvaluationRow`
inside the SAME first-score guard, so a re-processed result keeps the build that first made the row available. NULL for a
PENDING/ABANDONED row, a row with nothing to say and every row before V182. **Why the tag and not a commit sha:** production is
only ever built from a tag (`deploy.yml`), so the tag names exactly one commit, the number is already what `job_run.app_version`
(V80) records, it needs no plugin and no `.git` inside the Docker build context, and it reads in the report. ⚠️ **The value is
the release, so two commits between releases are one build** (local runs are all `dev`). `outcome_rating.app_build` is the
release running when the reader's rating was written (`OutcomeRatingStore.upsert`, replaced on a re-rate, because the rating was
made again by the build then running); it is NOT the forecast's build — the report reads the forecast row's. `prompt_hash` is
the SHA-256 (hex, `PromptHash`) of the SKY **system** prompt a batch request carried (the inland or the coastal one, so two
values exist at a time), stamped just BEFORE the batch is submitted by `ForecastPromptStore.stampPromptHashes` (before, because `submit()` publishes its pollable row before returning and the scoring handler does a full-entity save, so a post-submit stamp could be overwritten with a stale null) (one `UPDATE ... WHERE id IN` per
distinct hash, best-effort and never failing the submission) from `BatchRequestFactory.ForecastRequest.promptHash`.
⚠️ **It deliberately does NOT hash the user message**: that embeds the slot's own weather, so its hash would differ on every row
and group nothing; the system prompt changes only when someone edits it. The limit, stated: an edit to the USER-message
wording in `PromptBuilder.buildUserMessage` leaves `prompt_hash` unchanged and shows up in `app_build` only. NULL for
a triage row, a comfort row, a woodland or bluebell request and every row before V182. The two stamps can
come from different builds for one row (a batch lands up to 24 h after it was built and a deploy can fall between) — the
hash describes what was SENT, the build what SCORED it.

⚠️ **Round 12: "overwritten each run" is true, but "each run" means "each SUBMITTED evaluation," and
the row that wins is decided by submission order, never by which result happened to ARRIVE last.**
A Codex review of round 11 (#943-adjacent) found the round-10 `forced` fix incomplete: it recorded
`BriefingEvaluationResult.forced` at write time, but write order is not evaluation order.
`BatchPollingService` polls every still-`SUBMITTED` batch independently, and the Anthropic Batch API
allows up to 24h, so a batch can outlive its pipeline's internal safety timeout and complete AFTER a
NEWER cycle's batch for the same slot already wrote it — an older, delayed forced batch could then
overwrite a newer ordinary one (or vice versa) purely because it happened to finish polling later.
The `forced` mark was the first casualty found, but the investigation confirmed the RATING itself
was equally exposed — every write into this table was vulnerable, not only the exemption flag.
`BriefingEvaluationResult.submittedAt` (nullable `Instant`, `@JsonInclude(NON_NULL)`, no migration —
rides `results_json` exactly as `forced` does) is the fix: for a batch result it is the orchestrated
cycle's own `PipelineRunEntity.triggerTime` when the batch belongs to one (recoverable via the
already-existing `ForecastBatchEntity.pipelineRunId` — **no schema migration needed**, since both
columns already existed), or that batch's own `submittedAt` for an ad-hoc submission outside any
cycle; for a synchronous/admin result, the instant the evaluation call started. `BatchResultProcessor
.resolveSubmissionInstant` does the lookup once per batch and threads it through the (also new)
`ResultContext.submissionInstant`, down to `ForecastResultHandler`'s three `buildXxx` methods.
`BriefingEvaluationService.mergeFromBatch`, `mergeWoodlandFromBatch` and `recombineBluebell` (via
`mergeBluebellFromBatch`) each compare the incoming result's `submittedAt` against the currently-
stored result's for that location before writing: an incoming result strictly OLDER is rejected as
stale (logged once at INFO, nothing written); `null` on either side (a legacy row, or a result with
no instant to report) is never stale — unknown reads as safe, exactly like `forced`'s own convention.
⚠️ **The cycle's shared `triggerTime`, not the individual batch's own `submittedAt`, is the key
insight that makes OPEN_FELL recombination work at all.** Sky and bluebell are submitted as
genuinely SEPARATE Anthropic batches (`ScheduledBatchEvaluationService.submitBuckets` calls
`BatchSubmissionService.submit` once per lane), so their own `ForecastBatchEntity.submittedAt`
values almost never coincide even within one cycle — comparing those directly would make same-cycle
recombination fail almost every time. Stamping every result with the shared cycle instant instead
means a genuine same-cycle pair's two sides carry an EQUAL `submittedAt` by construction, so
`recombineBluebell` requires that equality (not merely "not stale") before averaging; a bluebell
that is newer than a stored sky entry but from a DIFFERENT cycle is written (not stale) but stands
alone rather than being wrongly blended, the same "sky hasn't arrived yet this cycle" race the class
already tolerated. `BriefingEvaluationResult.withForcedFromCombination`/`withSubmittedAtFromCombination`
both took a SECOND argument in this round (they used to read only the "newly-arrived" side, reasoning
that a same-cycle pair's two tasks always agree by construction — true in production, but a
synthetic test with the two flags genuinely differing exposed that the method itself never enforced
it); both are now a plain, explicit two-argument combination with no arrival-order reasoning left at
all. **Scope**: this fix covers `cached_evaluation` only. `forecast_score` and `slot_atmosphere`
upsert unconditionally on their natural key with no ordering check of any kind — the identical
weakness, unaddressed, named here as a follow-up rather than fixed in this round, since neither
column set is "trivial and safe" to extend without its own review. The `PENDING` `forecast_evaluation`
row-scoring seam (`ForecastResultHandler#scoreEvaluationRow`) is not exposed the same way — it scores
a row by primary key, one row per submission, so a late arrival can only ever affect its OWN row, never
overwrite a different cycle's; the pre-existing "latest row per slot" SERVE query is a separate,
already-documented `forecast_evaluation` limitation, not a new one this round created.

⚠️ **Round 13 found the class of bug went WIDER than round 12's own comparison could reach, on two
fronts — "gap 1" and "gap 2" — but round 13's OWN first cut of gap 1 was itself wrong, tested
against production and corrected in round 14.** A Codex review of round 12 found that comparing an
incoming result against whatever is currently STORED is not enough: a later cycle's Gate 4 stability
skip or weather-triage stand-down writes NO `cached_evaluation` entry to compare against at all —
only a `forecast_run_disposition` row — so a batch delayed past that later decision had nothing to
lose a staleness comparison against, and wrote its (chronologically stale) rating straight into the
cache, restoring the arrival-order bug for exactly the case the serve-time stability-skip retraction
above was built for.

⚠️ **Round 13's fix joined `forecast_batch` and allowed every disposition except `SKIPPED_CACHED` to
supersede — both wrong, proven against production on 2026-09-29.** Pipeline run 249 (INTRADAY,
trigger 14:00:00 UTC): all three Anthropic batch submissions failed with HTTP 500, so
`ScheduledBatchEvaluationService#persistCycleDispositions` anchored the cycle's 589 dispositions —
510 `EVALUATED`, 76 `SKIPPED_TRIAGED`, 3 `SKIPPED_UNKNOWN_LOCATION` — to a disposition-only "anchor
run" job_run with **no `forecast_batch` row at all** (`forecast_batch` holds zero rows for that
job_run_id; over five days, 589 of 13,120 disposition rows join to no batch at all). Round 13's
three-entity join through `forecast_batch` could not see ANY of these dispositions in either
direction, and separately, `EVALUATED`/`FORCE_EVALUATED` record only that a candidate was INCLUDED
for submission, never that a result was ever produced — the 510 `EVALUATED` rows on that failed
cycle produced exactly zero results, so treating them as superseding (round 13's actual rule,
`d.disposition <> 'SKIPPED_CACHED'`) would have rejected a perfectly good older rating in favour of
nothing, leaving the slot unrated where the stale-but-real prior rating should have kept serving.

**Gap 1, corrected (`SupersedingDispositionService`, round 14)** — a new, standalone service both
`BriefingEvaluationService.supersededByLaterRun` (the merge-level bulk check, called from
`mergeFromBatch`, `mergeWoodlandFromBatch` and `mergeBluebellFromBatch`) and
`ForecastResultHandler`'s three `forecast_score` write sites (correction 3, below) delegate to. The
rule needs no `forecast_batch` row at all: **a disposition supersedes a result when the
disposition's `created_at` is at or after the trigger time of the FIRST `pipeline_run` triggered
after the result's own `submittedAt`** — `PipelineRunRepository#findTriggerTimesAfter` answers this
purely from the `pipeline_run` table, which exists for every triggered cycle regardless of whether
that cycle ever produced a batch. A same-cycle disposition's `created_at` always falls strictly
before the next cycle's trigger time (cycles are hours apart; disposition persistence is
near-instant), so it is correctly read as simultaneous with its own result, never later — the
same-cycle safety round 13 achieved via the (now-removed) `forecast_batch` join is preserved without
it. ⚠️ **The disposition allow-list is now explicit and exactly two values** —
`ForecastRunDispositionRepository#findSupersedingDispositions`/`#existsSupersedingDisposition` query
`disposition IN ('SKIPPED_STABILITY', 'SKIPPED_TRIAGED')`, never "everything except." Every other
`DispositionCategory` value — `EVALUATED`, `FORCE_EVALUATED`, `SKIPPED_HARD_CONSTRAINT`,
`SKIPPED_NO_PROMPT`, `SKIPPED_CACHED`, `SKIPPED_PAST_DATE`, `SKIPPED_TRAVEL_DAY`,
`SKIPPED_UNKNOWN_LOCATION`, `SKIPPED_ERROR`, `SKIPPED_NO_REFRESH_NEEDED`, `SUBMISSION_FAILED`
(added after the 2026-09-29 incident itself — a candidate whose bucket failed to submit is even
less a decision against the slot than a bare `EVALUATED` row, since strictly nothing reached
Claude for it) — is excluded by construction (see `SupersedingDispositionService`'s class javadoc
for why each one is out, one at a time; `SKIPPED_HARD_CONSTRAINT` is no longer written by anything, so it
never will be a candidate). Query cost: one
cheap query in the common case (no
cycle has started since the earliest submission being checked — the ordinary, non-delayed case,
since a fresh result's `submittedAt` is recent), two when a later cycle exists at all (the same
call, plus one bulk disposition query). Results in one merge call may carry different `submittedAt`
values; the trigger-time list is loaded once from the EARLIEST of them and each result's own "next
trigger" is resolved from that one in-memory list. The `PENDING` `forecast_evaluation` row is still
scored unconditionally by `scoreEvaluationRow`, unaffected by any of this (it runs earlier, inside
`ForecastResultHandler#buildResult`, before the orchestrator ever calls the merge methods this check
lives in) — safe because `forecast_run_at` is stamped at collection time, never score time, so a
genuinely later cycle's own row already wins `EvaluationViewService#loadLatestForecasts`' per-slot
MAX comparison regardless of when an older cycle's Claude result happens to arrive late.

⚠️ **Correction 3: `forecast_score` was still reachable by a superseded response until round 14,
because it writes per-response, before the merge-level check ever runs.** `ForecastResultHandler`
writes `forecast_score` inside `buildResult`/`buildWoodlandResult`/`buildBluebellResult`, which run
once PER ANTHROPIC RESPONSE as `BatchResultProcessor` consumes the Batch API's streaming result
reader — and that reader only learns a batch's location set as it streams, so there is no point
"before any result of the batch is parsed" at which every location it will touch is already known.
A true batch-wide hoist is therefore not possible without buffering an entire batch before writing
anything, which this class does not do for any sink today. The accepted, explained fallback is
`SupersedingDispositionService#isSuperseded`, called once per response immediately before each of
the three `forecast_score` write sites — one query in the common case per response, two only when a
later cycle already exists for that one submission (a real but rare per-response cost, traded for
correctness over a true hoist). The synchronous path (`handleSyncResult`) carries the identical
check before its own write. `api_call_log` is written unconditionally regardless (cost accounting
stays complete even though a superseded response's rating reaches no sink), and
`slot_atmosphere` needs no gate here at all: `SlotAtmosphereWriter.write` is called from
exactly ONE place — inside `ForecastService.fetchWeatherAndTriage` itself, immediately after
weather is fetched and before that method's own triage checks (see the "record conditions for
every place" bullet above for why this is a single seam, not one call site per caller) — never
from `ForecastResultHandler` — so a superseded RESULT has no bearing on it; it captures measured
weather at collection time, not Claude's opinion of it.

**Gap 2** (`ForecastScoreWriter#upsert`): the identical unordered-overwrite weakness named as a
follow-up in round 12 turned out to be trivially and safely fixable after all, since
`ForecastScoreEntity` already stores the producing run's own `pipeline_run_id` directly — no
disposition join needed, and round 14's corrections to gap 1 do not change gap 2 at all. An incoming
write with a strictly SMALLER `pipelineRunId` than the stored row's is rejected (logged once at
INFO); ids are safe to compare directly because `PipelineRunEntity.id` is an autoincrement key
assigned in strict cycle-trigger order, so comparing ids IS comparing trigger times. Equal ids
overwrite as before, and a `null` on EITHER side (a legacy stored row, or an incoming sync/admin
write, which this table's own writer already documents as always `null`) is "unknown, cannot be
shown to be older" and proceeds exactly as it always has. `slot_atmosphere` remains the one
still-unaddressed member of round 12's follow-up list — `SlotAtmosphereWriter.write`
finds-or-creates by natural key with no stored run id at all to compare against, so closing it would
need its own schema change and review, out of scope here.

Two consequences worth stating plainly:

- **`forecast_evaluation` is not legacy dead weight — it is a second live engine.** The map view and
  the calibration gate both read it, and V128 (one of the newest migrations) indexes it. Do not
  "clean it up". The duplication is real and is already on the architecture review's list as the
  dual-engine consolidation; until that lands, both are load-bearing.
- **No store retains a per-location per-run history that the live pipeline writes.** Any feature
  needing "what was this rated last time" — a trend, a sparkline, a since-last-forecast diff —
  needs a new append-only sink first. `forecast_evaluation` looks like the answer and is not.
- ⚠️ **`forecast_evaluation` has stopped being a scoring table, whatever its name says.** Measured
  2026-08-03 over the preceding fourteen days: rows were written on **all fourteen**, and ratings on
  **one** (31 Jul, 207 of 268 rows; every other day zero). Roughly three quarters of the table's
  33k rows carry a null rating. It is now, in practice, a triage and base-forecast table. The batch
  pipeline does the scoring, and its two sinks (`cached_evaluation`, `forecast_score`) last wrote
  within 0.3 seconds of each other — the dual write is healthy.
- **This starves the calibration gate's old, natural-key source.** That source (a join of `forecast_evaluation` to the
  legacy `actual_outcome`, which held one real row, the owner's 13 Oct 2026 Angel sunset, copied into `outcome_rating` on 2026-10-27 before the table was dropped in V195) was deleted in audit-followups O1; the gate is now ratings-only. Worth knowing
  before anyone cites the gate as evidence of forecast accuracy. ⚠️ The gate reads readers' `outcome_rating` rows (below), which need no
  natural-key match — but it still reads `forecast_evaluation`: a rating pairs only with the one row it resolved to, and a rating whose row
  carries no star is not a pair, so the scarcity of rated rows limits it too.
- ⚠️ **A nightly Gate 4 stability skip writes no row to either store, so it retracts at serve time
  instead.** When `NightlyEligibilityPolicy` declines to re-score a slot (`SKIPPED_STABILITY`), the
  collector writes neither a `cached_evaluation` entry nor a `forecast_evaluation` row for that
  cycle — so an earlier, more eligible run's rating would otherwise go on being served forever, read
  as a live verdict long after the pipeline moved past it. `EvaluationViewService` bulk-loads each
  slot's most recent `SKIPPED_STABILITY` disposition from `forecast_run_disposition` (30-day
  retention comfortably covers the served horizon; one query per serve, never per slot) and applies
  `EvaluationViewService.isSlotRetracted` before any precedence rule runs: a stale rating and a stale
  triage row alike read as never-rated rather than as a weather stand-down. A later real evaluation
  (eligible or forced) simply outdates the skip. Excluded by construction: a region-level
  `SKIPPED_CACHED` reuse, a past-date/travel-day/error/triage skip, a row with neither a rating nor a
  triage reason (`hasSomethingToSay`, the same rule `cachedWins` already applied) UNLESS a skip is
  recorded against that exact slot (see the ⚠️ below), and the intraday cycle's
  `SKIPPED_NO_REFRESH_NEEDED` ("a later look is already guaranteed") — none of these is a decision
  against the rating. No migration; no new column. **A `forecast_score` component (INVERSION/
  BLUEBELL) is retracted by the identical skip, via the identical low-level primitive — see the
  `forecast_score` row of the table above for why its own version of the rule is simpler than this
  one, and its one known, narrower, unaddressed gap.**
  ⚠️ **Retraction is a property of the SLOT, decided once — not of whether the evidence a caller
  happened to see individually looked stale.** The first cut checked each source's own staleness
  independently (`isRetractedByStabilitySkip` against the cache, and separately against the forecast
  row) and inferred "nothing survived, so retract" only when it could actually SEE stale evidence. A
  second Codex re-review of #940 found the gap: `forecast_evaluation` is insert-only, and the
  dedup-at-source "latest row per slot" query (`loadLatestForecasts`) can return a newer, EMPTY row —
  an `ABANDONED` batch attempt, or any other `PENDING`-then-closed-out row with neither a rating nor
  a triage reason — written AFTER a real RATED row and hiding it completely from every caller. That
  empty row genuinely postdates the skip, so a per-source staleness check answers "not stale" — the
  wrong answer, because the only reason anything postdates the skip is a row that says nothing at
  all. `isSlotRetracted(cachedResult, cachedEvaluatedAt, forecastRow, latestStabilitySkipAt)` is the
  fix: a slot with a recorded skip is retracted when NO live evidence survives it at all — neither a
  cached result written after it, nor a forecast row that both postdates it AND has something to say
  — regardless of whether the caller can see the stale rated row, a newer empty row hiding it, or
  nothing. It is the ONE place every path decides this: `resolveForEnrichmentRetractionAware` (both
  `getScoresForEnrichment` and the restructured `getScoresForEnrichmentBulk`, which now resolves each
  slot through the SAME method the single-key read uses rather than a separate two-phase
  cache-then-forecast reconciliation), `mergeToView` and `ForecastController`'s raw-row filter all
  call it. `ForecastController` has no cache lookup of its own, so it calls `isSlotRetracted` with a
  null cached side — which correctly means "no live cache evidence available here" — and this closes
  a real, if narrow, behaviour gap there too: an otherwise-unscored row is now dropped (not merely
  served with a null rating) when a skip stands against it and nothing else is live, agreeing with
  `mergeToView`'s `Source.NONE`. An ordinary, never-skipped unscored row (the overwhelming majority of
  `forecast_evaluation`'s null-rating rows) is entirely unaffected — `isSlotRetracted` returns false
  immediately whenever no skip is recorded. Canopy (woodland) and bluebell slots are not exempted and
  need no carve-out: `ForecastTaskCollector` runs the Gate 4 eligibility decision for every candidate
  before it branches on `woodlandTask`/`bluebellWoodInSeason`, so a canopy or bluebell slot can carry
  a `SKIPPED_STABILITY` disposition exactly like a sky slot, and both mini-batches write their
  ratings into `cached_evaluation` via `mergeWoodlandFromBatch`/`mergeBluebellFromBatch` — the same
  store the resolver already reads.
  ⚠️ **The Plan payload needed a second piece, because it starts from a persisted tree rather than
  building one fresh.** A `BriefingSlot` read out of `daily_briefing_cache` already carries whatever
  rating the last build gave it, and `BriefingRegionEvaluationRollup.enrichSlot` correctly leaves a
  slot untouched when the resolver has nothing new to say for it (an uncovered date, an all-canopy
  region) — so a bare absent map entry cannot ALSO mean "retracted" without blanking those legitimate
  cases too. `EvaluationViewService` therefore returns `BriefingEvaluationResult.retracted(name)` — a
  `@JsonIgnore`d marker, never persisted — exactly when retraction is why nothing survived, and
  `enrichSlot` checks it before its own triage-clears-a-rating branch, clearing the slot's rating,
  sky rating, both potentials, summary and headline the same way, never by setting a triage reason.
  ⚠️ **Two accepted limits, not gaps.** The join is on location NAME (`forecast_run_disposition`'s
  denormalised snapshot), so a location renamed between its last skip and the serve misses its own
  history and keeps serving one cycle too long — safe-direction, not fixed. And "newest decision
  wins" means a hand-started or force-submitted admin evaluation made shortly before a nightly cycle
  is retracted by that cycle's own skip a few minutes later; the rule has no way to except a human's
  deliberate request, and a short admin-run window is preferred to making admin evaluations immune to
  ever being retracted once stale.
- ⚠️ **The raw `BriefingEvaluationService` cache is a store, never a read surface — every reader that
  produces something a person or admin sees goes through `EvaluationViewService`'s retraction-aware
  read instead.** Two callers read `briefingEvaluationService.getCachedScores` directly until a Codex
  review of #940 caught it: `BriefingRollupBuilder.computeRegionStats` (the best-bet advisor's
  region-level rollup — its `claudeAverageRating`/coverage figures) and
  `PipelineRunPickService.lookupAverageRating` (the `pipeline_run_pick.claude_average_rating`
  cross-run comparison snapshot, read by `PipelineRunsView`'s picks). Both read the cache **before**
  retraction is applied, so a rating a nightly stability skip had already withdrawn from the Plan
  card and the map still counted toward the advisor's pick and the run-to-run comparison — the exact
  "every surface must agree" requirement this whole feature exists for, broken one layer further in.
  The same gap is older than the stability-skip feature: a rating superseded by a newer **triage**
  row was equally still in the raw cache, uncounted nowhere else in the product. Both close the same
  way — `EvaluationViewService.getLiveScoresForEnrichment(regionName, date, targetType)`, a sibling of
  `getScoresForEnrichment` that applies the identical cached-vs-forecast-row precedence and
  stability-skip retraction and then, unlike its sibling, **filters out every `retracted()` marker**
  before returning: `getScoresForEnrichment` has to be able to say "this was here and a skip is why it
  is not any more" for `BriefingRegionEvaluationRollup.enrichSlot` to clear an embedded rating rather
  than leave it (above), but a caller with no such branch has no use for a marker whose every numeric
  field is null — it would either NPE on the rating or silently count the marker as "no opinion",
  neither a decision anyone made. `getLiveScoresForEnrichment` removes that whole class of mistake by
  construction: what comes back contains no retracted entries and no superseded ratings, exactly as
  if the affected location had never been evaluated at all. `BriefingRollupBuilder` and
  `PipelineRunPickService` now depend on `EvaluationViewService` instead of `BriefingEvaluationService`
  directly (the latter dependency is gone from both, and from `BriefingBestBetAdvisor`, which only
  ever held it to construct the rollup builder) — no circular dependency resulted, so neither needed
  `@Lazy` beyond the one already on `BriefingBestBetAdvisor`'s own `EvaluationViewService` param,
  matching `BriefingService`'s existing `@Lazy EvaluationViewService`. ⚠️ **A second, deliberate
  widening rides along, not just retraction.** Before this fix `computeRegionStats` only ever saw a
  location with a `cached_evaluation` entry; `getScoresForEnrichment` also resolves a location whose
  *only* evidence is a scored or triaged `forecast_evaluation` row, so such a location is now counted
  in the advisor's rollup and the pick snapshot too — narrowing the divergence between what the
  advisor is shown and what the Plan tab and map already serve, not merely retracting stale ratings.
  ⚠️ **The pre-existing fail-open roster-hygiene residual is unchanged, not fixed, by this move** — a
  location renamed, disabled or moved to another region since the batch wrote its cache entry still
  answers under a name no slot claims (`computeRegionStats`'s own javadoc), because
  `getScoresForEnrichment` carries that same cache-outlives-the-roster entry forward for the identical
  reason (dropping it would be an unrelated behaviour change). Two write-path readers of the raw cache
  were checked and correctly left alone: `ForecastResultHandler.hasSkyScoredEntry` asks "did *this same
  batch cycle* already write a sky-scored entry for this slot" to decide whether to derive tide as a
  fallback signal — an intra-cycle coordination question the retraction rule (which only ever compares
  across cycles) cannot answer and must not be asked to; and `EvaluationViewService`'s own
  `loadCachedEvaluations` (the DB-fallback half of `forDateRange`) feeds `mergeToView` per location,
  which is already retraction-aware, so it was never a naked reader in the first place.
- ⚠️ **A reader that covers a whole window loads it ONCE — the rule the paragraph above got wrong the
  first time.** `BriefingRollupBuilder.computeRegionStats` and `logCacheCoverage` originally called
  `EvaluationViewService.getLiveScoresForEnrichment` per region **and** event — up to
  `PlanRenderLimits.MAX_VISIBLE_EVENTS` (6) events × the region count × 2 call sites, each call
  costing three queries (the enabled roster, the latest forecast rows, the stability dispositions):
  roughly 216 queries per best-bet rollup against production's ~6 regions, where the direct in-memory
  cache read this whole feature replaced cost none. A second Codex re-review of #940 caught it.
  `EvaluationViewService.getLiveScoresForEnrichmentBulk(start, end, types)` is the fix — the
  `getLiveScoresForEnrichment` sibling of `getScoresForEnrichmentBulk`, filtering out retraction
  markers the identical way — and `BriefingRollupBuilder.loadLiveScores` calls it exactly ONCE per
  `buildRollupJson`, over the whole rollup's date range and every target type its days carry;
  `computeRegionStats` and `logCacheCoverage` both read the one pre-loaded map by
  `"regionName|date|targetType"` key, never calling `EvaluationViewService` themselves. Single-key
  `getLiveScoresForEnrichment` keeps exactly one caller, `PipelineRunPickService.lookupAverageRating`
  — at most a handful of calls per pipeline run (`persist` documents "normally 1 or 2" picks), so the
  single-key cost is the right shape there and a bulk load would trade a small, bounded cost for the
  same fixed per-call cost the bulk accessor itself carries, for no benefit. The **build path** for
  the Plan payload itself had the identical shape of bug, one query narrower: `BriefingService`'s
  `enrichWithCachedScores` call used to hand `BriefingRegionEvaluationRollup.enrich` the single-key
  `evaluationViewService::getScoresForEnrichment` as its resolver, one call per region/event — 2
  queries each before the stability-skip retraction feature added a third
  (`loadStabilitySkips`), which is what turned a pre-existing, tolerated pattern into a fix worth
  making in the same pass. `BriefingService.bulkScoreResolver` now loads
  `getScoresForEnrichmentBulk` once per build (the marker-preserving sibling, since
  `BriefingRegionEvaluationRollup.enrichSlot` still needs to see a retraction marker to clear an
  embedded rating — see above) and hands `rollup.enrich` a closure over it, the identical shape
  `ServedBriefingAssembler.reEnrichVerdicts` already used on the serve path. Per-request paths
  (`GET /api/briefing`, `GET /api/briefing/evaluate/scores`, `GET /api/forecast`) were not touched by
  either fix and still issue their existing one stability-skip query each.
- ⚠️ **The query counts just above are unaffected by the verdict-minimum-sample rule's force-
  evaluation exemption, and that is itself the result of a round-9 fix.** The rule's first two cuts
  bulk-loaded a `loadForceEvaluatedAt` disposition map alongside `loadStabilitySkips` (one extra
  query per call site — the historical "3 queries per call" shape this bullet used to describe), but
  that whole mechanism was removed: a Codex review found the underlying timestamp comparison
  provably wrong (see the Plan-screen confidence bullet's ⚠️ above), and the fix carries `forced` as
  provenance on the task/result itself rather than deriving it at serve time from any disposition —
  so `getScoresForEnrichmentBulk`/`getScoresForEnrichment` have **nothing left to bulk-load for it**
  and their query counts are back to what they were before the exemption existed. Measured now: one
  `GET /api/briefing` serve issues exactly **3** queries for scores+stability together
  (`findAllEnabled`, `loadStabilitySkips`, `findLatestRunPerSlotByLocationIds`) via the single
  `reEnrichVerdicts` call; one briefing BUILD issues **6** — the same 3 from `BriefingService
  .bulkScoreResolver`'s own call plus another 3 from `BriefingRollupBuilder.loadLiveScores`'s
  independent call for the best-bet advisor's rollup.
- ⚠️ **The verdict-minimum-sample rule's "examined" evidence (P1-A) is loaded by a SEPARATE pair of
  methods, never folded into the counts just above** — a second Codex review of #943 (P1-A, round
  2) found the first cut's own fold (stamping a synthetic `BriefingEvaluationResult.triagedByBatch`
  marker inside `getScoresForEnrichmentBulk`/`getScoresForEnrichment`, only in the branch where the
  resolver had nothing else to return) could not reach a slot production actually resolves to a
  real triage result, which is almost every batch-triaged slot (a batch triage always ALSO writes a
  real `forecast_evaluation` triage row alongside `SKIPPED_TRIAGED`) — so `examinedCount` silently
  collapsed to rated-only on the production shape the gate exists to protect (a 50-slot region with
  35 batch-triaged, 15 rated read 15-of-50, under half, INSUFFICIENT). `EvaluationViewService
  .getTriagedByBatchLocationNames`/`getTriagedByBatchLocationNamesBulk` are the fix: they read the
  same `loadTriagedByBatch` disposition-sourced set as before, but return it as a location-name set
  keyed identically to `getScoresForEnrichmentBulk`'s own map — `findAllEnabled` + `loadTriagedByBatch`,
  **2** queries — ⚠️ **and the second is written as a `NOT EXISTS` anti-join, never as
  `created_at = (SELECT MAX(...))` (2026-09-30).** As shipped in #943 it was the correlated `MAX`
  form, and `forecast_run_disposition` had no index by slot (V101 indexes `job_run_id` and
  `(disposition, created_at)` only), so Postgres — which cannot decorrelate a scalar subquery in
  `WHERE` — ran a full scan of the 30-day retention window once per outer row: **36 s per
  `GET /api/briefing`** on production-sized data, which is why on 2026-09-30 the Plan tab drew
  nothing and the map no heat field (the briefing never arrived before Cloudflare's 100 s limit;
  the chips kept their stars because `/evaluate/scores` never runs this query). The anti-join
  returns the identical rows in ~34 ms with no index; V160's slot-keyed index is belt and braces
  for any future per-slot "latest" read on this table, whichever shape it takes. The pair is
  handed to `BriefingRegionEvaluationRollup.enrich` through a brand-new, genuinely
  SEPARATE `TriagedByBatchResolver` parameter (`BriefingScoreEnricher`'s abstract method grew a third
  argument; a default 2-arg overload keeps every pre-existing caller and test compiling with an
  empty, safely-under-counting triaged set). The rollup then unions that disposition-sourced set
  with a SECOND, independent channel read directly off whatever the score resolver already returned
  for the same slot (`eval.triageReason() != null`) — needed because a hand-started or
  synchronous-engine run can triage a slot via a real `forecast_evaluation` row without ever writing
  a `forecast_run_disposition` row at all, so the disposition channel alone would miss it.
  `VerdictSampleGate.examinedCount`'s own `claudeRating() == null` guard (unchanged) still prevents
  double-counting a slot that is both rated and named by either channel. Only the two production
  sites that call `BriefingRegionEvaluationRollup.enrich` — `BriefingService.bulkTriagedResolver`
  and `ServedBriefingAssembler#reEnrichVerdicts`'s own triaged index — pay for this pair of queries;
  `BriefingRollupBuilder.loadLiveScores` (the best-bet advisor's separate rollup, which never calls
  `enrich`) has no use for triaged names and no longer pays for them either, a genuine reduction
  from what the first cut cost it. One `GET /api/briefing` serve now issues **5** queries in total
  for scores+stability+triaged (the 3 above plus the triaged pair), or **6** when any latest
  disposition is `EVALUATED`/`FORCE_EVALUATED` (the honesty filter's cycle-ended query,
  `PipelineRunRepository.findEndedDispositionJobRunIds`; the triaged pair is now `loadServeTriage`'s one
  disposition read plus the roster); one briefing BUILD issues
  **8** — the 6 above (3 + 3 from the two independent score-resolver bulk loads) plus the triaged
  pair, paid ONCE, only by `BriefingService.bulkTriagedResolver` (the build path never runs the
  cycle query: `getTriagedByBatchLocationNamesBulk` reads without it).

---
