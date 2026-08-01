# DRAFT — subsystem-deletion

> ⚠️ **Working material, not the build source.** Written against a mix of `a484d1c4`
> (not an ancestor of `main`) and `d421ef5f`; citations of `ForecastTaskCollector.java`
> past line 534 are off by +16. Adversarial verification refuted claims in this draft —
> see `subsystem-deletion.corrections.md`. **Resolved positions live in `../all-in-build-plan.md`,
> which supersedes this file wherever they disagree.**

---

# §D — The subsystem, data and config deletion program

Every file path, line number, constant and behaviour below was read from the tree at
`a484d1c4`. Nothing here is asserted from a Javadoc or from `CLAUDE.md`. Where I could not
check something I have written **UNVERIFIED**.

---

## D.0 — The rule this section applies, and where it bites

**Classify for display; never gate on the classification.** Three subsystems on the list are
each a classifier bolted to a gate. In every case the classifier's output has a reader that is
not the gate, and the deletion boundary runs between them:

| Subsystem | Classifier (survives) | Gate (deleted) | Non-gate reader that forced the split |
|---|---|---|---|
| Stability | `ForecastStabilityClassifier` (`service/ForecastStabilityClassifier.java`, 214 lines) | `NightlyEligibilityPolicy`, `IntradayEligibilityPolicy`, `ForecastCommandExecutor.applyStabilityFilter` | `PromptBuilder.java:607–630` FORECAST RELIABILITY block; `BriefingRollupBuilder.java:402–432` region rollup |
| Freshness | the three TTL numbers | `BriefingCandidateCollector.java:214–236` `SKIPPED_CACHED` | staleness display (§4.5 of the summary design) |
| Weather triage | `WeatherTriageEvaluator` (`service/WeatherTriageEvaluator.java`, 65 lines) | `ForecastService.java:365–383` early return | `forecast_evaluation.triage_reason` / `triage_message` (V96), surfaced in the API and map popover |

The one place the rule loses is **`job_run.active_strategies`** (V41 tail,
`JobRunEntity.java:99–100`). It is a persisted record of *why* past rows were selected. Under
the selection-effect constraint that column must stay even though nothing will ever write it
again. See D.4.

---

## D.1 — What is actually there (verified inventory)

### Deleted outright — `src/main`

| File | Lines | Inbound compile edges (src/main) |
|---|---|---|
| `config/FreshnessProperties.java` | 51 | `FreshnessResolver` only |
| `service/FreshnessResolver.java` | 67 | `BriefingEvaluationService`, `BriefingCandidateCollector`, `ForecastTaskCollector` |
| `service/batch/EligibilityPolicy.java` | 40 | `ForecastTaskCollector`, `ScheduledBatchEvaluationService`, `PipelineOrchestrator`, both policies |
| `service/batch/EligibilityDecision.java` | 66 | `EligibilityPolicy`, `ForecastTaskCollector`, both policies |
| `service/batch/NightlyEligibilityPolicy.java` | 73 | `ForecastCommandExecutor:624`, `ForecastTaskCollector:226/715/803`, `ScheduledBatchEvaluationService:173/196`, `PipelineOrchestrator:226/300` |
| `service/batch/IntradayEligibilityPolicy.java` | 54 | `PipelineOrchestrator:252` |
| `service/batch/ForceEvalHeadlineSelector.java` | 167 | `ForecastTaskCollector` **only** |
| `service/OptimisationSkipEvaluator.java` | 206 | `ForecastCommandExecutor:261` |
| `service/OptimisationStrategyService.java` | 241 | `ModelsController`, `ForecastCommandExecutor` |
| `entity/OptimisationStrategyEntity.java` | 60 | above + repository |
| `entity/OptimisationStrategyType.java` | 39 | above |
| `repository/OptimisationStrategyRepository.java` | 41 | `OptimisationStrategyService` |
| `model/OptimisationStrategyUpdateRequest.java` | 20 | `ModelsController` |
| `service/SentinelSelector.java` | 58 | `ForecastCommandExecutor:125` |
| `service/StabilitySnapshotProvider.java` | 204 | 8 files (see D.2 step 5) |
| `entity/StabilitySnapshotEntity.java` | 62 | provider, repository |
| `repository/StabilitySnapshotRepository.java` | 32 | provider |
| `controller/StabilityController.java` | 51 | — |
| `service/batch/GridCellStabilityService.java` | 238 | `ForecastCommandExecutor:128`, `ForecastTaskCollector:195` |
| `model/StabilitySummaryResponse.java` | 64 | 6 files |
| `entity/EvaluationDeltaLogEntity.java` | 70 | `BriefingEvaluationService` |
| `repository/EvaluationDeltaLogRepository.java` | 10 | `BriefingEvaluationService` |
| **Total** | **1,914** | |

`SentinelSelector` is not on the brief's list. It is orphaned the moment
`OptimisationStrategyType.SENTINEL_SAMPLING` goes: its only reader is
`ForecastCommandExecutor:317–337`, which is guarded by
`enabledStrategies.stream().filter(s -> s.getStrategyType() == SENTINEL_SAMPLING)`. Sentinel
sampling *is* a gate — "if all region sentinels rate ≤ 2, skip the rest of that region"
(`ForecastCommandExecutor.java:50–51`) — so it goes under correction (2) even without the
optimisation table.

### Kept, deliberately

- **`service/ForecastStabilityClassifier.java` (214)** and **`entity/ForecastStability.java`
  (66)** — the classifier and enum. Re-homed, not deleted (D.3).
- **`model/GridCellStabilityResult.java` (22)** — still the classifier's return type. Its
  `evaluationWindowDays` component (line 21) loses its last reader once
  `StabilitySummaryResponse` goes; drop that component and
  `ForecastStability.evaluationWindowDays()` (lines 44–65) with it, ≈25 lines.
- **`photocast.batch.min-prefetch-success-ratio`** — **survives**. Read at
  `ForecastTaskCollector.java:313`, it aborts the batch when the *weather fetch itself*
  degraded below 50 % (`"likely Open-Meteo outage"`, `:314`). That is the structural bound
  "you cannot evaluate beyond the weather data you hold", not a policy gate on Claude spend.
