## Verification appendix to §4.7 — corrections required before implementation

Verified against the working tree at `a484d1c4` (clean). Everything below was read from source.

### A. Must-fix before any code is written

**A1. The migration is `V139__briefing_slate.sql`, not V137.**
`ls backend/src/main/resources/db/migration/ | sort -V | tail -1` → `V138__durham_heritage_coast_locations.sql`.
`V137__region_groupings_regain_statistical_weight.sql` already exists (and its :158 does
`DELETE FROM daily_briefing_cache`). Two version-137 scripts = Flyway refuses to start.

**A2. `slate.aboveThreshold()` must not gate submission.**
As written, §4.7.5 calls `failRun` and returns *before* `submitPhase` when coverage < 50 %, so a
49 %-coverage cycle submits zero batches and produces zero evaluations. Today the order is
submit → wait → brief, and the below-threshold condition only degrades the **published** artefact
(`BriefingService.java:508`, `:550–574`) — the batch has already gone. Coverage is a fact *about the
slate*, so under the brief's rule it is carried on `BriefingSlate` and rendered, never consulted to
decide spend. Keep `locationsSucceeded/locationsFailed` and `partialFailure()`; delete
`aboveThreshold()` from the orchestrator's control flow entirely. The cycle proceeds; the published
briefing says how thin the coverage was.

**A3. `ForecastTaskCollector` cannot drop `BriefingService` without answering for two admin paths.**
The second reader is at **`:733`**, not `:717` — it is `collectRegionFilteredBatches(List<Long>)`
declared at `:732`, called from `ScheduledBatchEvaluationService.java:530`. The legacy no-arg
`collectScheduledBatches()` at `:223–227` is called from `ScheduledBatchEvaluationService.java:165`
(admin `submitForecastBatch()`) and ~30 test sites. Neither is inside an orchestrated cycle, so
neither has a `pipeline_run_id` or a persisted slate. Two options, pick one and state it:
either both admin paths call `briefingService.buildSlate()` themselves (a free Open-Meteo batch,
no persistence), or both are deleted along with `RegionFilteredBatchTasks`,
`ScheduledBatchEvaluationService.submitForecastBatch()`/`:530`, and their tests — which is the
answer the deletion bias prefers and is worth ~200 more lines.

**A4. Thread the slate through `ScheduledBatchEvaluationService`.** Missing from §4.7.7 entirely.
Three overloads need a `BriefingSlate` parameter: `:193`, `:210`, `:244`; the collector call is at
`:341`. Without this the orchestrator's `submitPhase(runId, cycleType, slate, strategy)` does not
compile.

**A5. `runCycleSynchronously(PipelineRunEntity)` (`PipelineOrchestrator.java:295–306`).**
Unaddressed. It calls the old four-arg `submitPhase` and then the tail directly; the new tail does
`briefingSlateStore.find(runId).orElseThrow(...)`, so it throws for every caller. It is a test-only
public duplicate of `runCycle` — **delete it**, update the class javadoc at `:46`, and move its
tests to `runCycle(CycleType.NIGHTLY)` with a deterministic executor.

**A6. `republish()` re-opens the hole `lastKnownGood` closed.**
§4.7.10 deletes `lastKnownGood` on the grounds that "a below-threshold slate never reaches
`publish`". That is true only on the orchestrated path. `republish() == publish(buildSlate())` has no
coverage check, and `publish` ends in `persistBriefing` (`BriefingService.java:594–605`, upserting
`daily_briefing_cache` id = 1). An admin pressing **Run Briefing** during an Open-Meteo outage
destroys the good copy. Recommended resolution, consistent with A2 and the deletion bias: **delete
the republish path** — `BriefingService.republish()`, `BriefingController.java:71–75`,
`briefingApi.js:25` `runBriefing`, `JobRunsMetricsView.jsx:364–376` `handleRunBriefing` and its
button, and the three `BriefingControllerTest` cases at `:153–176`. The cycle publishes twice a day;
nothing else needs to.

### B. Corrected file:line cites

