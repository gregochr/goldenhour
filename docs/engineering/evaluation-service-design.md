# EvaluationService Design (v2.12.3 baseline)

**Status:** Design only — Pass 3.1 of the v2.12 forecast evaluation consolidation programme. No code changes; this document is the blueprint for Pass 3.2 (engine + scheduled migration) and Pass 3.3 (admin migration + SSE retirement).

**Pre-reads:** `forecast-evaluation-architecture.md` (Pass 1 + 2.5), `test-improvement-standards.md`, the Pass 2 primitives in `service.evaluation`, and the integration tests in `integration/`.

---

## Executive summary

The unified `EvaluationService` proposed here is a thin orchestration layer that turns a **list of `EvaluationTask` records + a `BatchTriggerSource`** into a submitted batch and routes its eventual results to a per-task-type `ResultHandler`. Pass 2 already extracted submission, request building, custom IDs, and cache keys; Pass 3 wires those primitives behind a single facade and lets aurora and forecast share the engine through a sealed interface (`EvaluationTask.Forecast` / `EvaluationTask.Aurora`) instead of `instanceof` branches in the engine itself. **Single-location batch is too slow** — the production poller is 60 s, so a one-request batch takes ~120 s end-to-end before results are visible. The recommendation for v2.12 is therefore **path (b)** for SSE: retire only multi-location SSE, replace single-location admin triggers with a direct (non-batch) Anthropic call wrapped by the same observability scaffolding. **`ForecastCommandExecutor` cannot be deleted in v2.12** — it is the sole writer of `forecast_evaluation`, which `GET /api/forecast` (the main React forecast view) reads on every request. Plan: keep the executor in v2.12 (route it through `EvaluationService` for observability parity in Pass 3.3), and tackle reader migration (`forecast_evaluation` → `cached_evaluation` or a successor) as a separate v2.13 programme. Pass 3.2 lands the engine + scheduled callers; Pass 3.3 migrates admin endpoints and retires multi-location SSE without touching the main forecast read path.

---

## Section 1: Single-location batch UX feasibility

### Current single-location SSE behaviour

There is **no dedicated single-location SSE endpoint today**. The closest equivalents:

- `GET /api/briefing/evaluate` (`BriefingEvaluationController:71-82`) — accepts `regionName`, runs all eligible locations in the region **sequentially** in `BriefingEvaluationService.evaluateRegion()` (`:148-253`), emitting one `location-scored` event per location after each Claude call returns.
- `POST /api/forecast/run` and `/run/{very-short|short|long}-term` (`ForecastController:226-295`) — admin endpoints that fire the `ForecastCommandExecutor` async and return 202; progress is reported via `RunProgressTracker` over a separate SSE channel, not by streaming Claude output.

Per-location SSE latency is dominated by one synchronous `AnthropicApiClient.createMessage(...)` call (`ClaudeEvaluationStrategy:139`) plus the wrapping retry/circuit-breaker (`application-prod.yml:70-75`: 4 retries, 1–30 s backoff). Empirically Haiku non-streaming completion lands at **~5–15 s per location** for the production prompt; Sonnet is ~10–25 s. `MetricsLoggingDecorator` already records `durationMs` in `api_call_log` so per-call timing is recoverable from production data without new instrumentation.

### Anthropic batch API behaviour for single requests

The Anthropic batch API does **not** prioritise small batches. Anthropic's published SLA is "completes within 24 hours"; in practice, real-API runs of `ForecastBatchPipelineRealApiE2ETest` (the daily cron smoke at `.github/workflows/real-api-smoke.yml:14`) typically finish well under 5 minutes for a single Haiku request, but the dominant cost in the **observable** end-to-end isn't the Anthropic side — it's our polling cadence.

`BatchPollingService` is registered as a `FIXED_DELAY` job at **60 000 ms** with a 30 000 ms initial delay (`V73__forecast_batch.sql:32-36`). For a one-request batch:

- `T+0` — submit returns `msgbatch_*`
- `T+~30 s` — first poll, batch usually still `in_progress`
- `T+~90 s` — second poll, batch likely `ended`, `BatchResultProcessor` fetches results and writes the cache
- `T+~90–120 s` — `cached_evaluation` row visible to readers

Even if the Anthropic side completes in 15 s, **the user cannot see the result until the next poll fires**. Lowering the polling interval helps but is bounded — sub-30 s polling is wasteful for the bulk overnight batch and risks aggressive 429s on retrieve.

The `ForecastBatchPipelineRealApiE2ETest` itself uses `Awaitility.atMost(30 min).pollInterval(15 s).pollDelay(15 s)` (`ForecastBatchPipelineRealApiE2ETest:192-197`). The 30-minute upper bound is pessimistic for queue/cluster slack, not a typical figure; the 15 s polling is a test-time speedup, not production behaviour. Production reflects the V73 seed: 60 s fixed delay.

### Empirical signal

There is **no production telemetry** on single-request batch latency today, because production batches are large (~400+ requests overnight). The daily cron smoke is the only structured source. To turn it into a usable signal, log batch wall-clock at four points (submit, first-poll-not-done, first-poll-done, results-fetched) into `api_call_log` or a new `forecast_batch.first_poll_at` column, and aggregate over 7+ daily smoke runs.

### Recommendation: (c) with a scoped twist

**1-request batch is too slow for an interactive single-location admin trigger.** ~90–120 s is materially worse than SSE's ~10 s and degrades the admin UX (and any future "evaluate this location now" map popup) for no benefit beyond batch's 50 % cost discount.

The recommendation is to retire **multi-location SSE** as planned but to keep a **single-location synchronous evaluation path** that calls the Anthropic Messages API (not the batch API) directly through `EvaluationService`:

```
EvaluationService.evaluateNow(EvaluationTask task)  // synchronous, returns EvaluationResult
EvaluationService.submit(List<EvaluationTask> tasks, BatchTriggerSource trigger)  // async, batch
```

Both call sites share the same `EvaluationTask` model, the same `PromptBuilder` selection, the same `RatingValidator`, and the same `api_call_log` / `evaluation_delta_log` writes — the only divergence is "synchronous Messages API" vs. "Batch API + polling". This preserves the SSE-retirement decision in spirit (no more streaming-emitter churn) while preserving the latency the admin UX needs. The synchronous path is also what aurora real-time uses today (`AuroraOrchestrator.scoreAndCache()`), so wiring this in earns observability for that path too (Pass 1 §6 observability gap).

