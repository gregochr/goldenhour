# SSE retirement investigation (Pass 3.3.3)

Investigation only — no code changes. Purpose: map the SSE evaluation surface exhaustively so the deletion that follows is safe.

The prior intraday/SSE coupling check established three baseline facts that this investigation builds on:
- The SSE and batch paths share **no candidate-collection or submission code**.
- Both paths write the cache with identical full-replace semantics through the same `persistToDb`.
- SSE calls `cancelOutstandingForecastBatches()` at the start of `evaluateRegion`, killing in-flight overnight batches whenever a user drills down — the cost coupling that motivated retiring SSE first.

This document drills the remaining unknowns: exactly what gets deleted, what's shared, what's already dead, and what capability (if any) is lost.

---

## Section 1 — SSE call graph (SSE-ONLY vs SHARED)

### Controller endpoints (all under `/api/briefing/evaluate`)

[BriefingEvaluationController.java](../../backend/src/main/java/com/gregochr/goldenhour/controller/BriefingEvaluationController.java)

| Route | Method | Auth | Calls | Tag |
|---|---|---|---|---|
| `/api/briefing/evaluate` | GET (SSE) | PRO+ADMIN | `evaluationService.evaluateRegion` | **SSE-ONLY** |
| `/api/briefing/evaluate/cache` | GET | PRO+ADMIN | `evaluationService.getCachedScores` | **SHARED (read-only)** but unused — see §4 |
| `/api/briefing/evaluate/cache/timestamp` | GET | PRO+ADMIN | `evaluationService.getCachedEvaluatedAt` | **SHARED (read-only)** but unused — see §4 |
| `/api/briefing/evaluate/scores` | GET | **NO `@PreAuthorize`** | `evaluationViewService.forDateRange` | **SHARED** — used by Map tab + DailyBriefing |
| `/api/briefing/evaluate/cache` | DELETE | ADMIN | `evaluationService.clearCache` | **SHARED** — admin escape hatch |

The controller mixes one SSE endpoint with four REST endpoints. Three of the REST endpoints survive SSE retirement; one (`/cache`) appears unused (§4).

### Service methods on `BriefingEvaluationService`

[BriefingEvaluationService.java](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java)

```
BriefingEvaluationController.evaluate (controller:71)
   ↓
evaluateRegion (service:147)                              [SSE-ONLY]
   ├─ cancelOutstandingForecastBatches (service:152, 422) [SSE-ONLY]
   ├─ getEvaluableLocationNames (service:161, 612)        [SSE-ONLY]
   ├─ emitCachedResults (service:169, 631)                [SSE-ONLY]
   │     ├─ sendSafe (service:656)                        [SSE-ONLY]
   │     └─ completeEmitter (service:648)                 [SSE-ONLY]
   ├─ briefingService.isColourLocation                    [SHARED — survives]
   ├─ modelSelectionService.getActiveModel                [SHARED — survives]
   ├─ jobRunService.startRun/completeRun                  [SHARED — survives]
   ├─ evaluateSingleLocation (service:217, 582)           [SSE-ONLY]
   │     └─ forecastService.fetchWeatherAndTriage         [SHARED — survives]
   │     └─ forecastService.evaluateAndPersist            [SHARED — survives]
   │     └─ RatingValidator.validateRating                [SHARED — survives]
   ├─ cache.put (service:222, 239)                        [SHARED field, SSE-ONLY call sites]
   ├─ persistToDb (service:240, 543) source="SSE"         [SHARED — also called from writeFromBatch]
   ├─ sendSafe / completeEmitter                          [SSE-ONLY]
```

### Methods that LIVE in the same class but are batch-only (survive)

