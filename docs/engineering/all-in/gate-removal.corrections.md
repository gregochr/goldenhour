## Verification report — §G "Gate removal and the collapse of candidate collection"

**Verdict: the load-bearing architectural argument survives; the implementation detail does not.**

The one thing that would have been fatal — deleting the Plan tab's GO/MARGINAL/STANDDOWN display path by mistake — is **not** present. §G.1.1 draws the boundary correctly at every cited line, and I confirmed all nine `BriefingSlotBuilder` citations and all six claimed surviving verdict readers character-for-character. Five of the six risky claims the author flagged are confirmed, including the one they marked UNVERIFIED.

But the section cannot be implemented as written. Two findings are build-stopping and one invalidates the baseline.

---

### Fix before implementation

**1. `FreshnessResolver` and `FreshnessProperties` must NOT be deleted.**

`BriefingEvaluationService.logEvaluationDeltas` calls `freshnessResolver.maxAgeFor(stability)`:

- `BriefingEvaluationService.java:419` → `delta.setThresholdUsedHours(...)` at `:433`
- Javadoc at `:389-391`: *"Logs rating deltas to `evaluation_delta_log` for empirical freshness threshold refinement"*

This is a **measurement** surface (V97), not a gate. Under "classify for display; never gate on the classification", the TTLs stop gating (`BriefingCandidateCollector.java:214-236` goes) but `FreshnessResolver` survives to keep stamping the threshold that *would* have applied next to the observed delta — which is exactly how you retire the threshold empirically instead of by assertion.

Revised: **keep both classes, delete only the gate.** −118 lines from §G.11. `EvaluationDeltaLogTest` and `BriefingEvaluationServiceTest` stay green untouched; only `BriefingEvaluationServiceCacheFreshnessTest` (135 lines, `hasFreshEvaluation`) is deleted.

**2. `ForceSubmitBatchService` calls `fetchWeatherAndTriage` twice.**

- `ForceSubmitBatchService.java:108-110` (JFDI batch)
- `ForceSubmitBatchService.java:178-180` (force batch)

Both pass `tideAlignmentEnabled = false, jobRun = null` and both **ignore `preEval.triaged()`** — they use `preEval.atmosphericData()` and submit regardless. So they are behaviourally unaffected by triage removal, but the rename and the parameter drop break them at compile time. Add both to §G.5.2's call-site list (making it **nine**, not seven), and add `ForceSubmitBatchServiceTest` to §G.9.

Note the incidental discovery: today a JFDI run on a heavy-cloud slot writes a triaged `forecast_evaluation` row *and* submits a batch evaluation for the same slot. That double-write disappears with triage, which is a small argument in the change's favour.

**3. Re-derive every `ForecastTaskCollector` citation against `main`.**

The design was read at `a484d1c4`, which is **not an ancestor of `main`** (`git merge-base --is-ancestor` → false). `ForecastTaskCollector` is **899** lines on main, not 883: a 16-line TIDE-LESS COASTAL diagnostic was added at `:537-552`. Everything past line 534 shifts **+16**:

| §G says | Actually on `main` |
|---|---|
| `resolveEligibility` :665–701 | **:692–716** (signature at :708) |
| `EligibilityAggregator` :816–882 | **:832–898** |
| region-filtered triage :778–780 | **:794–796** |
| region-filtered eligibility :784–791 | **:800–807** |
| `SKIPPED_ERROR` catch :576–585 | **:592–601** |
| `includeDisposition` forced plumbing :655–663 | **:672–679** |
| `fetchWeatherAndTriage` call :774 | **:790** |

Citations below :534 are correct, as are all `BriefingCandidateCollector`, `ForecastCommandExecutor`, `ForecastService`, `BriefingSlotBuilder`, `BriefingGlossService` and `DispositionCategory` citations (those files did not move). Two small corrections outside the shift: the executor's `fetchWeatherAndTriage` call is `:473` not `:474`, and its ctor assignments are `:121-122` not `:120-121`.

---

### §G.8 — the cost number needs rebuilding

**The V101 example does not add up.** `V101__forecast_run_disposition.sql:6-8` says *"242 candidates → 163 evaluated · 48 hard-constraint · 41 triaged · 2 cached · 1 past-date"*. Those five sum to **255**. §G.8.2 divides by 242 to get 16.9% / 19.8% / 67.4% and ×1.48. Against 255 the shares are **16.1% / 18.8% / 63.9%**. The fixture that mirrors it (`JobMetricsControllerDispositionTest.java:35-39`) carries only the five counts and no 242 — the 242 exists nowhere but that prose line.

The section already labels the figure UNVERIFIED and then uses it anyway. Delete the multiplier entirely; state only that the exposure is unmeasured and that the queries below are the deliverable.