If empirical data later shows 1-request batches consistently completing under 30 s end-to-end (e.g. by tightening the poller for small batches), reverting single-location to batch is a one-line change inside `EvaluationService.evaluateNow`. Until then, keep it synchronous.

---

## Section 2: ForecastCommandExecutor deletion feasibility

### What writes to `forecast_evaluation`

Exactly **one method** writes to the table: `ForecastService.evaluateAndPersist(preEval, jobRun)` (`ForecastService:418`). It is called from:

| Caller | File:Line | Path |
|---|---|---|
| Sentinel pre-eval | `ForecastCommandExecutor:547` | Path 6 (admin command) |
| Full-evaluation pipeline | `ForecastCommandExecutor:597` | Path 6 (admin command) |
| Single-location SSE | `BriefingEvaluationService:596` | Path 1 (briefing SSE) |

`BatchResultProcessor` does **not** write to `forecast_evaluation` (verified — `grep` for `ForecastEvaluation` and `forecastEvaluation` in that file returns nothing). Batch results land in `cached_evaluation` only.

If both Path 1 (SSE) and Path 6 (executor) are retired, **nothing writes to `forecast_evaluation` ever again**.

### What reads from `forecast_evaluation`

The table is a primary read source for the production app:

| Reader | File:Line | Purpose |
|---|---|---|
| `ForecastController.getForecasts()` | `ForecastController:111-121` | **`GET /api/forecast` — the main React colour-forecast view** (T-7 → T+5 timeline) |
| `ForecastController.getHistory()` | `ForecastController:136-160` | `GET /api/forecast/history` |
| `ForecastController.getComparisons()` | `ForecastController:357` | `GET /api/forecast/compare` — convergence over time, per-run granularity |
| `EvaluationViewService.forDateRange()` | `EvaluationViewService:149` | Briefing merge — fallback when cache is missing |
| `EvaluationViewService.getScoresForEnrichment()` | `EvaluationViewService:230` | Map enrichment fallback |
| `OptimisationSkipEvaluator` | `:96-98`, `:121-123` | `SKIP_LOW_RATED`, `SKIP_EXISTING`, `FORCE_STALE` strategies (need per-run history) |
| `BluebellHotTopicStrategy:83` | | Seasonal scoring driven by per-location rows |
| `KingTideHotTopicStrategy:279` | | Tide-aligned counts |

The deepest issue is `GET /api/forecast`. Today it returns **`ForecastEvaluationDto`** (the role-aware DTO with directional and basic-tier scores). The full atmospheric/directional payload — `solar_low_cloud`, `inversion_score`, `surge_total_metres`, `bluebell_score`, `tide_state`, `dew_point_celsius`, ~40 columns total — lives **only** in `forecast_evaluation`. `cached_evaluation` is a per-region aggregated `results_json` blob (region/date/targetType → `List<BriefingEvaluationResult>` of name/rating/scores/summary). The cache table cannot replace `forecast_evaluation` without either widening its schema dramatically or rebuilding the API around a different shape.

### Schema diff (column-level)

| Concern | `forecast_evaluation` | `cached_evaluation` |
|---|---|---|
| Granularity | per (location, date, targetType, run) | per (region, date, targetType) — single row, multiple results inside JSON |
| Run history | yes — multiple rows per slot | no — last write wins |
| Atmospheric data | full (cloud, visibility, wind, AOD, PM2.5, dust, dew point, etc.) | none |
| Directional cloud | full (solar/antisolar/far_solar low/mid/high) | none |
| Cloud approach risk | full (V51 columns) | none |
| Tide data | full (state, next high/low, aligned) | none |
| Storm surge | full (V61 columns) | none |
| Inversion | full (V66 columns) | none |
| Bluebell | full (V84 columns) | none |
| Triage reason / message | yes (V96) | per-result inside JSON |
| Basic-tier scores | yes (V48) | inside JSON |
| Evaluation model | per row | implicit via `source` |
| Region name | derived via FK to `location.region` | denormalised |
| Region-level cache key | not applicable | `region\|date\|targetType` |

### Recommendation: (c) with deferral — preserve the executor in v2.12, migrate readers in v2.13

Of the three options:

- **(a) delete entirely** — would break `GET /api/forecast` and three optimisation strategies. Not viable for v2.12.
- **(b) preserve and unify observability** — the realistic v2.12 outcome.
- **(c) migrate readers first, then delete** — the right end-state, but the migration is a multi-week programme (schema redesign of either `cached_evaluation` or a successor "forecast slot" table; rewrite of `EvaluationViewService`, three optimisation strategies, two hot-topic strategies, three controller endpoints; data backfill).

The pragmatic split:

- **v2.12 (Pass 3.3):** keep `ForecastCommandExecutor` alive but route it through `EvaluationService.submit(..., BatchTriggerSource.ADMIN)` for the four admin "Run forecast" endpoints. Wire `api_call_log` and `evaluation_delta_log` from the unified service (closing the Pass 1 §6 gap automatically). Stop calling `evaluateAndPersist` from the SSE path because Pass 3.3 retires SSE; the executor remains the sole `forecast_evaluation` writer.
- **v2.13 (separate programme):** redesign the `forecast_evaluation` reader contract — either by widening `cached_evaluation` (or its successor) into a per-location slot table, or by re-projecting `forecast_evaluation` reads onto `cached_evaluation + locations` joins. After that lands, `ForecastCommandExecutor` and the table can be deleted.

This recommendation explicitly **does not match Pass 3.1's "delete in 3.3" preface in the prompt**. The investigation evidence — the executor is the only writer and the table is read by the primary React forecast view — does not support deletion in v2.12. Flagging this as a scope change in §"Recommendations for Pass 3.2+".

---

## Section 3: Aurora vs forecast — common ground and divergences

### Aurora batch path

`ScheduledBatchEvaluationService.submitAuroraBatch()` (`:275-287`, registered as `aurora_batch_evaluation` in `:202`). Cron seed: `0 30 3 * * *` daily, `PAUSED` by default with `config_source=aurora.enabled` (V73 seed). Trigger flow:

1. Skip if `auroraBatchRunning.get() == true` (concurrent guard).
2. Fetch NOAA SWPC data; derive alert level via `AuroraOrchestrator.deriveAlertLevel()` (`:183-205`).
3. **Skip if `level == QUIET`** (`:645-648`).
4. Bortle threshold by alert level (MODERATE → ≤4, STRONG → ≤5).
5. Weather triage (`weatherTriageService.triage`) — drop locations with > 75 % cloud across the lookahead window.
6. Build single batch request with all viable locations in one prompt, custom ID `au-{alertLevel}-{date}` (`CustomIdFactory.forAurora`).
7. `batchSubmissionService.submit(List.of(request), BatchType.AURORA, BatchTriggerSource.SCHEDULED, ...)`.
8. Result handling: `BatchResultProcessor.processAuroraBatch()` (`:385-516`) parses via `ClaudeAuroraInterpreter.parseBatchResponse()`, applies 1★ fallback for triaged locations, writes to **`AuroraStateCache` (in-memory only)** at `:493`. **No DB persistence from the batch path.**

