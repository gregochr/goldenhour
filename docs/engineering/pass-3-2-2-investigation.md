---
name: Pass 3.2.2 Investigation — StabilitySnapshotProvider extraction
description: Pre-code investigation findings for extracting stability-snapshot read/write methods out of ForecastCommandExecutor into a dedicated provider. Per the v2.11.13 brief, no code changes commit until this is reviewed.
---

# Pass 3.2.2 Investigation — `StabilitySnapshotProvider` extraction

**Status:** Investigation only. Pre-code review document. Per the v2.11.13 brief, no code changes commit until this is reviewed.

**Files inspected:**

- `backend/src/main/java/com/gregochr/goldenhour/service/ForecastCommandExecutor.java` (997 lines, 15 deps; the host class)
- `backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java` (Pass 3.2.1 caller)
- `backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java` (delta-log caller)
- `backend/src/main/java/com/gregochr/goldenhour/service/evaluation/BriefingBestBetAdvisor.java` (region-rollup caller)
- `backend/src/main/java/com/gregochr/goldenhour/controller/StabilityController.java` (admin endpoint caller)
- `backend/src/main/java/com/gregochr/goldenhour/repository/StabilitySnapshotRepository.java` (the only direct user of which is the executor)
- `backend/src/test/java/com/gregochr/goldenhour/service/ForecastCommandExecutorTest.java` (~2370 lines; relevant blocks at 1407–1462 and 2090–2371)
- `backend/src/test/java/com/gregochr/goldenhour/service/EvaluationDeltaLogTest.java` (5 mock setups on `getLatestStabilitySummary`)
- `backend/src/test/java/com/gregochr/goldenhour/service/evaluation/BriefingBestBetAdvisorTest.java` (5 mock setups)
- `backend/src/test/java/com/gregochr/goldenhour/service/batch/CollectForecastTasksCachedGateTest.java` (8 mock setups)
- `backend/src/test/java/com/gregochr/goldenhour/controller/StabilityControllerTest.java` (2 mock setups)
- `backend/src/test/java/com/gregochr/goldenhour/integration/ForecastBatchPipelineIntegrationTest.java` (no executor reference — confirmed insulated)
- `docs/engineering/pass-3-2-1-investigation.md` Item #6 (the executor-leakage smell that motivated this pass)

---

## Investigation 1 — Method inventory on `ForecastCommandExecutor`

Every public/package-private/private method on `ForecastCommandExecutor`, classified per the brief's taxonomy.

| # | Method (signature) | Class | Lines | Notes |
|---|---|---|---|---|
| 1 | `execute(ForecastCommand)` | (B) execute | 149–151 | Public entry point. Stays. |
| 2 | `execute(ForecastCommand, JobRunEntity)` | (B) execute | 163–228 | Two-arg overload. Stays. |
| 3 | `executeThreePhasePipeline(...)` | (B) execute | 234–392 | Three-phase pipeline. Stays. Internally calls `applyStabilityFilter()`, see below. |
| 4 | `prefetchWeather(...)` | (B) execute | 398–412 | Bulk weather pre-fetch for the pipeline. Stays. |
| 5 | `prefetchCloudPoints(...)` | (B) execute | 419–471 | Bulk cloud-point pre-fetch. Stays. |
| 6 | `runTriagePhase(...)` | (B) execute | 481–493 | Phase 1. Stays. |
| 7 | `runSentinelPhase(...)` | (B) execute | 504–585 | Phase 2. Stays. |
| 8 | `runFullEvalPhase(...)` | (B) execute | 594–601 | Phase 3. Stays. |
| 9 | **`getLatestStabilitySummary()`** | **(A) read** | **610–616** | **Public read accessor. Moves to provider.** Reads `latestStabilitySummary` AtomicReference, falls through to DB. |
| 10 | **`persistSnapshot(StabilitySummaryResponse)`** | **(C) write** | **622–648** | **Private write to DB.** Iterates cells, upserts each. Caller decision in Investigation 3. |
| 11 | **`loadSnapshotFromDb()`** | **(A) read** | **657–700** | **Private DB read.** Builds `StabilitySummaryResponse` from rows, applies 24h staleness guard, warms in-memory cache (`latestStabilitySummary.set(summary)` line 690). Moves with `getLatestStabilitySummary()`. |
| 12 | `applyStabilityFilter(...)` | (D) **mixed → execute, but writes the snapshot** | 724–797 | **Stability filter** — classifies per grid cell, filters tasks beyond stability window, then **writes the snapshot to in-memory + DB** (lines 766–767). The filter logic stays; the write side-effect *delegates* to the provider. After extraction, lines 766–767 become a single `stabilitySnapshotProvider.update(summary)` call. |
| 13 | `enrichWithStability(...)` | (B) execute | 807–833 | Pure in-memory enrichment, no snapshot interaction. Stays. |
| 14 | `executeWildlife(...)` | (B) execute | 839–880 | Wildlife bypass. Stays. |
| 15 | `isColourRunType(RunType)` | (B) helper | 890–894 | Static. Stays. |
| 16 | `hasColourTypes(LocationEntity)` | (B) helper | 903–908 | Stays. |
| 17 | `isPureWildlife(LocationEntity)` | (B) helper | 917–919 | Stays. |
| 18 | `shouldSkipEvent(...)` | (B) helper | 921–930 | Stays. |
| 19 | `runForecast(...)` | (B) execute | 932–942 | Wildlife per-task wrapper. Stays. |
| 20 | `submitParallel(...)` | (B) helper | 963–988 | Generic parallel submit. Stays. |

