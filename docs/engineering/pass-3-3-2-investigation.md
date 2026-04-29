---
name: Pass 3.3.2 Investigation — Migrate admin evaluation endpoints to EvaluationService
description: Pre-code investigation findings for Pass 3.3.2. Inventories every admin-triggered Claude path, recommends a `WriteTarget` enum design on `EvaluationTask.Forecast`, and scopes the `MetricsLoggingDecorator` deletion. Per the v2.11.14 brief, no code changes commit until this is reviewed.
---

# Pass 3.3.2 Investigation — Migrate admin evaluation endpoints to `EvaluationService`

**Status:** Investigation only. Pre-code review document. Per the v2.11.14 brief, no code changes commit until this is reviewed.

**Files inspected (key set):**

- `backend/src/main/java/com/gregochr/goldenhour/controller/ForecastController.java` (admin run endpoints + SSE)
- `backend/src/main/java/com/gregochr/goldenhour/controller/BatchAdminController.java` (admin batch endpoints)
- `backend/src/main/java/com/gregochr/goldenhour/controller/AuroraAdminController.java` (admin aurora endpoints)
- `backend/src/main/java/com/gregochr/goldenhour/controller/BriefingController.java` (admin briefing endpoints)
- `backend/src/main/java/com/gregochr/goldenhour/controller/BriefingEvaluationController.java` (SSE briefing drill-down)
- `backend/src/main/java/com/gregochr/goldenhour/controller/ModelTestController.java`, `PromptTestController.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/ForecastService.java` (line 425 — the executor's Claude call site)
- `backend/src/main/java/com/gregochr/goldenhour/service/EvaluationService.java` (the **OLD** `service.EvaluationService` facade)
- `backend/src/main/java/com/gregochr/goldenhour/service/evaluation/EvaluationService.java` (the **NEW** Pass 3.2 interface)
- `backend/src/main/java/com/gregochr/goldenhour/service/evaluation/EvaluationServiceImpl.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/evaluation/EvaluationTask.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/evaluation/ForecastResultHandler.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/evaluation/MetricsLoggingDecorator.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/batch/ScheduledBatchEvaluationService.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/batch/ForceSubmitBatchService.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/aurora/AuroraOrchestrator.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java` (SSE drill-down)
- `backend/src/main/java/com/gregochr/goldenhour/service/PromptTestService.java`, `ModelTestService.java`, `BriefingModelTestService.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/evaluation/BriefingBestBetAdvisor.java`, `BriefingGlossService.java`, `AuroraGlossService.java`
- `backend/src/test/...` — 30+ test classes across `ForecastServiceTest`, `EvaluationTaskTest`, `ForecastResultHandlerTest`, `ScheduledBatchEvaluationServiceTest`, `EvaluationServiceImplTest`, `AuroraOrchestratorTest`, `MetricsLoggingDecoratorTest`, `ModelTestServiceTest`, `PromptTestServiceTest`
- `docs/engineering/pass-3-3-1-investigation.md` (the foundation — Pass 3.3.1 was folded into this pass)

---

## Headline findings before the per-investigation walkthrough

1. **The admin-path inventory is larger than five.** Pass 3.3.1's investigation listed five `run*` callers in `ForecastController`. The full inventory is **18 admin-triggered endpoints** across six controllers — 9 of them touch Claude. After Pass 3.2 already migrated batch and aurora real-time, the **remaining work is a single migration site** (`ForecastService.evaluateAndPersist`) plus a hard-coded JFDI/force-submit pair (`ForceSubmitBatchService`) that bypasses `EvaluationService` entirely. The model-test, prompt-test, and briefing-best-bet/gloss admin paths are out of scope per the brief's "do not introduce new abstractions" rule.

2. **One migration site fans out to multiple admin endpoints.** `ForecastService.evaluateAndPersist(...)` is the convergence point for **every admin `/api/forecast/run/*` endpoint AND the SSE briefing drill-down at `/api/briefing/evaluate`**. So the single-method migration in Pass 3.3.1's plan covers more admin paths than the 3.3.1 prompt counted — including the SSE drill-down, which Pass 3.3.3 was going to retire anyway. After Pass 3.3.2: SSE drill-down's *transport* is engine-routed; only the streaming surface remains for 3.3.3 to delete.

3. **The Pass 1 §6 observability gap is closed already** (per Pass 3.3.1 investigation finding 2). The decorator wraps the executor's path in production. Pass 3.3.2's user-facing win is therefore not "close a gap" but "consolidate to one path so future deletions don't re-open the gap." The `MetricsLoggingDecorator` deletion plan is conservative — see Investigation 3.

4. **Two admin paths bypass `EvaluationService` entirely**: `ForceSubmitBatchService.submitJfdiBatch` (admin "Run JFDI Batch") and `ForceSubmitBatchService.forceSubmit` (admin "Force-submit batch") build `BatchCreateParams.Request` objects directly and call `BatchSubmissionService.submit(...)` without going through `EvaluationService.submit()`. Migrating these is mechanically easy (build `EvaluationTask.Forecast` instances, call `evaluationService.submit(tasks, BatchTriggerSource.JFDI / FORCE)`) and is in scope for this pass.

These findings shape the migration commit plan in the closing section.

---

## Investigation 1 — Complete admin path inventory

This is the critical scoping deliverable. The pass migrates every row in this table whose **current routing** is not already `submit()` or `evaluateNow()`.

### Admin endpoints that trigger Claude

| # | Endpoint | Controller method | Service method | Current routing | Post-migration routing | Frequency | Notes |
|---|---|---|---|---|---|---|---|
| 1 | `POST /api/forecast/run` | `ForecastController.runForecast` | → `ForecastCommandExecutor.execute` → `ForecastService.evaluateAndPersist` | OLD `service.EvaluationService.evaluate` (decorator-wrapped) | `EvaluationService.evaluateNow(task, ADMIN)` with `WriteTarget.NONE` | 0–3/day | Recovery / debug. ~30–80 Claude calls per click after triage. |
| 2 | `POST /api/forecast/run/very-short-term` | `ForecastController.runVeryShortTermForecast` | same chain | same | same | rare | Same pattern. |
| 3 | `POST /api/forecast/run/short-term` | `ForecastController.runShortTermForecast` | same chain | same | same | rare | Same pattern. |
| 4 | `POST /api/forecast/run/long-term` | `ForecastController.runLongTermForecast` | same chain | same | same | rare | Same pattern. |
| 5 | `POST /api/forecast/run/{runId}/retry-failed` | `ForecastController.retryFailed` | same chain | same | same | rare | Retries failed tasks from a previous run. |
| 6 | `POST /api/admin/batches/submit-scheduled` | `BatchAdminController.submitScheduledBatch` | `ScheduledBatchEvaluationService.submitScheduledBatchForRegions` | **`evaluationService.submit(..., ADMIN)`** | unchanged | rare | Already migrated in Pass 3.2. Confirmed at `ScheduledBatchEvaluationService:297, 300`. |
| 7 | `POST /api/admin/batches/submit-jfdi` | `BatchAdminController.submitJfdiBatch` | `ForceSubmitBatchService.submitJfdiBatch` | **`batchSubmissionService.submit(...)` direct** (bypasses engine) | `evaluationService.submit(tasks, BatchTriggerSource.JFDI)` with `WriteTarget.BRIEFING_CACHE` | rare | One of the four admin nuclear options. Hand-built `BatchCreateParams.Request`. |
| 8 | `POST /api/admin/batches/force-submit` | `BatchAdminController.forceSubmit` | `ForceSubmitBatchService.forceSubmit` | **`batchSubmissionService.submit(...)` direct** (bypasses engine) | `evaluationService.submit(tasks, BatchTriggerSource.FORCE)` with `WriteTarget.BRIEFING_CACHE` | rare | One of the four admin nuclear options. Single-region force. |
| 9 | `POST /api/admin/batches/reset-guards` | `BatchAdminController.resetBatchGuards` | `ScheduledBatchEvaluationService.resetBatchGuards` | no Claude call | unchanged | very rare | Emergency escape hatch only. |
| 10 | `POST /api/aurora/admin/run` | `AuroraAdminController.triggerRun` | `AuroraOrchestrator.run` | **`evaluationService.evaluateNow(task, SCHEDULED)`** | unchanged | rare | Already migrated in Pass 3.2 at `AuroraOrchestrator:273`. |
| 11 | `POST /api/aurora/admin/reset` | `AuroraAdminController.resetStateCache` | no Claude | n/a | unchanged | rare | State machine only. |
| 12 | `POST /api/aurora/admin/simulate` and `simulate/clear` | `AuroraAdminController.simulateAurora`, `clearSimulation` | no Claude | n/a | unchanged | rare | Test fixture injection. |
| 13 | `POST /api/aurora/admin/enrich-bortle` | `AuroraAdminController.enrichBortle` | `BortleEnrichmentService.enrichAll` | **lightpollutionmap.info HTTP** (not Anthropic) | unchanged | one-shot | Out of scope (different vendor). |
| 14 | `POST /api/forecast/run/tide` and `run/tide/backfill` | `ForecastController.refreshTideData`, `backfillTideData` | `ScheduledForecastService.refreshTideExtremes`, `backfillTideExtremes` | **WorldTides HTTP** (not Anthropic) | unchanged | rare | Out of scope (different vendor). |
| 15 | `POST /api/briefing/run` | `BriefingController.runBriefing` | `BriefingService.refreshBriefing` | **direct `AnthropicApiClient.createMessage` via `BriefingBestBetAdvisor.advise` + `BriefingGlossService.enrichGlosses` + `AuroraGlossService.enrichGlosses`** | **OUT OF SCOPE** | rare | Output shapes (best-bet picks, gloss strings) don't fit `EvaluationTask` / `EvaluationResult`. Migrating would require new task variants — violates "no new abstractions". Already self-logs `api_call_log` via `JobRunService.logApiCall`. |
| 16 | `POST /api/briefing/compare-models` | `BriefingController.runComparison` | `BriefingModelTestService.runComparison` → `BriefingBestBetAdvisor.compareModels` | **direct `AnthropicApiClient.createMessage`** | **OUT OF SCOPE** | very rare (admin A/B testing) | Same shape mismatch as #15. Does NOT log to `api_call_log` today (no `logApiCall` call in `compareModels`). Pre-existing observability gap; not opened or closed by 3.3.2. |
| 17 | `POST /api/model-test/run`, `run-location`, `rerun`, `rerun-determinism` | `ModelTestController.*` | `ModelTestService.executeRun` → OLD `evaluationService.evaluateWithDetails(..., null)` | **OLD service, decorator NOT wired** (jobRun=null) | **OUT OF SCOPE** | very rare | Returns `EvaluationDetail` carrying prompt+raw response for A/B/C comparison. New `EvaluationService` returns `EvaluationResult.Scored(SunsetEvaluation)` — strictly less data. Migrating requires extending `EvaluationResult` payload — violates "no new abstractions". Today silently bypasses `api_call_log` (jobRun=null). |
| 18 | `POST /api/prompt-test/run`, `replay` | `PromptTestController.*` | `PromptTestService.executeRun` → OLD `evaluationService.evaluateWithDetails(..., null)` | **OLD service, decorator NOT wired** (jobRun=null) | **OUT OF SCOPE** | very rare | Same pattern as #17. Stores prompt+raw response for replay. Same out-of-scope reasoning. |

### Side-effect of migrating endpoint #1–5: SSE drill-down rides along

`BriefingEvaluationService.evaluateRegion` (the SSE endpoint at `/api/briefing/evaluate` — `BriefingEvaluationController:73`) calls `evaluateSingleLocation(...)` per location at `BriefingEvaluationService:218`. `evaluateSingleLocation` at line 596 calls **`forecastService.evaluateAndPersist(preEval, jobRun)`** — the same method we're migrating for endpoints 1–5.

So migrating `evaluateAndPersist` once implicitly migrates the SSE drill-down's per-location Claude calls onto the engine. **The SSE plumbing itself is untouched (per the brief's exclusion).** Only the underlying transport changes. This is the "Pass 3.3.3 starts with a backend that's already engine-routed" win the brief promises.