### Aurora real-time path

`AuroraPollingJob` (`:84-85`, registered as `aurora_polling`) — fixed-delay 5 min, gated by `aurora.enabled` and night-only (`isDaylight()` check, `:174-182`). Two sub-paths:

- `AuroraOrchestrator.runForecastLookahead()` — daytime check of tonight's Kp forecast.
- `AuroraOrchestrator.run()` — synchronous Claude call via `ClaudeAuroraInterpreter.interpret()` (no batch). Result lands in `AuroraStateCache`.

The state machine in `AuroraStateCache` (IDLE → MONITORING → MODERATE/STRONG) suppresses duplicate notifications at the same level. Critically the cache is **shared** between both paths — daytime forecast NOTIFYs prevent nighttime real-time double-firing.

**Aurora real-time has zero observability today** (Pass 1 §6) — no `job_run`, no `api_call_log`. This is a fixable side-benefit of running it through `EvaluationService.evaluateNow()` (synchronous).

### Aurora result schema (vs forecast)

| Aspect | Forecast | Aurora |
|---|---|---|
| Output JSON | `{rating: 1-5, fiery_sky: 0-100, golden_hour: 0-100, summary: ...}` | `[{name, stars: 1-5, summary: ≤120 chars, detail: bullet list}, ...]` per location |
| Star rating field | `rating` | `stars` |
| Numeric scores | yes (two: fiery, golden) | no |
| Multi-location per request | one per request | all viable locations in one request |
| `RatingValidator` | applied (every path) | not applied |
| Result destination | `cached_evaluation` (briefing JSON) + `forecast_evaluation` (per location) | `AuroraStateCache` (in-memory, ephemeral) |
| Manual admin trigger persistence | n/a | `aurora_forecast_result` table — but written by `AuroraForecastRunService`, **not** by the batch path |
| Freshness rule | per-stability TTL (UNSETTLED 6h / TRANSITIONAL 12h / SETTLED 24h, `FreshnessResolver`) | none — runs whenever cron fires and level ≥ MINOR |
| Model selection | `BATCH_NEAR_TERM` / `BATCH_FAR_TERM` split, plus `SHORT_TERM` for SSE | single `AURORA_EVALUATION` model |
| Pre-submit gates | PAST_DATE, CACHED, VERDICT, UNKNOWN_LOCATION, TRIAGED, STABILITY, ERROR, PREFETCH_FAILURE | ALERT_LEVEL ≠ QUIET, BORTLE, TRIAGED, STATE_MACHINE |

### Common ground (already shared after Pass 2)

- Anthropic batch API client (`AnthropicClient`) — both call `messages().batches().create()` via `BatchSubmissionService.submit`.
- Submission persistence (`forecast_batch` table, `BatchType` enum already distinguishes `FORECAST`/`AURORA`).
- Polling and result fetching (`BatchPollingService` + the `ENDED` poll).
- `BatchResultProcessor` already dispatches: `processForecastBatch()` vs `processAuroraBatch()` based on `BatchType`.
- `JobRunService` for batch-run bookkeeping.
- `CustomIdFactory.parse()` — sealed `ParsedCustomId.Forecast` / `Aurora` (already present, Pass 2).

### Genuine divergences (engine must respect)

- **Result destination**: forecast → `cached_evaluation` (per-region JSON); aurora → `AuroraStateCache` (process-local, ephemeral).
- **Prompt builder**: forecast → `PromptBuilder` / `CoastalPromptBuilder`; aurora → `ClaudeAuroraInterpreter`.
- **Output schema**: dispatched by call site; the engine cannot use a single `parseEvaluation`.
- **Pre-submit gating**: completely different gates; forecast has stability + freshness, aurora has alert-level + Bortle + state-machine.
- **Trigger cadence**: forecast batches twice daily, aurora event-driven (only when level ≥ MINOR).
- **Custom ID structure**: forecast keys are per-location; aurora is per-(alertLevel, date) — one custom ID per batch, not per location.
- **Single-prompt vs multi-prompt batches**: forecast = N requests in batch (one per location); aurora = 1 request whose prompt lists all locations.

### Engine implication

The engine cannot directly know about prompt builders, cache writers, or pre-submit gates. The clean factoring is:

- A **`PromptBuilderStrategy`** per task type (`ForecastPromptBuilderStrategy`, `AuroraPromptBuilderStrategy`).
- A **`ResultHandler`** per task type, written by Pass 3.2.
- A **per-task-type pre-submit filter** (the existing `OptimisationSkipEvaluator` for forecast, a new `AuroraSubmitGate` for aurora) — the engine merely accepts an already-filtered task list, never re-applies gates.

This keeps `EvaluationService` ignorant of "what is forecast" and "what is aurora"; it only sees `EvaluationTask`s and dispatches by sealed-type pattern matching at well-defined seams.

---

## Section 4: EvaluationService interface design

### Overview

```
EvaluationService (engine) ── submit() / evaluateNow() ──┐
   ├─ uses BatchRequestFactory + BatchSubmissionService (batch path)
   ├─ uses AnthropicApiClient.createMessage           (synchronous path)
   ├─ writes api_call_log + evaluation_delta_log via JobRunService
   └─ emits results to ResultHandler<T extends EvaluationTask>
```

### EvaluationTask sealed hierarchy

```java
public sealed interface EvaluationTask
        permits EvaluationTask.Forecast, EvaluationTask.Aurora {

    /** Used to build the Anthropic custom ID. */
    String taskKey();

    /** Used for diagnostic logging only — not for engine dispatch. */
    String displayLabel();

    record Forecast(
            LocationEntity location,
            LocalDate date,
            TargetType targetType,
            EvaluationModel model,
            AtmosphericData data
    ) implements EvaluationTask {
        public String taskKey() {
            return location.getId() + "/" + date + "/" + targetType;
        }
        public String displayLabel() {
            return location.getName() + " " + date + " " + targetType;
        }
    }

    record Aurora(
            AlertLevel alertLevel,
            LocalDate date,
            EvaluationModel model,
            List<AuroraLocationContext> locations,
            AuroraSpaceWeather spaceWeather,
            TriggerType triggerType
    ) implements EvaluationTask {
        public String taskKey() {
            return alertLevel.name() + "/" + date;
        }
        public String displayLabel() {
            return "Aurora " + alertLevel + " " + date;
        }
    }
}
```

