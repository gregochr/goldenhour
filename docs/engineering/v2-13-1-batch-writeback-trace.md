# Batch Write-Back Trace — where the scored `forecast_evaluation` rating is dropped

**Read-only investigation.** No source changed. Traced from code (Boot 4 backend, `main` @ `7fabb4f` / tag `v2.11.27`).

**Verdict up front:** This is **mechanism (a)** — the batch result-handling path **never writes a
`forecast_evaluation` scored row at all.** It writes only to the briefing/plan cache
(`cached_evaluation`, via `BriefingEvaluationService.writeFromBatch`). The only code that writes a
*scored* `forecast_evaluation` row is the **synchronous** `ForecastService.evaluateAndPersist` path
(admin / legacy "Run Forecast"). So an overnight batch produces **0** scored `forecast_evaluation`
rows by construction. This is architectural and **long-standing** (true since at least 2026-04-27,
`10e9b26`), **not** a v2.13.1 regression. **Reverting v2.13.1 will not fix it.**

---

## 1. The ordered batch scored-row write path (and the batch-vs-sync writer verdict)

### What actually runs when an overnight batch completes

| # | File · method | Receives | Returns | Type carrying the rating |
|---|---------------|----------|---------|--------------------------|
| 1 | `batch/BatchPollingService.pollBatch(ForecastBatchEntity)` | a `SUBMITTED` batch row | void | — (polls SDK; on `ENDED` calls processor) |
| 2 | `batch/BatchResultProcessor.processForecastBatch(ForecastBatchEntity)` | the ended batch | void | streams `MessageBatchIndividualResponse` |
| 3 | `evaluation/ForecastResultHandler.parseBatchResponse(location, ForecastIdentity, ClaudeBatchOutcome, ResultContext)` | one parsed response | `Optional<BatchSuccess>` | **`BriefingEvaluationResult`** (inside `BatchSuccess`) |
| 4 | back in `processForecastBatch`: groups `BatchSuccess.result()` into `Map<cacheKey, List<BriefingEvaluationResult>>` | — | — | `BriefingEvaluationResult` |
| 5 | `ForecastResultHandler.flushCacheKey(cacheKey, List<BriefingEvaluationResult>)` | one cache-key group | void | `BriefingEvaluationResult` |
| 6 | `BriefingEvaluationService.writeFromBatch(cacheKey, results)` | the group | void | **writes `cached_evaluation` (briefing/Plan cache) — NOT `forecast_evaluation`** |

**The path terminates at `cached_evaluation`.** There is no hop from here to `forecast_evaluation`.
Confirmed mechanically:

```
$ grep -rn "forecast_evaluation|ForecastEvaluationRepository|setRating" service/batch/
  → (no matches in the entire batch package)
```

The batch package never references the `forecast_evaluation` table or repository.

### Where the sync `Scored(eval.withRating(...))` payload goes — a SEPARATE writer

- `ForecastResultHandler.handleSyncResult(...)` (the **only** producer of `EvaluationResult.Scored`)
  is invoked exclusively by `EvaluationServiceImpl.evaluateNowForecast(...)` — a **synchronous live
  Claude call**, not the batch transport.
- `ForecastService.evaluateAndPersist(preEval, jobRun)` calls
  `engineEvaluationService.evaluateNow(task, …)`, switches on the result
  (`case EvaluationResult.Scored s -> (SunsetEvaluation) s.payload();`), builds the entity, and calls
  `repository.save(entity)` — **this is the one and only writer of a scored `forecast_evaluation` row**
  (`ForecastService.java:443`, `:454`).