**Field inventory (relevant to extraction):**

| Field | Type | Disposition |
|---|---|---|
| `SNAPSHOT_STALENESS_HOURS` | `long` constant | **Moves to provider** (defines the cache-staleness contract). |
| `latestStabilitySummary` | `AtomicReference<StabilitySummaryResponse>` | **Moves to provider** (the in-memory cache). |
| `stabilitySnapshotRepository` | `StabilitySnapshotRepository` | **Moves to provider** (only used by the moved methods). |
| All other 14 deps | various | Stay on executor. |

**Class summary post-extraction:**

- `ForecastCommandExecutor` loses 3 fields (`SNAPSHOT_STALENESS_HOURS`, `latestStabilitySummary`, `stabilitySnapshotRepository`) and 3 methods (`getLatestStabilitySummary`, `persistSnapshot`, `loadSnapshotFromDb`). Gains 1 dep (`stabilitySnapshotProvider`). Body of `applyStabilityFilter` shortens by 2 lines.
- `StabilitySnapshotProvider` (new) takes the 3 fields and 3 methods verbatim. Adds a public `update(StabilitySummaryResponse)` entry point that the executor calls from `applyStabilityFilter`. See Investigation 3 for why writes move.

---

## Investigation 2 — All callers of moved methods (read path: `getLatestStabilitySummary`)

The method is called by **5 production classes** (4 + executor self-reference is not a real one — there is no internal self-call after extraction). Wider footprint than the brief's "1–3" estimate, which makes the case for extraction stronger, not weaker: a read accessor with 4 distinct consumers is unambiguously general-purpose infrastructure that does not belong on a class named `*CommandExecutor`.

| # | Caller class | Method | Use | Migration shape |
|---|---|---|---|---|
| 1 | `ForecastTaskCollector` | `buildStabilityLookup()` line 610 | Builds location-name → stability lookup for stability-aware caching gate. | Swap dep `ForecastCommandExecutor` → `StabilitySnapshotProvider`. Mechanical. |
| 2 | `BriefingEvaluationService` | `buildStabilityLookup()` line 402 (private, called from `persistToDb` for delta logging) | Looks up per-location stability for the delta log entry. | Swap dep. Mechanical. Constructor count: 12 → 12 (same dep count, appropriate dep). |
| 3 | `BriefingBestBetAdvisor` | `appendStabilityToRegion()` line 820 | Worst-case stability per briefing region for the best-bet rollup. | Swap dep. Mechanical. Constructor count: 7 → 7. |
| 4 | `StabilityController` | `getStabilitySummary()` line 45 | Admin endpoint exposing the snapshot. | Swap dep. Mechanical. Constructor count: 1 → 1. |
| 5 | `ForecastCommandExecutor` (self) | N/A — no internal callers of `getLatestStabilitySummary` | — | After extraction, no self-reference is needed. The executor only needs to *write* to the provider via `update()`, never read. |

No caller takes any action other than reading the snapshot. There is no caller that does both read + write of the snapshot. The read API is a pure getter; all 4 consumers are happy with that contract.

**Verdict:** The read methods extract cleanly. Four mechanical caller migrations.

---

## Investigation 3 — Snapshot writes: should they move too?

The write happens in exactly one place: `ForecastCommandExecutor.applyStabilityFilter()` lines 766–767:

```java
latestStabilitySummary.set(summary);
persistSnapshot(summary);
```

These two lines are paired — the AtomicReference set + the DB upsert always occur together at the same point in the pipeline. `persistSnapshot()` is a private helper used only here.