| §4.7 says | Actually |
|---|---|
| `fetchWeatherSequential (:787–848)` | declared at **`:824`** |
| `captureGridCoordinates (:857–879)` | declared at **`:894`**; the `!loc.hasGridCell()` guard is at **`:901`** |
| `fetchHorizonCloud (:893–949)` | declared at **`:930`** |
| Open-Meteo forecast batch `:813` | `openMeteoClient.fetchForecastBriefingBatch(coords)` at **`:850`** |
| Cloud-only batch `:926` | `openMeteoClient.fetchCloudOnlyBatch(coords)` at **`:962`** |
| collector's 2nd briefing read `:717–721` | **`:733–737`** (`collectRegionFilteredBatches`, declared `:732`) |
| `ServeTimeReEnrichment (:2250)` | `@Nested` at **`:2292`**, class at `:2294` |
| `BriefingServiceTest.java (2404 lines)` | **2446** lines; 56 `refreshBriefing` sites |
| `RunType.java:29` javadoc | javadoc at **`:28`**, `BRIEFING` at `:29` |
| `ConfidenceDeriver` null-on-zero-coverage `:738` | `ConfidenceDeriver.derive(...)` called at **`:735`** |
| 14 `refreshBriefing` verifies in `PipelineOrchestratorTest` | **15** — `:824` is missing from the list |
| `getCachedDays` stubs "~100" | **82** token occurrences (48 King, 34 Spring) |

Everything the design cites in `BriefingService.java:404–575`, `PipelineOrchestrator.java`,
`BriefingCandidateCollector.java`, `PipelinePhase.java`, `BriefingGlossService.java`,
`DailyBriefing.jsx`, `briefingApi.js` and the migrations is **correct as written**.

### C. Risks the design flagged — now resolved

**C1. `@JdbcTypeCode(SqlTypes.JSON)` on H2 — SAFE, verified by disassembly.**
`hibernate-core-7.4.1.Final` (Spring Boot 4.1.0, `pom.xml:10`): `H2Dialect` registers
`DdlTypeImpl(3001, "json")`; `PostgreSQLDialect` registers `DdlTypeImpl(3001, "jsonb")`.
`SqlTypes.JSON == 3001`. The six `@SpringBootTest` H2 contexts will generate a `json` column and
start. Prod is `ddl-auto: validate` (`application.yml:12`, `application-prod.yml:13`), and the entity
maps to `jsonb`, matching the migration.
**Delete the proposed fallback.** `@Column(columnDefinition = "TEXT")` against a `jsonb` column fails
Hibernate schema validation at boot in prod.

**C2. Hot topics reading the slate — SAFE, and the UNVERIFIED caveat can be closed.**
`grep -rn getCachedDays src/main` returns exactly two call sites: `KingTideHotTopicStrategy.java:97`
and `SpringTideHotTopicStrategy.java:92`. There are 13 `implements HotTopicStrategy` classes; the
other 11 never call it. Neither tide strategy reads `claudeRating`, `displayVerdict`,
`fierySkyPotential` or `claudeSummary` — and neither does any class under `service/batch/`
(`ForceEvalHeadlineSelector.java:107–114` reads only `verdict()`, `tide().tideAligned()` and
`BriefingGatingPolicy.isEligibleForEvaluation`). An unenriched slate is safe for both hot topics and
selection.

**C3. `PipelinePhase.SLATE` first in the enum — SAFE, for a reason the design did not check.**
`PipelineRunEntity:40–42` and `PipelineRunPhaseEntity:32,39` are `@Enumerated(EnumType.STRING)`, and
`V102__pipeline_run.sql` declares `current_phase VARCHAR(40)` / `phase VARCHAR(40)` with **no CHECK
constraint**. Ordinals are never persisted. Replace the "ordinal order matches execution order"
justification with this, so the next reader does not repeat the reasoning.