| Method | Caller | Tag |
|---|---|---|
| `getCachedScores` (262) | `EvaluationViewService` + REST endpoint | **SHARED — survives** |
| `getCachedEvaluatedAt` (277) | REST endpoint only (no frontend caller) | **SHARED — but dead** |
| `hasEvaluation` (292) | **No production callers** (only tests) | **SHARED — survives (future intraday use)** |
| `hasFreshEvaluation` (309) | `ForecastTaskCollector:530` (batch) | **SHARED — survives** |
| `writeFromBatch` (327) | `ForecastResultHandler:175, 215` (batch + sync) | **SHARED — survives** |
| `logEvaluationDeltas` (343) | `writeFromBatch` | **SHARED — survives** |
| `buildStabilityLookup` (400) | `logEvaluationDeltas` | **SHARED — survives** |
| `clearCache` (452) | Admin DELETE endpoint | **SHARED — survives** |
| `rehydrateCacheOnStartup` (471) | `@EventListener(ApplicationReadyEvent.class)` | **SHARED — survives** |
| `onBriefingRefreshed` (529) | `@EventListener` for `BriefingRefreshedEvent` | **SHARED — survives (no-op log)** |
| `persistToDb` (543) | Both SSE write site AND `writeFromBatch` | **SHARED — survives** |

### Deletion line within `BriefingEvaluationService`

SSE-only methods to remove (≈140 lines of 663):
- `evaluateRegion` (147–252) — 106 lines
- `cancelOutstandingForecastBatches` (422–444) — 23 lines
- `evaluateSingleLocation` (582–607) — 26 lines
- `getEvaluableLocationNames` (612–629) — 18 lines
- `emitCachedResults` (631–646) — 16 lines
- `completeEmitter` (648–654) — 7 lines
- `sendSafe` (656–662) — 7 lines

Plus injected dependencies that become unused after deletion:
- `anthropicClient` (constructor param, used only by `cancelOutstandingForecastBatches`)
- `batchRepository` (constructor param, used only by `cancelOutstandingForecastBatches`)
- The `BriefingService` dependency may also drop if `isColourLocation` has no other call site — needs a quick verify before deletion.

The remaining ~520 lines of `BriefingEvaluationService` stay intact.

---

## Section 2 — The `cancelOutstandingForecastBatches` coupling

**Confirmed**: invoked from exactly one production call site — [BriefingEvaluationService.java:152](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java:152), inside `evaluateRegion`. No other production code calls it. Tests reference it through `evaluateRegion`.

The capability it uses (`anthropicClient.messages().batches().cancel`) has no other usage in the codebase. After SSE retirement, both the method and the capability go. The `BatchAdminController` ([BatchAdminController.java](../../backend/src/main/java/com/gregochr/goldenhour/controller/BatchAdminController.java)) does NOT expose a "cancel batch" endpoint — only `reset-guards` (a different feature, resets in-memory boolean guards). So there is no admin batch-cancel feature to preserve. The cancel capability is purely a side-effect of SSE; it disappears entirely with no replacement needed.

---

## Section 3 — Shared cache-write path

`persistToDb` (line 543) is shared. SSE calls it with `source="SSE"` at line 240; `writeFromBatch` calls it with `source="BATCH"` at line 334. The method itself is source-agnostic — it parses the cache key, serialises the result list, and upserts a `CachedEvaluationEntity` row. **`persistToDb` survives unchanged.**

`cache.put` (the in-memory `ConcurrentHashMap`) is called from four sites:
- Line 222: SSE per-location partial write (delete)
- Line 239: SSE final write (delete)
- Line 333: `writeFromBatch` (keep)
- Line 506: `rehydrateCacheOnStartup` (keep)

Removing SSE removes the first two call sites. The map field itself stays.

### SSE-only cache behaviours that need preserving — NONE

- **No SSE-only cache invalidation**: `onBriefingRefreshed` (528) explicitly retains the cache regardless of source. It's a no-op log.
- **No SSE-only TTL**: `hasFreshEvaluation` applies the same Duration regardless of source.
- **No SSE-only event publication**: SSE does not publish any application events when its writes complete.
- **No SSE-only rehydration path**: `rehydrateCacheOnStartup` reads all rows ≥ today regardless of `source`.

Cache infrastructure survives SSE retirement cleanly.

---

## Section 4 — Hidden-coupling sweep

### A — SSE symbols outside the obvious files

Greps for `evaluateRegion`, `evaluateSingleLocation`, `cancelOutstandingForecastBatches`, `TEXT_EVENT_STREAM`, `SseEmitter`:

- `evaluateRegion` / `evaluateSingleLocation` / `cancelOutstandingForecastBatches`: **zero production references outside `BriefingEvaluationService`**. Test-only otherwise.
- `SseEmitter` / `TEXT_EVENT_STREAM_VALUE` appears in **three independent SSE features** that must NOT be touched:
  - [StatusController.java:103-104](../../backend/src/main/java/com/gregochr/goldenhour/controller/StatusController.java:103) — `/api/status/stream` (system status SSE)
  - [ForecastController.java:417-419](../../backend/src/main/java/com/gregochr/goldenhour/controller/ForecastController.java:417) — `/api/forecast/run/{runId}/progress` (admin run progress)
  - [ForecastController.java:429-430](../../backend/src/main/java/com/gregochr/goldenhour/controller/ForecastController.java:429) — `/api/forecast/run/notifications` (run-complete notifications)
  - These use `RunProgressTracker` ([service/RunProgressTracker.java](../../backend/src/main/java/com/gregochr/goldenhour/service/RunProgressTracker.java)), a separate SSE infrastructure unrelated to briefing evaluation.

### B — Readers of the `cached_evaluation.source` column

**Critical finding: there are no readers.**

- Grep for `getSource()` and `.source` on the entity: zero production callers. The column is purely written (line 240 for SSE, line 334 for BATCH), never read.
- [CachedEvaluationEntity.java](../../backend/src/main/java/com/gregochr/goldenhour/entity/CachedEvaluationEntity.java) defines `private String source` with Lombok `@Getter @Setter`, but no consumer ever calls `getSource()` outside the entity itself.
- No JPQL or native query filters by source.
- [V91__create_cached_evaluation_table.sql:11](../../backend/src/main/resources/db/migration/V91__create_cached_evaluation_table.sql:11) defines the column as `NOT NULL VARCHAR(20)` with no index, constraint, or backfill referencing specific values.
- The literal string `"SSE"` appears in: (a) the write site, (b) a Javadoc comment, (c) the test assertion at `BriefingEvaluationServiceTest.java:1311`, (d) coincidentally in `PromptUtils.java` as a compass direction. None of these read column values.
- **Important disambiguation**: `LocationEvaluationView.Source` is a separate Java enum `{CACHED_EVALUATION, FORECAST_EVALUATION_SCORED, FORECAST_EVALUATION_TRIAGE, NONE}`. It tells the frontend which TABLE the row came from, never SSE-vs-BATCH. Its Javadoc explicitly says `CACHED_EVALUATION` = "From cached_evaluation table (batch or SSE)". Source-agnostic.

Implication: when SSE rows stop being created, **nothing in the codebase notices or behaves differently**. The `source` column becomes pure audit metadata that always reads "BATCH". Could be left as-is or made nullable in a later cleanup — out of scope.

### C — Branching on RunType / user-initiated

- `evaluateRegion` records its job runs as `RunType.SHORT_TERM, isUserInitiated=true` (line 197).
- Grep for `isUserInitiated` / `userInitiated` in production: **zero downstream branching**. The flag is stored on `JobRunEntity` for reporting only. After SSE removal, no SHORT_TERM job runs will be created via this path; SHORT_TERM jobs from `ForecastCommandExecutor` (admin "Run Forecast" button) still work.

### D — Frontend event-name backend readers

None. Event names (`location-scored`, `evaluation-error`, `progress`, `evaluation-complete`) are frontend-only contracts produced by `sendSafe` and consumed only by `briefingEvaluationApi.js`.

### E — Cache-write side: a sync-write-to-briefing-cache exists but is dormant

[EvaluationServiceImpl.evaluateNow](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/EvaluationServiceImpl.java:111) is a synchronous-Anthropic path used by `ForecastService:440` (admin "Run Forecast" via ForecastCommandExecutor) and `AuroraOrchestrator:273` (aurora).

[ForecastResultHandler.handleSyncResult:213](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/ForecastResultHandler.java:213) will call `writeFromBatch` IFF `task.writeTarget() == BRIEFING_CACHE`. But:
- `ForecastService:438` passes `WriteTarget.NONE` — admin "Run Forecast" does NOT write to briefing cache.
- `BRIEFING_CACHE` is only set by `ForecastTaskCollector` and `ForceSubmitBatchService` — both batch paths.