- **`DispositionCategory` constants** `SKIPPED_STABILITY`, `SKIPPED_CACHED`,
  `FORCE_EVALUATED`, `SKIPPED_NO_REFRESH_NEEDED` — **all four stay in the enum**, with their
  Javadoc rewritten to "historical only; no longer produced". `forecast_run_disposition`
  stores them as `VARCHAR(40)` (`V101__forecast_run_disposition.sql:26`) with 30-day
  retention (`:41–44`), and `DispositionCategory.fromString` returns `Optional.empty()` for
  unknown values (`:117–127`). Removing the constants would silently drop 30 days of skip
  records out of the disposition view — which is precisely the selection-effect blindness §2.7
  of the summary design exists to prevent.

### Deleted — `src/test` (2,700 lines, whole files)

`FreshnessResolverTest` 91 · `IntradayEligibilityPolicyTest` 67 ·
`NightlyEligibilityPolicyTest` 58 · `OptimisationSkipEvaluatorTest` 368 ·
`OptimisationStrategyServiceTest` 216 · `StabilitySnapshotProviderTest` 287 ·
`StabilityControllerTest` 87 · `GridCellStabilityServiceTest` 207 · `EvaluationDeltaLogTest`
229 · `ForecastTaskCollectorForceEvalTest` 350 · `ForecastTaskCollectorEligibilityPolicyTest`
117 · `CollectForecastTasksCachedGateTest` 350 · `BriefingEvaluationServiceCacheFreshnessTest`
135 · `SentinelSelectorTest` 138.

Trimmed in place (≈500 lines): `ForecastTaskCollectorTest` (`:1104`, `:1145`, `:1220`,
`:1358`), `ForecastCommandExecutorTest`, `PipelineOrchestratorTest`,
`ScheduledBatchEvaluationServiceTest` (`:277`, `:280`), `BriefingServiceTest`,
`ModelsControllerTest`, `AbstractControllerTest` (`:35`/`:161` optimisation mock, `:42`/`:201`
stability mock), `BriefingBestBetAdvisorTest`, `BriefingEvaluationServiceTest`,
`OrchestratedDispositionWriteIntegrationTest` (`:137`, `:140`, `:262`),
`BestBetAuroraPromptRegressionTest`.

**`BestBetAuroraPromptRegressionTest` lives under `service/evaluation/`, not
`src/test/java/**/regression/`** — verified: `find src/test -path '*regression*'` returns the
prompt-regression package separately. It mocks `StabilitySnapshotProvider` for construction
only. Editing its constructor call is not an assertion change. **Do not touch any assertion in
`src/test/java/**/regression/`.**

---

## D.2 — The deletion order

Nine commits. Every one compiles and leaves `./mvnw verify` green. The order is leaf-inward:
**cut the call sites first, then delete the now-unreferenced class**, because Java will not
let you do it the other way round.

### Commit 1 — `feat: classify stability at slate-build time`

Additive. Nothing deleted. This is the load-bearing prerequisite: it gives both surviving
readers of `ForecastStability` a source that is not the snapshot table.

`BriefingService.refreshBriefing()` already holds everything needed at
`BriefingService.java:433–434`:

```java
List<BriefingSlotBuilder.LocationWeather> locationWeathers =
        fetchWeatherSequential(colourLocations, jobRun);
```

and `LocationWeather` is `(LocationEntity location, OpenMeteoForecastResponse forecast)`
(`BriefingSlotBuilder.java:364`). Insert after line 434:

```java
/**
 * Classifies synoptic stability once per unique Open-Meteo grid cell from the weather
 * already fetched for the slate, and flattens it to a location-name lookup.
 *
 * <p>Arithmetic over data in hand — no API call. A location whose cell cannot be
 * classified is <b>absent</b> from the map rather than defaulted: absence reads as
 * "no FORECAST RELIABILITY block", which the system prompt already handles
 * (PromptBuilder.java:225). The former TRANSITIONAL fallback
 * (GridCellStabilityService.java:229) asserted an approaching front that was never
 * measured — acceptable when the value only chose a TTL, dishonest now that it only
 * feeds a prompt and a rollup.
 *
 * @param locationWeathers per-location slate weather
 * @return location name → stability, omitting unclassifiable locations
 */
private Map<String, ForecastStability> classifyStability(
        List<BriefingSlotBuilder.LocationWeather> locationWeathers) {
    Map<String, GridCellStabilityResult> byCell = new HashMap<>();
    Map<String, ForecastStability> byLocation = new LinkedHashMap<>();
    for (BriefingSlotBuilder.LocationWeather lw : locationWeathers) {
        LocationEntity loc = lw.location();
        if (lw.forecast() == null || !loc.hasGridCell()) {
            continue;
        }
        GridCellStabilityResult r = byCell.computeIfAbsent(loc.gridCellKey(),
                k -> stabilityClassifier.classify(k, loc.getGridLat(), loc.getGridLng(),
                        lw.forecast().getHourly()));
        byLocation.put(loc.getName(), r.stability());
    }
    return byLocation;
}
```

Thread it to the best-bet rollup — **one caller**, verified: `bestBetAdvisor.advise` is called
exactly once in `src/main`, at `BriefingService.java:499`.

```java
// BriefingBestBetAdvisor.java:195 — add the 4th parameter
public BestBetResult advise(List<BriefingDay> days, Long jobRunId,
        Map<String, Integer> driveMap, Map<String, ForecastStability> stabilityByLocation)

// BriefingRollupBuilder.java:111 — add the 3rd parameter
public RollupResult buildRollupJson(List<BriefingDay> days, LocalDateTime now,
        Map<String, ForecastStability> stabilityByLocation)
```

`BriefingRollupBuilder.appendStabilityToRegion` (`:402–432`) is rewritten from a 30-line
grid-cell-key/location-name cross-match into a reduction over the region's own slots. **Move,
do not rewrite, the reduction**: `BriefingCandidateCollector.mostVolatileStability`
(`:335–353`) already implements exactly "worst case across a region's slots, UNSETTLED wins,
TRANSITIONAL beats SETTLED" and is about to be deleted. Lift it verbatim into
`BriefingRollupBuilder` as a private static, changing only the empty-map case: return `null`
(omit the field) rather than `ForecastStability.UNSETTLED` (`:339`), for the same
absence-is-not-a-finding reason as above.