The `loadSnapshotFromDb()` *read* path also writes the AtomicReference (line 690) to warm the in-memory cache after a DB-backed restore. So the AtomicReference has two writers: one explicit (post-classification) and one implicit (cache warm).

### Decision matrix

**Option A — writes stay on executor:**
- Executor keeps `stabilitySnapshotRepository`, `latestStabilitySummary`, and `persistSnapshot()`.
- Provider hosts only `getLatestStabilitySummary()` and `loadSnapshotFromDb()`.
- **Problem:** the AtomicReference is now mutated from BOTH classes (executor sets it post-classify; provider sets it post-DB-load). Shared mutable state with two owners. Either the field lives in the provider and the executor calls into it (which means writes effectively move), or the field lives in the executor and the provider has a back-reference to set it (which is worse than the original "executor leakage" smell — now we have "provider leakage").
- **Verdict:** structurally untenable.

**Option B — writes move to provider (recommended):**
- Provider hosts `latestStabilitySummary`, `stabilitySnapshotRepository`, `SNAPSHOT_STALENESS_HOURS`, all read methods, and `persistSnapshot()`. Adds a public `update(StabilitySummaryResponse)` entry point.
- Executor's `applyStabilityFilter` becomes: build summary → `stabilitySnapshotProvider.update(summary)` → continue. One line replaces two.
- AtomicReference has a single owner. DB read + write are tightly coupled (24h staleness guard, grid-cell shape) and naturally co-located.
- Repo dep no longer leaks into the executor.

### Provider's public API (recommended)

```java
public class StabilitySnapshotProvider {
    /** Returns the latest snapshot, in-memory first then DB fallback (24h staleness). */
    public StabilitySummaryResponse getLatestStabilitySummary();

    /** Sets the in-memory cache and persists per grid cell. Non-fatal on DB failure. */
    public void update(StabilitySummaryResponse summary);
}
```

`update` is a single entry point that does both the in-memory set and the DB persist. This matches the existing call-site shape (the two ops are always paired). DB persist remains best-effort (try/catch, log warn, never throws). Keeps the existing behaviour byte-identical.

### Naming

`StabilitySnapshotProvider` — matches the term used in Pass 3.2.1's investigation (Decision #6) and the brief. The "Provider" suffix slightly understates that it also writes, but read traffic dominates (4 callers) and the name is the one Chris filed in the prior investigation, so I'll keep it. Alternative `StabilitySnapshotStore` would be marginally more accurate but introduces nomenclature churn.

**Recommendation: writes move to the provider. Single class, single public entry point per direction (`getLatestStabilitySummary` for read, `update` for write).**

---

## Investigation 4 — Tests affected

### `ForecastCommandExecutorTest` — current state

A `@Mock private StabilitySnapshotRepository stabilitySnapshotRepository;` field is wired into the executor's constructor (line 156). Three test groups directly exercise the moved methods:

| Group | Lines | Tests | Disposition |
|---|---|---|---|
| Stability snapshot via execute() | 1410–1462 | `stabilitySnapshot_populatedAfterScheduledRun`, `stabilitySnapshot_notUpdatedByManualRun` | **Stay in `ForecastCommandExecutorTest`, but rewrite assertions.** Both tests run `executor.execute(cmd)` and then assert on `executor.getLatestStabilitySummary()`. After extraction, the assertion changes to `verify(stabilitySnapshotProvider).update(any())` (or `never()` for the manual-run case). The executor's real-world behaviour we're testing is "did execute() trigger a snapshot update?" — that's an executor concern (does the pipeline write the snapshot at all?). The detail of *what* the snapshot contains is now a provider concern, tested separately. |
| `StabilitySnapshotPersistence` (read path) | 2090–2207 | 6 tests: `returnsInMemoryWhenSet`, `returnsDbSnapshotWhenFresh`, `returnsNullWhenNoDbRows`, `returnsNullWhenDbStale`, `dbLoadWarmsCacheForSubsequentCalls`, `dbReadFailureReturnsNull` | **Relocate verbatim to `StabilitySnapshotProviderTest`.** They mock the repo directly and call `getLatestStabilitySummary()`. After extraction, drop the executor wiring, instantiate the provider directly, point the same assertions at it. |
| `StabilitySnapshotWritePath` (write path) | 2213–2371 | 3 tests: `persistSnapshot_savesEntityPerGridCellWithCorrectFields`, `persistSnapshot_updatesExistingEntity`, `persistSnapshot_failureDoesNotBreakRun` | **Relocate to `StabilitySnapshotProviderTest`** as direct unit tests on `provider.update(summary)`. They are simpler in the new location: no need to stub the entire `execute()` flow; just build a summary and call `provider.update(summary)`. The "failure does not break run" test becomes "update tolerates DB failure" — the executor-side guarantee (run completes despite persist failure) is also worth keeping, but the provider's contract is the more direct test. **One additional thin test in the executor suite verifies "execute() does not throw when `provider.update` throws"** to preserve that guarantee. |

