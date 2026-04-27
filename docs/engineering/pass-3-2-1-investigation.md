# Pass 3.2.1 Investigation — `ForecastTaskCollector` extraction

**Status:** Investigation only. Pre-code review document. Per the v2.12.5 brief, no code changes commit until this is reviewed.

**Files inspected (top-level):**

- `backend/src/main/java/com/gregochr/goldenhour/service/batch/ScheduledBatchEvaluationService.java` (971 lines, 18 deps including config value)
- `backend/src/test/java/com/gregochr/goldenhour/service/batch/ScheduledBatchEvaluationServiceTest.java` (474 lines, 16 tests, 17 mocks)
- `backend/src/main/java/com/gregochr/goldenhour/service/BriefingService.java` (`getCachedBriefing` only)
- `backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java` (`hasFreshEvaluation` only)
- `backend/src/test/java/com/gregochr/goldenhour/integration/ForecastBatchPipelineIntegrationTest.java` (5 tests, starts from `BatchSubmissionService.submit`, NOT from the scheduled service)
- `docs/engineering/forecast-evaluation-architecture.md` (3-phase pipeline location verified)

---

## Critical scope correction (read first)

**The Pass 3.2.1 brief assumes `ScheduledBatchEvaluationService` runs a "3-phase pipeline (full-evaluation, sentinel, wildlife/comfort)". That is incorrect.**

The 3-phase pipeline lives in `ForecastCommandExecutor`, per `forecast-evaluation-architecture.md` §1 Path 6:

> `Controller → ForecastCommandFactory.create() → ForecastCommandExecutor.execute() → executeThreePhasePipeline() → Phase 1: triage → Phase 2: sentinel sampling (optional) → Phase 3: full evaluation`

`ScheduledBatchEvaluationService` does something structurally different. Its forecast-batch flow is:

1. `collectForecastTasks(briefing)` — first pass over the cached daily briefing, building a `List<ForecastTask>` of `(location, date, targetType)` triples for GO/MARGINAL slots, skipping past dates / cached regions / unknown locations / non-eligible verdicts. No API calls.
2. **Bulk weather pre-fetch** — `openMeteoService.prefetchWeatherBatchResilient(coords)` for all unique coordinates.
3. **Bulk cloud-point pre-fetch** — `openMeteoService.prefetchCloudBatch(...)` for the 5 directional sample points + upwind point per task.
4. **Triage + stability + bucket loop** — for each candidate task, call `forecastService.fetchWeatherAndTriage(...)`, drop triaged, drop tasks whose `daysAhead` exceeds the per-grid-cell stability window, then bucket the survivors into **near/far × inland/coastal** (4 buckets).
5. **Submit** — `evaluationService.submit(bucket, SCHEDULED)` once per non-empty bucket.

There is also a region-filtered admin variant (`doSubmitForecastBatchForRegions`) and an aurora variant (`doSubmitAuroraBatch`).

**Implications for the pass:**

- The collector should produce these four buckets directly (or one flat list that the scheduled service buckets — see Investigation 3).
- The "~6 deps target" in the brief was anchored to the imagined 3-phase shape. The realistic post-extraction count for `ScheduledBatchEvaluationService` is **~9–10** because the aurora path stays here per the brief's explicit out-of-scope rule and brings 5 dedicated deps with it (`noaaSwpcClient`, `weatherTriageService`, `auroraOrchestrator`, `locationRepository`, `auroraProperties`) plus `modelSelectionService` (shared). To hit 6, an `AuroraBatchSubmitter` would also need extracting — that is a separate pass.
- Method names on the collector should reflect actual structure: `collectScheduledBatches()` and `collectRegionFilteredBatches(regionIds)`. There is no `collectFullEvaluationTasks` / `collectSentinelTasks` / `collectComfortTasks` — those don't map to anything real here.

---

## Investigation 1 — Dependency taxonomy