`BriefingRollupBuilder.isMoreUnstable` (`:444–446`) and its comment about `ordinal()` vs
`evaluationWindowDays` stay untouched.

**Verified, and it makes this commit cheap:**

- `PromptBuilder.java:607–609` emits the block only when
  `stability != null && stability != ForecastStability.SETTLED`.
- `PromptBuilder.java:225–226` (inside `SYSTEM_PROMPT`) reads: *"When no FORECAST RELIABILITY
  block is present, or stability is SETTLED, make recommendations with full confidence."*

So an absent block needs **no system-prompt edit**. `SYSTEM_PROMPT` is not touched in any
commit of this programme. `MIN_CACHEABLE_SYSTEM_PROMPT_CHARS = 15_500`
(`PromptBuilder.java:65`) is asserted by `SystemPromptCacheabilityTest:40`/`:52`; that test
stays green because the string is unchanged.

**A finding that is not written down anywhere.** `AtmosphericData.withStability` has exactly
**one** production call site — `ForecastCommandExecutor.java:667`
(`grep -rn "withStability" src/main` returns `AtmosphericData.java:219` and that line, nothing
else). The batch pipeline has *never* emitted a FORECAST RELIABILITY block. The block is
admin-synchronous-path-only today. See D.6 open question 1.

### Commit 2 — `refactor: HorizonModelSelector replaces the four eligibility types`

Additive first. New file `service/batch/HorizonModelSelector.java`:

```java
package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.service.ModelSelectionService;

/**
 * The whole of what replaces {@code EligibilityPolicy}, {@code EligibilityDecision},
 * {@code NightlyEligibilityPolicy} and {@code IntradayEligibilityPolicy}.
 *
 * <p>{@link #withinHorizon(int)} is a <b>structural bound</b>, not a policy: T+4 and
 * beyond is outside the briefing window the slate is built for
 * ({@code BriefingService.BRIEFING_WINDOW_DAYS}, BriefingService.java:118) and a past
 * date cannot be shot. Nothing here consults stability, freshness, verdict or cost.
 *
 * <p>The near/far split is a <b>model tier</b>, not a gate: every task inside the
 * horizon is evaluated, the only question is on which model. The split point is
 * {@code ForecastTaskCollector.NEAR_TERM_MAX_DAYS = 1} (ForecastTaskCollector.java:93),
 * which is also the bucket boundary at :481, so one constant governs both.
 */
public final class HorizonModelSelector {

    /** Inclusive upper bound on the evaluable horizon, in days ahead. */
    public static final int MAX_DAYS_AHEAD = 3;

    /** Last {@code daysAhead} served by the near-term model tier. */
    public static final int NEAR_TERM_MAX_DAYS = 1;

    private final EvaluationModel nearTerm;
    private final EvaluationModel farTerm;

    /**
     * Resolves both tiers once per cycle from the active model configuration.
     *
     * @param modelSelectionService resolves the active model per {@link RunType}
     * @return a selector bound to this cycle's two tiers
     */
    public static HorizonModelSelector forCycle(ModelSelectionService modelSelectionService) {
        return new HorizonModelSelector(
                modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM),
                modelSelectionService.getActiveModel(RunType.BATCH_FAR_TERM));
    }

    private HorizonModelSelector(EvaluationModel nearTerm, EvaluationModel farTerm) {
        this.nearTerm = nearTerm;
        this.farTerm = farTerm;
    }

    /**
     * Whether a horizon is structurally evaluable.
     *
     * @param daysAhead forecast horizon, T+0 = 0
     * @return {@code true} for T+0..T+3
     */
    public static boolean withinHorizon(int daysAhead) {
        return daysAhead >= 0 && daysAhead <= MAX_DAYS_AHEAD;
    }

    /**
     * Model tier for a horizon. Callers must have checked {@link #withinHorizon}.
     *
     * @param daysAhead forecast horizon, T+0 = 0
     * @return the near-term model for T+0/T+1, the far-term model otherwise
     */
    public EvaluationModel modelFor(int daysAhead) {
        return daysAhead <= NEAR_TERM_MAX_DAYS ? nearTerm : farTerm;
    }
}
```

`RunType.BATCH_NEAR_TERM` and `RunType.BATCH_FAR_TERM` verified present at
`entity/RunType.java:51` and `:54`; `ModelSelectionService.getActiveModel(RunType)` is already
called with exactly those two at `ForecastTaskCollector.java:278–281`.

**Every call site, exhaustively:**

| Call site | Today | After |
|---|---|---|
| `ForecastTaskCollector.java:278–281` | two `getActiveModel` calls into locals | `HorizonModelSelector.forCycle(modelSelectionService)` |
| `ForecastTaskCollector.java:448–449` | `eligibilityPolicy.resolve(daysAhead, stability, near, far)` | `if (!withinHorizon(daysAhead)) { …record SKIPPED_PAST_HORIZON…; continue; }` then `EvaluationModel model = selector.modelFor(daysAhead);` |
| `ForecastTaskCollector.java:450–479` | force-eval branch | **deleted whole** |
| `ForecastTaskCollector.java:708–716` (`resolveEligibility` helper) | delegates to `NightlyEligibilityPolicy.INSTANCE` | **deleted** |
| `ForecastTaskCollector.java:803–804` (collect-missing path) | `NightlyEligibilityPolicy.INSTANCE.resolve(daysAhead, stability, model, model)` | `withinHorizon(daysAhead)` |
| `ForecastCommandExecutor.java:624` (`applyStabilityFilter`) | `NightlyEligibilityPolicy.INSTANCE.permitsHorizon(daysAhead, stability)` | `HorizonModelSelector.withinHorizon(task.daysAhead())` |
| `ScheduledBatchEvaluationService.java:173, :196, :212, :246, :338` | `EligibilityPolicy` parameter threaded through 4 signatures | parameter dropped from all four |
| `PipelineOrchestrator.java:226, :252, :273, :300, :362` | `NightlyEligibilityPolicy.INSTANCE` / `IntradayEligibilityPolicy.INSTANCE` | parameter dropped; nightly and intraday now differ **only** in their `CandidateCollectionStrategy` (the window), which is the honest remaining difference |