The engine **never inspects the inner fields** of either record. It only calls `taskKey()` (for custom ID) and uses `instanceof` / `switch` once — at the dispatch seam to a `PromptBuilderStrategy` or `ResultHandler`.

### Public interface

```java
public interface EvaluationService {

    /**
     * Asynchronous batch path. Builds requests via per-task-type strategies,
     * submits via BatchSubmissionService, returns a handle. Results land in
     * the appropriate ResultHandler when the poller sees ENDED.
     */
    EvaluationHandle submit(List<EvaluationTask> tasks, BatchTriggerSource trigger);

    /**
     * Synchronous path for single-task evaluations that need low latency
     * (admin "evaluate now", aurora real-time). Uses the Anthropic Messages
     * API (not batch). Writes the same api_call_log / evaluation_delta_log
     * as the batch path; the only difference is sync vs async.
     */
    EvaluationResult evaluateNow(EvaluationTask task, BatchTriggerSource trigger);
}

public record EvaluationHandle(
        Long jobRunId,
        String batchId,        // null when no requests were submitted
        int submittedCount
) { }

public sealed interface EvaluationResult
        permits EvaluationResult.Scored, EvaluationResult.Skipped, EvaluationResult.Errored {
    record Scored(Object payload) implements EvaluationResult { }   // payload type is task-handler-specific
    record Skipped(String reason) implements EvaluationResult { }
    record Errored(String errorType, String message) implements EvaluationResult { }
}
```

The engine returns a structured `EvaluationHandle` for batch and a structured `EvaluationResult` for sync. Status-checking and result-streaming for batch handles is **not** part of the engine — that's `BatchPollingService` + `BatchResultProcessor`'s job, which already do it. A frontend that wants to know "is my batch done" polls a new admin endpoint that reads `forecast_batch` directly. The engine stays narrow.

### Per-task-type strategies (the seams)

```java
interface PromptBuilderStrategy<T extends EvaluationTask> {
    BatchCreateParams.Request buildBatchRequest(T task);
    MessageCreateParams       buildSyncRequest(T task);   // for evaluateNow()
}

interface ResultHandler<T extends EvaluationTask> {
    /** Called by BatchResultProcessor when an ENDED batch is being processed. */
    void handleBatchResult(T task, ClaudeBatchOutcome outcome);

    /** Called by EvaluationService.evaluateNow() after a sync call. */
    void handleSyncResult(T task, ClaudeSyncOutcome outcome);
}
```

`ClaudeBatchOutcome` and `ClaudeSyncOutcome` are simple records carrying succeeded/errored status, raw text, token counts, error type/message — i.e. the canonical "Claude said X" payload, drained of SDK types so the handlers are testable.

Two concrete handlers ship in Pass 3.2:

- `ForecastResultHandler` — wraps the existing forecast write logic in `BatchResultProcessor.processForecastBatch()` + `BriefingEvaluationService.persistToDb()` + `RatingValidator` + `evaluation_delta_log`.
- `AuroraResultHandler` — wraps `BatchResultProcessor.processAuroraBatch()` + `AuroraStateCache.updateScores()` + the 1★ fallback.

### How Pass 2 primitives plug in

| Primitive | Role under Pass 3 |
|---|---|
| `CacheKeyFactory` | Used inside `ForecastResultHandler` to build `regionName\|date\|targetType` keys. The engine doesn't see cache keys. |
| `CustomIdFactory` (and `ParsedCustomId`) | Used by the `PromptBuilderStrategy` implementations to build IDs at request-build time, and used by `BatchResultProcessor` to dispatch results to the right handler. |
| `BatchRequestFactory` | Used by `ForecastPromptBuilderStrategy.buildBatchRequest`; the aurora strategy builds its own `BatchCreateParams.Request` since it has a different prompt builder. **Optional refactor:** widen `BatchRequestFactory` to take a system-prompt + user-message pair directly, so aurora can use it too. Pass 3.2 decision. |
| `BatchSubmissionService` | Stays as-is. `EvaluationService.submit` calls it directly. |
| `BatchTriggerSource` | Threaded through `submit()`/`evaluateNow()`; logged in `api_call_log`/`forecast_batch` for forensics. No engine branching. |

### What happens to existing services

| Service | Disposition under Pass 3 |
|---|---|
| `ScheduledBatchEvaluationService` | Becomes a **caller of `EvaluationService.submit`** — keeps task collection (location loading, weather prefetch, triage, stability) but stops building requests directly. Its `aurora_batch_evaluation` registration also moves to call `EvaluationService.submit(auroraTasks, SCHEDULED)`. ~50 % shrinkage. |
| `ForceSubmitBatchService.forceSubmit()` | Becomes a thin caller of `EvaluationService.submit(forceTasks, FORCE)`. |
| `ForceSubmitBatchService.submitJfdiBatch()` | Becomes a caller of `EvaluationService.submit(jfdiTasks, JFDI)`. |
| `ForceSubmitBatchService.getResult()` | **Stays as-is, untouched.** Per Pass 2.5, this is a synchronous admin peek, not a duplicate of the polling path. |
| `ClaudeEvaluationStrategy` | **Deleted in Pass 3.3** — its sole production caller (`BriefingEvaluationService.evaluateSingleLocation`) is part of the SSE retirement. The synchronous Anthropic call moves into `EvaluationService.evaluateNow()`. The 40 unit tests migrate to `EvaluationServiceTest` covering the same behaviour through the new entry point. `MetricsLoggingDecorator` is preserved by being applied to `AnthropicApiClient` instead of the strategy. |
| `BriefingEvaluationService.evaluateRegion()` (SSE) | **Deleted in Pass 3.3.** Replaced by an admin-triggered region batch via `EvaluationService.submit(regionTasks, ADMIN)` + frontend polling on `forecast_batch` status. |
| `ForecastCommandExecutor` | **Stays in v2.12** (Section 2 recommendation). In Pass 3.3, replace its inline `ForecastService.evaluateAndPersist` calls with `EvaluationService.evaluateNow()` for observability — but the executor itself, the `forecast_evaluation` writes, and the four admin endpoints stay. |
| `BatchPollingService` | Stays. Internal to Pass 3 in the sense that it's still the canonical async result fetcher. |
| `BatchResultProcessor` | Refactored to dispatch via `ResultHandler<T>` keyed on `ParsedCustomId` subtype. The two `processForecastBatch` / `processAuroraBatch` private methods become two `ResultHandler` implementations. |
| `AuroraOrchestrator` | Real-time path's `scoreAndCache` calls `EvaluationService.evaluateNow(auroraTask, SCHEDULED)` instead of `ClaudeAuroraInterpreter.interpret` directly. State-machine + Bortle + triage logic stay in the orchestrator. |
| `AuroraPollingJob` | No change in shape — still polls every 5 min, still gates on night/daylight. Delegates to the orchestrator as today. |
| `BriefingEvaluationService` | Loses `evaluateRegion()` (SSE) and `evaluateSingleLocation()` in 3.3. Keeps the in-memory cache, `getCachedScores`, and `persistToDb` as a result-handler dependency for `ForecastResultHandler`. |