### Observation on what is NOT in the inventory

- **`/api/aurora/forecast/run`** — exists in `AuroraForecastController` but already routed through `submit()` on a scheduled basis (per Pass 3.2). Confirm in Step 1 that the admin trigger goes through `EvaluationService.submit()` cleanly; if so, no migration needed.
- **No standalone `/api/admin/evaluate-once` endpoint exists** — every admin Claude trigger fans through one of the four routes inventoried above.

### Summary of in-scope migration work

After exclusions, the **migration work for Pass 3.3.2 is three substitutions**:

1. **`ForecastService.evaluateAndPersist`** — swap OLD `service.EvaluationService.evaluate(...)` for NEW `evaluation.EvaluationService.evaluateNow(...)` with `WriteTarget.NONE`. Closes endpoints 1–5 and SSE drill-down in one move.
2. **`ForceSubmitBatchService.submitJfdiBatch`** — replace direct `batchSubmissionService.submit(requests, FORECAST, JFDI, ...)` with `evaluationService.submit(tasks, BatchTriggerSource.JFDI)`. Build `EvaluationTask.Forecast` instances instead of hand-built `BatchCreateParams.Request`. Closes endpoint 7.
3. **`ForceSubmitBatchService.forceSubmit`** — same pattern as #2, with `BatchTriggerSource.FORCE`. Closes endpoint 8.