`ForecastCommandExecutor.applyStabilityFilter` (`:615–640`) collapses to a horizon filter and
is renamed `applyHorizonBound`. Its `GridCellStabilityService` dependency goes; the enrichment
map it also produced is rebuilt in commit 5.

Delete at the end of this commit: `EligibilityPolicy` (40), `EligibilityDecision` (66),
`NightlyEligibilityPolicy` (73), `IntradayEligibilityPolicy` (54),
`ForceEvalHeadlineSelector` (167). Also delete `ForecastTaskCollector.EligibilityAggregator`
(`:828–899`, 72 lines) — the `[BATCH ELIG]` `(daysAhead × stability)` breakdown log; with
inclusion no longer a function of stability it is a table of one column.

### Commit 3 — `refactor: drop the freshness gate; delete FreshnessResolver`

- `BriefingCandidateCollector.java:210–236` — the whole `SKIPPED_CACHED` block. `cacheKey` is
  still built (`:210–211`) if the disposition detail wants it; the `hasFreshEvaluation` call
  and the 22-line skip branch go.
- `BriefingCandidateCollector.java:135–142` (the `cachedByStability`/`eligibleByStability`
  counters and `buildStabilityLookup()` call), `:281` (`logStabilityBreakdown`), `:292–312`
  (`logStabilityBreakdown` body), `:313–333` (`buildStabilityLookup`), `:335–353`
  (`mostVolatileStability` — already **moved** in commit 1, so this is a move-completion not a
  loss). Fields `:55–56`, ctor params `:72–79`.
- `BriefingEvaluationService`: delete `hasFreshEvaluation` (`:174–192`, its only caller is the
  block above — verified, `grep -rn "hasFreshEvaluation" src/main` returns the declaration and
  `BriefingCandidateCollector:217`), `logEvaluationDeltas` (`:388–445`), `buildStabilityLookup`
  (`:447–462`), fields `:69–72`, ctor params `:112–120`.
- `ForecastTaskCollector`: `freshnessResolver` field `:103`, ctor param `:158`, assignment
  `:176`, and its pass-through into the `BriefingCandidateCollector` construction at `:194`.

Then delete `FreshnessResolver` (67), `FreshnessProperties` (51), `EvaluationDeltaLogEntity`
(70), `EvaluationDeltaLogRepository` (10).

### Commit 4 — `refactor: delete the stability snapshot`

Readers, all eight verified by `grep -rln "\bStabilitySnapshotProvider\b" src/main`:

| File | Line | Action |
|---|---|---|
| `controller/StabilityController.java` | whole file | delete (51) |
| `service/BriefingEvaluationService.java` | `:451` | already removed in commit 3 |
| `service/ForecastCommandExecutor.java` | `:114`, `:128–129` | ctor param + `GridCellStabilityService` construction removed |
| `service/batch/BriefingCandidateCollector.java` | `:314` | already removed in commit 3 |
| `service/batch/ForecastTaskCollector.java` | `:177`, `:194`, `:195–196` | field, pass-through, construction removed |
| `service/batch/GridCellStabilityService.java` | whole file | delete (238) |
| `service/evaluation/BriefingBestBetAdvisor.java` | `:92`, `:133`, `:143`, `:150` | ctor param dropped (the rollup builder now takes the map) |
| `service/evaluation/BriefingRollupBuilder.java` | `:67`, `:85`, `:92`, `:403` | ctor param dropped; `:403` already rewritten in commit 1 |

Then delete `StabilitySnapshotProvider` (204), `StabilitySnapshotEntity` (62),
`StabilitySnapshotRepository` (32), `StabilitySummaryResponse` (64),
`GridCellStabilityService` (238). Delete `ForecastStability.evaluationWindowDays()`
(`entity/ForecastStability.java:44–65`) and `GridCellStabilityResult`'s
`evaluationWindowDays` component (`model/GridCellStabilityResult.java:21`) — the only readers
were `GridCellStabilityService:186` and `StabilitySnapshotEntity`.

`ScheduledForecastService.java:26` is a Javadoc `{@link}` to `GridCellStabilityService` — it
will not compile once the class is gone. Reword to name the slate-time classifier.

### Commit 5 — `refactor: sync path classifies stability inline`

`ForecastCommandExecutor.enrichWithStability` (`:650–676`) **survives** — it is the prompt
enrichment, the display side of the rule. It needs the per-cell map that
`GridCellStabilityService.classifyGridCellsAndPublishSnapshot(batch)` used to build
(`:616–617`). Inline the memoised classify (the loop at `GridCellStabilityService:137–152`
minus the snapshot publish, ≈16 lines) as a private helper on the executor:

```java
/**
 * Classifies stability once per unique grid cell across the batch, for prompt
 * enrichment only. No snapshot, no persistence, no gate — the classification is
 * arithmetic over weather already fetched by the triage phase.
 *
 * @param batch tasks surviving triage
 * @return classification keyed by grid-cell key
 */
private Map<String, GridCellStabilityResult> classifyGridCells(
        List<ForecastPreEvalResult> batch) { … }
```

`applyHorizonBound` and `classifyGridCells` are now two independent calls at
`ForecastCommandExecutor.java:349–369` instead of one `StabilityFilterResult` record
(`:596–599`, deleted). The `triggeredManually` bypass at `:352–358` goes with the filter: a
horizon bound is structural, so a manual run is bounded by it too.

### Commit 6 — `refactor: delete the optimisation strategies`

Backend:
- `ModelsController`: field `:32`, the `optimisationStrategies` map entry `:52`, and
  `PUT /api/models/optimisation` (`:101–115`). **The endpoint is removed from the API surface**
  — update the endpoint list in `CLAUDE.md`.
- `ForecastCommandExecutor`: fields `:70–71`, ctor params `:107–108`/`:121–122`,
  `enabledStrategies`/`strategiesAudit` (`:163–168`), the `strategiesAudit` argument to
  `jobRunService.startRun` (`:173` → pass `null`), the log at `:194–196`, the
  `enabledStrategies` parameter on `executeThreePhasePipeline` (`:229`), `optimisationSkip`
  (`:260–265`), `tideAlignmentEnabled` (`:286–287` → hard `false`), the sentinel phase
  (`:316–337`), `runSentinelPhase` (`:491–~560`), `SentinelPhaseResult` (`:778–780`),
  `DEFAULT_SENTINEL_RATING_THRESHOLD` (`:62`), `sentinelSelector` (`:74`, `:110`, `:125`).