### `ForecastCommandExecutorTest` setup change

```java
// Before:
@Mock private StabilitySnapshotRepository stabilitySnapshotRepository;
executor = new ForecastCommandExecutor(..., openMeteoService, stabilitySnapshotRepository);

// After:
@Mock private StabilitySnapshotProvider stabilitySnapshotProvider;
executor = new ForecastCommandExecutor(..., openMeteoService, stabilitySnapshotProvider);
```

### Caller test classes — mechanical mock swaps

| Test class | Stub count | Change |
|---|---|---|
| `StabilityControllerTest` | 2 | `forecastCommandExecutor.getLatestStabilitySummary()` → `stabilitySnapshotProvider.getLatestStabilitySummary()`. Constructor mock swap. |
| `EvaluationDeltaLogTest` (BriefingEvaluationService tests) | 5 | Same swap. Constructor mock swap. |
| `BriefingBestBetAdvisorTest` | 5 | Same swap. Constructor mock swap. |
| `CollectForecastTasksCachedGateTest` (ForecastTaskCollector tests) | 8 | Same swap. Constructor mock swap. |
| `ForecastTaskCollectorTest` | 0 (no `getLatestStabilitySummary` stubs in this file — only `forecastCommandExecutor` mock injection) | Constructor mock swap only. |
| `BriefingEvaluationServiceTest` | 0 | Constructor mock swap only (no stability-summary assertions in this file). |
| `BriefingEvaluationServiceCacheFreshnessTest` | 0 | Constructor mock swap only. |

### Integration tests — no changes required

`ForecastBatchPipelineIntegrationTest` and `IntegrationTestBaseSmokeTest` were grep'd for `ForecastCommandExecutor` and `getLatestStabilitySummary`: **zero matches.** The integration pipeline starts at `BatchSubmissionService.submit` and flows through poll → process → cache. The stability snapshot is upstream of all of that, owned by the scheduled batch flow. The contract is unaffected — these tests pass without modification. This is the safety net.

### New test class

`StabilitySnapshotProviderTest` — combines the relocated read-path (6 tests) and write-path (3 tests) suites into a single `@ExtendWith(MockitoExtension.class)` class with one `@Mock StabilitySnapshotRepository`. ~9 tests + a couple of additions to round out the contract:

- `update_setsInMemoryCache` (verifies the AtomicReference is observable via subsequent `getLatestStabilitySummary` without DB hit) — already implicitly covered by relocated tests but worth a dedicated assertion.
- `update_persistsCellsToDb` (verifies repo.save count matches cell count) — implicit in existing write-path tests.

Target ≥ 80% line coverage on the new class, per the brief's coverage gate.

---

## Investigation 5 — Constructor impact