**Query (4) measures the wrong thing.** `evaluation_date` is the forecast **target** date, not the cycle date; over 30 days of overlapping 4–5-date windows it counts ~34 target dates and says nothing about how many cycles ran. Cost is per cycle-day. Replace with:

```sql
-- (4) Cycle-day denominator. Spend is incurred per CYCLE, not per target date.
SELECT count(DISTINCT j.started_at::date) AS cycle_days
FROM job_run j
WHERE j.started_at >= now() - interval '30 days'
  AND j.run_type LIKE 'BATCH%';

-- (4b) Of those, how many carried at least one non-travel candidate.
SELECT count(DISTINCT j.started_at::date) AS at_home_cycle_days
FROM forecast_run_disposition d
JOIN job_run j ON j.id = d.job_run_id
WHERE d.created_at >= now() - interval '30 days'
  AND d.disposition <> 'SKIPPED_TRAVEL_DAY';
```

Queries (1)–(3) are sound. I specifically checked the one that could have been off by ~100×: `JobRunService.logBatchResult` (`:219-262`) writes **one `api_call_log` row per `custom_id`** with `isBatch(true)`, so `avg(cost_micro_dollars)` in query (3) really is the per-slot unit price.

---

### §G.9 — three more test classes break

Add to the amended list:

| Class | Why |
|---|---|
| `controller/AbstractControllerTest.java:35, :161` | declares `protected OptimisationStrategyService optimisationStrategyService;` — the base class of **every** controller test |
| `service/batch/ForceSubmitBatchServiceTest.java` | constructs `ForecastPreEvalResult` |
| `service/batch/GridCellStabilityServiceTest.java` | constructs `ForecastPreEvalResult` |
| `frontend/src/test/ModelSelectionView.test.jsx` | optimisation UI |
| `frontend/src/test/JobRunsMetricsViewBatch.test.jsx` | `activeStrategies` column |

**`src/test/java/**/regression/` does not exist.** `find src/test/java -type d -name regression` returns nothing. The real files are `service/evaluation/{PromptRegressionTest,BestBetAuroraPromptRegressionTest,PromptGoldenMasterTest,CachePayloadGoldenMasterTest,BluebellPromptGoldenMasterTest,PromptBuilderCoptHillSimulationTest}.java` plus the `eval/` package. I checked them: **none** references `ForecastStability` or `withStability`, so the manual-run reliability-block change does not break any golden master. But the assurance was given against a path that cannot be violated — restate it against the real files.

Also: the `TravelDayGate` nested class in `CollectForecastTasksCachedGateTest` has **two** tests, `travelDaySkipsWholeDay` (:291–311) and `nonTravelDayPassesThrough` (:313+). Move both.

---

### §G.9 — the real JaCoCo exposure

The rule is 80% **LINE per CLASS** (`pom.xml:505-514`). `ScheduledBatchEvaluationService` and `ForceSubmitBatchService` are excluded (`pom.xml:498-500`); **`BriefingCandidateCollector` and `ForecastTaskCollector` are not.**

- `BriefingCandidateCollector` (~110 lines after surgery) is exercised almost entirely by `CollectForecastTasksCachedGateTest` (350 lines), which §G.9 deletes while moving one test out. A ~110-line class cannot hold 80% on one test.
- `ForecastTaskCollector` (~640 lines) loses `ForecastTaskCollectorForceEvalTest` (350), `ForecastTaskCollectorEligibilityPolicyTest` (117), the cached-gate file, and ~10 tests from its own suite.

Budget a real `BriefingCandidateCollectorTest` — travel day (both cases), past date, unknown location, `region.slots() == null`, the cycle-window filter recording no disposition, and the hoisted-lookup single-call assertion. Do not delete the guards to hit the number.

---

### Missed: things the deletion bias says should also go

**The `triaged` display surface, ~40 lines.** §G.5.2 renames `fetchWeatherAndTriage` because *"a lie in a method name is how the mechanism gets reinvented"* — then leaves:

- `RunPhase.TRIAGE` set at `ForecastCommandExecutor.java:298`, default at `RunProgress.java:31`
- `runTriagePhase` at `ForecastCommandExecutor.java:468`
- `LocationTaskState.TRIAGED` consumed at `RunProgress.java:136, :169, :195`
- `RunProgress.getTriaged()` serialised as `"triaged"` at `RunProgressTracker.java:258, :281`

After this section `getTriaged()` can only return 0 and `LocationTaskState.TRIAGED` is unreachable. Rename the phase (`WEATHER_ASSEMBLY`) and drop the counter and the state.

**`PipelinePhase.STABILITY_RECLASSIFY`.** §G.7 retypes `ReclassSummary` → `CollectionSummary` on the grounds the hook still separates two phases (`PipelineOrchestrator.java:365, :373-377`). But `ReclassSummary.detail()` (`:49-52`) renders *"N considered, N settled-skipped, N unsettled-evaluated"* into a **user-visible** phase detail column, and with every skip gone `considered == evaluated` by construction. Same argument, same fix: rename the phase, and book the replacement record as a rename-plus-test, not a 53-line deletion.

