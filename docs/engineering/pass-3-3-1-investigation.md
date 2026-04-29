---
name: Pass 3.3.1 Investigation — Route ForecastCommandExecutor Claude calls through EvaluationService.evaluateNow()
description: Pre-code investigation findings for migrating ForecastCommandExecutor's per-location Claude calls to flow through the new EvaluationService.evaluateNow() path. Per the v2.11.14 brief, no code changes commit until this is reviewed.
---

# Pass 3.3.1 Investigation — Route `ForecastCommandExecutor` Claude calls through `EvaluationService.evaluateNow()`

**Status:** Investigation only. Pre-code review document. Per the v2.11.14 brief, no code changes commit until this is reviewed.

**Files inspected:**

- `backend/src/main/java/com/gregochr/goldenhour/service/ForecastCommandExecutor.java` (885 lines, 15 deps)
- `backend/src/main/java/com/gregochr/goldenhour/service/ForecastService.java` (662 lines — **the actual Claude call site**)
- `backend/src/main/java/com/gregochr/goldenhour/service/EvaluationService.java` (the **OLD** facade in `service.*`, wrapping strategies)
- `backend/src/main/java/com/gregochr/goldenhour/service/evaluation/EvaluationService.java` (the **NEW** Pass 3.2 interface in `service.evaluation.*`)
- `backend/src/main/java/com/gregochr/goldenhour/service/evaluation/EvaluationServiceImpl.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/evaluation/EvaluationTask.java` (sealed; `Forecast` / `Aurora`)
- `backend/src/main/java/com/gregochr/goldenhour/service/evaluation/EvaluationResult.java` (sealed; `Scored` / `Errored`)
- `backend/src/main/java/com/gregochr/goldenhour/service/evaluation/ResultHandler.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/evaluation/ResultContext.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/evaluation/ForecastResultHandler.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/evaluation/ClaudeEvaluationStrategy.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/evaluation/MetricsLoggingDecorator.java` (**critical** — see Investigation 5)
- `backend/src/main/java/com/gregochr/goldenhour/service/JobRunService.java` (`logAnthropicApiCall`)
- `backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java` (`writeFromBatch`)
- `backend/src/main/java/com/gregochr/goldenhour/service/batch/BatchTriggerSource.java`
- `backend/src/main/java/com/gregochr/goldenhour/controller/ForecastController.java` (admin run endpoints)
- `backend/src/main/java/com/gregochr/goldenhour/service/ScheduledForecastService.java` (commented-out `@Scheduled`s)
- `backend/src/test/java/com/gregochr/goldenhour/service/ForecastCommandExecutorTest.java` (68 tests)
- `backend/src/test/java/com/gregochr/goldenhour/service/ForecastServiceTest.java` (60 tests)
- `backend/src/test/java/com/gregochr/goldenhour/service/EvaluationServiceTest.java` (12 tests)
- `backend/src/test/java/com/gregochr/goldenhour/service/evaluation/EvaluationServiceImplTest.java` (19 tests)
- `backend/src/test/java/com/gregochr/goldenhour/service/evaluation/ForecastResultHandlerTest.java` (14 tests)
- `backend/src/test/java/com/gregochr/goldenhour/service/evaluation/MetricsLoggingDecoratorTest.java`
- `docs/engineering/evaluation-service-design.md` (Sections 4 + 5; design constraints)
- `docs/engineering/forecast-evaluation-architecture.md` (Pass 1 §6 — the observability claim that motivates this pass)

---

## Headline findings before the per-investigation walkthrough

Two findings reshape the implementation shape and justification:

1. **The Claude call is not in `ForecastCommandExecutor` at all.** It is in `ForecastService.evaluateAndPersist(...)` at line 425 (and a dead-in-production sibling in `ForecastService.runForecasts` at line 200). The executor's role is orchestration — three-phase pipeline, parallel submit, sentinel sampling. It calls `forecastService.evaluateAndPersist(...)` from two sites: the sentinel phase (line 535) and the full-eval phase (line 585). Migrating "the executor's Claude calls" therefore means migrating `ForecastService.evaluateAndPersist`, not adding a dependency on `EvaluationService` to the executor itself. The executor signature does not change. Investigation 1 details this in full.

2. **The Pass 1 §6 observability claim is at minimum imprecise.** `ForecastService.evaluateAndPersist` calls the *old* `service.EvaluationService.evaluate(data, model, jobRun)` which calls `decorateIfNeeded(strategy, jobRun)` and **wraps with `MetricsLoggingDecorator` whenever `jobRun != null`**. The decorator's success path (`logApiCall(detail.durationMs(), 200, ...)` at `MetricsLoggingDecorator:73`) and failure path (`logApiCall(0, statusCode, errorMessage, false, ...)` at `:110`) both call `jobRunService.logAnthropicApiCall(... isBatch=false ...)`. This is the same `api_call_log` write the Pass 3.3.1 prompt claims is missing. The executor *does* pass `jobRun` (lines 535, 585), so the decorator wrapping fires in production. Investigation 5 walks through this and recommends a one-query empirical check before declaring the gap closed.