- Its callers are all **synchronous**: `ForecastCommandExecutor` lines 535 / 585 (admin "Run
  Forecast" / legacy command path). The nightly batch pipeline never calls `evaluateAndPersist`.

**Verdict: batch and sync do NOT share the final `forecast_evaluation` writer.** They are two disjoint
systems:

```
SYNC  (admin "Run Forecast"):
  ForecastCommandExecutor → ForecastService.evaluateAndPersist
    → EvaluationService.evaluateNow (LIVE call) → handleSyncResult → Scored(eval.withRating(r))
    → repository.save(entity w/ rating)         ===> forecast_evaluation  (SCORED rows)

BATCH (overnight pipeline):
  ForecastTaskCollector → BatchSubmissionService.submit → [Anthropic Batch API]
    → BatchPollingService → BatchResultProcessor.parseBatchResponse → BriefingEvaluationResult
    → flushCacheKey → BriefingEvaluationService.writeFromBatch  ===> cached_evaluation  (briefing/Plan)
                                                                  ===> forecast_evaluation: NEVER
```

### Where the 325 triaged-null `forecast_evaluation` rows come from (closes the loop)

The nightly batch **collector** writes those nulls — *before* submission, not from the result:

- `batch/ForecastTaskCollector` (lines 348, 690) calls `forecastService.fetchWeatherAndTriage(...)`.
- Inside `fetchWeatherAndTriage`, the triage branches write a **null-rating** entity directly:
  `ForecastService.java:367-373` (weather triage) and `:398-404` (tide-alignment triage):
  `SunsetEvaluation emptyEval = new SunsetEvaluation(null, null, null, null); … repository.save(entity);`
- Survivors (not triaged) return `ForecastPreEvalResult(false, …)` with **no** `forecast_evaluation`
  write, and are submitted to the batch — whose result only ever reaches `cached_evaluation`.

So `forecast_evaluation` receives **only** the triaged-out nulls (325) and **zero** scored rows — an
exact match to the production numbers.

---

## 2. The exact drop point — mechanism (a)

**Mechanism (a): the batch path never calls a scored-row writer.** It parses into
`BriefingEvaluationResult` for the briefing/Plan cache; the code that would write `forecast_evaluation`
scored rows is **not on the batch path at all** (it lives only on the synchronous
`evaluateAndPersist` path).

Precise locations:

- **The rating is computed but routed to the wrong store:**
  `ForecastResultHandler.parseBatchResponse` — `ForecastResultHandler.java:147-159`. It computes
  `combinedRating`, validates it, and packs it into a **`BriefingEvaluationResult`** (line ~155),
  returned as `BatchSuccess`. The rating is *not lost here* — it is deliberately written to the
  briefing cache, never to `forecast_evaluation`.
- **The terminal write that is briefing-only:**
  `ForecastResultHandler.flushCacheKey` → `briefingEvaluationService.writeFromBatch`
  (`ForecastResultHandler.java:176`). No `forecast_evaluation` save exists on this path.
- **The missing seam:** there is **no** call to `ForecastService.evaluateAndPersist`,
  `repository.save` of a `ForecastEvaluationEntity`, or any `forecast_evaluation` write anywhere in
  `BatchResultProcessor` or `ForecastResultHandler.parseBatchResponse`. Verified by grep over the
  whole `service/batch/` package (no matches) and by inspection of `parseBatchResponse`.

Not (b): the rating is parsed correctly and is non-null in the batch path — it just goes to
`cached_evaluation`. Not (c): no swallowed persistence exception; the null `forecast_evaluation` rows
are written deliberately at triage time, not as a failed partial. Not (d): see §4 — the v2.13.1
combiner is on this path but only feeds the briefing `BriefingEvaluationResult`, and on the sync path
it is field-equal to `eval.rating()`; it neither creates nor removes a `forecast_evaluation` write.

---

## 3. When batch scored-row writing last worked (git + prod)

**It never wrote scored `forecast_evaluation` rows in the observable window.** The batch result path
has terminated at `briefingEvaluationService.writeFromBatch` (briefing cache) continuously:

```
$ git grep -n "writeFromBatch|forecast_evaluation|EvaluationResult.Scored" 10e9b26~1 -- '*BatchResultProcessor*'
  → only briefingEvaluationService.writeFromBatch(...)   (line 357, 2026-04-27 parent)
```

i.e. even **before** the ResultHandler refactor (`10e9b26`, 2026-04-27) the batch path wrote only the
briefing cache. No `forecast_evaluation`, no `Scored`.

Commits touching the batch persist path, 2026-04-25 → 2026-06-03 (newest first):

| Commit | Date | What it did | Relevant to the drop? |
|--------|------|-------------|-----------------------|
| `b840129` | Jun 2 | v2.13.1 visitor/combiner | No — combiner feeds briefing result on batch path; field-equal on sync. |
| `eb8aea7` | — | propagate jobRunId for disposition writes | No (disposition rows, not forecast_evaluation). |
| `7334f55` | — | disposition breakdown endpoint | No. |
| `5e4b6c6` | — | persist per-candidate disposition (V101) | No (`forecast_run_disposition`, not `forecast_evaluation`). |
| `c1336f7` | — | persist/surface Claude headline on SSE path | No. |
| `a7b1d0b` | **May 23** | **Gate 2 — Claude headline field** | **No** — see below. |
| `5c987bb` | — | route `evaluateAndPersist` through `evaluateNow` | Touches the **sync** scored writer only. |
| `87ab668` | — | `WriteTarget` enum + dispatch | Sync-path write targeting; batch unaffected. |
| `10e9b26` | **Apr 27** | route `BatchResultProcessor` through `ResultHandler` | Batch already briefing-only before and after. |
| `e9d6592` | — | introduce `ResultHandler` strategy | Same. |

**The `headline` commit `a7b1d0b` is not the cause.** Its `ForecastResultHandler` change is +6 lines
adding `eval.headline()` into the `BriefingEvaluationResult` constructor; it did not touch rating
mapping and did not add or remove any `forecast_evaluation` write.

**The 4-row anomaly on May 17** is consistent with a one-off **synchronous** admin "Run Forecast"
(`ForecastCommandExecutor` → `evaluateAndPersist` → scored `forecast_evaluation` rows) on that day —
the only route that writes scored rows. It is not evidence that the batch path ever worked.

**Timeline statement:** Batch scored-row writing to `forecast_evaluation` *never demonstrably worked*
in the April–June window — there is no code path from a batch result to a scored `forecast_evaluation`
row. The persist-path commits since late April refactored structure (ResultHandler dispatch, WriteTarget,
visitor) but none added the missing batch→`forecast_evaluation` write. The most likely "breaking change"
is therefore **architectural, predating the window**: the overnight pipeline was wired to populate the
briefing/Plan cache (`cached_evaluation`) and the legacy `forecast_evaluation` scored-row write was
never carried onto the batch transport.

---

## 4. Revert-relevance verdict

**Would reverting v2.13.1 (`b840129` + `4ec6fe8`) fix the batch outage? → NO.**

- The outage is mechanism (a): batch results are written to `cached_evaluation`, and no code writes a
  scored `forecast_evaluation` row on the batch path. The v2.13.1 combiner does not create or remove
  that write. On the batch path the combiner output flows into the `BriefingEvaluationResult` (briefing
  cache); on the sync path it flows into `Scored(eval.withRating(...))`. Reverting it restores a direct
  `eval.rating()` read on the **sync** path and leaves the **batch** path exactly as broken.
- A revert would burn a deploy without touching the cause.

**Is the combiner-null-propagation a separate must-fix? → It is a latent structural risk, but it is
NOT an active bug in v2.13.1, and NOT this outage.**

- `RatingCombiner.combine` returns `null` only when no applied visitor yields a score. The only
  registered visitor is `SkyVisitor`, whose `appliesTo → true` and `evaluate` returns
  `OptionalInt.of(rating)` whenever `rating != null`. So for v2.13.1 `combine(...) == eval.rating()`
  exactly (single visitor, field-equal) — the Part A golden masters and the commit's equivalence tests
  confirm this. It neither improves nor worsens the null case versus the pre-visitor direct read.
- It becomes a real hazard the moment a *second* visitor is added (averaging could null/observably
  shift a rating). Worth hardening (e.g. make `combine` fall back to `eval.rating()` when empty, and
  never null a present rating), but that is forward-looking, not the fix for 432→0.

### What the fix actually is

**"Repair the batch writer," not "revert v2.13.1."** Two genuinely separate concerns:

1. **The outage (do this):** wire the batch result path to persist a scored `forecast_evaluation` row
   for each succeeded survivor — i.e. give `BatchResultProcessor` / `parseBatchResponse` a route to
   `ForecastService` (build + save a `ForecastEvaluationEntity` with the combined rating), mirroring
   what `evaluateAndPersist` does on the sync path. **First settle the design question:** is
   `forecast_evaluation` still the intended store for batch scored results, or has the product moved
   reads to `cached_evaluation` (briefing/Plan) and is `forecast_evaluation` now legacy? The empirical
   "scored rows expected nightly" framing says `forecast_evaluation` must be populated — confirm before
   building.
2. **The combiner null risk (optional, defer):** harden `RatingCombiner` so it can never null a
   present rating once multiple visitors exist. Independent of the outage; no deploy urgency.

---

## Appendix — commands run

```bash
# Batch entry → result → write
sed/cat BatchPollingService.java BatchResultProcessor.java ForecastResultHandler.java
grep -rn "forecast_evaluation|ForecastEvaluationRepository|setRating" service/batch/        # → empty
grep -n "EvaluationResult.Scored|repository.save|new SunsetEvaluation(null" ForecastService.java
grep -rn "fetchWeatherAndTriage" service/                                                    # collector @348/690
grep -rn "evaluateAndPersist|persistCannedResult" service/                                   # ForecastCommandExecutor only

# History
git log --oneline --since=2026-04-25 --until=2026-06-03 -- '*BatchResultProcessor*' '*ForecastResultHandler*' '*ForecastService.java' '*BatchPolling*' '*ForecastDisposition*'
git show a7b1d0b --stat                                                                       # headline: +6 in ForecastResultHandler, no rating-map change
git grep -n "writeFromBatch|forecast_evaluation|EvaluationResult.Scored" 10e9b26~1 -- '*BatchResultProcessor*'   # briefing-only pre-refactor
```