| Class | Before | After | Net |
|---|---|---|---|
| `ForecastCommandExecutor` | 15 deps | 15 deps (drops `StabilitySnapshotRepository`, gains `StabilitySnapshotProvider`) | Same count. Dep is appropriate (the executor uses the provider for snapshot writes only, exactly the contract you'd expect). |
| `StabilitySnapshotProvider` (new) | — | 1 dep (`StabilitySnapshotRepository`) | New class, single dep. |
| `ForecastTaskCollector` | 10 deps + `@Value` ratio (constructor arg 11) | 10 + ratio (drops `ForecastCommandExecutor`, gains `StabilitySnapshotProvider`) | Same count. Dep is appropriate. |
| `BriefingEvaluationService` | 12 deps | 12 deps | Same. |
| `BriefingBestBetAdvisor` | 7 deps | 7 deps | Same. |
| `StabilityController` | 1 dep | 1 dep | Same. |

**No constructor count is meaningfully shrunk.** This is expected and correct — the brief explicitly notes the point is dep correctness, not count reduction. After Pass 3.2.2, every consumer of stability snapshots depends on a class whose name reflects its job. That's the win.

---

## Decisions to confirm before code changes

1. **Provider class name and package — `StabilitySnapshotProvider` in `com.gregochr.goldenhour.service`.** Matches the prior investigation's filed name. Same package as the executor (not pushed into `service.evaluation` or `service.batch`) because the provider serves four different package neighbourhoods (controller, batch, evaluation, briefing) and doesn't belong to any one of them.
2. **Writes move (Option B in Investigation 3) — recommended.** Single owner of the AtomicReference. Repo dep no longer leaks. Executor's `applyStabilityFilter` shrinks by 1 line. **If Option A is preferred for any reason, flag now — the implementation diverges significantly.**
3. **`update()` as the public write API name.** Single method that does both in-memory set + DB persist. Alternative names considered: `record`, `store`, `save`, `publish`. `update` reads cleanly at the call site (`stabilitySnapshotProvider.update(summary)`) and matches what the operation does (overwrite the cache).
4. **`StabilitySnapshotWritePath` test relocation — to `StabilitySnapshotProviderTest`** as direct unit tests, with one thin "execute() doesn't break when update() throws" smoke test left behind in `ForecastCommandExecutorTest` to preserve the executor's guarantee. Alternative: leave the write-path tests where they are and have them run via `executor.execute()`. **Recommendation: relocate.** The provider is the natural home; the executor test is bloated and the write-path tests do not need 100+ lines of execute-flow stubbing for what is fundamentally `provider.update(summary); verify(repo).save(...)`.
5. **Caller test mock swaps treated as part of the migration commits, not as a separate cleanup.** Each commit (steps 2–5 below) includes the test mock swaps for the classes it touches. Keeps each commit's diff coherent.

---

## Recommended commit sequence (post-review)

Per the brief:

1. **`docs: investigation findings for Pass 3.2.2 StabilitySnapshotProvider extraction`** — this document.
2. **`feat: introduce StabilitySnapshotProvider with snapshot read+write extracted from ForecastCommandExecutor`** — new class with full unit tests (read path 6 + write path 3 + 1–2 contract tests = ~9–11 tests). Not yet wired; the executor still hosts its own copy.
3. **`refactor: migrate ForecastTaskCollector and snapshot consumers to StabilitySnapshotProvider`** — bundle all 4 caller migrations (`ForecastTaskCollector`, `BriefingEvaluationService`, `BriefingBestBetAdvisor`, `StabilityController`) plus their test mock swaps into one commit. They are mechanically identical (constructor swap, mock swap), and splitting them adds churn without value.
4. **`chore: remove moved snapshot methods from ForecastCommandExecutor`** — final cleanup. Wires the executor to the provider for the write side (`applyStabilityFilter` calls `provider.update(summary)`), drops the 3 fields and 3 methods, drops the repo dep, updates `ForecastCommandExecutorTest` (mock swap, relocated tests removed, the two `stabilitySnapshot_*` execute-tests rewritten to verify on the provider mock).

Each commit leaves `main` green. JaCoCo gate verified locally before push, per the brief.

---

## Out of scope (explicit non-goals)

- Routing `ForecastCommandExecutor`'s per-location Claude calls through `EvaluationService.evaluateNow()` — that is Pass 3.3's territory.
- Reshaping the `stability_snapshot` table or its schema (V98).
- Changing the 24-hour staleness threshold.
- Renaming `latestStabilitySummary` or `StabilitySummaryResponse`.
- Any extraction beyond `StabilitySnapshotProvider` (no `WeatherDataProvider`, no `LocationContextProvider`).
- Modifying integration tests.
- Any behaviour change. The user-visible output of the scheduled batch must remain byte-identical.

---

## Risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| `applyStabilityFilter`'s write side-effect is silently dropped during refactor | Low | Dedicated executor test verifies `verify(provider).update(any())` after a non-manual run. Existing `stabilitySnapshot_populatedAfterScheduledRun` shifts to this assertion shape. |
| Two writers race during cache warm + classification | Vanishingly low | The classification path runs from the scheduled batch (single AtomicBoolean-guarded entry). The DB-warm path runs from any read caller. Both target an `AtomicReference.set`, which is the operation it's designed for. No mitigation needed beyond the existing `AtomicReference`. |
| Provider's package choice causes circular deps | Low | Provider imports only `StabilitySnapshotRepository`, `StabilitySnapshotEntity`, `StabilitySummaryResponse`, `ForecastStability`, and stdlib. None of these import service classes. Verified by mentally tracing the import graph. |
| JaCoCo gate trips on the new class | Low | Relocated tests give ~9 tests on the provider. Add ≤ 2 tests if any branch isn't already covered. Brief's explicit coverage discipline applies. |
| Hidden caller of `getLatestStabilitySummary` was missed | Low | Grep across `backend/src` returned exactly 4 production callers + 5 test files (all enumerated above). Repeat the grep before the migration commit lands as a final sanity check. |