That's it. Everything else either (a) is already migrated (#6, #10) or (b) is out of scope (#15–18 by output-shape constraints; #9, #11–14 because no Claude call).

The investigation agrees with the brief's framing: this pass is finite. Larger than 3.2.1 but bounded.

### Risk: scope ballooning

The brief flags "if 15+ paths need migrating, the pass may need to split." We are at **3 sites**. Comfortably within a single-pass budget.

The bigger risk is investigation creep — being asked to migrate model-test or prompt-test paths out of scope. Recommendation: hold the line. They explicitly need `EvaluationDetail` (prompt + raw response for storage), which `EvaluationService.evaluateNow` does not return. Adding it would either (a) widen `EvaluationResult.Scored.payload` opaquely (already an `Object`; this is fine but doesn't help) or (b) add a dedicated `evaluateForRegression(...)` method — both are abstractions beyond `WriteTarget`, both are explicitly out of scope per the brief.

---

## Investigation 2 — `WriteTarget` enum design

Per the Pass 3.3.1 investigation's recommendation. This investigation reaffirms the design and answers the open questions.

### Recommended design (unchanged from Pass 3.3.1)

`WriteTarget` lives **on `EvaluationTask.Forecast` directly** as a nested enum, with two values:

```java
record Forecast(
        LocationEntity location,
        LocalDate date,
        TargetType targetType,
        EvaluationModel model,
        AtmosphericData data,
        WriteTarget writeTarget                 // NEW
) implements EvaluationTask {

    public enum WriteTarget {
        NONE,            // engine returns parsed result; caller handles persistence
        BRIEFING_CACHE   // engine writes to cached_evaluation via ForecastResultHandler
    }
    // … existing requireNonNull checks + new requireNonNull for writeTarget …
}
```

The aurora task is unaffected. No new abstractions beyond this enum.

### Should there be a third value `PER_LOCATION_FORECAST_TABLE`?

**No.** Two reasons:

1. The brief explicitly defers `forecast_evaluation` reader migration to v2.13. Putting `forecast_evaluation` write responsibility on the engine pre-empts that migration without unlocking anything in Pass 3.3.2.
2. The `forecast_evaluation` write is currently coupled to the entity-build logic in `ForecastService.buildEntity(...)` — a 90-line method that consumes ~40 fields off `AtmosphericData` plus `SunsetEvaluation`. Moving that into `ForecastResultHandler` would require either (a) lifting `buildEntity` to the engine, which is a refactor; or (b) calling back to `ForecastService` from the handler, which inverts the dep direction. Both are out of the surgical-substitution scope of 3.3.2.

The conservative answer holds: **`WriteTarget` enum with two values; per-location `forecast_evaluation` write stays on `ForecastService.evaluateAndPersist`** which calls `evaluateNow` with `WriteTarget.NONE` and handles its own `repository.save(...)`.

### Default value

**Don't default.** Make the field non-null with a `requireNonNull` check in the compact ctor. Every call site explicitly states its intent. Two consumers:

- `ForecastTaskCollector` (batch path, 2 sites — `:265`, `:385`) — pass `WriteTarget.BRIEFING_CACHE`
- `ForecastService.evaluateAndPersist` (sync path, 1 site post-migration) — pass `WriteTarget.NONE`
- `ForceSubmitBatchService.submitJfdiBatch`, `.forceSubmit` (admin batch paths, 2 sites post-migration) — pass `WriteTarget.BRIEFING_CACHE` (these populate the briefing cache, same as the scheduled batch)

The "convenience factory `Forecast.briefing(...)`" suggested in Pass 3.3.1 is **rejected**. It doesn't materially shorten call sites; it adds an alternative ctor that future readers must understand. Five call sites is below the threshold where a factory pays.

### Handler dispatch

In `ForecastResultHandler.handleSyncResult`, the dispatch is one `if`:

```java
persistSyncLog(context, outcome, task);                                  // unconditional
if (task.writeTarget() == WriteTarget.BRIEFING_CACHE) {
    String cacheKey = CacheKeyFactory.build(regionName, task.date(), task.targetType());
    briefingEvaluationService.writeFromBatch(cacheKey, List.of(result)); // existing behaviour
}
return new EvaluationResult.Scored(eval);
```

The `parseBatchResponse` path (the batch flow) stays as-is — batch results always go through `flushCacheKey(...)` after aggregation, which is `WriteTarget.BRIEFING_CACHE` semantics by definition. **No change to `parseBatchResponse`.**

This means the `WriteTarget` field is **only consulted on the sync path**. Batch flow ignores it entirely. The field is technically wasted on batch tasks — but the cost is zero (one enum reference) and the symmetry of "every Forecast task has a write target" is worth it.

### Constructor literal sites needing update

Inventoried via `grep -rn "new EvaluationTask.Forecast"`:

| Site | File:Line | New arg |
|---|---|---|
| Production (batch) | `ForecastTaskCollector:265` | `WriteTarget.BRIEFING_CACHE` |
| Production (batch) | `ForecastTaskCollector:385` | `WriteTarget.BRIEFING_CACHE` |
| Test | `EvaluationTaskTest:46, 60, 73, 83, 85, 87, 89, 91, 98` (9 sites) | `WriteTarget.BRIEFING_CACHE` (default for existing assertions) + 1 new test for null check |
| Test | `ScheduledBatchEvaluationServiceTest:131, 134, 167` (3 sites) | `WriteTarget.BRIEFING_CACHE` |
| Test | `ForecastResultHandlerTest:282, 310, 334, 354` (4 sites) | mix of both — see Investigation 4 |
| Test | `EvaluationServiceImplTest:478` (1 site, in helper method) | `WriteTarget.BRIEFING_CACHE` |

Total: **17 production literal sites** + new sites added for the JFDI/force-submit migrations. Mechanical.

---

## Investigation 3 — `MetricsLoggingDecorator` removal plan

### Today's wire-up

`MetricsLoggingDecorator` is created by `service.EvaluationService.decorateIfNeeded(...)` (line 98) whenever a non-null `JobRunEntity` is passed. It is **not** a singleton bean — it's instantiated per-call. So "deleting the decorator" means:

1. Removing the `MetricsLoggingDecorator` class.
2. Removing the `decorateIfNeeded` method on the OLD `service.EvaluationService`.
3. Inlining or simplifying the decorator-free `getStrategy` lookup.

### Callers of OLD `service.EvaluationService` after Pass 3.3.2

After migration completes:

| Caller | Method | Passes jobRun? | Decorator currently fires? | After 3.3.2? |
|---|---|---|---|---|
| `ForecastService.runForecasts` line 200 (non-wildlife branch) | `evaluate(data, model, jobRun)` | yes | yes | **still fires** — branch is dead in production but live in tests |
| `ForecastService.evaluateAndPersist` (line 425 today) | `evaluate(data, model, jobRun)` | yes | yes | **migrated to new engine; OLD call deleted** |
| `PromptTestService` (line 185, 342) | `evaluateWithDetails(data, model, null)` | no (null) | no | unchanged |
| `ModelTestService` (line 172, 310, 457, 594) | `evaluateWithDetails(data, model, null)` | no (null) | no | unchanged |

So **after Pass 3.3.2, the decorator fires only for `ForecastService.runForecasts` non-wildlife** — a branch that is dead in production (the production hot path goes through `ForecastCommandExecutor` → `evaluateAndPersist`, not `runForecasts`) but is still exercised by ~5 tests in `ForecastServiceTest`.

### Three options

**(a) Delete `MetricsLoggingDecorator` in this pass.**

Requires also deleting / updating the dead `runForecasts` non-wildlife branch and its 5 tests. The branch is genuinely dead — the executor uses `fetchWeatherAndTriage` + `evaluateAndPersist` for non-wildlife. So the dead branch could be deleted too. But that's a separate refactor unrelated to "migrate admin endpoints to EvaluationService" — it's clean-up of a parallel path. **Out of scope for this pass; risks scope creep.**

**(b) Keep `MetricsLoggingDecorator` for now; defer to Pass 3.3.3 or v2.13.**

Decorator survives. `runForecasts` non-wildlife branch survives. Both are dead in production but live in tests. Pass 3.3.3 (SSE retirement) doesn't touch them. v2.13 picks up the cleanup when it touches the `forecast_evaluation` reader — at which point the dead branches become obvious and can be culled together.

**(c) Delete the decorator AND the dead `runForecasts` non-wildlife branch in this pass, as a final commit.**

Faster overall but expands scope.

### Recommendation: option (b) — defer

Reasons:

1. **The brief says "if Investigation 3 confirms it's safe; otherwise skip."** "Safe" here means "deletes don't break anything in or out of production." Deleting the decorator IS safe (its only live caller is dead in production), but only if `runForecasts` non-wildlife is also deleted or its tests are rewritten. That's a refactor.
2. **Pass 3.3.3 may simplify this further.** When SSE drill-down's *streaming* surface is retired, the question of "do we still need the OLD `service.EvaluationService` for `evaluateWithDetails`?" becomes louder. PromptTest/ModelTest are the only consumers of `evaluateWithDetails`. They could plausibly migrate to a dedicated `EvaluationDetailService` or stay on the OLD service — that's a 3.3.3-or-later call.
3. **A separate cleanup pass costs little.** The decorator is small (124 lines) and harmless. Carrying it through 3.3.2 → 3.3.3 → v2.13 is cheap.

**Outcome for Pass 3.3.2 commit plan:** no `chore: delete MetricsLoggingDecorator` commit. Add a one-line note to `pass-3-3-3` planning that it's fair game alongside SSE work.

If Chris prefers option (a) or (c), the implementation cost is small but the test changes are non-trivial: `MetricsLoggingDecoratorTest` (203 lines, ~10 tests) and the 5 `ForecastServiceTest` tests touching the non-wildlife `runForecasts` branch all need rewriting or deleting. Quote ~2 hours of test work on top of the migration.

---

## Investigation 4 — Test surface

### Tests that mock the OLD service (must rewire after migration)

**`ForecastServiceTest`** — 60 total tests, ~30 mock sites for `evaluationService.evaluate(forecastData, ..., ...)`. After migration:

- Tests that exercise `evaluateAndPersist` (~20 sites, identifiable by their flow: triage already done → fan into `evaluateAndPersist`) rewire to mock the NEW engine: `when(newEvaluationService.evaluateNow(any(EvaluationTask.Forecast.class), eq(BatchTriggerSource.ADMIN))).thenReturn(EvaluationResult.Scored.of(evaluation))`.
- Tests that exercise the dead `runForecasts` non-wildlife branch (~5 sites at lines 977, 1134, 1192, 1230, 1448, etc.) keep mocking the OLD service. They are unaffected by the migration.

The challenge is identifying which is which. Pragmatic approach: rewire all mocks to the NEW service, run the test class, see which fail. The failing ones are the 5 `runForecasts` tests — back out the rewire on those; keep the OLD service dep injected into `ForecastServiceTest` for them.

Estimate: **2–3 hours of test work** for `ForecastServiceTest` alone. Largest test impact in this pass.

**`ScheduledBatchEvaluationServiceTest`** — 3 `EvaluationTask.Forecast` constructor literals at `:131, :134, :167`. Each gets a 6th arg `WriteTarget.BRIEFING_CACHE`. Mechanical.

**`EvaluationTaskTest`** — 9 constructor literals. All get `WriteTarget.BRIEFING_CACHE` as the 6th arg, except the existing nullness tests at `:73, 83, 85, 87, 89, 91` which need a new sibling test asserting `requireNonNull(writeTarget)`.

**`ForecastResultHandlerTest`** — 4 constructor literals at `:282, 310, 334, 354`. Most existing tests assert `cached_evaluation` IS written, so they pass `BRIEFING_CACHE`. **2 new tests required** per Investigation 2:

1. `handleSyncResult writes api_call_log AND cached_evaluation when WriteTarget.BRIEFING_CACHE` (current behaviour)
2. `handleSyncResult writes api_call_log but NOT cached_evaluation when WriteTarget.NONE`

**`EvaluationServiceImplTest`** — 1 helper method at `:478` constructs forecast tasks for tests. Mechanical update. May need 1 added assertion if any test asserts cache is written via the engine sync path.

**`AuroraOrchestratorTest`** — unaffected. `EvaluationTask.Aurora` doesn't change.

### Tests for new migration sites (`ForceSubmitBatchService`)

`ForceSubmitBatchServiceTest` (if present — verify) needs:

- Test that `submitJfdiBatch` builds `EvaluationTask.Forecast` instances with `WriteTarget.BRIEFING_CACHE` and submits via `evaluationService.submit(...)` with `BatchTriggerSource.JFDI`.
- Test that `forceSubmit` does the same with `BatchTriggerSource.FORCE`.

Verify the test class exists and inventory before code commit.

### Tests to add (engine-routed observability assertions)

Per the brief's success criteria ("admin per-location evaluation produces an api_call_log row via the engine"):

1. **Integration test or unit test** asserting that calling `evaluateAndPersist` writes one `api_call_log` row with `is_batch=false`, `target_date` non-null, `target_type` non-null. This is the test that closes Pass 1 §6's surface area.
2. **Negative test:** when `evaluateNow` returns `EvaluationResult.Errored`, no `forecast_evaluation` row is written and `submitParallel` treats the slot as failed.

Per Pass 3.3.1's investigation, both can be implemented as unit tests with mocked `EvaluationService` and mocked `JobRunService`.

### Net test footprint

- ~30 mock-rewire sites in `ForecastServiceTest` (largest impact).
- ~17 constructor-literal updates across 5 test classes.
- ~6 new tests (2 for `WriteTarget` dispatch in `ForecastResultHandlerTest`, 2 for engine routing in `ForecastServiceTest`, 2 for `ForceSubmitBatchService` migration).
- `MetricsLoggingDecoratorTest` and `EvaluationServiceTest` (OLD service) **unchanged** per Investigation 3 (decorator deferred).

**Estimated test work: half a day to a day.** Bounded.

---

## Investigation 5 — Edge case: transactional concerns

### `cached_evaluation` writes

`BriefingEvaluationService.writeFromBatch` (line 328) is **not** annotated `@Transactional`. It uses `cache.put(...)` for in-memory and `persistToDb(...)` for the DB write. `persistToDb` (line 544) is also not transactional — it does a `findByCacheKey` + `save` non-atomically, with try/catch swallowing failures.

So **the existing `cached_evaluation` write semantics are best-effort** — not atomic with anything. The migration preserves this exactly: the same `writeFromBatch` is called from the same `ForecastResultHandler.handleSyncResult` location after migration.

### `forecast_evaluation` writes

`ForecastService.evaluateAndPersist` (line 418) is not `@Transactional`. It's `@Bulkhead(name = "claude")` for concurrency limiting only. The `repository.save(entity)` runs inside Spring Data JPA's auto-managed transaction (default propagation REQUIRES_NEW won't apply because no annotation is set; the `save` opens its own short transaction).

The sequence today is:
1. `evaluationService.evaluate(...)` — Claude call + `api_call_log` write (own transaction or none, via decorator).
2. `repository.save(entity)` — `forecast_evaluation` write (its own short transaction).
3. Notifications (best-effort, exceptions swallowed).

After migration, the sequence becomes:
1. `evaluationService.evaluateNow(task, ADMIN)` — Claude call + `api_call_log` write (own short transaction inside `JobRunService.logAnthropicApiCall`) + (if `BRIEFING_CACHE`) `cached_evaluation` write.
2. (Caller, with `WriteTarget.NONE`) `repository.save(entity)` — `forecast_evaluation` write (its own short transaction).
3. Notifications.

**The migration changes nothing about transactional scope.** Both paths are non-atomic. Both rely on best-effort log writes inside `try/catch`. The existing semantics are preserved.

### `JobRunService.logAnthropicApiCall` participation

`JobRunService.logAnthropicApiCall` (line 133) is `@Transactional` (verified from the inventory in Pass 3.3.1's investigation, line 354). It opens its own short transaction. This holds in both the OLD decorator path and the NEW handler path. Identical.

### Recommendation

**No transactional changes needed.** The migration is byte-identical from the outside. Any future tightening of transactional guarantees (e.g. atomic `forecast_evaluation` + `api_call_log`) is v2.13+ territory.

---

## Migration shape (post-investigation, pre-code)

A series of substitutions per the brief.

### Step A — `EvaluationTask.Forecast` carries `WriteTarget`

- Add the enum (two values).
- Add the field (sixth arg) with a `requireNonNull` check.
- Update 17 production constructor literals (2 in `ForecastTaskCollector`, the rest in tests).
- Add `EvaluationTaskTest` cases for null check.

### Step B — `ForecastResultHandler.handleSyncResult` dispatches on `WriteTarget`

- One `if (task.writeTarget() == WriteTarget.BRIEFING_CACHE)` guard around `briefingEvaluationService.writeFromBatch(...)`.
- `persistSyncLog` stays unconditional.
- Add 2 tests — `BRIEFING_CACHE` writes cache; `NONE` doesn't.

### Step C — Migrate `ForecastService.evaluateAndPersist`

- Replace `evaluationService.evaluate(...)` (OLD service) with:
  ```java
  EvaluationTask.Forecast task = new EvaluationTask.Forecast(
          preEval.location(), preEval.date(), preEval.targetType(),
          preEval.model(), preEval.atmosphericData(),
          EvaluationTask.Forecast.WriteTarget.NONE);
  EvaluationResult outcome = newEvaluationService.evaluateNow(task, BatchTriggerSource.ADMIN);
  SunsetEvaluation evaluation = switch (outcome) {
      case EvaluationResult.Scored s -> (SunsetEvaluation) s.payload();
      case EvaluationResult.Errored e -> {
          // best-effort: log; throw so submitParallel marks the slot failed (preserves today's behaviour)
          throw new EvaluationFailedException(e.errorType(), e.message());
      }
  };
  ```
  (or return null instead of throwing — the existing call site `submitParallel` already catches exceptions per-task, so either is fine. Pass 3.3.1's investigation recommends throwing because the OLD path throws too — preserves error propagation semantics.)
- Inject `service.evaluation.EvaluationService` as new dep on `ForecastService`. Keep the OLD `service.EvaluationService` dep — `runForecasts` line 200 (non-wildlife) still uses it.
- Update ~30 `ForecastServiceTest` mock-rewires; identify and preserve the ~5 that exercise the dead `runForecasts` non-wildlife branch.

### Step D — Migrate `ForceSubmitBatchService.submitJfdiBatch`

- Replace `requests` (List of `BatchCreateParams.Request`) with `tasks` (List of `EvaluationTask.Forecast`).
- Each task built from the same `forecastService.fetchWeatherAndTriage(...)` output as today, with `WriteTarget.BRIEFING_CACHE`.
- Replace `batchSubmissionService.submit(requests, FORECAST, JFDI, ...)` with `evaluationService.submit(tasks, BatchTriggerSource.JFDI)`.
- The engine builds the `BatchCreateParams.Request` via `BatchRequestFactory` — same code path as the scheduled batch. `CustomIdFactory.forJfdi` becomes implicit (the engine routes by `BatchTriggerSource`); confirm `BatchRequestFactory.buildForecastRequest` writes the right custom-id format for JFDI submissions, and if it doesn't, that's a small extension to the factory.
- `FORCE_JFDI_MAX_TOKENS = 512` is currently passed as a 4th arg to `buildForecastRequest`. The engine uses `task.model().getMaxTokens()` — verify the value matches or accept the change. **TBD: confirm `JFDI` and `FORCE` paths can use `model.getMaxTokens()` instead of a hard-coded 512.** If they can't (some historical reason), the engine needs a max-tokens override path — that's an engine-shape change. Flag at code review.
- Drop `AnthropicClient anthropicClient` dep on `ForceSubmitBatchService` only if `getResult(...)` is also migrated (verify it doesn't bypass `BatchPollingService`).

### Step E — Migrate `ForceSubmitBatchService.forceSubmit`

- Same pattern as Step D, with `BatchTriggerSource.FORCE`.
- The result-summary shape (`ForceSubmitResult` with batch-id, request count, failed locations) is preserved — the engine returns an `EvaluationHandle` from which the same fields can be derived.

### Step F — Validation

- `./mvnw checkstyle:check` first.
- Targeted test classes — `ForecastServiceTest`, `ForecastResultHandlerTest`, `EvaluationTaskTest`, `ScheduledBatchEvaluationServiceTest`, `ForceSubmitBatchServiceTest`.
- Full `./mvnw verify`.
- JaCoCo gate — must hold (new dispatch + new tests should offset migration churn).
- Post-deploy: run the SQL queries from Pass 3.3.1 Investigation 5 to confirm `api_call_log` rows are flowing through the engine path.

### What stays untouched (per the brief)

- `ForecastCommandExecutor` constructor and method signatures.
- `forecast_evaluation` schema and write contract.
- `EvaluationService` public interface (only `EvaluationTask.Forecast` extended).
- `BatchResultProcessor`, `BatchPollingService`, `BatchSubmissionService` internals.
- All SSE controllers (`/api/forecast/run/{runId}/progress`, `/api/forecast/run/notifications`, `/api/briefing/evaluate`).
- `MetricsLoggingDecorator` — survives, per Investigation 3.
- OLD `service.EvaluationService` — survives; PromptTest/ModelTest/`runForecasts` still use it.
- All aurora paths — already engine-routed.
- All best-bet / gloss services — different output shapes, out of scope.
- Frontend.

---

## Suggested commit plan

Per CLAUDE.md: commit locally, do not push.

1. **`docs: investigation findings for Pass 3.3.2 admin endpoint migration`** — this document. Reviewed by Chris before code proceeds.

(After review:)

2. **`feat: extend EvaluationTask.Forecast with WriteTarget enum and update ForecastResultHandler dispatch`** — Step A + Step B together. 17 constructor literal updates + handler dispatch + 2 new handler tests.
3. **`refactor: migrate ForecastService.evaluateAndPersist to EvaluationService.evaluateNow`** — Step C. The largest commit by test-touch, but a single conceptual change.
4. **`refactor: migrate ForceSubmitBatchService JFDI and force-submit paths to EvaluationService.submit`** — Step D + Step E together. Closes endpoints 7 and 8.

If `MetricsLoggingDecorator` deletion is approved (option (a) or (c) from Investigation 3) in code review, add:

5. **`chore: delete MetricsLoggingDecorator and ForecastService.runForecasts dead non-wildlife branch`** — only if Chris approves the scope expansion.

Each commit leaves main green. JaCoCo passes locally before push.

---

## Open questions for Chris before code proceeds

1. **`MetricsLoggingDecorator` deletion — defer or include?** Investigation 3 recommends defer (option (b)). Confirm or override to option (a)/(c). If (a)/(c), expect ~2 hours additional test work and an expanded commit plan.

2. **`FORCE_JFDI_MAX_TOKENS = 512` vs `model.getMaxTokens()`.** Today the JFDI/force-submit paths hard-code 512 tokens. The engine uses `task.model().getMaxTokens()` which is currently 1024 for HAIKU and 2048 for SONNET. Are these JFDI paths intentionally constrained to 512 (cost control? historical artifact?), or is the limit incidental? If they can use the model defaults, migration is straightforward. If not, the engine needs a token-override path — confirm before starting.

3. **`ForceSubmitBatchService.getResult(...)` polling-bypass.** It calls `anthropicClient.messages().batches().retrieve(batchId)` directly, bypassing `BatchPollingService`. Per Pass 2.5 design, this was intentional. Migrating the submit paths to the engine doesn't affect this bypass. Confirm OK to leave as-is; not in scope.

4. **Briefing model-test (#16) observability gap.** `BriefingBestBetAdvisor.compareModels` doesn't write `api_call_log` rows today (`callModel` at `:953` doesn't call `logApiCall`, unlike `advise` at `:375` which does). This is a pre-existing observability gap, not opened or closed by 3.3.2. Worth filing as a separate v2.12+ issue?

5. **PromptTest / ModelTest migration deferral.** Investigation 1 places these out of scope because they need `EvaluationDetail` (prompt + raw response). Confirmation requested: is "they keep the OLD service" the durable answer, or is there a desire to extend `EvaluationResult.Scored.payload` to carry an optional `EvaluationDetail` later? If yes, that's a v2.12+ design pass — not 3.3.2 work.

End of investigation document.