So the only routes that hit `writeFromBatch` are batch-related. No hidden second sync path. **Safe.**

### F — Admin "evaluate now" alternatives

Exists and is well-developed: [BatchAdminController.java](../../backend/src/main/java/com/gregochr/goldenhour/controller/BatchAdminController.java)
- `POST /api/admin/batches/force-submit` → [ForceSubmitBatchService.java](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForceSubmitBatchService.java)
- `POST /api/admin/batches/submit-scheduled` and `submit-jfdi` (regions array)
- Frontend wires both at [batchApi.js:21, 30](../../frontend/src/api/batchApi.js:21).

**Admin** has a complete batch-submission replacement for SSE.

### G — Most surprising finding

**`/api/briefing/evaluate/cache` and `/cache/timestamp` are dead endpoints.**

- `getCachedEvaluationScores` (frontend) is referenced only in a `vi.fn()` test mock in `DailyBriefing.test.jsx:15`. Zero production callers.
- `/cache/timestamp` has zero frontend callers. The `cachedTimestamp` references in `HeatmapGrid.jsx:581` read from in-memory React state populated by the SSE `onComplete` event, not the REST endpoint.

These endpoints can be deleted alongside SSE — they exist purely as legacy companions to the SSE flow. No capability is lost.

**Second surprise — flagged but tangential**: `/api/briefing/evaluate/scores` has no `@PreAuthorize` annotation ([BriefingEvaluationController.java:133](../../backend/src/main/java/com/gregochr/goldenhour/controller/BriefingEvaluationController.java:133)). Spring Security's default at method level falls through to the URL-level config, but every other endpoint on this controller is explicitly annotated. Worth flagging in a separate ticket; not in scope for this retirement.

---

## Section 5 — Frontend

### Triggering UI

Misnomer in the planning prompt: there is no single "Run Full Forecast" button. SSE evaluation is triggered **per region×date×event drill-down** inside `DailyBriefing.jsx`.

- [DailyBriefing.jsx:838](../../frontend/src/components/DailyBriefing.jsx:838) — `handleRunEvaluation(regionName, date, targetType)` callback. Triggered when a user opens a drill-down cell in the briefing heatmap.
- Available to **ADMIN and PRO_USER** (line 805 `canRunEvaluation = role === 'ADMIN' || role === 'PRO_USER'`).
- React state: `evaluationScores` (Map), `evaluationProgress` (object), `evaluationTimestamps` (Map). Cleaned up via `evalCleanupRef` on unmount/stop.

The drill-down trigger is interwoven into the heatmap grid UX rather than living on a single button. Removing it removes the entire "click a cell to get fresh Claude scores" interaction.

### SSE consumer (the only one)

[briefingEvaluationApi.js](../../frontend/src/api/briefingEvaluationApi.js) — single module, three exports:
- `subscribeToBriefingEvaluation` (line 22) — the SSE consumer. **DELETE.**
- `getCachedEvaluationScores` (line 45) — REST `/cache`. **Dead code, DELETE.**
- `getAllEvaluationScores` (line 64) — REST `/scores`. **KEEP** — used by both DailyBriefing and Map tab to hydrate batch-scored medallions.

After SSE removal: the file shrinks to a single function (`getAllEvaluationScores`) or could be merged into another api module.

### Misleading hit ruled out

`MarkerPopupContent.jsx:316` (`handleRunForecast`) is admin-only and uses `/api/forecast/run/{jobRunId}/progress` SSE — that's the **RunProgressTracker** SSE, a different feature for tracking admin "Run Forecast" jobs. Unrelated to briefing evaluation. Not affected by this retirement.

### Other REST consumers of `/api/briefing/evaluate/*`

- `/scores` — `DailyBriefing.jsx:893` (`getAllEvaluationScores()` on mount) and via prop-drilling to Map tab. **Keep.**
- `/cache` (REST) — no frontend caller in production code. **Dead.**
- `/cache/timestamp` — no frontend caller. **Dead.**
- DELETE `/cache` — no obvious frontend caller; appears unused in admin UI (verify before deletion). May be wired into the "Operations" admin tab; quick re-check during implementation.