### The four admin "nuclear options"

| Endpoint | Today | After Pass 3.3 |
|---|---|---|
| `POST /api/admin/batches/submit-scheduled` | `ScheduledBatchEvaluationService.doSubmitForecastBatchForRegions(...)` | Same call site, same task-collection, but submission goes through `EvaluationService.submit(tasks, ADMIN)`. |
| `POST /api/admin/batches/submit-jfdi` | `ForceSubmitBatchService.submitJfdiBatch(...)` | Routed through `EvaluationService.submit(tasks, JFDI)`. |
| `POST /api/admin/batches/force-submit` | `ForceSubmitBatchService.forceSubmit(...)` | Routed through `EvaluationService.submit(tasks, FORCE)`. |
| `POST /api/forecast/run` (and the three `/run/*-term` variants) | `ForecastCommandExecutor.execute(cmd, jobRun)` | **Stays inside `ForecastCommandExecutor`** in v2.12. The executor's per-location Claude call is replaced with `EvaluationService.evaluateNow(task, ADMIN)` so observability is unified. The 3-phase pipeline, sentinel sampling, and stability snapshots are preserved verbatim. |

`GET /api/admin/batches/force-result/{batchId}` (the synchronous peek, Pass 2.5) is unchanged.

### Frontend impact

| Surface | Change |
|---|---|
| Briefing region drill-down (current SSE consumer of `/api/briefing/evaluate`) | Replace SSE consumption with: (a) an admin-only "Run forecast for region" button that calls `POST /api/admin/batches/submit-scheduled` with the selected region, (b) a polling read on `forecast_batch.status` + `cached_evaluation` for the result. |
| Map popup (no current "evaluate now" UI) | Optional Pass 3.3+ feature: PRO "evaluate now" button → `POST /api/evaluation/now` → calls `EvaluationService.evaluateNow(forecastTask, ADMIN)` synchronously, returns the new `BriefingEvaluationResult` for the popup to render. ~10–15 s (Haiku). |
| Admin "Run forecast" buttons (existing 4 endpoints) | No URL change. The progress UX is unchanged — `RunProgressTracker` still emits per-location events on its existing channel; only the underlying Claude call is wrapped now. |
| Admin batch view | New polling endpoint `GET /api/admin/batches/{id}` exposing `status`, `succeededCount`, `erroredCount`, `endedAt` — already exists per `BatchAdminController:79-96`. No new endpoint needed. |

The critical frontend retirement is the SSE event source for `/api/briefing/evaluate`. Until Pass 3.3 ships its replacement, the frontend must keep both: SSE component for v2.12.4 (Pass 3.2), polling component for v2.12.5 (Pass 3.3). This is why the SSE retirement is bound to Pass 3.3, not 3.2.

---

## Section 5: Migration sequencing

### Pass 3.2 — engine + safe callers (target: v2.12.4)

**Goal:** add the engine and migrate the two callers that don't change user-visible behaviour.

**Add (production):**

- `service.evaluation.EvaluationService` (interface) + `EvaluationServiceImpl`
- `service.evaluation.EvaluationTask` (sealed interface + `Forecast` / `Aurora` records)
- `service.evaluation.EvaluationHandle` + `EvaluationResult` (sealed)
- `service.evaluation.PromptBuilderStrategy<T>` interface + `ForecastPromptBuilderStrategy` + `AuroraPromptBuilderStrategy`
- `service.evaluation.ResultHandler<T>` interface + `ForecastResultHandler` + `AuroraResultHandler`
- `service.evaluation.ClaudeBatchOutcome` + `ClaudeSyncOutcome` records

**Modify (production):**

- `BatchResultProcessor.processForecastBatch` / `processAuroraBatch` — extract their bodies into the two new `ResultHandler`s, then dispatch via `CustomIdFactory.parse(customId) instanceof ParsedCustomId.Forecast → forecastHandler.handleBatchResult(...)`. (`BatchResultProcessor` keeps its outer skeleton — the JSONL stream loop, error counts, token aggregation.)
- `ScheduledBatchEvaluationService.doSubmitForecastBatch()` / `doSubmitAuroraBatch()` — task-collection logic stays; replace the `BatchRequestFactory` + `BatchSubmissionService` calls with `evaluationService.submit(tasks, BatchTriggerSource.SCHEDULED)`.
- `BatchSubmissionService` — no change to public API; still called by `EvaluationServiceImpl` internally. The two existing callers (scheduled, force-submit) become indirect via the engine.

**Tests added:**

- `EvaluationServiceImplTest` — unit tests covering: (a) submit empty task list → null handle, (b) submit forecast tasks → routes through forecast strategy, (c) submit aurora tasks → routes through aurora strategy, (d) `evaluateNow` for forecast and aurora, (e) `evaluateNow` errors propagate as `EvaluationResult.Errored`, not exceptions.
- `ForecastResultHandlerTest`, `AuroraResultHandlerTest` — port the relevant `BatchResultProcessorTest` cases (62 tests today) to the per-handler structure. Some tests stay on `BatchResultProcessor` as integration glue.
- `ForecastBatchPipelineIntegrationTest` — **kept verbatim**. The test's contract (submit via `BatchSubmissionService`, poll, write to `cached_evaluation`, write to `api_call_log`) is unchanged because the engine sits *above* `BatchSubmissionService`. The test still validates the pipeline shape.

**Tests modified (no contract change):**

- `ScheduledBatchEvaluationServiceTest` — many assertions about request building move to `EvaluationServiceImplTest` or `ForecastPromptBuilderStrategyTest`. The 48-test suite shrinks.
- `ForceSubmitBatchServiceTest` — same shrinkage; some tests move.