- `RunPhase.SENTINEL_SAMPLING` (`model/RunPhase.java:18`) and the state-diagram comment at
  `:7`.
- Then delete `OptimisationSkipEvaluator` (206), `OptimisationStrategyService` (241),
  `OptimisationStrategyEntity` (60), `OptimisationStrategyType` (39),
  `OptimisationStrategyRepository` (41), `OptimisationStrategyUpdateRequest` (20),
  `SentinelSelector` (58).

⚠️ **`tideAlignmentEnabled` is a behaviour change, not a no-op.** It is passed into
`ForecastService.fetchWeatherAndTriage` (`ForecastService.java:282`) and gates the SEASCAPE
tide-alignment triage at `:385–413`. That is a *gate*, so it goes under correction (2), and it
goes by construction when `TIDE_ALIGNMENT` is deleted. Flag it in the commit message —
coastal locations at a mismatched tide will now be evaluated.

Frontend (`/Users/gregochr/IdeaProjects/goldenhour/frontend`):
- `src/components/ModelSelectionView.jsx`: `STRATEGY_INFO` (`:98–139`), `CONFLICTS`
  (`:141–149`), `handleStrategyToggle` (`:215–239`), `handleParamChange` (`:241–256`),
  `enabledTypes`/`getConflictReason` (`:298–305`), the "Cost Optimisation" panel
  (`:623–706`), and the bullet at `:713`. ≈190 lines.
- `src/api/modelsApi.js`: `updateOptimisationStrategy` (`:54–75`) and the
  `optimisationStrategies` key in the JSDoc (`:4–7`). ≈24 lines.
- `src/components/JobRunsMetricsView.jsx`: the `STRATEGY_LABELS` map (`:21`), the
  `setStrategies` load (`:228–232`), the "Active optimisations" panel (`:839`), and the
  stale copy at `:883` ("same triage and stability gates as the overnight scheduled job").
  ≈30 lines.
- `src/test/ModelSelectionView.test.jsx` — strategy-toggle tests removed.
- `src/components/DispositionBreakdown.jsx:19` — **keep** the `SKIPPED_STABILITY` row. It
  renders history.

### Commit 7 — `chore: drop the gate tables`

See D.4. Two files, no code change.

### Commit 8 — `chore: delete the H2 local-dev profile`

See D.5.

### Commit 9 — `docs: local dev is Postgres`

See D.5. `CLAUDE.md`, `README.md`, `DEVOPS.md`.

---

## D.3 — Where the classifier lives now (summary of the wiring)

```
BriefingService.refreshBriefing()            [SLATE — free, weather only]
  ├─ fetchWeatherSequential(...)             → List<LocationWeather>       (:433)
  ├─ classifyStability(locationWeathers)     → Map<name, ForecastStability> (NEW, commit 1)
  │     └─ ForecastStabilityClassifier.classify(...)   ← the surviving classifier
  └─ bestBetAdvisor.advise(days, runId, driveMap, stabilityByLocation)  (:499)
        └─ BriefingRollupBuilder.buildRollupJson(days, now, stabilityByLocation)
              └─ appendStabilityToRegion(node, region, map)   ← region rollup, Claude prompt

ForecastCommandExecutor.executeThreePhasePipeline()   [sync admin path]
  ├─ classifyGridCells(batch)                → Map<cellKey, GridCellStabilityResult> (commit 5)
  └─ enrichWithStability(batch, map)         → AtmosphericData.withStability(...)  (:667)
        └─ PromptBuilder:607–630             ← FORECAST RELIABILITY block
```

Neither arrow reads a table. Neither arrow decides whether to spend a Claude call.