**C4. `BRIEFING_PUBLISH` — no migration, no model row.** `job_run.run_type` is `VARCHAR(20)`
(`V29:5`); the constant is 16 chars. `ModelSelectionService.getActiveModel(RunType)` (`:43–48`)
falls back to `EvaluationModel.HAIKU` on a missing row. The only `RunType.values()` loops are
`ModelsControllerTest:53,:109` and they only build maps.
**But**: the split fragments `job_run` cost history across two run types, and the §4.7.9 measurement
query stops working the day it ships — it must become
`WHERE jr.run_type IN ('BRIEFING','BRIEFING_PUBLISH')`. Given `pipeline_run_phase` already records
BRIEFING as its own timed phase, reconsider whether the second run type earns its keep.

### D. Boundary the design must draw explicitly

`BriefingGatingPolicy` is used by **both** sides:

- **Gate (deleted by the gate-removal work):** `BriefingCandidateCollector.java:239–240`.
- **Display (must survive):** `BriefingGlossService.java:203` (`hasAnyEligibleSlot` — decides which
  regions get Claude prose on the Plan tab) and `:268` (which slots enter the gloss user message);
  `BriefingHonestyFilter.java:19` documents its dependence.
- Also `ForceEvalHeadlineSelector.java:110`.

State in §4.7.7 that `BriefingGatingPolicy` **survives**, and that only its call site at
`BriefingCandidateCollector.java:239` goes. §4.7.9's own gloss-count arithmetic depends on
`hasAnyEligibleSlot` still existing.

### E. Coverage and test-cost corrections

- `BriefingSlateRoundTripIT extends IntegrationTestBase` contributes **zero** JaCoCo coverage to the
  CI-gating run, because CLAUDE.md's CI-reproduction command excludes `**/integration/**`. Add a
  Mockito `BriefingSlateStoreTest` (save/find/findLatest/prune) and plain accessor tests for
  `BriefingSlateEntity`, or the 80 %/class rule fails on the first push.
- `BriefingSlateRepository.deleteByBuiltAtBefore(Instant)` is a derived delete: it needs
  `@Modifying` + `@Transactional`, otherwise it throws at runtime.
- `CollectForecastTasksCachedGateTest.java:107–115` binds by reflection to the **private**
  `ForecastTaskCollector.collectForecastCandidates(DailyBriefingResponse, List, CandidateCollectionStrategy)`
  — a wrapper §4.7 never mentions. Changing its signature breaks that test with
  `NoSuchMethodException`, not a compile error, and it breaks in Phase 1 if the slate ships alone.
- Real test churn: 2,446 lines split three ways + 82 `getCachedDays` sites + 15
  `PipelineOrchestratorTest` verifies + every `ForecastTaskCollectorTest` fixture. §4.7.12's
  "test lines removed ~120" is accurate but is not the cost.

### F. Frontend claim: state the condition

"Six events is at most three whole days, so `dates[4]` is never rendered" holds only while every day
carries two `eventSummaries`. `BriefingService.java:468` already suppresses the sunset slot for
woodland-only locations, and `selectUpcomingEvents` (`DailyBriefing.jsx:114–124`) walks
`day.eventSummaries()`. The 5→4 change is safe regardless (4 dates × 2 events = 8 ≥ 6) — but say
*why* rather than asserting an unconditional invariant.

### G. Things the design kept that the deletion bias says should go

1. `runCycleSynchronously` — test-only public duplicate (A5).
2. The whole republish path — endpoint, service method, API module function, button, 3 tests (A6).
3. `BriefingModelTestService` + `briefing_model_test_run` / `briefing_model_test_result` +
   `BriefingModelTestView` + `POST /api/briefing/compare-models` and its two GET twins. §4.7.3
   repairs it (re-points `:100` at the slate holder). A model-comparison harness that has never
   gated anything is a deletion candidate, not a repair candidate — and deleting it removes the
   third caller of `getCachedBriefing()` for free.
4. Note in passing: V103's own comment at `:7–11` says the `daily_briefing` `@PostConstruct`
   registration was **deliberately retained** as a one-line revert path. Deleting it (correct) should
   say so, so the next reader does not treat V103's comment as still true.