**Files to delete (Pass 3.2):** none yet — the goal is to add the engine and route the two safe callers. Old call paths still exist as private methods in their host services until Pass 3.3.

**Risks specific to 3.2:**

- The two `ResultHandler`s must produce **byte-identical** writes to what `BatchResultProcessor` does today. The integration tests are the safety net.
- Constructor parameter explosion if `EvaluationServiceImpl` directly depends on every collaborator. Mitigation: pass the per-task-type strategies as a `Map<Class<? extends EvaluationTask>, PromptBuilderStrategy<?>>` injected once at construction.
- Aurora's "single batch request listing all locations" shape doesn't fit `BatchRequestFactory.buildForecastRequest`'s per-location signature. Either (a) `AuroraPromptBuilderStrategy.buildBatchRequest` builds its own request (acceptable), or (b) `BatchRequestFactory` is widened to a generic `buildRequest(systemPrompt, userMessage, customId, model, maxTokens)` method that both strategies use. **Recommendation: (a)** — aurora is a one-off; extending `BatchRequestFactory` for one extra caller is over-design.

**Tag and ship as v2.12.4.** Production behaviour is unchanged; this is a refactor under the integration tests.

### Pass 3.3 — admin migration + SSE retirement (target: v2.12.5)

**Goal:** route the admin nuclear options through the engine; retire all SSE code paths; close the `ForecastCommandExecutor` observability gap.

**Modify (production):**

- `ForceSubmitBatchService.forceSubmit()` — collect `EvaluationTask.Forecast` list from the location/date/event triple, then `evaluationService.submit(tasks, FORCE)`. Inline request building deleted.
- `ForceSubmitBatchService.submitJfdiBatch()` — same shape; submit via `JFDI` trigger.
- `BatchAdminController.submitScheduledBatch` — unchanged URL, calls now flow through the engine via the modified `ScheduledBatchEvaluationService` from 3.2.
- `ForecastCommandExecutor.executeFullEvaluationPhase()` — replace `forecastService.evaluateAndPersist(task, jobRun)` with a path that wraps the existing per-location Claude call inside `evaluationService.evaluateNow(forecastTask, ADMIN)`. The persist-to-`forecast_evaluation` step stays inside the executor (or moves into `ForecastResultHandler.handleSyncResult` with a `BatchTriggerSource == ADMIN` branch — design call at 3.3 implementation time).
- `AuroraOrchestrator.scoreAndCache()` — replace `claudeInterpreter.interpret(...)` with `evaluationService.evaluateNow(auroraTask, SCHEDULED)`. The result handler does the `AuroraStateCache.updateScores` write.
- `BriefingEvaluationController.evaluate()` — endpoint deleted (HTTP 410 for one release if cautious).
- `BriefingEvaluationService.evaluateRegion()` and `evaluateSingleLocation()` — methods deleted. `getCachedScores`, `getCachedTimestamp`, and the in-memory cache stay.
- `ClaudeEvaluationStrategy` and `MetricsLoggingDecorator` — strategy deleted; the decorator moves to wrap `AnthropicApiClient.createMessage` directly so sync calls still log.

**Files to delete:**

- `service.evaluation.ClaudeEvaluationStrategy.java`
- `service.evaluation.MetricsLoggingDecorator.java` (logic absorbed into `AnthropicApiClient`)
- `service.evaluation.NoOpEvaluationStrategy.java` (verify — it may have a wildlife caller still active; check before deletion)
- `service.evaluation.EvaluationStrategy.java` (the abstract type; if `NoOpEvaluationStrategy` survives, this stays too)
- `controller.BriefingEvaluationController.java` (verify no tests reference its endpoint via MockMvc; migrate or delete tests)
- `BriefingEvaluationService.evaluateRegion()`, `evaluateSingleLocation()` — methods only, not the class
- All SSE-related frontend code in `frontend/src/api/briefingApi.js`, the `EventSource` consumer in the briefing drill-down component (audit at implementation time)

**Files NOT to delete (despite Pass 3.1 prompt suggesting otherwise):**

- `service.ForecastCommandExecutor` — kept per Section 2 recommendation
- `service.ScheduledForecastService` — kept; its tide refresh and daily briefing scheduler registrations are unrelated to the evaluation consolidation
- `entity.ForecastEvaluationEntity` and `forecast_evaluation` table — kept; reader migration is v2.13

**Frontend changes:**

- Remove the SSE `EventSource` consumer in the briefing drill-down. Replace with a poll on `GET /api/admin/batches/{id}` (already exists) plus a read on `cached_evaluation` via the existing briefing API.
- Pre-flight: audit every `EventSource` reference in `frontend/src/`. Some may be `RunProgressTracker` SSE for the admin "Run forecast" button — that one stays.
- Document the UX shift in CHANGELOG: "Region drill-down now polls instead of streams; first results appear after the next batch poll cycle (~60 s)."

**Tests modified:**

- `BriefingEvaluationServiceTest` (62 tests) — drops the SSE-evaluation tests; keeps the cache tests.
- `BriefingEvaluationControllerTest` (28 tests) — drops the SSE-emit tests; keeps the cache-read tests.
- `ClaudeEvaluationStrategyTest` (40 tests) — migrate to `EvaluationServiceImplTest.evaluateNow_*` cases.
- `ForecastCommandExecutorTest` (77 tests) — assert that `evaluateNow` is called with the right `EvaluationTask.Forecast` for each surviving phase; the 3-phase orchestration tests stay.
- `ForecastBatchPipelineIntegrationTest` — augment with one test: an admin force-submit goes through `EvaluationService.submit` and produces the same `cached_evaluation`/`api_call_log` writes as a scheduled run.

**Risks specific to 3.3:**

- Admin force-submit and JFDI tests mock `AnthropicClient` at different levels than the engine. Migration will require careful test rewiring.
- The frontend SSE removal must not race with the backend SSE deletion. Sequence: ship Pass 3.3 backend with deprecated-but-functional SSE for one release, then ship the frontend that no longer consumes it, then delete the backend SSE code in v2.12.6 (or fold all three into 3.3 if confident).
- If `NoOpEvaluationStrategy` is still wired to wildlife/comfort runs, deleting `EvaluationStrategy` breaks them. **Verify before deletion**: `grep -r NoOpEvaluationStrategy backend/src/main`.
- `MetricsLoggingDecorator` removal means `api_call_log.duration_ms` may stop populating for sync calls if the absorption into `AnthropicApiClient` is sloppy. The integration tests don't catch this — add a unit test in 3.3.
- `ForecastCommandExecutor`'s `evaluateAndPersist` rewrite is the single highest-risk change in 3.3. The 77 tests on the executor are the safety net but they're heavily mocked. Add an integration test that proves a `/api/forecast/run` call writes to both `forecast_evaluation` AND `api_call_log` (the latter being the new behaviour).