**Knowledge preserved in the classifier's own Javadoc** — `ForecastStability.java:6–13` and
`ForecastStabilityClassifier.java:14–20` both name the four signals ("pressure tendency,
precipitation probability, WMO weather codes, and wind gust variance"). Those comments stay.
Delete only the two paragraphs that point at the gate: `ForecastStability.java:16–22` (the
"Note for maintainers" naming `NightlyEligibilityPolicy`) and `:44–57` (the
`evaluationWindowDays` Javadoc), and the "reliable enough for extended Claude evaluation"
clause at `ForecastStabilityClassifier.java:19–20`.

---

## D.4 — Migrations

Latest in the tree: **`V138__durham_heritage_coast_locations.sql`** (verified,
`ls backend/src/main/resources/db/migration/ | sort -V | tail -1`). New files are **V139** and
**V140**.

Verified before writing them: `grep -rn "REFERENCES optimisation_strategy|REFERENCES
stability_snapshot|REFERENCES evaluation_delta_log" *.sql` → **no matches**;
`grep -rln "CREATE.*VIEW" *.sql` → **no matches**; the three tables appear in no migration
other than the ones that created them (V41/V42/V49/V52/V54, V97, V98). No FK, no view, no
dependent index. Postgres 17 only — `application-local.yml` had `flyway.enabled: false`
(`:19–20`), so **no migration in this project has ever run against H2**. Write Postgres
natively.

**`V139__drop_gate_tables.sql`**

```sql
-- The stability gate, the freshness gate and the optimisation strategies are gone.
-- These three tables existed only to serve them. Verified before writing: no foreign key,
-- no view and no other migration references any of them.
--
-- Postgres 17 is the only runtime database (backend/pom.xml declares H2 at <scope>test</scope>;
-- application-local.yml, the sole H2 runtime config, never ran Flyway at all).

-- V98. Cross-restart recovery for a gate that no longer exists. The classifier survives and
-- runs at slate-build time from weather already fetched, so nothing needs to be recovered.
DROP TABLE IF EXISTS stability_snapshot;

-- V97. Existed to refine the per-stability freshness thresholds empirically
-- (36h / 12h / 4h). There are no thresholds left to refine.
DROP TABLE IF EXISTS evaluation_delta_log;

-- V41, seeded further by V42/V49/V52/V54. The seven toggleable skip strategies and the
-- mutual-exclusion validation surface. Every one of them decided whether to spend a Claude
-- call; all of that is now unconditional within the horizon bound.
DROP TABLE IF EXISTS optimisation_strategy;

-- job_run.active_strategies (added by V41) is DELIBERATELY KEPT. It records which strategies
-- selected the rows of each historical run. Every retraction in the evidence base was a
-- statistic computed over a population selected by the thing being measured; dropping this
-- column destroys the only record of what selected those populations. Nothing writes it from
-- this release onward, so new rows are NULL — which is itself the marker for "no strategies
-- were in play".
COMMENT ON COLUMN job_run.active_strategies IS
    'Historical only. Optimisation strategies were removed in the all-in redesign; '
    'NULL from that release onward. Retained so pre-redesign runs remain interpretable.';
```

**`V140__deprecate_gate_disposition_categories.sql`**

```sql
-- forecast_run_disposition.disposition is VARCHAR(40), not a native enum (V101:26), so the
-- four categories the removed gates produced remain readable without any schema change.
-- They stay in DispositionCategory for the same reason. This migration only records the fact
-- next to the data, so a future reader of a 2026-07 row is not left guessing.
COMMENT ON COLUMN forecast_run_disposition.disposition IS
    'DispositionCategory name. SKIPPED_CACHED, SKIPPED_STABILITY, SKIPPED_NO_REFRESH_NEEDED '
    'and FORCE_EVALUATED are historical: their gates were removed in the all-in redesign and '
    'nothing produces them from that release onward. Retention is 30 days (V101), so they '
    'disappear from live data one month after the deploy.';
```

**Scheduler rows — nothing to remove.** Verified across every migration touching
`scheduler_job_config`: the seeded keys are `tide_refresh`, `daily_briefing` (removed by
V103), `aurora_poll`, `met_office_scrape` (removed by V90), `run_progress_cleanup`,
`forecast_batch*` (V73), `refresh_token_cleanup` (V81), `briefing_model_comparison` (V79),
`disposition_cleanup` (V101), `intraday_forecast_refresh` (V105),
`sky_rating_eval*` (V118/V122), `nlc_sighting_scrape` (V120),
`cloud_verification_backfill` (V131), `drive_time_refresh` (V133). **None of these is a
gate.** `intraday_forecast_refresh` survives — the intraday *cycle* stays; only
`IntradayEligibilityPolicy` (the settled-skip cost gate inside it) goes.

---

## D.5 — Config and the H2 removal

### The H2 investigation, done before proposing anything

**The test-scope H2 dependency at `backend/pom.xml:248–254` must STAY.** It is genuinely
used. `src/test/resources/application.yml:2–13` sets

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
  jpa:
    database-platform: org.hibernate.dialect.H2Dialect
    hibernate:
      ddl-auto: create-drop
  flyway:
    enabled: false
```

and **14 test classes boot a context against it**: 8 `@DataJpaTest`
(`ActualOutcomeRepositoryTest`, `CloudVerificationRepositoryTest`,
`ForecastEvaluationRepositoryTest`, `ForecastScoreRepositoryTest`, `LocationRepositoryTest`,
`PipelineRunPickRepositoryTest`, `SkyRatingEvalRepositoryTest`, `TravelDayRepositoryTest`)
plus 6 `@SpringBootTest` that do not override the datasource (`GoldenHourApplicationTests`,
`JwtAuthenticationFilterTest`, `ResilienceConfigTest`, `AbstractControllerTest`,
`DynamicSchedulerServiceIntegrationTest`, `LocationFailureTrackingTest`). The remaining two
`@SpringBootTest` classes (`IntegrationTestBase`, `ForecastBatchPipelineRealApiE2ETest`)
override with a `postgres:17-alpine` Testcontainer via `@DynamicPropertySource`
(`IntegrationTestBase.java:44–46`, `:77`).

Migrating those 14 to Testcontainers is **out of scope here and should not be bundled**:
`IntegrationTestBase.java:47–75` documents at length why the container restarts per class and
why `@DirtiesContext` is mandatory, and moving 14 fast slice tests onto that machinery would
add ~14 container restarts to every build. The correct scope for this programme is: **fix the
comment, delete the runtime H2, keep the test H2.**

`backend/pom.xml:247–250` currently reads *"H2 - used in tests (@DataJpaTest) and local dev
profile (H2 file DB). test scope makes it available for test execution and spring-boot:run
(via Maven)…"*. Rewrite to:

```xml
<!-- H2 - TEST ONLY. Backs the default `test` profile datasource
     (src/test/resources/application.yml), which serves 8 @DataJpaTest slices and 6
     @SpringBootTest classes with ddl-auto=create-drop and Flyway disabled. Integration
     tests under src/test/java/**/integration/ override it with a postgres:17-alpine
     Testcontainer so the V1..Vn migrations run against the production engine.
     There is no H2 runtime: every profile targets Postgres 17. -->
```

### Deleted

| Object | Lines | Note |
|---|---|---|
| `src/main/resources/application-local.yml` | **194** | H2 file DB at `:6`, `flyway.enabled: false` at `:19–20`, `spring.h2.console.enabled: true` at `:21–23`. Because Flyway was off and `ddl-auto: update` on, this profile never validated a single migration. |
| `backend/data/goldenhour.mv.db` | — | 81 KB, last written 28 Jul, git-ignored (`.gitignore:47`) |
| `backend/data/goldenhour.trace.db` | — | 629 KB of H2 trace log |
| `application-example.yml:175–179` | 5 | the `photocast.freshness` block |
| `SecurityConfig.java:76` | 1 | `.requestMatchers("/h2-console/**").permitAll()` |
| `SecurityConfig.java:83–84` | 2 | `// Allow H2 console frames in the local profile` + `frameOptions(fo -> fo.sameOrigin())` |

`SecurityConfig.java:76` is an unauthenticated allowlist entry in the **production** filter
chain for a console that does not exist in production. No test asserts it (`grep -rn
"h2-console" src/test frontend/src` → no matches). Removing `:83–84` restores Spring
Security's default `X-Frame-Options: DENY`; nothing in the app frames itself, but this is the
one line in the H2 removal with a blast radius outside dev, so ship it in its own commit.

### Config keys, checked in every file

`grep -n "^photocast:"` across all five resource files: present only in
`application-dev.yml:67` and `application-example.yml:134`. `application-prod.yml` has **no
`photocast:` root key at all** — every `photocast.*` value in production comes from the
`@Value`/`@ConfigurationProperties` defaults.

| Key | Where declared | Verdict |
|---|---|---|
| `photocast.freshness.settled-hours` / `.transitional-hours` / `.unsettled-hours` / `.safety-floor-hours` | `application-example.yml:175–179` only; bound by `FreshnessProperties:29/36/43/50` | **delete** |
| `photocast.batch.force-eval-cap` | **nowhere in YAML** — only the `@Value("${photocast.batch.force-eval-cap:6}")` default at `ForecastTaskCollector.java:164` and a Javadoc mention at `DispositionCategory.java:28` | **delete** (code only; no YAML edit needed) |
| `photocast.batch.min-prefetch-success-ratio` | `application-example.yml:150`; `@Value` default at `ForecastTaskCollector.java:162` | **survives** — weather-availability abort (`:313–322`), not a spend gate |
| `photocast.registration.*`, `.best-bet.*`, `.briefing.min-coverage-ratio`, `.eval.batch.*`, `.pipeline.safety-timeout`, `.forecast-score.dual-write`, `.survivor-atmosphere.write`, `.season.bluebell.*` | example / dev | untouched |

### Local dev after the deletion — this is not a documentation-only change

`application.yml` (git-ignored, the operator's own) is **55 lines** and carries no `jwt`,
`management` or `resilience4j` block. `application-local.yml` carried all three (`:108–112`,
`:178–194`, `:125–177`). So deleting the `local` profile without a replacement leaves no
bootable local configuration.

**The replacement already exists: `application-dev.yml`** (74 lines). It targets
`jdbc:postgresql://100.76.73.16:5434/goldenhour_dev` (`:6`) with `flyway.enabled: true` (`:14`),
`ddl-auto: validate` (`:12`), and carries `jwt` (`:55–58`), `turnstile` (`:59–60`), `cors`
(`:61–62`) and `app.frontend-base-url` (`:63–64`). It has no `resilience4j` or `management`
block — neither does `application.yml`, and Resilience4j falls back to library defaults; the
health endpoint config lives in `application-example.yml:263`. So the corrected instruction is:

```bash
# Backend — Postgres (dev database over Tailscale)
export ANTHROPIC_API_KEY=your-key
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Backend — Postgres on localhost:5432
#   copy application-example.yml to application.yml (it carries jwt / management /
#   resilience4j), set spring.datasource to jdbc:postgresql://localhost:5432/goldenhour,
#   then run with no profile. Flyway builds the schema from V1.
cd backend && ./mvnw spring-boot:run
```

### Docs to rewrite (all verified present)

- `CLAUDE.md:7` "H2/PostgreSQL persistence" → "PostgreSQL persistence"
- `CLAUDE.md:14` "Open-Meteo → Claude → H2" → "→ Postgres"
- `CLAUDE.md:81` `application-local.yml (H2 local dev)` line in the tree diagram → delete
- `CLAUDE.md:98–100` "# Backend (H2, no Docker)" + `profiles=local` → the dev-profile block above
- `CLAUDE.md:124` "Running the app locally does not need Docker (H2 file DB)" → **now false**: local dev needs a Postgres. Rewrite.
- `CLAUDE.md:128` the whole H2-console paragraph → delete; replace with `psql` reset instructions
- `CLAUDE.md:146` "`application-local.yml` has locations for local dev" → `application-dev.yml`
- `README.md:53` "H2 for local dev and tests" → "H2 in the test slice only"; also **"Flyway migrations (V1–V129)"** is stale — the tree is at V138 and will be at V140. Replace the range with the "read it from the tree" instruction, per the lesson already recorded in `CLAUDE.md`'s migration table.
- `README.md:93`, `:98`, `:106` — the whole "local profile / H2 console" section
- `DEVOPS.md:28–29`, `:72–76`, `:153`, `:224`, `:240` — same

---

## D.6 — The triage conflict, quantified

Correction (2) overrides §2.2/§2.3 of the summary design. **The previously published all-in
delta of +£32–48/month was computed with weather triage RETAINED and no longer holds.** Any
review that compares a post-deploy week against that number is comparing against a threshold
for a different system.

The gate being removed is `ForecastService.java:365–383`:

```java
Optional<TriageResult> triageResult = weatherTriageEvaluator.evaluate(forecastData);
if (triageResult.isPresent()) { … repository.save(entity); … return new ForecastPreEvalResult(true, …); }
```

driven by `WeatherTriageEvaluator` (`service/WeatherTriageEvaluator.java`, 65 lines), three
rules, first match wins (`:36–63`): solar-horizon low cloud `> 80 %` (`CLOUD_THRESHOLD = 80`,
`:26`, preferring `directionalCloud().solarLowCloudPercent()` and falling back to
`cloud().lowCloudPercent()`, `:38–41`), precipitation `> 2.0 mm` (`:27`, `:49–53`), visibility
`< 5000 m` (`:28`, `:57–61`).

**It is exactly measurable, from two independent sources, and both are already populated.**

Per-cycle, last 30 days (30-day retention on this table, `V101:41–44`):

```sql
SELECT jr.id,
       jr.run_type,
       jr.started_at,
       count(*) FILTER (WHERE d.disposition = 'SKIPPED_TRIAGED')  AS triaged,
       count(*) FILTER (WHERE d.disposition = 'EVALUATED')        AS evaluated,
       count(*)                                                   AS candidates,
       round(100.0 * count(*) FILTER (WHERE d.disposition = 'SKIPPED_TRIAGED')
             / nullif(count(*), 0), 1)                            AS pct_triaged
FROM   forecast_run_disposition d
JOIN   job_run jr ON jr.id = d.job_run_id
WHERE  d.created_at >= now() - interval '30 days'
GROUP  BY jr.id, jr.run_type, jr.started_at
ORDER  BY jr.started_at;
```

Long history, and split by *which rule* fired — `forecast_evaluation` keeps triage rows
forever, with `triage_reason` categorised by V96 (`V96__forecast_triage_categorisation.sql:8–12`):

```sql
SELECT date_trunc('day', forecast_run_at) AS day,
       days_ahead,
       triage_reason,
       count(*) AS rows
FROM   forecast_evaluation
WHERE  triage_reason IS NOT NULL
  AND  forecast_run_at >= now() - interval '30 days'
GROUP  BY 1, 2, 3
ORDER  BY 1, 2, 3;
```

Cost of the change, in one number:

```sql
-- Multiply `triaged` by the observed mean cost per evaluation call.
SELECT avg(cost_micro_dollars) / 1e6 AS mean_usd_per_call
FROM   api_call_log
WHERE  service = 'ANTHROPIC'
  AND  called_at >= now() - interval '30 days';
```

**Run all three before commit 1 and paste the numbers into the commit message.** Per the
evidence base the 30-day window held ~15 travel days, so divide by non-travel days before
comparing to the at-home rate — a raw month-over-month diff on this will mislead in the same
way §1 of the summary design already documents.

**The design consequence for my area.** With the early return gone, `WeatherTriageEvaluator`
still runs and still produces a `TriageResult`, but it decorates rather than diverts: the entity
gets `setTriage(new TriageDetails(...))` *and* a Claude score. That is the rule of this section
applied to the third subsystem. `TriageReason`, `TriageRule`, `TriageResult`,
`forecast_evaluation.triage_reason` / `triage_message` and the map-popover display **all
survive**. What is deleted is the `return` at `ForecastService.java:381–383` and `:409–412`,
the `triaged` boolean's use as a skip signal at `ForecastTaskCollector.java:434`, and the
`SKIPPED_TRIAGED` disposition write at `:439–444`. Sequencing and exact edits belong to the
gate-removal area; the boundary is stated here so both areas cut in the same place.

---

## D.7 — Lines removed

| Bucket | Lines | Working |
|---|---:|---|
| `src/main` files deleted whole | **1,914** | the 22-row table in D.1, summed |
| `src/main` in-place excisions | **~702** | `ForecastTaskCollector` ~170 (EligibilityAggregator `:828–899` = 72, `resolveEligibility` `:682–717` = 36, force-eval `:450–479` = 30, fields/ctor ~22, collect-missing `:781–810` ~10) · `BriefingCandidateCollector` ~110 (`:292–353` = 62, cached gate `:210–236` = 27, fields/ctor/counters ~21) · `ForecastCommandExecutor` ~177 (`applyStabilityFilter` `:590–640` = 51 net −16 for the new `classifyGridCells`, sentinel `:316–337` = 22 + `runSentinelPhase` ~70 + record 8, optimisation wiring ~30, `StabilityFilterResult` 4) · `BriefingEvaluationService` ~107 (`:388–445` = 58, `:447–462` = 16, `:174–192` = 19, fields/ctor 14) · `BriefingRollupBuilder` ~31 net (`:398–432` = 35 replaced by ~12, provider wiring 8) · `BriefingBestBetAdvisor` 8 · `ModelsController` ~25 · `ScheduledBatchEvaluationService` + `PipelineOrchestrator` ~30 · `ForecastStability`/`GridCellStabilityResult` 25 · `RunPhase` 3 · `SecurityConfig` 3 · `ScheduledForecastService` javadoc 1 |
| Config (`src/main/resources`) | **199** | `application-local.yml` 194 + `application-example.yml:175–179` 5 |
| Frontend production | **~244** | `ModelSelectionView.jsx` ~190 · `modelsApi.js` ~24 · `JobRunsMetricsView.jsx` ~30 |
| **Production total** | **≈ 3,059** | |
| Tests deleted whole | 2,700 | 14 files, listed in D.1 |
| Tests trimmed in place | ~500 | 12 files, listed in D.1 |
| **Grand total** | **≈ 6,259** | |

Added back: `HorizonModelSelector` ~60 · `BriefingService.classifyStability` ~25 ·
`ForecastCommandExecutor.classifyGridCells` ~20 · `BriefingRollupBuilder` rewritten
`appendStabilityToRegion` + moved `mostVolatileStability` ~30 · two migrations ~40. **≈175
lines added.** Net production removal **≈ 2,884**.

JaCoCo's 80 %-per-class rule applies to `HorizonModelSelector`: `withinHorizon` has three
branches (`< 0`, in range, `> MAX_DAYS_AHEAD`) and `modelFor` two. `HorizonModelSelectorTest`
must assert `withinHorizon(-1)` false, `withinHorizon(0..3)` true, `withinHorizon(4)` false,
and `modelFor(0)`/`modelFor(1)` = near, `modelFor(2)`/`modelFor(3)` = far — cover the guards,
do not delete them.

---

## D.8 — New and renamed tests

| Test | Asserts |
|---|---|
| `HorizonModelSelectorTest` (new) | the six cases above; that `NEAR_TERM_MAX_DAYS` equals the bucket boundary read by `ForecastTaskCollector` |
| `BriefingServiceStabilityClassificationTest` (new) | `classifyStability` classifies once per grid cell for co-located locations; **omits** a location whose `forecast()` is null or which has no grid cell (no TRANSITIONAL default) |
| `BriefingRollupBuilderStabilityTest` (new) | worst-case reduction over a region's slots; the `stability` field is **absent** from the region node when the map has no entry for any of its slots |
| `SystemPromptCacheabilityTest` (existing, `:40`/`:52`) | unchanged and must stay green — the guard that `SYSTEM_PROMPT` was not trimmed |
| `PromptBuilderTest` (existing) | add: a task with `stability == null` emits **no** `FORECAST RELIABILITY` substring |
| `DispositionCategoryTest` (existing, `:61–62`) | extend to assert `fromString("SKIPPED_STABILITY")`, `"SKIPPED_CACHED"` and `"FORCE_EVALUATED"` still resolve — pins the "historical categories stay in the enum" decision |
| `SecurityConfigTest` (new or existing) | `/h2-console/**` is **not** permitted anonymously |

Local verification ladder, per `CLAUDE.md`, gating on exit code and never on grepped output:

```bash
cd backend && ./mvnw compile -q >/tmp/c.log 2>&1; echo "exit: $?"
cd backend && ./mvnw checkstyle:check -q >/tmp/cs.log 2>&1; echo "exit: $?"
cd backend && ./mvnw clean verify --batch-mode --no-transfer-progress \
  -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false >/tmp/v.log 2>&1; echo "exit: $?"
```

Commit 7 (the migrations) is the one that **requires Docker** — the `IntegrationTestBase`
Testcontainer is the only place V139/V140 execute before production.