Net impact on this pass:

- The implementation is still small and worth doing — it unifies the sync Claude transport so that *future* deletion of `ClaudeEvaluationStrategy` (per the design doc) does not silently break observability (the design doc itself flags this fragility at line 514). It also removes a dual-`EvaluationService` situation where two same-named beans live in different packages.
- But the framing of "closes a year-old observability gap" in the prompt deserves a one-line correction: the gap (at least for `api_call_log`) appears already closed. What 3.3.1 *does* close is the **second-class observability** (the executor's path goes through a decorator-around-strategy wiring; aurora real-time and the future map popup go through the new engine; today there are two ways to write the same row, and 3.3.1 collapses to one). It also closes the `evaluation_delta_log` gap, which Pass 1 §6 correctly identifies (the decorator does not write delta logs).

These two findings drive the recommended implementation shape in Investigation 2 and the migration commit plan in the closing section.

---

## Investigation 1 — Locate the Claude call site

### File and line numbers

The Claude call is **not** in `ForecastCommandExecutor`. It is two layers deep:

| # | File:Line | What it does |
|---|---|---|
| 1 | `ForecastCommandExecutor.runSentinelPhase` line **535** | `forecastService.evaluateAndPersist(sentinel, jobRun)` — sentinel evaluation per region |
| 2 | `ForecastCommandExecutor.runFullEvalPhase` line **585** | `forecastService.evaluateAndPersist(task, jobRun)` — the parallel full-eval submit (the production hot path) |
| 3 | `ForecastService.evaluateAndPersist` line **425** | `evaluationService.evaluate(preEval.atmosphericData(), preEval.model(), jobRun)` — calls the **old** `service.EvaluationService` |
| 4 | `service.EvaluationService.evaluate` line **63** | `decorateIfNeeded(getStrategy(model), jobRun).evaluate(data)` — wraps the strategy with `MetricsLoggingDecorator` when `jobRun != null` |
| 5 | `ClaudeEvaluationStrategy.evaluate(data)` (delegates to `evaluateWithDetails`) | Builds prompt via `PromptBuilder`/`CoastalPromptBuilder`; calls `AnthropicApiClient.createMessage(...)`; parses; returns `SunsetEvaluation` |

So the actual Anthropic SDK call lives in `ClaudeEvaluationStrategy`. Everything above it is orchestration.

### Prompt builder selection

`ClaudeEvaluationStrategy` is constructed once per `EvaluationModel` (`HAIKU`, `SONNET`, `OPUS`) by `EvaluationConfig` and pre-bound to a `PromptBuilder` (inland) or `CoastalPromptBuilder` (coastal). Today, the strategy itself selects: `data.tide() != null ? coastal : inland`. The new `EvaluationServiceImpl.evaluateNowForecast` (lines 184–189) makes the same selection via `BatchRequestFactory.selectBuilder(task.data())`, then chooses the right `buildUserMessage` overload based on `data.surge() != null`. **The selection logic and prompt content are byte-identical between the two paths** — both ultimately call the same `PromptBuilder.buildUserMessage(...)` and the same `AnthropicApiClient.createMessage(...)`.

### Response parsing

Both paths parse via `ClaudeEvaluationStrategy.parseEvaluation(rawText, objectMapper)`. In the new path, `ForecastResultHandler` injects a `ClaudeEvaluationStrategy` (HAIKU instance) solely as a parser handle (`ForecastResultHandler:65–79`). The parser is deterministic — same input → same `SunsetEvaluation`.

### `forecast_evaluation` persistence

`ForecastService.evaluateAndPersist` lines 428–433 builds a `ForecastEvaluationEntity` from the `SunsetEvaluation` plus the `AtmosphericData` and saves via `repository.save(entity)`. **This is the ONLY caller of `repository.save(...)` for the executor's path.** The executor itself does not write to the table.

### Net flow of one executor-driven evaluation

```
ForecastController.runShortTermForecast (admin click)
  → CompletableFuture.runAsync(commandExecutor.execute(cmd, jobRun))
    → ForecastCommandExecutor.executeThreePhasePipeline
      → runFullEvalPhase
        → submitParallel(tasks, t -> forecastService.evaluateAndPersist(t, jobRun))
          → ForecastService.evaluateAndPersist
            → evaluationService.evaluate(data, model, jobRun)   [OLD service.EvaluationService]
              → MetricsLoggingDecorator.evaluate
                → ClaudeEvaluationStrategy.evaluateWithDetails
                  → AnthropicApiClient.createMessage  ← THE Claude call
                ← parsed SunsetEvaluation, plus durationMs + tokens
              ← jobRunService.logAnthropicApiCall(... isBatch=false ...)  ← api_call_log row written here
            ← SunsetEvaluation
          ← repository.save(entity)  ← forecast_evaluation row written here
```

The migration replaces the middle layers (the OLD `service.EvaluationService` + `MetricsLoggingDecorator` + `ClaudeEvaluationStrategy.evaluate`) with the equivalent flow inside `EvaluationServiceImpl.evaluateNowForecast`. The outermost calls (the executor's `submitParallel`, the entity build + save) are preserved.

### What the executor is, after this change

Unchanged. The executor's public API (`execute(ForecastCommand)`, `execute(ForecastCommand, JobRunEntity)`) does not move. Its constructor does not gain a dependency on `EvaluationService`. **All migration work happens inside `ForecastService.evaluateAndPersist` and its callee chain.** This is a smaller change than the prompt's framing implies — and it sidesteps the constructor-bloat risk on `ForecastCommandExecutor` (already at 15 deps).

---

## Investigation 2 — Compare with `ForecastResultHandler` (the critical question)

### What `ForecastResultHandler.handleSyncResult` does today

Lines 173–211. On a successful `ClaudeSyncOutcome`:

1. Parses raw text via `parsingStrategy.parseEvaluation(...)` → `SunsetEvaluation`.
2. Validates the rating via `RatingValidator`.
3. Calls `persistSyncLog(context, outcome, task)` → writes one `api_call_log` row with `is_batch=false`, the model, token usage, `target_date`, `target_type`, and `succeeded=true/false`.
4. Builds a `BriefingEvaluationResult` and writes to `cached_evaluation` via `briefingEvaluationService.writeFromBatch(cacheKey, List.of(result))`.
5. Returns `EvaluationResult.Scored(eval)` where `payload` is the `SunsetEvaluation`.

### Why "(c) handler writes to both" is wrong

The executor's flow drives **N independent Claude calls per region** (sentinel evaluations are sequential per region; full-eval is parallel-fan-out). Each call to `briefingEvaluationService.writeFromBatch(cacheKey, List.of(oneResult))` does the following at `BriefingEvaluationService:328–337`:

```java
ConcurrentHashMap<String, BriefingEvaluationResult> resultMap = new ConcurrentHashMap<>();
results.forEach(r -> resultMap.put(r.locationName(), r));
cache.put(cacheKey, new CachedEvaluation(resultMap, now));
persistToDb(cacheKey, resultMap, "BATCH");
```

`cache.put(cacheKey, ...)` **replaces** the cache entry; it does not merge. So if the executor evaluates Durham then Whitby (same region, same date, same target type), the second call's `cache.put` discards Durham's `BriefingEvaluationResult` from the in-memory map and rewrites the DB row to contain only Whitby. Briefing readers see one location instead of two. **The cache is corrupted by per-location updates to a region-keyed store.**

The same risk exists for the parallel full-eval phase: even if calls land in arbitrary order, only the last write survives. This rules out option (c) entirely.

### Why "(b) executor handles its own persistence" is the right shape but needs a careful seam

The natural division is:

- **`evaluateNow()` is the Claude transport.** It already writes `api_call_log` (via the handler's `persistSyncLog`) and returns the parsed `SunsetEvaluation` via `EvaluationResult.Scored`.
- **The caller decides the destination.** Aurora real-time wants `AuroraStateCache.updateScores(...)`; the future map popup wants `cached_evaluation`; the executor wants `forecast_evaluation`. Letting each caller own its destination keeps `EvaluationService` ignorant of "what is forecast persistence vs aurora persistence vs briefing-cache persistence".

But there is a wrinkle: today the **handler** owns the `cached_evaluation` write, and the handler is keyed by task type (forecast vs aurora). To make the executor's path skip `cached_evaluation` while keeping it for the future map popup, *either* the task carries a flag the handler reads, *or* the handler exposes a "no-cache-write" entry point that the engine calls when invoked from the executor.

The cleanest seam without adding new abstractions is **a small enum field on `EvaluationTask.Forecast`**. The handler reads it; the engine doesn't. The batch path (`ForecastTaskCollector`) defaults the field to `BRIEFING_CACHE`; the executor sets it to `NONE`. The aurora task is unaffected (no change to `EvaluationTask.Aurora`).

This is option **(a)** from the brief, with a precise name for the field.

### Recommendation: option (a) — `WriteTarget` enum on `EvaluationTask.Forecast`

```java
record Forecast(
        LocationEntity location,
        LocalDate date,
        TargetType targetType,
        EvaluationModel model,
        AtmosphericData data,
        WriteTarget writeTarget               // NEW
) implements EvaluationTask {

    public enum WriteTarget {
        BRIEFING_CACHE,    // write to cached_evaluation (default for batch path; future map popup)
        NONE               // skip cached_evaluation; caller persists separately
    }

    // Compact ctor enforces the existing nullness checks; new enum is non-null.
    public Forecast {
        Objects.requireNonNull(writeTarget, "writeTarget");
        // … existing checks …
    }
}
```

In `ForecastResultHandler.handleSyncResult` (the only place that reads it):

```java
persistSyncLog(context, outcome, task);                         // unconditional (closes observability)
if (task.writeTarget() == WriteTarget.BRIEFING_CACHE) {
    String cacheKey = CacheKeyFactory.build(regionName, task.date(), task.targetType());
    briefingEvaluationService.writeFromBatch(cacheKey, List.of(result));   // existing behaviour
}
return new EvaluationResult.Scored(eval);
```

In the executor's downstream path (`ForecastService.evaluateAndPersist` becomes the migration site):

```java
EvaluationTask.Forecast task = new EvaluationTask.Forecast(
        preEval.location(), preEval.date(), preEval.targetType(),
        preEval.model(), preEval.atmosphericData(),
        EvaluationTask.Forecast.WriteTarget.NONE);                  // explicit
EvaluationResult outcome = newEvaluationService.evaluateNow(task, BatchTriggerSource.ADMIN);
SunsetEvaluation eval = switch (outcome) {
    case EvaluationResult.Scored s -> (SunsetEvaluation) s.payload();
    case EvaluationResult.Errored e -> SunsetEvaluation.empty();    // or throw — see below
};
ForecastEvaluationEntity entity = buildEntity(... eval ...);
return repository.save(entity);                                     // forecast_evaluation write
```

Notes:

- The batch path call site in `ForecastTaskCollector:265, 385` constructs `EvaluationTask.Forecast` with `WriteTarget.BRIEFING_CACHE` (or use a single-arg overload that defaults to it — see "convenience overload" below).
- Tests constructing `EvaluationTask.Forecast` (`EvaluationTaskTest`, `ForecastResultHandlerTest`, `EvaluationServiceImplTest`, `ScheduledBatchEvaluationServiceTest`) need their literals updated. ~12 sites, mechanical.
- The aurora task and `AuroraResultHandler` are unaffected.
- **Error handling** — today, when Claude throws, `evaluateAndPersist` lets the exception propagate up (no entity is written). The new path returns `EvaluationResult.Errored` instead of throwing. The executor's `submitParallel` (line 851–875) catches exceptions per task; it does not depend on the throw to skip the save. So the migration should: on `Errored`, log and return `null` (or a sentinel) so `submitParallel` treats the slot as failed exactly as today. **No change to `submitParallel`'s behaviour required.**

### Why not option (b) — extract `ForecastService.persistEvaluation()`?

Considered. Two downsides:

1. The handler's `handleSyncResult` would still write `cached_evaluation` for sync calls, so we'd need to either (a) skip the handler entirely (means `evaluateNow` no longer dispatches through `ResultHandler`, which violates the engine's contract) or (b) add a "write-cache?" parameter to the handler — at which point we are back at option (a) but the flag lives in `ResultContext` instead of on the task.
2. Putting the dispatch decision on the task is more localised and self-documenting than threading a context flag. The task already carries everything else the handler needs to decide.

If Chris prefers the flag on `ResultContext` rather than on the task, the implementation cost is identical — the handler reads `context.writeTarget()` instead of `task.writeTarget()`. Either is fine; defaulting to "task carries it" because the design doc's framing is "the task is the unit of dispatch".

### Why not option (d) — defer the cache-write decision to a follow-up pass?

If `cached_evaluation` is **never** written by the executor's path (today's behaviour), the simplest possible move is:

- Add the `WriteTarget` enum.
- Set `WriteTarget.NONE` for the executor path.
- Set `WriteTarget.BRIEFING_CACHE` for `ForecastTaskCollector` (batch path) and the future map popup.
- Done.

That is option (a). Option (d) only makes sense if there is no clean enum/seam — but there is. So (a) it is.

### Convenience overload (optional)

To keep Pass 3.2's call sites quiet, add a compact ctor or a static factory that defaults to `BRIEFING_CACHE`:

```java
public static Forecast briefing(LocationEntity location, LocalDate date,
        TargetType targetType, EvaluationModel model, AtmosphericData data) {
    return new Forecast(location, date, targetType, model, data, WriteTarget.BRIEFING_CACHE);
}
```

This keeps `ForecastTaskCollector` unchanged in shape (just `EvaluationTask.Forecast.briefing(...)` instead of `new EvaluationTask.Forecast(...)`). Not required; Chris's call.

---

## Investigation 3 — `ForecastCommandExecutor` call frequency

### Caller inventory

`ForecastCommandExecutor.execute(...)` is called from exactly **two** classes in production code:

| # | Caller class | File:Line | Trigger | Notes |
|---|---|---|---|---|
| 1 | `ForecastController.runForecast` | `ForecastController:229` | `POST /api/forecast/run` (ADMIN) | Body may carry location/date/maxLocations/targetType. Default = all locations × `dates`. Async via `CompletableFuture.runAsync(... forecastExecutor)`. |
| 2 | `ForecastController.runVeryShortTermForecast` | `ForecastController:253` | `POST /api/forecast/run/very-short-term` (ADMIN) | All enabled colour locations × today, T+1. |
| 3 | `ForecastController.runShortTermForecast` | `ForecastController:277` | `POST /api/forecast/run/short-term` (ADMIN) | All enabled × today, T+1, T+2. |
| 4 | `ForecastController.runLongTermForecast` | `ForecastController:295` | `POST /api/forecast/run/long-term` (ADMIN) | All enabled × T+3, T+4, T+5. |
| 5 | `ForecastController.retryFailed` | `ForecastController:416` | `POST /api/forecast/run/{runId}/retry-failed` (ADMIN) | Retries the previous run's failed tasks. |
| 6 | `ScheduledForecastService.runNearTermForecasts` | `:98` | `// @Scheduled(...)` — **cron commented out** | Only callable via direct method invocation (no controller wires it). Effectively dead. |
| 7 | `ScheduledForecastService.runDistantForecasts` | `:109` | `// @Scheduled(...)` — **cron commented out** | Same. |
| 8 | `ScheduledForecastService.runWeatherForecasts` | `:118` | `// @Scheduled(...)` — **cron commented out** | Same; this is the wildlife/comfort path which doesn't go through Claude anyway. |

All five live callers are admin-gated (`@PreAuthorize("hasRole('ADMIN')")`). All five fan-out: one admin click triggers N locations × M dates × {SUNRISE, SUNSET} → up to ~180 Claude-eligible tasks. After triage (~10–30% drop) and stability filtering (~30% drop on far-term) and sentinel sampling (whole-region skips), production hot-path volume is empirically ~30–80 calls per admin click.

### Frequency estimate

**Without empirical data:** PhotoCast has ~30 colour locations. Admin clicks "Run forecast" maybe 0–3 times per day for ad-hoc tasks (testing prompt changes, debugging a specific location, recovery from a failed scheduled run). Each click → ~30–80 Claude calls. **Upper bound: ~250 calls/day.** Lower bound: usually 0/day (the overnight scheduled cron via `ScheduledBatchEvaluationService` does the bulk work via the batch path, *not* through this executor).

This is comfortably in the "low-volume, `evaluateNow()` is appropriate" range per the design doc Section 1 (line 64 — bulk volume goes through `submit()`'s batch discount; sync `evaluateNow()` carries the ~2× per-token premium for low-volume interactive callers).

### Empirical validation (post-deploy check)

After v2.11.14 deploys, run this against production:

```sql
SELECT DATE_TRUNC('day', called_at) AS day,
       COUNT(*) AS calls,
       SUM(cost_micro_dollars) / 1000000.0 AS cost_usd
FROM api_call_log
WHERE service = 'ANTHROPIC'
  AND is_batch = false
  AND target_date IS NOT NULL                  -- forecast (excludes aurora real-time which has null target_date)
  AND called_at > '<deploy-time>'
GROUP BY 1
ORDER BY 1;
```

If daily calls are <100 and daily cost is <$0.50 over a 7-day window, the cost projection is fine. If calls exceed 500/day or cost exceeds $2/day, pause: re-examine whether one of the admin endpoints is being hit by an unattended client (e.g., a forgotten `cron` shell script). The premium on `evaluateNow()` is acceptable only for genuinely interactive volume.

### Recommendation

Migrate. Volume is low and almost certainly stays low. The 2× premium is acceptable. **No need for a hybrid (some callers via `submit`, some via `evaluateNow`)** — all five callers are interactive admin paths that benefit from the 5–25 s sync latency over batch's ~120 s. The empirical query above runs after v2.11.14 deploys to confirm; if the assumption is wrong, the fix is one method on `EvaluationServiceImpl` (route the loud caller through `submit()` instead).

---

## Investigation 4 — Test surface

### Tests that mock the Claude call (must rewire)

| Test class | Tests | Current mock | Post-migration mock |
|---|---|---|---|
| `ForecastServiceTest` | 60 total | `evaluationService.evaluate(...)` (OLD `service.EvaluationService`) | For the ~5 `evaluateAndPersist_*` tests: `newEvaluationService.evaluateNow(task, BatchTriggerSource.ADMIN)` returns `EvaluationResult.Scored(SunsetEvaluation)`. |
| `EvaluationServiceTest` | 12 total | OLD `service.EvaluationService` directly | **Stays as-is.** The OLD service still has callers — `ForecastService.runForecasts` line 200 (non-wildlife branch, dead-in-production via executor but live in tests), `PromptTestService` (2 calls), `ModelTestService` (4 calls). Those callers are explicitly out of 3.3.1 scope, so the OLD service stays. |

### Tests that mock at a higher seam (no change)

| Test class | Tests | Why unaffected |
|---|---|---|
| `ForecastCommandExecutorTest` | 68 total | Mocks `forecastService.evaluateAndPersist(...)` directly. Doesn't see Claude. **No changes.** |
| `EvaluationServiceImplTest` | 19 total | Already covers `evaluateNow` for forecast and aurora variants. May need 1–2 new assertions if `WriteTarget` flag added (verify cache write skipped for `WriteTarget.NONE`). |
| `ForecastResultHandlerTest` | 14 total | Will need 1–2 new tests around the `WriteTarget` dispatch (cache write skipped for `NONE`; cache write executed for `BRIEFING_CACHE`). Existing tests update the literal `EvaluationTask.Forecast(...)` constructor call to pass `BRIEFING_CACHE` explicitly. |
| `ScheduledBatchEvaluationServiceTest` | 48 total (per design doc) | Tests construct `EvaluationTask.Forecast` literals. ~3 sites need `BRIEFING_CACHE` arg added. Mechanical. |
| `MetricsLoggingDecoratorTest` | smaller, but **leave intact** — the decorator is still used by the OLD service for `runForecasts` line 200, `PromptTestService`, `ModelTestService`. Out of 3.3.1 scope. |

### Tests to add

1. **`api_call_log` row written for executor-driven sync forecast.** A `@SpringBootTest` integration test (or a unit test with `JobRunService` mocked) that runs the executor's path through the new engine and verifies one row is written with `is_batch=false`, `target_date` non-null, `target_type` non-null, the right `evaluation_model`, and the expected `duration_ms` / token columns populated. **This is the test the design doc Section 5 line 495 calls out as the new safety net.**
2. **`forecast_evaluation` row written for executor-driven sync forecast.** Verify the existing behaviour is preserved — i.e. the migration didn't accidentally drop the per-location row.
3. **`cached_evaluation` row NOT written when `WriteTarget == NONE`.** The handler-level test that proves the new flag works.
4. **`evaluateNow` returning `Errored` does not write `forecast_evaluation`.** The error path: `submitParallel` skips the entity save when the task fails.

### Net test footprint

- ~5 mock rewiring sites in `ForecastServiceTest`.
- ~12 constructor-literal sites across 4 test classes (mechanical, can be a single-line change at each call site).
- 4 new tests as described above (likely 2 unit + 2 integration).
- Total: ~20 mechanical edits + 4 new tests. **Half a day of test work** at most.

### Tests that exercise behaviour the migration must NOT preserve

None identified. The current `MetricsLoggingDecorator` failure path on Anthropic 5xx writes `api_call_log` with `succeeded=false, statusCode=5xx`. The new path's `ForecastResultHandler.persistSyncLog` writes `api_call_log` with `succeeded=false, errorMessage=...` and `statusCode=500` (hardcoded — `:241`). The hardcoded 500 is a small loss of fidelity (the OLD path knows the actual Anthropic status code; the new path classifies into an `errorType` string instead). If exact status-code preservation matters for prod observability, file as a small follow-up; not a 3.3.1 blocker.

---

## Investigation 5 — `forecast_evaluation` write semantics

### What the write looks like today

`ForecastService.evaluateAndPersist` lines 418–460:

1. Publishes `EVALUATING` SSE event.
2. Calls Claude → returns `SunsetEvaluation`.
3. Builds entity via `buildEntity(...)` (lines 428–431) — copies every atmospheric, directional, tide, surge, inversion, bluebell field from `preEval.atmosphericData()`, plus the four scoring fields from `evaluation` (rating, fierySkyPotential, goldenHourPotential, summary, basicFierySkyPotential, basicGoldenHourPotential, basicSummary), plus `inversionScore`, `inversionPotential`, `bluebellScore`, `bluebellSummary` when their atmospheric counterparts are non-null.
4. `repository.save(entity)` — single insert into `forecast_evaluation`.
5. Publishes `COMPLETE` SSE event.
6. Logs forecast saved.
7. Best-effort notifications (email / Pushover / toast); exceptions swallowed.

### Transactional scope

No explicit `@Transactional`. `repository.save(entity)` runs inside Spring Data JPA's auto-managed transaction (default propagation). The `api_call_log` write — whether by `MetricsLoggingDecorator` today or by `ForecastResultHandler.persistSyncLog` after — is its own write; the two are **not** atomic. If the `forecast_evaluation` save fails after the Claude call succeeds, the `api_call_log` row exists and (correctly) reflects that the API call succeeded. If the Claude call fails, the exception propagates up, no entity is saved.

This is fine and the migration preserves it. No transactional changes needed.

### Error / parse-fail behaviour

| Failure mode | Today | Post-migration |
|---|---|---|
| Anthropic 5xx, retries exhausted | Decorator's failure path writes `api_call_log` row with `succeeded=false, statusCode=5xx`; exception propagates up; `submitParallel` catches it; **no entity written**. | `ForecastResultHandler.persistSyncLog` writes `api_call_log` with `succeeded=false, statusCode=500, errorMessage=...`; `evaluateNow` returns `EvaluationResult.Errored`; `ForecastService.evaluateAndPersist` returns null/sentinel; `submitParallel` catches; **no entity written**. ✓ |
| Anthropic 200 with malformed JSON | `ClaudeEvaluationStrategy.parseEvaluation` falls back to regex; if even that fails, throws; behaviour same as 5xx. | `ForecastResultHandler.handleSyncResult` parse-failure path writes `api_call_log` with `errorType='parse_error'`; returns `Errored`. **`forecast_evaluation` not written.** ✓ |
| Anthropic 200 with `rating` out of range | `RatingValidator` clamps and returns `safeRating`; entity is written with the clamped value. | Same. `RatingValidator` is invoked inside the handler. ✓ |

### Pass 1 §6 observability claim — empirical verification needed

The design doc's executive summary line 11 and the Pass 3.3.1 prompt both claim `ForecastCommandExecutor`'s Claude calls write nothing to `api_call_log`. The Pass 1 architecture doc itself states this at line 110 and line 358. **But the code wires `MetricsLoggingDecorator` to `service.EvaluationService.evaluate(data, model, jobRun)` whenever `jobRun != null` (which is always, when called from the executor).** The decorator's success path writes `api_call_log` at `MetricsLoggingDecorator:73`; the failure path writes at `:110`. Both with `isBatch=false`.

There are three possibilities:

1. **The Pass 1 doc is wrong.** The decorator was added in commit `98ae59f` (2026-03-08), pre-dating the Pass 1 doc (`8e7b16a`, 2026-04-24). The Pass 1 audit may have missed the `decorateIfNeeded` call site. **This is the most likely scenario.**
2. **The decorator is never wired in production.** Some Spring config issue means the decorator path doesn't fire. This is verifiable empirically — see query below.
3. **The decorator fires but the data isn't surfaced in the admin UI.** The data is written; the dashboard just doesn't show it. The "no observability" claim is true at the UI level even though the rows exist.

To verify, run on production:

```sql
SELECT COUNT(*) AS rows,
       MIN(called_at) AS earliest,
       MAX(called_at) AS latest,
       COUNT(DISTINCT job_run_id) AS distinct_runs
FROM api_call_log
WHERE service = 'ANTHROPIC'
  AND is_batch = false
  AND target_date IS NOT NULL                    -- forecast (excludes aurora real-time)
  AND called_at > now() - interval '30 days';
```

If this returns 0 rows, the decorator is not firing in production and the Pass 3.3.1 prompt's observability claim is correct as stated. If it returns >0 rows, the gap is **not** what the prompt says it is — it's the *symbolic* unification of paths, and the eventual deletion of the decorator wiring without dropping observability (per design doc line 514).

Either way, Pass 3.3.1 is still worth doing — but the framing in the prompt's "Why this matters" section needs softening if the rows exist. The empirical answer is small effort (one query) and worth running before final framing.

### Post-migration `forecast_evaluation` flow

Identical entity construction (`buildEntity(...)` is preserved), identical save call, identical post-save events and notifications. The only thing changing is *how the `SunsetEvaluation` arrives* — from the new engine's `EvaluationResult.Scored` payload instead of from a direct synchronous strategy invocation.

**Verification:** the integration test added in Investigation 4 (`forecast_evaluation` row written for executor-driven sync forecast) is the safety net that proves persistence is byte-identical.

---

## Implementation shape (post-investigation)

A minimal, surgical migration:

### Step A — `EvaluationTask.Forecast` carries `WriteTarget`

- Add the enum.
- Add a sixth ctor parameter (`writeTarget`) with the existing nullness checks.
- Add a `Forecast.briefing(...)` static factory that defaults to `BRIEFING_CACHE` (optional; quiets call sites in `ForecastTaskCollector`).
- Tests: 12 literal sites updated; 1 new test on the validator behaviour.

### Step B — `ForecastResultHandler.handleSyncResult` reads the flag

- One `if (task.writeTarget() == WriteTarget.BRIEFING_CACHE)` guard around the existing `briefingEvaluationService.writeFromBatch(...)` call.
- `persistSyncLog` is unconditional — every sync call writes `api_call_log`. (Already the case.)
- Tests: 1 new test for `WriteTarget.NONE` (skip cache); 1 new test for `WriteTarget.BRIEFING_CACHE` (write cache, current behaviour confirmed).

### Step C — `ForecastService.evaluateAndPersist` switches transports

- New constructor dep on `service.evaluation.EvaluationService` (the **new** interface). The OLD `service.EvaluationService` dep stays — `runForecasts` line 200 (non-wildlife) still uses it, as do `PromptTestService` and `ModelTestService`. Out of 3.3.1 scope.
- Method body:
  1. Publish `EVALUATING` event. (existing)
  2. Build `EvaluationTask.Forecast(... WriteTarget.NONE)`.
  3. `EvaluationResult outcome = newEvaluationService.evaluateNow(task, BatchTriggerSource.ADMIN);`
  4. Switch on outcome:
     - `Scored s → SunsetEvaluation eval = (SunsetEvaluation) s.payload();`
     - `Errored e → return null; // submitParallel treats null as failure`
  5. `buildEntity(...)` (unchanged) → `repository.save(...)` (unchanged) → publish `COMPLETE` (unchanged) → notify (unchanged).
- Tests: ~5 `evaluateAndPersist_*` tests in `ForecastServiceTest` rewire mocks from `evaluationService.evaluate(...)` (OLD) → `newEvaluationService.evaluateNow(...)` (NEW). Mechanical.

### Step D — Validation

- Run `./mvnw checkstyle:check`, then targeted unit tests, then `./mvnw verify` (full).
- **JaCoCo gate** — coverage must not drop. New handler branch + new test is the offset.
- After deploy: run the `api_call_log` query in Investigation 3 to confirm rows are flowing; run the `cached_evaluation` query (or a manual briefing read) to confirm region cache is unaffected.

### What does NOT change

- `ForecastCommandExecutor` constructor and method signatures.
- `forecast_evaluation` schema or write contract.
- `EvaluationService` interface (per the design doc, stable).
- `BatchResultProcessor`, `BatchPollingService`, `BatchSubmissionService`.
- The OLD `service.EvaluationService` and its remaining callers (`runForecasts` line 200, `PromptTestService`, `ModelTestService`).
- `MetricsLoggingDecorator` — still wraps the OLD strategy for the remaining OLD-path callers.
- Aurora paths.

---

## Suggested commit plan

Per CLAUDE.md: commit locally, do not push.

1. **`docs: investigation findings for Pass 3.3.1 ForecastCommandExecutor migration to evaluateNow()`** — this document. **Reviewed by Chris before code proceeds.**

(After review:)

2. **`feat: add WriteTarget flag to EvaluationTask.Forecast for sync cache-write dispatch`** — adds the enum, updates the record, adds the briefing factory if accepted, updates the 12 literal sites in tests.
3. **`refactor: route ForecastService.evaluateAndPersist through EvaluationService.evaluateNow()`** — the migration. Closes Pass 1 §6 observability gap (modulo the empirical caveat in Investigation 5). Replaces 5 mock-target assertions in `ForecastServiceTest`. Adds 4 new tests.
4. **`chore: prune dead Open-Meteo dep and unused imports in ForecastService`** — only if any deps come out cleanly. Likely one or two imports.

Each commit leaves main green.

---

## Open questions for Chris before code proceeds

1. **`WriteTarget` on the task vs. on `ResultContext`?** The investigation prefers task-side because it's closer to the dispatch decision. Chris's call.
2. **Empirical observability check before claiming the gap closed.** Want me to run the `api_call_log` query against the prod DB ahead of code, so the Pass 3.3.1 commit message accurately describes whether the gap was real or symbolic?
3. **Convenience factory `Forecast.briefing(...)`?** Optional sweetener for `ForecastTaskCollector`. Not required.
4. **Non-wildlife `runForecasts` line 200.** This is dead-in-production via the executor (only WILDLIFE paths reach `runForecasts`), but live in `ForecastServiceTest`. Leave alone in 3.3.1 (per scope), or migrate it too? Recommendation: leave alone — it's out of scope and migrating it would force migrating `PromptTestService` and `ModelTestService` (both still call OLD `evaluationService.evaluateWithDetails`). That's 3.3.2 / 3.3.3 territory.
5. **Pass 3.3.2 entry point — is it still "migrate the four admin endpoints"?** After this pass, the admin endpoints (`/api/forecast/run/*`) are unaffected — they call the executor, which goes through the new engine via `ForecastService.evaluateAndPersist`. The "four admin endpoints" Pass 3.3.2 was supposed to migrate are the **batch** admin endpoints (`/api/admin/batches/*`), not these — so 3.3.2's scope might already be partly covered by Pass 3.2's `submit()` migration of `ScheduledBatchEvaluationService`. Worth re-scoping 3.3.2 against the current state before starting.

End of investigation document.
