## Verifier's corrections to §D

### 1. The T+3 ceiling is a GATE, not a structural bound — this is the section's fatal flaw

`HorizonModelSelector`'s Javadoc justifies `MAX_DAYS_AHEAD = 3` by pointing at
`BriefingService.BRIEFING_WINDOW_DAYS`. That constant is **5**
(`BriefingService.java:118`), and `:410` builds `IntStream.range(0, BRIEFING_WINDOW_DAYS)`
— dates T+0..T+4. **T+4 is inside the window.** The ceiling is the surviving half of
`NightlyEligibilityPolicy.java:54` (`default -> skip("T+" + daysAhead + " beyond horizon")`).
`BriefingService.java:107-116` says in its own words that the fifth date exists *so the window
still reaches T+3 after ageing overnight* — a scheduling artefact documented as a defect.

Two consequences:

- Under correction (2), a horizon cap that is not forced by the data you hold is a policy gate
  and must go. Replace `MAX_DAYS_AHEAD` with the actual weather-data extent (measure what
  `OpenMeteoService` requests; do not assume), or state plainly in the commit message that a
  T+3 policy cap is being **retained** against the brief and why.
- The Javadoc as written will be read as settled fact by the next engineer. Do not ship it.

### 2. Do not remove the `triggeredManually` bypass — that ADDS a gate

`ForecastCommandExecutor.java:348-358` currently reads
`if (!triggeredManually) { applyStabilityFilter … } else { LOG.info("Stability filter bypassed — manual run"); }`.
An admin manual run today has **no horizon filter at all**. Applying `withinHorizon` to it
introduces a new cap on the one path that had none. Keep the bypass, or the redesign
delivers a *narrower* admin path than the one it replaces.

### 3. `evaluationWindowDays` has a third reader — the classifier you are keeping

`ForecastStabilityClassifier.java:87` calls `stability.evaluationWindowDays()` to build the
record, and `:65-66` hard-codes the 6th argument `1`. There are **28** `new GridCellStabilityResult(...)`
sites tree-wide (2 main, 26 test across `ForecastCommandExecutorTest`,
`ForecastTaskCollectorTest`, `ForecastTaskCollectorForceEvalTest`, `GridCellStabilityServiceTest`),
of which three test classes survive the deletion list.
`ForecastStabilityClassifierTest.java:40, :53, :67, :123-125` asserts on it and appears in
neither the delete nor the trim list. Add it, and add the record-component change to the
commit-4 blast radius.

### 4. Missed subsystem: `STABILITY_RECLASSIFY` / `ReclassSummary` / `ephemeral`

Not mentioned anywhere in §D, and orphaned by commit 4:

| Object | Where | Fate |
|---|---|---|
| `PipelinePhase.STABILITY_RECLASSIFY` | `entity/PipelinePhase.java:33`, doc at `:12` | delete |
| `service/batch/ReclassSummary.java` (53 lines) | counts `SKIPPED_NO_REFRESH_NEEDED` at `:38`; renders the intraday cost-gate row at `:49-52` | delete |
| `Consumer<ReclassSummary> betweenCollectAndSubmit` | `ScheduledBatchEvaluationService.java:73, :248, :340, :348` | delete |
| `ephemeral` boolean | `ForecastTaskCollector.java:251-263, :271, :341`; `PipelineOrchestrator.java:333, :365, :373`; `GridCellStabilityService` (7 refs) | delete |
| `PipelineOrchestratorTest` intraday-phase nested class | `:697-740` | delete |

`GridCellStabilityService.classifyGridCellsAndPublishSnapshot(List, Map, boolean)` — the
3-arg ephemeral overload — is still called at `ForecastTaskCollector.java:341`, so commit 4
does **not** compile as written. ~150 further production lines to remove.

### 5. Commit-order defects