### Capability gap

**ADMIN**: No loss. Force-submit endpoints (`/api/admin/batches/force-submit`, `submit-scheduled`, `submit-jfdi`) cover the "evaluate now" use case and are already wired in `batchApi.js`.

**PRO_USER**: Real capability loss. PRO users currently click any drill-down cell on a date/region/event and get fresh Claude scores within a minute. After SSE retirement, PRO users wait for the next scheduled batch (overnight + the planned noon/afternoon intraday refreshes). They lose on-demand re-evaluation.

Whether this matters is a product call. Options:
1. **Accept the loss.** The intraday refresh hits all volatile cells twice a day; the overnight covers stable ones. The on-demand button was always a stopgap before batching matured. Honest product framing: "We pre-compute everything you need; no button to push."
2. **Add a PRO-tier force-submit button.** Reuse `ForceSubmitBatchService` but expose a region-scoped variant to PRO_USER. Modest amount of work; adds back a force-submit capability with the cost coupling moved to the batch path (lower throughput, but no SSE).
3. **Defer until intraday lands.** Build intraday first, then judge whether the capability gap is felt in practice before adding a button.

Recommendation: option (3). Don't add UI for a need that may not materialise once intraday is shipping refreshes at noon and 15:00.

---

## Section 6 — Tests

### SSE-ONLY (delete)

| File | Lines | Notes |
|---|---|---|
| [BriefingEvaluationControllerTest.java](../../backend/src/test/java/com/gregochr/goldenhour/controller/BriefingEvaluationControllerTest.java) | 245 | Mostly SSE-endpoint tests. Keep the 4 tests for `/cache` GET (lines 64-95, 144-218) and DELETE `/cache` (218-244) if those endpoints survive — but those endpoints are unused so probably go too. Net: delete the whole file. |
| `BriefingEvaluationServiceTest.java` `evaluateRegion_*` tests | ~40 tests | All exercise `evaluateRegion`. Delete. |
| `BriefingEvaluationServiceTest.java` `cancelOutstandingForecastBatches_*` tests | 6 tests | Lines 917, 928, 954, 968, 991, 1085. Delete. |
| `BriefingEvaluationServiceTest.java` SSE merge/partial-cache tests | Lines 1018-1099 | Tests the per-location cache write at line 222. Delete. |
| `BriefingEvaluationServiceTest.java::evaluateRegion_persistsToDbWithSourceSse` | 1286-1320 | Tests `source="SSE"` is written. Delete. |

### SHARED — rewrite via batch path (don't lose coverage)