| # | Dependency | Class | Call sites in `ScheduledBatchEvaluationService` | Notes |
|---|------------|-------|------------------------------------------------|-------|
| 1 | `locationService` | C | 1 (`findAllEnabled` via `findLocation` helper) | Used during forecast task collection to look up `LocationEntity` by name. **Moves to collector.** |
| 2 | `briefingService` | C | 2 (`getCachedBriefing` × 2 — both forecast paths) | Pure read of the cached briefing. **Moves to collector.** |
| 3 | `briefingEvaluationService` | D | 1 (`hasFreshEvaluation` — cache-freshness skip) | Pure read of the in-memory cache freshness; decides whether to skip a region. **Moves to collector.** |
| 4 | `forecastService` | D | 2 (`fetchWeatherAndTriage` — both forecast paths) | Per-task triage + stability gating combined into one call. **Moves to collector.** |
| 5 | `stabilityClassifier` | D | 1 (`classify` in `getStabilityWindowDays`) | Per-grid-cell stability classification. **Moves to collector.** |
| 6 | `modelSelectionService` | C+E | 4 (3 forecast: `BATCH_NEAR_TERM` × 2, `BATCH_FAR_TERM` × 1; 1 aurora: `AURORA_EVALUATION`) | Forecast call sites move to collector; aurora call site stays in scheduled. **Used by both — must stay shared.** Either: (a) keep dep in both (collector + scheduled service) — minor duplication; (b) move dep to collector only and have scheduled service ask the collector for the aurora model — awkward. **Recommendation: keep in both**, since `modelSelectionService` is a thin lookup with no mutable state. |
| 7 | `noaaSwpcClient` | E | 1 (`fetchAll` — aurora) | **Stays in scheduled** (aurora out of scope). |
| 8 | `weatherTriageService` | E | 1 (`triage` — aurora) | **Stays in scheduled** (aurora out of scope). |
| 9 | `auroraOrchestrator` | E | 1 (`deriveAlertLevel` — aurora) | **Stays in scheduled** (aurora out of scope). |
| 10 | `locationRepository` | E | 1 (`findByBortleClassLessThanEqualAndEnabledTrue` — aurora) | **Stays in scheduled** (aurora out of scope). |
| 11 | `auroraProperties` | E | 1 (`getBortleThreshold` — aurora) | **Stays in scheduled** (aurora out of scope). |
| 12 | `dynamicSchedulerService` | B | 1 (`registerJobTarget` × 2 in `@PostConstruct`) | **Stays in scheduled.** Job-target registration is an orchestration concern. |
| 13 | `openMeteoService` | C | 4 instance calls (`prefetchWeatherBatchResilient`, `computeDirectionalCloudPoints`, `computeUpwindPoint`, `prefetchCloudBatch`) + 3 static `coordKey` references (no dep needed) | **Moves to collector.** |
| 14 | `solarService` | C | 4 (sunrise/sunset azimuth + UTC for cloud-point pre-fetch) | **Moves to collector.** |
| 15 | `freshnessResolver` | D | 3 (`maxAgeFor` — once for forecast freshness check, once for aurora threshold logging, once for warning log) | The forecast freshness call goes to the collector. Aurora and the diagnostic log are minor. **Move to collector**; for the aurora-side diagnostic log, replace with a constant-string message or accept the dep stays in both (Recommendation: log message can lose the threshold without product cost — it's diagnostic). |
| 16 | `forecastCommandExecutor` | D | 1 (`getLatestStabilitySummary` for the stability lookup) | This is the cleanest cross-service dep to break. **Moves to collector.** Note: the collector will depend on `ForecastCommandExecutor` only for this read-only snapshot accessor; this is not a structural problem (executor is a Spring singleton, snapshot is read-only) but it's worth flagging as an "executor leakage" — Pass 3.3 may want to extract `getLatestStabilitySummary()` into a dedicated `StabilitySnapshotProvider`. **Out of scope for 3.2.1.** |
| 17 | `evaluationService` | A | 7 (4 in `doSubmitForecastBatch`, 2 in `doSubmitForecastBatchForRegions`, 1 in `doSubmitAuroraBatch`) | **Stays in scheduled.** This IS the submission infrastructure. |
| 18 | `minPrefetchSuccessRatio` (`@Value`) | B / D (depends on Investigation 5 outcome) | 1 (in `doSubmitForecastBatch` only) | If gate stays in scheduled (Option A in the brief), stays here. If gate moves to collector (Option B, my recommendation — see Investigation 5), moves to the collector. |

**Classifications recap:** A = submission infrastructure; B = orchestration / scheduling; C = data fetching for task construction; D = triage/freshness/stability gating; E = out-of-scope (aurora); F = entangled (none in this codebase).

### Realistic post-extraction shape

**`ScheduledBatchEvaluationService` keeps:**

- `evaluationService` (A)
- `dynamicSchedulerService` (B)
- `forecastTaskCollector` (NEW)
- `noaaSwpcClient`, `weatherTriageService`, `auroraOrchestrator`, `locationRepository`, `auroraProperties` (E — aurora, 5 deps)
- `modelSelectionService` (shared with collector for aurora model lookup)
- AtomicBooleans (state, not constructor deps)

That's **9 deps** if `minPrefetchSuccessRatio` moves to the collector, **10** if it stays. The brief's "~6 deps" target was structurally unachievable without also extracting an aurora collector/submitter; this is documented as a follow-up below, not a blocker.

**`ForecastTaskCollector` takes:**

- `briefingService`, `briefingEvaluationService`, `locationService` (briefing read + location lookup)
- `forecastService` (per-task triage)
- `stabilityClassifier`, `freshnessResolver`, `forecastCommandExecutor` (stability + freshness gates)
- `openMeteoService`, `solarService` (weather + cloud-point pre-fetch)
- `modelSelectionService` (per-task model assignment — `BATCH_NEAR_TERM` / `BATCH_FAR_TERM`)
- `minPrefetchSuccessRatio` (config — if gate moves; see Investigation 5)

That's **10 deps** for the collector. This is acceptable — they all serve task construction + per-task triage + stability + freshness gating, and the methods are pure (input → output, no side effects beyond logging).

---

## Investigation 2 — Briefing service entanglement

### `BriefingService` (1 method called: `getCachedBriefing`)

`ScheduledBatchEvaluationService` calls `briefingService.getCachedBriefing()` exactly twice — once at the top of `doSubmitForecastBatch` and once at the top of `doSubmitForecastBatchForRegions`. Both call sites are immediately upstream of task collection (the briefing IS the source of candidate slots).

This is unambiguously **data-fetching for task construction**. No result-handling responsibility here. **Moves cleanly to the collector.**

### `BriefingEvaluationService` (1 method called: `hasFreshEvaluation`)

`ScheduledBatchEvaluationService` calls `briefingEvaluationService.hasFreshEvaluation(cacheKey, freshness)` exactly once, inside `collectForecastTasks` at line 711. The method is a pure read of an in-memory `ConcurrentHashMap` — it returns `true` if the cache has at least one result for the key and it's within the freshness window.

Inspection of `BriefingEvaluationService.java:310-316`:

```java
public boolean hasFreshEvaluation(String cacheKey, Duration maxAge) {
    CachedEvaluation cached = cache.get(cacheKey);
    if (cached == null || cached.results().isEmpty()) {
        return false;
    }
    return cached.evaluatedAt().isAfter(Instant.now().minus(maxAge));
}
```

The result-handling counterpart in this service (`writeFromBatch`, `persistToDb`, `logEvaluationDeltas`) is called from `BatchResultProcessor` / `ForecastResultHandler`, **not** from `ScheduledBatchEvaluationService`. There is no entanglement to disentangle.

**Verdict: clean extraction.** `briefingEvaluationService` becomes a collector dep, used solely as a cache-freshness oracle for the skip decision.

---

## Investigation 3 — Pipeline structure (the actual one)

`ScheduledBatchEvaluationService` runs **two distinct collection variants** (forecast-scheduled and forecast-region-filtered) plus an aurora path that is out of scope. Neither forecast variant has the named "phases" the brief refers to.

### Forecast-scheduled (`doSubmitForecastBatch`)

1. **Pre-collection (no API calls):** Read briefing → drop past dates → drop cached regions → drop non-GO/non-MARGINAL slots → drop unknown locations → emit candidate `(location, date, targetType)` triples.
2. **Bulk pre-fetch:** Weather + air quality for unique coords (resilient chunked).
3. **Prefetch ratio gate:** If empty → abort. If `< minPrefetchSuccessRatio` → abort. If partial-but-above-threshold → log warning, continue.
4. **Bulk cloud-point pre-fetch:** 5 directional points + 1 upwind point per task. Failures are non-fatal.
5. **Per-task triage + stability + bucket:** Loop over candidates; for each, call `forecastService.fetchWeatherAndTriage(...)`; drop triaged; drop stability-gated; bucket survivors into near/far × inland/coastal using `daysAhead <= NEAR_TERM_MAX_DAYS` (1) and `atmosphericData.tide() != null`.
6. **Submit:** Submit each non-empty bucket separately via `evaluationService.submit(bucket, SCHEDULED)`. Per-bucket diagnostic logging.

### Forecast-region-filtered (`doSubmitForecastBatchForRegions`)

Same as above, but:
- Filtered to `regionFilter` set after step 1.
- Uses `BATCH_NEAR_TERM` model for **all** tasks (no near/far split, no model-per-task tier).
- Buckets only into inland/coastal (no near/far).
- Uses `BatchTriggerSource.ADMIN`.
- Returns a `BatchSubmitResult` for the synchronous admin caller.

### Aurora-batch (`doSubmitAuroraBatch`) — out of scope for 3.2.1

NOAA → derive alert level → Bortle filter → triage → submit single multi-location task. Stays in scheduled service.

### Proposed collector method signatures

```java
public class ForecastTaskCollector {

    /**
     * Collects scheduled forecast batches with near/far × inland/coastal bucketing.
     * Returns Optional.empty() when the batch should be skipped (no briefing,
     * empty candidate list, or prefetch ratio below threshold). Logs all
     * skip reasons internally with the same [BATCH DIAG] format used today.
     */
    public Optional<ScheduledBatchTasks> collectScheduledBatches();

    /**
     * Collects region-filtered forecast batches with inland/coastal bucketing only.
     * Uses BATCH_NEAR_TERM for all tasks. Returns Optional.empty() when the batch
     * should be skipped (no briefing, no candidates after filter, or prefetch fail).
     */
    public Optional<RegionFilteredBatchTasks> collectRegionFilteredBatches(List<Long> regionIds);
}

public record ScheduledBatchTasks(
    List<EvaluationTask.Forecast> nearInland,
    List<EvaluationTask.Forecast> nearCoastal,
    List<EvaluationTask.Forecast> farInland,
    List<EvaluationTask.Forecast> farCoastal,
    int includedNear,
    int includedFar,
    Map<String, List<String>> diagnostics  // optional — for logBatchBreakdown
) { }

public record RegionFilteredBatchTasks(
    List<EvaluationTask.Forecast> inland,
    List<EvaluationTask.Forecast> coastal
) { }
```

The `logBatchBreakdown` per-bucket diagnostic logging stays in the scheduled service (it has the `evaluationService.submit` call site and naturally pairs with each submission). The collector is responsible for `[BATCH DIAG] SKIP …` and `[BATCH DIAG] INCLUDE …` logs internal to the candidate loop, since those are byte-for-byte tied to the triage decisions it now owns.

---

## Investigation 4 — Test coverage

### Existing unit tests directly exercising `ScheduledBatchEvaluationService`

`ScheduledBatchEvaluationServiceTest` — **16 tests, 17 mocks**. The 17 mocks match the constructor's 17 deps (everything except the `@Value double`). Test classes:

| Group | Count | Tests | Disposition |
|-------|-------|-------|-------------|
| Job-target registration | 1 | `registerJobTargets_registersExpectedKeys` | **Stays.** Orchestration concern. |
| Forecast pre-collection short-circuits | 3 | `submitForecastBatch_noBriefing_skips`, `_allStanddown_skips`, `_pastDatesOnly_skipsWithoutWeatherFetch` | **Migrate to `ForecastTaskCollectorTest`** (collection logic). Replace with a thin scheduled-service test asserting `evaluationService` is not invoked when the collector returns `Optional.empty()`. |
| Forecast dispatch through evaluationService | 3 | `_goLocation_dispatchesToEvaluationService`, `_triagedLocation_skipsTask`, `_cachedRegion_skipsRegion` | **Split.** Triage / cache-region tests move to `ForecastTaskCollectorTest`. The scheduled service keeps a thin "given collector returns N tasks, evaluationService.submit is called N times" test. |
| Aurora | 4 | `submitAuroraBatch_*` (4 tests) | **Stays.** Aurora out of scope. |
| Concurrency guards | 5 | `resetBatchGuards_*` (2), `_guardAlreadyHeld_skips` (2), `_exceptionInDoSubmit_clearsGuard` (1) | **Stays.** Concurrency guards remain in scheduled service. The exception test stubs `briefingService.getCachedBriefing()` to throw — after extraction, this test stubs `forecastTaskCollector.collectScheduledBatches()` to throw. |

After extraction the `ScheduledBatchEvaluationServiceTest` shrinks from 16 tests to ~10 tests; mocks shrink from 17 to ~9 (everything `(C)` and `(D)` moves out, replaced by a single `forecastTaskCollector` mock).

### Proposed `ForecastTaskCollectorTest` (new)

Targets ~14–18 tests covering the moved-out behaviour:

| Group | Tests |
|-------|-------|
| Collection short-circuits | `collectScheduledBatches_returnsEmptyWhenNoBriefing`, `_returnsEmptyWhenNoCandidates` (all standdown + past dates), `_skipsPastDatesWithoutWeatherFetch`, `_skipsCachedRegions` |
| Prefetch gate | `_returnsEmptyWhenPrefetchYieldsZero`, `_returnsEmptyWhenPrefetchBelowRatio`, `_continuesWithPartialPrefetchAboveRatio` |
| Triage / stability | `_excludesTriagedTasks`, `_excludesTasksBeyondStabilityWindow`, `_swallowsPerTaskExceptions` |
| Bucketing | `_bucketsNearInland`, `_bucketsNearCoastal`, `_bucketsFarInland`, `_bucketsFarCoastal` (or one parameterised test) |
| Region-filtered | `collectRegionFilteredBatches_filtersByRegionId`, `_returnsEmptyWhenNoMatchingRegion`, `_usesNearTermModelForAllTasks` |
| Diagnostics | `_logsStabilityBreakdown` (optional — log-spy test) |

### Integration tests — disposition

`ForecastBatchPipelineIntegrationTest` (5 tests) and `IntegrationTestBaseSmokeTest` (5 tests) start the pipeline from `BatchSubmissionService.submit` directly, **not** from `ScheduledBatchEvaluationService`. Quoting the integration test comment at line 68–71:

> "Tests start the pipeline from `BatchSubmissionService.submit` so they do not need to seed weather/triage/briefing prerequisites. Those are extensively unit-tested by `ScheduledBatchEvaluationServiceTest`; integration scope here is 'submit → poll → process → cache.'"

These tests are **byte-identical robust** to the collector extraction. They should pass without modification — the safety net per the brief.

`ForecastBatchPipelineRealApiE2ETest` similarly does not depend on the scheduled service's internal structure.

---

## Investigation 5 — Prefetch ratio gate

### Current location

Lines 305–326 of `ScheduledBatchEvaluationService.doSubmitForecastBatch`. The gate sits **between** weather pre-fetch and cloud-point pre-fetch, not between collection and submission. Three abort paths:

1. `prefetchedWeather.isEmpty()` → log error, return.
2. `successRatio < minPrefetchSuccessRatio` → log error, return.
3. `prefetchedWeather.size() < uniqueLocationCount` (but ratio above threshold) → log warning, continue.

### Option A (brief's lean) — keep gate in scheduled service

The collector returns the prefetch counts and the bucketed tasks; the scheduled service inspects and decides whether to submit.

**Problem:** The gate is checked **before** the collector's expensive triage+stability+bucket loop. To preserve this short-circuit, the collector would need a two-method interface: `prefetchAndCollectCandidates()` first, then `triageAndBucket(prefetchResult)`. The scheduled service threads the prefetch result back in. This adds plumbing for a single decision and breaks the collector's "input → output, no policy" purity that the brief argues for.

Alternatively the collector exposes a single method but accepts the threshold and aborts internally — at which point the gate is effectively in the collector anyway.

### Option B (my recommendation) — move gate to collector

The collector enforces `minPrefetchSuccessRatio` internally. It returns `Optional.empty()` when the threshold is not met (or when there are zero candidates, or no briefing). The scheduled service simply iterates non-empty buckets and submits.

The threshold becomes a constructor param of the collector (`@Value("${photocast.batch.min-prefetch-success-ratio:0.5}") double`), parsed exactly once.

**Why this is preferable:**

1. **Natural data-fetching policy.** "Is the upstream weather data good enough to triage?" is a question the data-fetcher should answer, not the orchestrator. It's analogous to a DB connection retry config: it doesn't bubble up to the caller.
2. **Single round-trip API.** The scheduled service's flow becomes the textbook shape: `var tasks = collector.collect(); if (tasks.isPresent()) submit(tasks.get());`. No interleaved gate-check, no two-phase interface.
3. **Logging cohesion.** All `[BATCH DIAG]` collection-related logs live in the collector. The scheduled service's logging is purely about submission outcomes.
4. **Concurrency story is unchanged.** The AtomicBoolean still guards the entire entry point (Investigation 6). Gate location does not affect it.

The brief leaned Option A but acknowledged the actual code might suggest otherwise. It does. I recommend Option B.

If the user strongly prefers Option A, the fallback shape is:

```java
public ScheduledBatchTasks collectScheduledBatches();  // always returns; never aborts

public record ScheduledBatchTasks(
    /* buckets ... */,
    int prefetchedLocationCount,
    int uniqueLocationCount
) {
    public double prefetchSuccessRatio() { ... }
}
```

…and the scheduled service does the threshold check. This costs the collector running its full triage+bucket loop even on prefetch failure (since the gate now sits after collection completes), which is a measurable but tolerable inefficiency on the rare abort path.

---

## Investigation 6 — Concurrency

### `forecastBatchRunning` AtomicBoolean

Currently:
- Set to `true` at the top of `submitForecastBatch()` (line 212) and `submitScheduledBatchForRegions()` (line 231).
- Cleared in the `finally` block of both (lines 219, 238).
- Also cleared by `resetBatchGuards()` (admin escape hatch).

After extraction:
- The AtomicBoolean stays in `ScheduledBatchEvaluationService` (the entry point service). Same call-and-release pattern.
- The collector is **stateless** (a Spring singleton with only injected deps and `@Value` config) and is safe for concurrent calls.
- The collector's methods are read-only at the system level: no DB writes, no mutation of caches, no scheduled-service internal state. Open-Meteo + DB reads + briefing read are concurrency-safe by Spring's standard contracts.

### What happens if a second batch trigger arrives while the first is still in collection?

Today: blocked at the AtomicBoolean check. Collection cannot start.

After extraction: identical behaviour. The AtomicBoolean is set BEFORE `forecastTaskCollector.collectScheduledBatches()` is invoked, and cleared in the `finally` block AFTER the `evaluationService.submit(...)` calls return. The collector is never invoked concurrently from the scheduled-cron path because the AtomicBoolean serialises entry.

If a different caller (admin endpoint, test) invokes the collector directly while the scheduled-cron path is mid-collection, that's safe — the collector is stateless and Spring-singleton — but it's also irrelevant because no such caller exists in scope for 3.2.1.

### Pseudocode after extraction

```java
public void submitForecastBatch() {
    if (!forecastBatchRunning.compareAndSet(false, true)) {
        LOG.warn("Forecast batch already running — skipping concurrent trigger");
        return;
    }
    try {
        forecastTaskCollector.collectScheduledBatches().ifPresent(this::submitBatches);
    } finally {
        forecastBatchRunning.set(false);
    }
}

private void submitBatches(ScheduledBatchTasks tasks) {
    if (!tasks.nearInland().isEmpty()) {
        evaluationService.submit(tasks.nearInland(), BatchTriggerSource.SCHEDULED);
        logBatchBreakdown(tasks.nearInland(), "near-term inland");
    }
    // ... and so on for the other three buckets
}
```

The behaviour around the existing test `submitForecastBatch_exceptionInDoSubmit_clearsGuard` is preserved verbatim — the `try/finally` guarantees the guard is cleared even if `forecastTaskCollector.collectScheduledBatches()` throws.

---

## Decisions confirmed before code changes

All six decisions resolved. Proceeding with implementation.

1. **Method names on the collector — confirmed.** `collectScheduledBatches()` + `collectRegionFilteredBatches(regionIds)`. Matches the actual structure of the two forecast paths. The imagined 3-phase shape is dropped from the design.
2. **Return type shape — defer to implementation.** Either `Optional<ScheduledBatchTasks>` or always-present record with empty buckets is acceptable; pick whichever reads cleaner at the call site. **The non-negotiable:** when no submission happens, the log line at the decision point clearly distinguishes "prefetch gate tripped (ratio X below threshold Y)" from "no candidates this cycle". Implementation choice: **always-present record**, since it keeps the scheduled-service call site as a single uniform `submit each non-empty bucket` loop and the failure-vs-empty distinction is preserved by the collector's internal logging at the decision point.
3. **Investigation 5 — Option B accepted.** Prefetch gate moves to the collector. Collector logs the gate decision clearly when tripped, returns empty buckets, scheduled service handles empty-list as a no-op submission.
4. **Constructor count target — confirmed ~9–10 for `ScheduledBatchEvaluationService`** post-extraction, ~10 for the new `ForecastTaskCollector`. Aurora deps stay per the explicit out-of-scope boundary; this is the honest floor and the success criterion for 3.2.1. **Future-pass lever filed:** an `AuroraBatchSubmitter` extraction would shrink the scheduled service to ~6 deps. Not committed; noted as available.
5. **`logBatchBreakdown` location — confirmed in scheduled service.** Diagnostic per-bucket logs at the `evaluationService.submit(...)` call site, not threaded through return values.
6. **`getLatestStabilitySummary()` dep — confirmed for 3.2.1.** The collector takes a dep on `ForecastCommandExecutor` for stability snapshot reads. **Filed as Pass 3.2.2 candidate:** extract `StabilitySnapshotProvider` as a read-only utility to remove the executor-leakage dep. Small follow-up, do before Pass 3.3 if appetite permits. Not committing now.

---

## Recommended commit sequence (post-review)

1. **`docs: investigation findings for Pass 3.2.1 ForecastTaskCollector extraction`** — this document.
2. **`feat: introduce ForecastTaskCollector with per-batch task construction`** — new class with full unit test coverage. Not yet wired.
3. **`refactor: migrate ScheduledBatchEvaluationService to use ForecastTaskCollector`** — caller migration. Integration tests still pass.
4. **`chore: shrink ScheduledBatchEvaluationService dependencies and dead-code cleanup`** — remove now-unused deps; update `ScheduledBatchEvaluationServiceTest` setup.

Each commit leaves `main` green. After all four land and the next 01:00 UTC scheduled batch + daily real-API smoke validate behaviour parity, **tag v2.12.5**.

---

## Out of scope (explicit non-goals)

- Aurora batch path (stays in `ScheduledBatchEvaluationService`).
- `ForecastCommandExecutor` migration (Pass 3.3).
- Admin endpoint migration (Pass 3.3).
- SSE retirement (Pass 3.3).
- `forecast_evaluation` reader migration (v2.13).
- `BatchSchemaIntegrationTest` disposition (separate decision).
- Pass 2 primitives (`CacheKeyFactory`, `CustomIdFactory`, `BatchRequestFactory`, `BatchSubmissionService`).
- Any new abstraction beyond `ForecastTaskCollector` (no `WeatherFetcher`, no `TriageGate`, no `StabilitySnapshotProvider` — those are separate refactors).
- Any behaviour change. The user-visible output of the scheduled batch must remain byte-identical.