- **`ForecastTaskCollector.java:446-447`** calls `gridCellStabilityService.stabilityFor(...)`
  and is listed in no commit. Commit 4 breaks the build there. Its `stabilityClassifier` field
  (`:99`), ctor param (`:150`) and assignment (`:172`) also go dead — and
  `ForecastTaskCollectorTest` stubs `stabilityClassifier.classify(...)` at many sites.
- **Commit 1 is not additive.** It changes `BriefingBestBetAdvisor.advise`
  (`:195-196`) and `BriefingRollupBuilder.buildRollupJson` (`:111`), breaking
  `BriefingBestBetAdvisorTest` and `BestBetAuroraPromptRegressionTest:111/:175/:219`
  in the first commit.
- **Commits 2→5 leave `enrichWithStability` (`ForecastCommandExecutor.java:369`) without a
  map** for three commits. Merge commit 5 into commit 2, or move it first. As written, either
  it does not compile or FORECAST RELIABILITY is silently off on the only path that emits it.

### 6. `src/test/java/**/regression/` does not exist

`find . -type d -name regression` returns nothing. Prompt-regression tests are selected by
**`@Tag("prompt-regression")`** (`pom.xml:23` excludedGroups, `:29-32` profile). Tagged files:
`PromptRegressionTest`, `BestBetAuroraPromptRegressionTest` (`:57`), `SkyRatingEvalTest`.
`BestBetAuroraPromptRegressionTest` **is** a prompt-regression test; the constructor edit at
`:111/:175/:219` is still permissible (it is not an assertion), but restate the binding
constraint tag-wise.

### 7. `tideAlignmentEnabled` — delete the lane, do not pin it `false`

Pinning `false` leaves permanently unreachable code at `ForecastService.java:386-415` (30 lines),
plus `TideAlignmentEvaluator` (111) and `TideAlignmentEvaluatorTest` (247), and the dead
boolean at `ForecastService.java:261, :282` and `ForecastCommandExecutor.java:464, :469, :475`.
`ForecastServiceTest:514/:559` pass `true` directly so JaCoCo will not even flag it. Remove
the whole lane. **388 further lines.**

### 8. `BriefingEvaluationService` deletion ranges swallow `objectMapper`

Fields are `:68` cachedEvaluationRepository, `:69` deltaLogRepository, **`:70` objectMapper**,
`:71` freshnessResolver, `:72` stabilitySnapshotProvider; ctor `:111-120` with
`this.objectMapper = objectMapper;` at `:118`. Delete `:69, :71, :72` and
`:112, :114, :115, :117, :119, :120` only.

### 9. Frontend additions

Add to the commit-6 frontend list:

- `ModelSelectionView.jsx:466-490` — admin prose asserting *"T+2 and T+3 — only evaluated when
  the weather stability classifier says SETTLED"* (`:470`) and explaining the classifier as an
  eligibility gate (`:482-483`). False after the change.
- `ModelSelectionView.jsx:525` and `:555` — call-volume copy tied to triage + stability; these
  will under-state the new volume, which is the number the operator checks the cost delta against.
- `ModelSelectionView.jsx:2` (import), `:179` (`setStrategies(...)`), `:572` (copy).
- `JobRunsMetricsView.jsx:289` (`STRATEGY_LABELS[s.strategyType]`); `STRATEGY_LABELS` is at
  **`:22`**, not `:21`.
- `frontend/src/test/JobRunsMetricsViewBatch.test.jsx:65, :118, :359` — `:118`/`:359` assert the
  exact `/triage and stability gates/` copy at `JobRunsMetricsView.jsx:883`.

### 10. `application-dev.yml` has no `resilience4j` block — do not hand-wave it

`resilience4j` exists only at `application-example.yml:210`, `application-local.yml:125`,
`application-prod.yml:41`. `application-local.yml:125-177` sets the Open-Meteo limiter to
`limitForPeriod: 8 / 1s`, and `BriefingService.java:430-432` explicitly depends on it
(*"the @RateLimiter on fetchForecast() throttles naturally at 8 calls/second"*). Resilience4j's
library default is 50 per 500ns — effectively unthrottled. Copy the block into
`application-dev.yml` in the same commit.