**Tag and ship as v2.12.5.**

### Sub-pass split: is it the right shape?

Yes — but with two caveats:

1. **3.2 and 3.3 cannot ship simultaneously.** 3.2 must bake in production for at least 24 h before 3.3 starts to merge. The integration tests are good; the empirical risk is 3.2's silent regressions in `BatchResultProcessor` dispatch logic.
2. **A 3.4 "deferred SSE frontend cleanup + reader migration" pass is plausible.** If frontend SSE removal is risky to bundle with 3.3's backend changes, split it out: 3.3 makes SSE endpoints return deprecation headers; 3.4 deletes them after frontend cuts over. This is a judgement call at 3.3 implementation time — flagged here as an option.

---

## Section 6: Risks and unknowns

### What Pass 1 / Pass 2 might have missed

- **Path 6 writes to `forecast_evaluation` only**, and that table is the source of truth for `GET /api/forecast`, the primary forecast view. Pass 1 §6 noted the observability gap but framed it as "command executor doesn't write to `cached_evaluation`" — i.e. backwards. The real issue is that **the cache is not the system of record**; the table is. This inverts Pass 1's implicit "we want everything in `cached_evaluation`" framing and is the central reason `ForecastCommandExecutor` cannot be deleted in v2.12.
- **Aurora batch persistence is volatile only.** `AuroraStateCache` is in-process — a JVM restart loses the state. Pass 1 §6 marked aurora batch as having `forecast_batch` lifecycle ✓ but missed that the **scored output** survives only in memory. A restart between the batch ENDING and the next user request returns to a "no aurora data" state until the next polling cycle. This is unrelated to the consolidation but is a latent product issue worth flagging.
- **`MetricsLoggingDecorator` is a Decorator wrapping `EvaluationStrategy`, not `AnthropicApiClient`.** Deleting `ClaudeEvaluationStrategy` without re-wiring the decorator silently drops `api_call_log.duration_ms` and `total_tokens` for sync calls. Pass 1 §2 named the decorator but didn't flag the wiring fragility.
- **`MINOR` aurora alert level may be unhandled.** `submitAuroraBatch` skips `QUIET` only (`:645-648`); MODERATE and STRONG have explicit Bortle thresholds; **MINOR has no explicit branch**. Worth a follow-up read of `AuroraOrchestrator.deriveAlertLevel` and the Bortle threshold map. Out of scope for Pass 3 but a "fix as we go" candidate during 3.2 if Aurora task-collection logic is touched.
- **`ForceSubmitBatchService.forceSubmit` and `submitJfdiBatch` allocate distinct custom-ID prefixes** (`force-` and `jfdi-`). The engine doesn't need to care, but the V99 partial indexes on `api_call_log.custom_id` may have been built assuming forecast-only prefixes. Verify before 3.3 inserts batches with `force-`/`jfdi-` prefixes through the new code path — the existing services already do this, so the indexes should be fine, but worth a 5-minute check.

### What the integration test pyramid does NOT cover

- **No test for `evaluation_delta_log` writes.** `BriefingEvaluationServiceCacheFreshnessTest` (6 tests) tests freshness but not delta logging. If Pass 3.2 silently breaks the delta-log call site, no test fails. Add one in 3.2.
- **No test for `BatchSubmissionService` failure handling other than 500.** The integration tests cover happy path, errored-only, mixed, and submission 500. They don't cover: 401 (key invalid), 429 (rate limit), 5xx then 200 retry-success. Anthropic SDK retries internally for some of these; verify that behaviour matches expectations.
- **No test of `forecast_batch.last_polled_at` updates.** The poller writes it on every poll; nothing asserts it. Mostly a forensic field — low priority.
- **No test of the concurrent batch guard** (`forecastBatchRunning` / `auroraBatchRunning` in `ScheduledBatchEvaluationService`). Pass 3.2 should preserve this guard inside whichever service still owns task collection.
- **No test of `ForecastResultHandler` → `evaluation_delta_log` for the `BATCH` source path.** The current path goes via `BriefingEvaluationService.persistToDb` → `logEvaluationDeltas`; 3.2's refactor must preserve it.
- **Aurora real-time observability tests don't exist** because there is nothing to test today (no `api_call_log`). After 3.3 wires `evaluateNow` for aurora, a test should verify `api_call_log` rows for aurora real-time calls.
- **No frontend test for SSE consumer removal.** The Vitest suite has 363 tests but I haven't audited which (if any) cover the briefing drill-down's `EventSource` lifecycle. Audit at 3.3 start.

### Migration failure modes