**`DispositionBreakdown.jsx` needs a value ADDED, not only seven removed.** `CATEGORY_DISPLAY` at `frontend/src/components/DispositionBreakdown.jsx:12-23` has ten keys and **`SKIPPED_TRAVEL_DAY` is not one of them** (nor is `FORCE_EVALUATED`); it falls through the generic unknown-category path. §G.12 item 5 says the filter "shrinks to five values" — in fact travel day, the *only* surviving policy gate and the whole subject of §G.7's selection-effect argument, currently has no label in the Ops UI. Fix it in the same commit.

---

### Smaller corrections

- **`BriefingGatingPolicy` has four other references, not two**: `ForceEvalHeadlineSelector.java:10, :110` (self-resolving) and `BriefingHonestyFilter.java:19` (a same-package `{@link}` that dangles silently).
- **`WeatherTriageEvaluator` has two `{@link}` references, not one**: `Verdict.java:6` (cited) and `BriefingVerdictEvaluator.java:19` (*"All thresholds mirror those used by {@link WeatherTriageEvaluator}"*) — which is the very sentence §G.1.2 relies on to argue the knowledge survives. Rewrite it to state the numbers rather than link a deleted class.
- **The stability-apparatus deletion in `BriefingCandidateCollector` is one method short**: `mostVolatileStability` at `:335-353` (called only from the deleted `:212-213`). §G.3.1 cites `:292-333`.
- **`ScheduledBatchEvaluationService`**: "four signatures" but five sites — `:173, :196, :212, :246, :338`.
- **`JobRunDto`**: `activeStrategies` at `:29` and `:48` only; `:75` does not reference it.
- **No migration number.** Latest on the tree is `V138__durham_heritage_coast_locations.sql`, so the drop file is **V139**. `optimisation_strategy` is created by V41 and INSERTed by V42/V49/V52/V54 with **no inbound FK**, so plain `DROP TABLE optimisation_strategy;` and `ALTER TABLE job_run DROP COLUMN active_strategies;` are valid PG17 — write them out.
- **No intra-§G deletion order.** At minimum: (a) inline the model tier + drop the `EligibilityPolicy` params, (b) delete force-eval, (c) delete `BriefingGatingPolicy` + the four eligibility types, (d) collapse `ForecastPreEvalResult`, (e) delete `fetchWeatherAndTriage`'s triage blocks — with the §4.4 `evaluation_event` dual-write landing before (e).
- **§G.11 arithmetic**: 1,583 is exact against the tree, but −67 −51 (freshness, kept) −53 (ReclassSummary, renamed) = **~1,412** whole-file; section total ~2,080, not 2,250.

---

### Refinements to two confirmed claims

**Manual runs (§G.1.3).** The chain is fully verified, including the step marked UNVERIFIED: `PromptBuilder.java:608-610` emits the block only when `data.stability() != null && stability != ForecastStability.SETTLED`. So manual runs gain the block **only for TRANSITIONAL/UNSETTLED cells** — SETTLED cells never had one and still will not. Say that in the commit message; "manual runs gain a reliability block" over-claims.

**The horizon (§G.4.4).** The claim survives and is stronger than argued. `IntradayEligibilityPolicy.java:47-52` switches on stability **only** and ignores `daysAhead` entirely; intraday's horizon comes from `IntradayCandidateCollectionStrategy.includes` (today-SUNSET, T+1 SUNRISE/SUNSET), which §G keeps. So `NightlyEligibilityPolicy.java:54` genuinely is the only horizon stop being removed, and `BRIEFING_WINDOW_DAYS` genuinely becomes the sole control. The recommendation of 4 stands. The model-tier inline is also safe on both paths: `NEAR_TERM_MAX_DAYS = 1` (`ForecastTaskCollector.java:93`) reproduces nightly's table bit-for-bit, and intraday only ever sees `daysAhead ∈ {0,1}`, so it always lands on `nearTermModel` — matching `IntradayEligibilityPolicy:51`.

---

### Understated: a user-visible view loses a rung

`EvaluationViewService.java:483-495` renders a drill-down entry from a triaged `forecast_evaluation` row with a STANDDOWN `DisplayVerdict` and a concrete reason string; `:294-302` and `:394-400` project those rows into `BriefingEvaluationResult`. §G.5.4 point 3 correctly notes nothing will write them, but frames it as dead types. The user-facing consequence is that a heavy-cloud slot that today reads *"STANDDOWN — sun blocked"* will read a Claude rating instead, and that the `Source.FORECAST_EVALUATION_*` fallback disappears entirely from the batch path if a `cached_evaluation` write fails. That deserves the same ⚠️ treatment §G.1.1 gives the gloss median.