### 11. Docs the list misses

`docs/engineering/integration-test-strategy.md:519` (preprod seed SQL generated from
`application-local.yml`'s `forecast.locations` — deleting the file destroys the provenance of
`scripts/seed/preprod-seed.sql`), `docs/engineering/snow-trio-investigation.md:198`,
`docs/engineering/briefing-cache-write-decoupling.md:214`, `CLAUDE.archive.md:771, :778`.
**And `CHANGELOG.md:524`**, which records the opposite decision verbatim: *"H2 is not being
retired from the project, only from production… deliberately"*. Cite and overturn it in the
commit message, or the next reader restores it.

### 12. Corrected line citations

| Design says | Actual |
|---|---|
| `ModelsController` optimisation endpoint `:101-115` | `:100-119` |
| `modelsApi.js updateOptimisationStrategy :54-75` | JSDoc `:53-61`, fn `:62-75` |
| `JobRunsMetricsView STRATEGY_LABELS :21` | `:22` |
| `V101…sql:26` (disposition column) | `:27` |
| `pom.xml:248-254` dep / `:247-250` comment | dep `:250-254`, comment `:247-249` |
| `ForecastService` tide return `:409-412` | `:411-413`; block `:386-415` |
| `mostVolatileStability` UNSETTLED at `:339` | `:340` |
| `runSentinelPhase` ≈70 lines `:491-~560` | `:491-572`, 82 lines |
| `ScheduledBatchEvaluationServiceTest (:277, :280)` | `:136, :159, :187, :232, :257, :283, :318, :476, :482, :532, :547` |
| `OrchestratedDispositionWriteIntegrationTest (:137,:140,:262)` | `:23, :152, :216, :271, :286` |
| `ForecastTaskCollectorTest (:1104,:1145,:1220,:1358)` | `:827, :864, :1111, :1138, :1173` |
| `ScheduledForecastService:26` "will not compile" | Javadoc `{@link}`; javac ignores it and there is **no** `maven-javadoc-plugin` in the POM (`grep -c` → 0). Doc rot, not a build break. |

### 13. Two smaller deletion-bias misses

- `BriefingBestBetAdvisor.advise`'s `driveMap` is documented at `:193` as *"unused — retained
  for API compatibility"*, with one caller passing `Map.of()` (`BriefingService.java:499`).
  Delete it in the same signature change rather than adding a fourth parameter beside it.
- `JobRunService` already has a 3-arg `startRun(RunType, boolean, EvaluationModel)` overload
  (`:79-82`) that passes `null` internally. Call that instead of threading an explicit `null`.
- `ForecastStability.java:9-13` names the enum's three consumers, two of which are being
  deleted. Update it — it is the one comment recording what stability is *for*.

### 14. What survived verification unchanged

All 22 `src/main` and all 14 `src/test` deleted-file line counts are exact. The
display/gate boundary is drawn correctly at the three places it matters: the Plan tab's
GO/MARGINAL/STANDDOWN verdict (`BriefingVerdictEvaluator`, `BriefingSlotBuilder`,
`BriefingHierarchyBuilder`) is never touched; `WeatherTriageEvaluator` + `TriageReason` +
`forecast_evaluation.triage_reason/triage_message` survive as decoration; `PromptBuilder`'s
FORECAST RELIABILITY block survives. `SYSTEM_PROMPT` is untouched and
`SystemPromptCacheabilityTest:40/:52` stays green. The migration analysis is sound: no FK,
no view, no cross-migration reference to the three dropped tables, latest is V138, and both
new files are valid Postgres 17. The `withStability`-has-one-call-site finding
(`ForecastCommandExecutor:667`) is correct and is the most valuable thing in the section.