| File | Tests | Action |
|---|---|---|
| `BriefingEvaluationServiceTest.java` `writeFromBatch_*` tests | ~12 tests (lines 794-905, 1140-1450) | **Keep as-is.** They exercise the batch write path. No rewrite needed — already source-agnostic. |
| [BriefingEvaluationServiceCacheFreshnessTest.java](../../backend/src/test/java/com/gregochr/goldenhour/service/BriefingEvaluationServiceCacheFreshnessTest.java) | 147 lines, ~6 tests | **Keep as-is.** Tests `hasEvaluation` / `hasFreshEvaluation` — survives. Uses `writeFromBatch` to seed, not `evaluateRegion`. |
| [EvaluationDeltaLogTest.java](../../backend/src/test/java/com/gregochr/goldenhour/service/EvaluationDeltaLogTest.java) | 241 lines, 6 tests | **Keep as-is.** Tests delta logging on `writeFromBatch`. Survives. |
| [ForecastResultHandlerTest.java](../../backend/src/test/java/com/gregochr/goldenhour/service/evaluation/ForecastResultHandlerTest.java) | ~7 `writeFromBatch` verifications | **Keep as-is.** Tests the batch result-handler → `writeFromBatch` integration. Survives. |
| `BriefingEvaluationServiceTest.java::evaluateRegion_partialCacheHit_finalCacheMergesBatchAndSseResults` | Line 1052 | Tests that batch-cached results survive an SSE rerun. **Delete** (the scenario can't happen post-retirement). |
| `BriefingEvaluationServiceTest.java::evaluateRegion_partialCacheHit_skipsAlreadyCachedLocation` | Line 1018 | Same — delete. |

`BriefingEvaluationServiceTest.java` (1643 lines) will shrink substantially — roughly 60–70% of the test class is `evaluateRegion`-based. Triage carefully during implementation; a few tests may indirectly cover the shared `persistToDb` path and need rewriting through `writeFromBatch` instead of deleting (e.g. `evaluateRegion_twoLocations_finalDbSaveContainsBoth` at line 1361 could become `writeFromBatch_twoLocations_finalDbSaveContainsBoth`).

### Frontend tests

- `frontend/src/test/DailyBriefing.test.jsx:14-16` — mocks `subscribeToBriefingEvaluation`, `getCachedEvaluationScores`, `getAllEvaluationScores`. Line 1805 imports `subscribeToBriefingEvaluation` and asserts it was called. **Delete the SSE-related assertions**; keep tests that exercise non-SSE branches of `DailyBriefing`.
- `frontend/src/test/createEventSource.test.js` — generic utility test (used by other SSE features too). **Keep.**

---

## Section 7 — Deletion plan

### What gets deleted

**Backend**
1. SSE controller endpoint (`@GetMapping` at line 71), keeping the other endpoints initially.
2. Service methods: `evaluateRegion`, `cancelOutstandingForecastBatches`, `evaluateSingleLocation`, `getEvaluableLocationNames`, `emitCachedResults`, `completeEmitter`, `sendSafe`.
3. Unused dependencies pruned from the constructor: `anthropicClient`, `batchRepository`, possibly `briefingService` (verify).
4. `/cache` GET, `/cache/timestamp` GET, and (probably) DELETE `/cache` if no frontend caller — endpoint-level confirmation before removal.
5. Backend tests covering deleted methods (see §6).

**Frontend**
1. `briefingEvaluationApi.js`: `subscribeToBriefingEvaluation`, `getCachedEvaluationScores` exports.
2. `DailyBriefing.jsx`: `handleRunEvaluation`, `handleStopEvaluation`, `evalCleanupRef`, `evaluationProgress`, `evaluationTimestamps` state, and any UI that triggers them. The drill-down "Run evaluation" UX disappears.
3. SSE-related parts of `DailyBriefing.test.jsx`.

### What stays

- `BriefingEvaluationService`'s batch surface: `writeFromBatch`, `getCachedScores`, `getCachedEvaluatedAt` (service method, even if REST endpoint goes), `hasEvaluation`, `hasFreshEvaluation`, `clearCache`, `rehydrateCacheOnStartup`, `onBriefingRefreshed`, `persistToDb`, `logEvaluationDeltas`, `buildStabilityLookup`, the `cache` field, the `CachedEvaluation` record, the `CachedEvaluationRepository`.
- `/api/briefing/evaluate/scores` endpoint (shared, used by Map tab + DailyBriefing hydration).
- DELETE `/api/briefing/evaluate/cache` (admin escape hatch — confirm no other path before removing).
- The entire batch pipeline (`ScheduledBatchEvaluationService`, `ForecastTaskCollector`, `ForceSubmitBatchService`, `EvaluationService.submit`, `BatchPollingService`, `ForecastResultHandler`).
- All non-SSE SSE features: `StatusController`, `ForecastController` run-progress endpoints, `RunProgressTracker`.
- The `source` column on `cached_evaluation`. Becomes always-"BATCH" but harmless; nothing reads it.

### What needs preserving or moving

None. No SSE-only behaviour requires preserving. The cache, its persistence, its rehydration, and its delta logging all survive on the batch path.

### What might need replacing (capability gap)

The PRO_USER on-demand re-evaluation capability (see §5). Recommendation is to defer this decision until after intraday refresh is in production.

### Suggested commit breakdown

Each commit should leave main green.

**Commit 1 — Remove frontend SSE consumer.**
- `briefingEvaluationApi.js`: remove `subscribeToBriefingEvaluation` and `getCachedEvaluationScores`.
- `DailyBriefing.jsx`: remove `handleRunEvaluation`, `handleStopEvaluation`, `evalCleanupRef`, related state, and UI triggers.
- `DailyBriefing.test.jsx`: drop SSE-related assertions and mocks.
- Frontend tests pass; backend endpoints still exist (deletion of unused endpoints is harmless until next commit).

**Commit 2 — Remove backend SSE controller endpoint + cancel invocation.**
- Delete the `GET /api/briefing/evaluate` mapping (controller lines 60-82).
- Delete the unused REST companions (`/cache`, `/cache/timestamp`, possibly DELETE `/cache`). Verify the admin DELETE has no caller before removing.
- In `BriefingEvaluationService`: delete the `cancelOutstandingForecastBatches()` call from `evaluateRegion`'s call chain (the method body still exists at this point — its caller goes away first to isolate the change).
- Backend test deletions: `BriefingEvaluationControllerTest.java` SSE tests.
- Backend compiles; tests pass.

**Commit 3 — Remove SSE service methods + unused dependencies.**
- Delete the seven SSE-only service methods.
- Prune unused constructor params: `anthropicClient`, `batchRepository`, possibly `briefingService`.
- Delete `evaluateRegion_*` and `cancelOutstandingForecastBatches_*` tests in `BriefingEvaluationServiceTest.java` (~60% of the file).
- Rewrite the 1–2 tests that covered `persistToDb` through `evaluateRegion` to go through `writeFromBatch` instead (preserve coverage).
- Backend compiles; tests pass.

**Commit 4 (optional, follow-up) — Tidy.**
- Drop the unused `source` column from `cached_evaluation` (migration), or make it nullable, or leave it as audit metadata. Out of scope for this retirement.
- Fix the missing `@PreAuthorize` on `/scores` (separate ticket).
- Reconsider whether `getCachedEvaluatedAt` service method has any remaining caller after `/cache/timestamp` is gone — if not, delete it too.

### Riskiest part of the deletion

**Commit 3 — pruning the constructor dependencies.** `BriefingEvaluationService` is injected with `AnthropicClient` and `ForecastBatchRepository` specifically for `cancelOutstandingForecastBatches`, and (likely) `BriefingService` only for `isColourLocation` inside `evaluateRegion`. Removing those parameters changes the bean's signature, which can ripple into any test that constructs it manually (most of them do — `BriefingEvaluationServiceTest.java` builds the service directly with mocks). Every test class that instantiates `BriefingEvaluationService` needs its constructor call updated. Worth doing the deletion in a deliberate single commit so the test fan-out is visible in review.

Lesser risks:
- The DELETE `/cache` endpoint may have an admin caller I missed in `Manage` view. Quick re-grep before removing.
- `briefingService.isColourLocation` may have other callers that aren't visible from the immediate sweep. Verify with a grep before dropping the dependency.

### Most surprising finding (overall)

The `source` column on `cached_evaluation` has **no readers**. The merge-vs-replace concern that motivated half this investigation turned out to be a non-issue because the discriminator the concern was built around doesn't actually drive any behaviour. The column is purely audit metadata, written but never inspected. After SSE retirement, every new row will say "BATCH" and nothing in the system will notice or care.

The second surprise: the planning prompt's mental model of "the Run Full Forecast button" doesn't match the actual UI. SSE is triggered by per-cell drill-down interactions across the briefing heatmap, available to PRO users, not by a single button. The deletion removes a small surface area in code but a meaningful interaction pattern for PRO users.

### Open questions before implementation prompt

1. **PRO_USER capability gap**: accept, build a force-submit button, or defer until intraday ships? (Recommendation: defer.)
2. **DELETE `/api/briefing/evaluate/cache`**: confirm there is no caller before removing. Quick `grep -r "clearCache\|evaluate/cache" frontend/src` during implementation.
3. **The `source` column**: leave (always "BATCH"), drop, or make nullable? Cleanup-only, defer to a follow-up.
4. **`/scores` endpoint missing `@PreAuthorize`**: pre-existing security smell, separate ticket. Should it be addressed inside the retirement commit chain or after?
5. **`isColourLocation` and `BriefingService` dependency**: verify it has another caller before pruning the constructor injection.