- **Cache key collisions.** Forecast and aurora write to different stores (`cached_evaluation` vs `AuroraStateCache`), so the cache-key namespace is naturally separate. No collision risk *unless* a future feature decides to persist aurora results to `cached_evaluation` — at which point a `region_name` of `AURORA-MODERATE` could collide with a real region. Mitigation: never write aurora to `cached_evaluation` without prefixing the key.
- **Custom ID parser confusion.** `CustomIdFactory.parse` already dispatches by prefix (`fc-`, `jfdi-`, `force-`, `au-`). If 3.2 introduces a fifth prefix (it shouldn't), update the parser at the same time.
- **Concurrent batch submission.** The current guards (`AtomicBoolean` per type) live in `ScheduledBatchEvaluationService`. After 3.2 they must remain there (in the caller, not the engine), because the engine accepts pre-collected tasks and shouldn't second-guess the caller. Verify the guard call sites still execute on the right side of the new boundary.
- **Lost observability during migration.** If `ForecastResultHandler.handleBatchResult` forgets to call `JobRunService.logBatchResult`, every successful batch result appears as 0 in the dashboard. Mitigation: explicit assertion in the integration test that `api_call_log` has the right number of rows after a batch ENDs.
- **Frontend race.** Cleanest mitigation per §5: 3.3 backend keeps SSE endpoints alive (deprecated logging) for one release while frontend removes consumers in lockstep.
- **Stability snapshot ownership.** `ForecastCommandExecutor` writes the `stability_snapshot` table (V98). Both `ScheduledBatchEvaluationService` and `BriefingBestBetAdvisor` read it. If 3.3 changes how the executor writes snapshots, the readers may see stale state. The recommendation is to **not touch snapshot logic in Pass 3 at all** — leave it inside `ForecastCommandExecutor`.

### Empirical unknowns (data needed before 3.3)

- **Real-API E2E single-request batch wall-clock distribution.** The daily smoke is the source. Add timing instrumentation to the test (or a separate "first-poll-not-done"/"first-poll-done" marker) and aggregate over 7+ days before finalising the §1 (c) recommendation. If the median is < 30 s, reconsider; if > 60 s, the recommendation stands.
- **`/api/briefing/evaluate` real-world usage.** Is the SSE drill-down an admin-only diagnostic or a regular user action? Server logs (`/Users/gregochr/goldenhour-data/logs/`) over the last week would tell us. If it's heavily used, the polling replacement needs a polished UX; if it's effectively admin-only, a basic progress bar suffices.
- **Aurora real-time call volume.** The polling job runs every 5 min, night-only, `aurora.enabled`-gated, and only fires Claude on a NOTIFY action. Real call volume is very low. Verify before 3.3: is observability instrumentation worth the effort, or should aurora real-time stay synchronous-and-silent? My recommendation is to instrument it (the cost is one wrapping call), but the data should confirm.

---

## Recommendations for Pass 3.2+

### Headline departures from the prompt's framing

1. **Single-location SSE retirement is split: keep a synchronous direct-Messages-API path** for low-latency single-eval (admin "Run forecast" + future map "evaluate now"). Multi-location SSE retires as planned. This is a softening of decision (1) in the prompt's "Pass 3 is..." preamble. The evidence (60 s polling cadence × 2 polls = ~120 s) makes batch unsuitable for one-off interactive evaluations. The synchronous path runs through `EvaluationService.evaluateNow()` so observability is unified — the spirit of the consolidation is preserved.

2. **`ForecastCommandExecutor` cannot be deleted in v2.12.** The reader of `forecast_evaluation` is the main React forecast view, not just niche optimisation strategies. Reader migration is its own programme (v2.13 or later). v2.12.5 lands the executor's per-location Claude call routed through `EvaluationService` for observability parity, but the executor and table stay alive.

3. **`forecast_evaluation` lives.** It is the system of record for the per-location, per-run colour evaluation. `cached_evaluation` is the briefing-shaped cache. The two are not interchangeable. v2.13's reader-migration programme either widens `cached_evaluation`'s schema or builds a successor — that's a separate design pass.

### Things that should change in the planned 3.2 / 3.3 split

- **3.3 should not retire SSE in the same release as the executor refactor.** That's two large risky changes coupled. Sequence: 3.3a retires SSE; 3.3b does the executor wiring. If both bundle into one v2.12.5 cut, the regression surface is large.
  - **Counter-argument:** the executor wiring is small if it's just "wrap the per-location Claude call". I'd accept the bundling if 3.3 lands with strong integration coverage on `/api/forecast/run`.
- **Add a pre-3.2 instrumentation pass.** Before the engine lands, instrument `ForecastBatchPipelineRealApiE2ETest` with timing checkpoints (submit → first-poll-done) so a week of daily-smoke data is in the bag by the time 3.2 ships and 3.3 needs to validate the §1 recommendation. This is one-line worth of timing logs in the test.

### Things that should defer to v2.13 (or later)

- **`forecast_evaluation` reader migration.** A multi-week schema-and-API redesign.
- **Aurora persistent storage.** `AuroraStateCache`'s in-memory-only design is a latent product issue; persisting aurora batch results to a `aurora_score_cache` table (or widening `cached_evaluation`) survives JVM restarts. Out of scope for Pass 3 — this is product, not consolidation.
- **`OpenMeteoService` (1155 lines), `BriefingBestBetAdvisor` (1066 lines), `ModelTestService` (869 lines)** — Pass 1 §10 already deferred these.
- **Visitor-pattern evaluator architecture** (the v2.13 hint in the prompt) — the sealed-interface + per-task `ResultHandler` design proposed here is **already** a visitor variant. If the v2.13 programme has a different definition of "visitor pattern", clarify before proceeding; the current design may already satisfy it.

### "Fix as we go" candidates spotted during investigation

- **Pass 1 §6 table row for Force/JFDI `evaluation_delta_log`** is `—` but Pass 2.5 corrected it to `conditional`. Update Pass 1 doc inline (one-line edit) when Pass 3.2 commits.
- **`ScheduledForecastService`'s commented-out `@Scheduled` annotations** (`:80, 95, 106, 115`) are dead code — the methods only run from `ForecastController` endpoints today. Worth cleaning the commented-out annotation lines as a small inline tidy in Pass 3.2 (no behaviour change). Do not delete the methods; they are still controller-driven.
- **`ScheduledBatchEvaluationService` constructor parameter count** (22 per Pass 1 §10). After Pass 3.2, several dependencies (`BatchRequestFactory`, `BatchSubmissionService`, prompt builders) move into the engine, so the constructor naturally shrinks. Don't pre-empt the cleanup; let the migration produce it.

### Blocking concerns to resolve before Pass 3.2 starts

- **Confirm Section 2's recommendation with the user.** The prompt expected (a) "delete `ForecastCommandExecutor`" or at most (c) with a preliminary reader-migration pass. The recommendation here is (b)+defer, which is a programme-level scope change. Get explicit alignment before committing 3.2.
- **Confirm Section 1's recommendation with the user.** The prompt expected single-location SSE to retire. The recommendation here keeps a synchronous (non-batch) single-eval path. Confirm this is acceptable framing before committing 3.2.
- **Audit `NoOpEvaluationStrategy` callers.** If wildlife/comfort still depends on the strategy abstraction, `EvaluationStrategy` cannot be deleted in 3.3 (`NoOpEvaluationStrategy` keeps it alive). 5-minute grep at the start of 3.2.
- **Confirm the v2.13 visitor-pattern programme's relationship to this design.** Is the proposed sealed-interface + `ResultHandler` arrangement the v2.13 visitor pattern, or is it something different? If different, design here may need revision before 3.2 implementation.

### Tag decision

The prompt offered: tag now as v2.12.3 (docs-only deploy, no production behaviour change), or hold the tag until Pass 3.2 ships. **Recommendation: hold the tag.** Ship the design document as a commit on `main`, get review, then tag v2.12.4 when Pass 3.2 lands. The release pipeline is built around behavioural changes; a docs-only tag triggers a no-op deploy and pollutes the release timeline. The design document has value as a `main` commit regardless of tagging.
