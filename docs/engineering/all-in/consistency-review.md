# Cross-area consistency review — the all-in redesign

Verified against the tree at **`d421ef5f`** (`git log --oneline -1`). Note: three of the six areas state they read the tree at `a484d1c4`, which is **not an ancestor of `main`** — it is on the abandoned `fix/release-ahead-guard` branch. Line citations in those areas past `ForecastTaskCollector.java:534` are systematically off by +16. Every citation below was re-read at `d421ef5f`.

---

## A. Hard contradictions — two areas, two different answers

### A1 [FATAL] Three areas all claim migration **V139**

`ls backend/src/main/resources/db/migration/ | sort -V | tail -1` → `V138__durham_heritage_coast_locations.sql`.

| Area | Claims |
|---|---|
| 1 (circularity) | `V139__briefing_slate.sql` (after its own V137 refutation) |
| 3 (subsystem deletion) | `V139__drop_gate_tables.sql`, `V140__deprecate_gate_disposition_categories.sql` |
| 4 (spine) | `V139` (evaluation_event), `V140` (backfill), `V141` (cloud_verification remap) |
| 5 | no number stated |
| 6 | no number stated; needs a `PipelinePhase` rename migration it does not write |

Two scripts with the same version number is a **Flyway boot failure in production**, not a lint. There is no global migration ledger and no area owns one.

**Resolution — assign now, in phase order:**

| # | File | Owner |
|---|---|---|
| V139 | `evaluation_event` + projections | Area 4 (Phase 0, first) |
| V140 | backfill `forecast_evaluation` → `evaluation_event` | Area 4 |
| V141 | `cloud_verification` FK remap | Area 4 |
| V142 | `briefing_slate` | Area 1 |
| V143 | `pipeline_phase` value rename (see A5) | Area 6 |
| V144 | drop `stability_snapshot`, `evaluation_delta_log`, `optimisation_strategy` | Area 3 |
| V145 | `forecast_run_disposition` disposition (see A4) | Area 2/4 jointly |

### A2 [FATAL] `BriefingGatingPolicy` — Area 2 deletes it; three live consumers need it

Verified consumers (`grep -rn BriefingGatingPolicy src/main --include="*.java"`):

- **GATE** — `BriefingCandidateCollector.java:239-240` (`isEligibleForEvaluation` / `isHardConstraintSkip`). Deleted by correction (2).
- **GATE** — `ForceEvalHeadlineSelector.java:110`. Self-resolving (that class dies).
- **DISPLAY/PUBLISH** — `BriefingGlossService.java:203` `hasAnyEligibleSlot(region)` decides whether a region gets a gloss Claude call at all; `:268` `.filter(BriefingGatingPolicy::isEligibleForEvaluation)` shapes the gloss user message.
- **Javadoc** — `BriefingHonestyFilter.java:19` (same package, no import — dangles silently).

Area 2's file list deletes `BriefingGatingPolicy.java` (130 lines). Areas 1 and 6 independently flagged this. **It compiles-breaks `BriefingGlossService` and, if force-fixed by deleting the guard, every all-hard-constrained region silently loses its Plan-tab headline and detail prose.**

**Resolution:** `BriefingGatingPolicy` **SURVIVES** as a publish-side display helper, with `BriefingGatingPolicyTest`. Only the two call sites in `BriefingCandidateCollector` go. Add a class-Javadoc line saying so, or it gets deleted again in six months.

### A3 [FATAL] `StabilitySnapshotProvider` — Areas 3, 5 and 6 all delete it; it is a display read surface

`grep -rn "stabilitySnapshotProvider" src/main` returns **nine production call sites across eight classes**:

| Site | Kind |
|---|---|
| `BriefingRollupBuilder.java:403` `getLatestStabilitySummary()` | **DISPLAY** — region rollup → `BriefingRegion` → Plan tab |
| `BriefingBestBetAdvisor.java:150` | **PUBLISH** — best-bet prompt input |
| `StabilityController.java:45` | ADMIN endpoint |
| `BriefingEvaluationService.java:451` | delta-log write |
| `ForecastTaskCollector.java:177,194,196` | gate wiring |
| `BriefingCandidateCollector.java:314` | gate wiring |
| `GridCellStabilityService.java:203` `update(summary)` | producer |
| `ForecastCommandExecutor.java:129` | producer wiring |

Area 3 deletes it, Area 5 deletes it, Area 6 deletes it in **Phase 2a — three phases before the UI that consumes it is removed in Phase 5**. The design doc itself (§4.2, lines 223-228) says in one breath "`BriefingBestBetAdvisor` and `BriefingRollupBuilder` read `StabilitySnapshotProvider` to surface … stability on the Plan tab" and then "Delete … the provider". **The source document contains the contradiction; three areas inherited it.**

**Resolution:** the provider is the *only* thing that carries stability from the classifier to the Plan tab. Either (a) it survives, backed by an in-memory `AtomicReference` with the DB half deleted, or (b) the slate carries per-region stability and `BriefingRollupBuilder`/`BriefingBestBetAdvisor` read it from there — which is Area 3's commit 1, and which **must land before** any area deletes the provider. Pick (b); it is consistent with SLATE→PUBLISH. Assign it to Area 1 (slate owner), not Area 3.

### A4 [FATAL] `DispositionCategory` has four different fates

| Area | Fate |
|---|---|
| 1 | keeps it; adds nothing |
| 2 | **deletes the enum** (files list), leaving five values |
| 3 | `V140__deprecate_gate_disposition_categories.sql` — deprecates rows |
| 4 | **absorbs it** as `evaluation_event.skip_category`, deletes `forecast_run_disposition`, `ForecastDispositionService`, `CandidateDisposition`, `DispositionBreakdownResponse` |
| 5 | keeps a **trimmed** enum + a new `DispositionLine.jsx` reading `/api/metrics/disposition-breakdown` |

Area 4 deletes the endpoint Area 5 is building a component against. Area 2 deletes the enum Area 4's column stores. The current enum has **12 constants** (`DispositionCategory.java:22,33,40,57,59,65,71,79,86,93,100,107`).

**Resolution:** §2.7 of the design is explicit — *the disposition record merges into the outcomes table*. Area 4 wins. Therefore:
- `DispositionCategory` **survives**, reduced to `EVALUATED`, `SKIPPED_TRAVEL_DAY`, `SKIPPED_PAST_DATE`, `SKIPPED_UNKNOWN_LOCATION`, `FAILED`. That is 5 values; Area 2's "shrinks to five" is right, its deletion of the file is wrong.
- `forecast_run_disposition`, `ForecastDispositionService` (158), `ForecastDispositionCleanupService` (44), `ForecastRunDispositionEntity` (97), `DispositionBreakdownResponse` (60), `CandidateDisposition` (37) all go — **but only in Area 4's Phase 4**, and Area 5's `DispositionLine.jsx` must be built against a **new** endpoint over `evaluation_event`, not the old one. Nobody has designed that endpoint. See I2.

### A5 [FATAL] `PipelinePhase` — two areas rewrite the same enum, differently, neither with a migration

- Area 1: adds `SLATE`, renames `BRIEFING`→`PUBLISH`, possibly drops `STABILITY_RECLASSIFY`.
- Area 6: renames `FORECAST_BATCH_SUBMIT`→`EVALUATE_SUBMIT`, `FORECAST_BATCH_WAIT`→`EVALUATE_WAIT`, `RETRY_FAILED`→`EVALUATE_RETRY`, `BRIEFING`→`PUBLISH`, drops `STABILITY_RECLASSIFY`.

Both columns are `@Enumerated(EnumType.STRING)`. Historical `pipeline_run.current_phase` and `pipeline_run_phase.phase` rows hold the old names; after either rename, reading any pre-change row throws `IllegalArgumentException: No enum constant`. **Neither area writes the `UPDATE` migration.** And `PipelineOrchestrator.java:333-334` names `STABILITY_RECLASSIFY` in the restart path, so dropping it is also a compile break in a file both areas edit.

**Resolution:** one enum, one owner (Area 6), one migration (V143) doing `UPDATE pipeline_run SET current_phase = ...` and the same for `pipeline_run_phase`, plus a `CHECK` if you want it pinned. Area 1's `SLATE` value is added in the same migration.

### A6 [MAJOR] The 50% weather-coverage threshold — Area 1 makes it a spend gate, Area 6 says it never was one

`BriefingService.java:508` computes `aboveThreshold`; the branch is at `:537` / `:550-561`. **Below threshold the briefing still publishes** — only `persistBriefing()` is skipped and a last-known-good stale copy is served.

- **Area 1** promotes it into `runCycle`: `if (!slate.aboveThreshold()) { failRun(); return; }` **before** `submitPhase`. A 49%-coverage cycle now submits zero batches and produces zero evaluations.
- **Area 6** correctly reads the current behaviour and objects.

Area 1 has, in a change whose whole mandate is *remove policy gates on Claude spend*, **created a new all-or-nothing one on a path that today degrades gracefully**. It is also the classify-for-display rule inverted: coverage is a fact to annotate the publication with, not a decision about whether to spend.

**Resolution:** `aboveThreshold` annotates the published briefing (as today). It never gates SELECT or EVALUATE. Area 1's `failRun` branch is deleted from the design.

### A7 [MAJOR] `BRIEFING_WINDOW_DAYS` — three areas, three values

`BriefingService.java:118` → **`= 5`** (verified; `:410` `IntStream.range(0, BRIEFING_WINDOW_DAYS)`).

- Area 1: change to 4, on the argument that `dates[4]` is never rendered.
- Area 2: open question, "4 or 5?"
- Area 3: asserts it is **4** and builds its "T+3 is a structural bound" argument on that — the assertion is false, and with it the claim that the T+3 ceiling is structural rather than a surviving policy gate.
- Area 6: Phase 1 costing assumes the 5→4 change lands.

**Resolution:** one owner (Area 1), one commit, one number. And Area 3's `HorizonModelSelector.MAX_DAYS_AHEAD = 3` must be re-justified against **Open-Meteo's actual forecast extent**, which no area measured, not against the briefing window.

### A8 [MAJOR] `FreshnessResolver` — deleted by three areas; has a non-gate consumer

`FreshnessResolver.java` (67) + `FreshnessProperties.java` (51) are on the delete list of Areas 2, 3 and 5. But `BriefingEvaluationService.java:71,114,419` calls `freshnessResolver.maxAgeFor(stability)` and writes the result into `evaluation_delta_log.threshold_used_hours` — the V97 empirical-threshold dataset, which is a *measurement instrument*, not a gate.

Meanwhile **Area 3 and Area 4 both delete `EvaluationDeltaLogEntity`/`Repository`**, and **Area 4 deletes `BriefingEvaluationService` entirely** (665 lines).

**Resolution:** the ordering is forced and nobody stated it. `BriefingEvaluationService` (Area 4, Phase 4) must go **before or with** `FreshnessResolver` (Area 3). If Area 3 ships first, the build is red. Book the delta-log deletion once, in Area 4, and delete `FreshnessResolver` in the same commit.

### A9 [MAJOR] The H2 test slice — three incompatible assumptions

`src/test/resources/application.yml:1-13`: `jdbc:h2:mem:testdb`, `ddl-auto: create-drop`, `flyway.enabled: false`.

- **Area 3** deletes the H2 dependency from `pom.xml` and `application-local.yml` under correction (1).
- **Area 4** assumes the H2 slice survives, and states that all its projection tests must therefore be Testcontainers-based *because* the H2 slice cannot see Flyway-built views.
- **Area 1** has an open question about whether `@JdbcTypeCode(SqlTypes.JSON)` works under H2 with `ddl-auto: create-drop`.

Correction (1) authorises deleting H2 from **runtime** profiles. It says nothing about the **test slice**, and deleting that is a different, much larger change: it converts every unit test into a Testcontainers test and makes CLAUDE.md's CI-reproduction command (`-Dtest='!**/integration/**'`) meaningless.

**Resolution:** split the two. Delete `application-local.yml`, the H2 console config and `backend/data/*.db` (Area 3). **Keep the test-scope H2 dependency and `src/test/resources/application.yml`** — and record in CLAUDE.md that any test touching a Flyway-created view, `DISTINCT ON`, `JSONB`, a partial index or a `CHECK` constraint must extend `IntegrationTestBase`. Area 1's JSONB open question then dissolves: the slate entity is only exercised in an IT.

---

## B. Unowned seams — falls between two areas, owned by neither

### B1 `ephemeral` / `STABILITY_RECLASSIFY` / `ReclassSummary`

Area 1 raises it as an open question. Area 2 lists it under "missed". Area 3 lists it under "missed". Area 6 deletes it and asserts it is a no-op. **Nobody owns it.**

`ForecastTaskCollector.java:341` passes `ephemeral` to `classifyGridCellsAndPublishSnapshot`; the flag suppresses the intraday write-through so the 14:00 cycle does not overwrite the 01:00 snapshot that `BriefingRollupBuilder.java:403` and `BriefingBestBetAdvisor.java:150` read all day. **Deleting the flag is a display-behaviour change, asserted by Area 6 as a no-op.** `ReclassSummary.detail()` renders "N considered, N settled-skipped, N unsettled-evaluated" into the user-visible Pipeline Runs phase detail; with all skip categories gone, `considered == evaluated` definitionally.

**Owner:** Area 6 (pipeline phases), but the display consequence belongs to A3's resolution — once stability rides the slate, the snapshot table and the flag both go cleanly.

### B2 The two batch submit paths that write no dispositions

`persistCycleDispositions` is called at exactly one place, `ScheduledBatchEvaluationService.submitBuckets` (`:360`, persist near `:429`). Two other paths reach `evaluationService.submit(...)` without it:

- `doSubmitForecastBatchForRegions` (`ScheduledBatchEvaluationService.java:528-550`), collecting via `ForecastTaskCollector.collectRegionFilteredBatches` (`:732`, briefing read at `:733`).
- `ForceSubmitBatchService` (340 lines), calling `fetchWeatherAndTriage` at `:108-110` and `:178-180`.

Both produce terminal `EVALUATED` events through the shared `ForecastResultHandler`. **Under Area 4's spine they add numerator rows whose denominator was never recorded** — precisely the selection defect §2.7 exists to eliminate, and Area 4's reconciliation invariant (submitted = terminal + outstanding) can never balance.

Area 1 flags that `collectRegionFilteredBatches` has no slate to read. Area 2 asks whether it earns its place. Area 4 records neither path. **Owner: Area 4**, and the deletion-biased answer is to delete `collectRegionFilteredBatches` (93 lines) + `RegionFilteredBatchTasks` and route the admin region filter through the same collector as everything else.

### B3 `BriefingHonestyFilter` (198 lines) — its remaining purpose is being deleted

Its Javadoc (`BriefingHonestyFilter.java:19-45`) enumerates the three residual zero-coverage causes after the Gate 2 redesign. The second is verbatim: *"Regions whose every slot is hard-constrained (e.g. all-tide-mismatched coastal regions) … the hard-constraint slots never reach Claude."*

Under no-gates, hard-constrained slots **do** reach Claude and get scored. Combined with triage removal, per-region coverage rises to ~100%, and both the zero-coverage rewrite and the `lightlyEvaluated` tier become near-dead code. Area 2 touches the file for a copy edit; Area 1 mentions `.stale()` pass-through at `:119`. **Nobody re-derives whether the filter still earns 198 lines.**

**Owner:** Area 2 (it owns the gate that made the filter necessary). Under maximum deletion the honest answer is: keep only the batch-failure branch, delete the hard-constraint branch and its tests, and rewrite the Javadoc — or delete the class and let coverage ride the confidence channel Area 5 already owns.

### B4 The `/api/forecast/run/{runId}/progress` and `/run/notifications` SSE pair

`ForecastController.java:471` and `:483`, consumed by `frontend/src/api/runProgressApi.js:17,53`. Area 6 deletes the four run endpoints in Phase 3 but says nothing about these two. Area 2 leaves `RunPhase.TRIAGE` (`RunPhase.java:15`), `RunProgress.phase = TRIAGE` (`:31`) and `getTriaged()` (`:136`) in place, permanently reading zero. **Owner: Area 6.** Either both SSE endpoints go with the engine, or they stream nothing forever.

### B5 Where `FORECAST RELIABILITY` comes from after the sync engine dies

`grep -rn withStability src` → **one production call site: `ForecastCommandExecutor.java:667`** (declaration at `AtmosphericData.java:219`). The batch path has never emitted the block.

- **Area 3** keeps `ForecastStabilityClassifier` (214 lines) *specifically because* `PromptBuilder.java:608-625` emits the block — and files "should the batch path start emitting it?" as an **open question**.
- **Area 6** deletes `ForecastCommandExecutor` (826 lines) in Phase 3.

If Area 6 lands, the block has **no producer at all** and Area 3's justification for keeping the classifier evaporates. The design doc §4.2 makes the same argument and inherits the same hole.

**Resolution:** Area 3's open question is not optional — it is a **precondition** of Area 6 Phase 3. Either the batch path emits the block (cost: zero API calls; the classification is arithmetic over weather already fetched at `ForecastTaskCollector.java:302-303`) or the classifier and the block are both deleted and `SYSTEM_PROMPT` is left untouched (`PromptBuilder.java:225` already branches on absence). Decide before Phase 1.

---

## C. The same decision made two different ways

| Decision | Area A | Area B | Correct |
|---|---|---|---|
| `RunType.BRIEFING` | Area 1 splits into `BRIEFING` + `BRIEFING_PUBLISH` | Areas 3 & 6 write cost queries `WHERE run_type = 'BRIEFING'` | **Do not split.** The split silently breaks metric continuity on the admin cost dashboard and invalidates Area 1's own £-saving query the day it ships. `pipeline_run_phase` already times PUBLISH separately. |
| Staleness threshold | Area 1: `STALE_AFTER = 26h` property | Area 5: `24h` constant, derived from an assumed cron | **Neither.** Both are wrong for T+2..T+4 cells, which the intraday cycle never touches (`IntradayCandidateCollectionStrategy` restricts to T sunset / T+1 sunrise / T+1 sunset), so their healthy cadence is exactly 24h. One owner (Area 5), one value, read the real cron from `scheduler_job_config` first. |
| `forecast_run_disposition` shape | Area 4 absorbs it | Area 5 shrinks it | Area 4 (see A4) |
| `EvaluationViewService` (629) | Area 4 deletes it | Area 5 adds `oldestProducedAt` plumbing to it | Area 4 deletes; Area 5's staleness must be sourced from `evaluation_current.produced_at`, not from a new field on a class that is going away. **Area 5's §8.1.2 is dead work as written.** |
| `CalibrationBucket` `int`→`Integer` | Area 5 | design §7 | consistent — no conflict |

---

## D. Ordering conflicts and build-green failures

The brief asks whether the deletion order keeps the build green at each step. **It does not, at six places.**

1. **Area 6 Phase 2a deletes `OptimisationStrategyService`** while `ModelsController.java:32,52,106,110` still uses it and Area 5 removes the UI in Phase 5. Red between 2a and 5. Also `AbstractControllerTest.java` declares it as a shared mock (267 lines, base class of **every** controller test) — the whole controller test package fails to compile.
2. **Area 6 Phase 2a deletes `FreshnessResolver`** while `BriefingEvaluationService.java:419` uses it and Area 3/4 retire the delta log later. Red between 2a and 3.
3. **Area 4 step 7 deletes `EvaluationViewService` and `BriefingEvaluationService`** — both are `@MockitoBean` fields on `AbstractControllerTest`. Same package-wide compile break. Area 4's "green at each step" claim does not hold.
4. **Area 1 ships slate-only "before the gates"**, which forces `ForecastTaskCollectorForceEvalTest` (350) and `CollectForecastTasksCachedGateTest` (350) to be re-fixtured for `BriefingSlate` and then deleted one phase later. Touched twice. `CollectForecastTasksCachedGateTest.java:107-115` binds `collectForecastCandidates` by exact parameter types via reflection — it fails at **runtime**, not compile, so CI catches it late.
5. **Area 1's `ForecastTaskCollector` field removal does not compile** — it lists two briefing readers and misses `:733` (`collectRegionFilteredBatches`, called from `ScheduledBatchEvaluationService.java:530`) and the legacy no-arg `collectScheduledBatches()` at `:223-227`.
6. **Area 3's commit 2 renames `applyStabilityFilter`** and drops `stabilityByCell` while `ForecastCommandExecutor.java:369` still calls `enrichWithStability(fullEvalBatch, stabilityByCell)` until commit 5. Either it does not compile or the reliability block is silently disabled for three commits.

**Global sequencing that does work:**

```
Phase 0  Area 4: V139 evaluation_event + dual-write + V140 backfill + V141 cloud_verification remap
         Area 5: calibration zero-data fix + outcome recording UI       [both additive]
Phase 1  Area 1: slate split (V142) + stability onto the slate (from A3)
         Area 6: PipelinePhase rename + V143 data migration
Phase 2  Area 2: gate removal (all of it, one commit per gate, each green)
Phase 3  Area 3: subsystem deletion + V144 drops
Phase 4  Area 4: switch reads to evaluation_current, delete EvaluationViewService,
                 BriefingEvaluationService, cached_evaluation, forecast_evaluation
         Area 6: delete the sync engine (only after B5 is resolved)
Phase 5  Area 5: UI shrink + new disposition endpoint
```

The non-negotiable edges: **A3 before Area 3**; **B5 before Area 6 Phase 3**; **Area 4 Phase 0 before Area 2** (the disposition write must exist before the categories change); **`AbstractControllerTest` is touched in Phase 4, not incrementally**.

---

## E. "Publication is terminal" — breaks still standing

Complete reader set (`grep -rn "getCachedBriefing\|getCachedDays" src/main --include="*.java"`), 9 call sites in 7 classes:

| Site | Under the new design |
|---|---|
| `ForecastTaskCollector.java:272` | → slate (Area 1) ✓ |
| `ForecastTaskCollector.java:733` | **unaddressed** — region-filtered admin path (B2) |
| `PipelineOrchestrator.java:538` | → deleted (Area 1) ✓ |
| `SpringTideHotTopicStrategy.java:92`, `KingTideHotTopicStrategy.java:97` | → slate (Area 1) ✓ |
| `BriefingModelTestService.java:100` | → slate (Area 1) — but see below |
| `BriefingController.java:58` | serve path, legitimate ✓ |
| `CloseToHomeService.java:136` | **unclassified by any area** |

**`CloseToHomeService.java:136` calls `getCachedBriefingForApi()`.** No area mentions it. It is a *serve*-path read by a different panel, so it does not create a cycle — but "publication is terminal — nothing reads it" as stated is false, and the ArchUnit guard Area 6 proposes would fire on it. **State the rule precisely: no stage of the cycle reads the publication; serve-path panels may.** Then the guard is writable.

**`republish()` / `POST /api/briefing/run` is the one remaining true break.** Area 1 keeps it, defined as `publish(buildSlate())`, with no `aboveThreshold` check anywhere. During an Open-Meteo degradation an admin pressing the button (`JobRunsMetricsView.jsx:364-376`) publishes a <50%-coverage briefing **and** persists it via `persistBriefing` (`BriefingService.java:594-605`, upsert id=1), destroying the last known good with no recovery. Under maximum deletion: **delete the endpoint, the controller method, `briefingApi.runBriefing`, the button and its three controller tests.** The twice-daily cycle already does the job.

---

## F. The selection-effect rule, quietly dropped in three places

1. **The travel-day gate is invisible to the display layer.** Area 4's `evaluation_scored_current` filters `outcome = 'EVALUATED'`, so no `SKIPPED` event reaches any display path — including the one gate the redesign deliberately keeps. `frontend/src/components/DispositionBreakdown.jsx:12-23` does not even carry a `SKIPPED_TRAVEL_DAY` label; it falls through the generic unknown-category path. **The one surviving policy decision, which removes whole days non-randomly, ships as an unlabelled row.** Owner: Area 5, blocked on A4.
2. **B2's two submit paths record nothing.** Numerator without denominator.
3. **`ForecastCommandExecutor`'s synchronous engine writes no dispositions at all**, and it is the sole home of the sentinel early-stop gate (`ForecastCommandExecutor.java:491-572`, `:565-566` logs "sentinel early-stop — N tasks skipped"). Area 6 correctly notes this gate cannot be measured with the instrument the design names. Under correction (2) it is a policy gate and must go — but its cost must be booked from `job_run`/`api_call_log` filtered to `triggered_manually = true`, not from `forecast_run_disposition`, which has no rows for it. **No area writes that query.**

---

## G. The display/gate boundary — drawn four different ways

| Signal | Where the areas put the line |
|---|---|
| **Stability** | A3: classifier survives for display. But Areas 3/5/6 delete the *transport* (`StabilitySnapshotProvider`) and Area 6 deletes the *only* prompt producer (B5). Boundary drawn in three places, none of them consistent. |
| **Triage** | Area 2 correctly identifies `BriefingVerdictEvaluator` (display) vs `WeatherTriageEvaluator` (gate) and keeps the former. But **Area 4 deletes the triage display path without saying so** — `EvaluationViewService.java:458` derives `Verdict.STANDDOWN` from `cachedResult.triageReason()` and feeds `DisplayVerdict.resolve` (`DisplayVerdict.java:40-58`), the fallback that colours unscored Plan cells; `ForecastDtoMapper.java:240,612` render `triageReason`/`triageMessage` into the API payload; `BriefingEvaluationResult:27-28` carries them. Post-change a heavy-cloud slot renders a Claude rating instead of "STANDDOWN — sun blocked", and the fallback ladder loses a rung. **User-visible, unflagged.** |
| **Freshness** | Area 2/3 delete the resolver; Area 5 keeps a staleness display but sources it from a class Area 4 deletes (C). |
| **Verdict** | `BriefingGlossService.java:203` is a surviving policy gate on Claude spend, inside PUBLISH, that only Areas 1 and 6 noticed and neither claimed. |

**One rule, stated once, for the whole design:** *the classifier and the verdict are computed at SLATE time and ride the slate into PUBLISH for display and prompt enrichment. No stage between SELECT and RECORD may branch on either, except SELECT's travel-day test.* Every area's section must be re-read against that sentence.

---

## H. What removing triage breaks that no single area owns

This is the question the brief singles out, and it is the weakest part of the combined design.

### H1 Batch size and latency — nobody modelled it

`submitBuckets` (`ScheduledBatchEvaluationService.java:360`) fans out into **six** Anthropic batches: `nearInland`, `nearCoastal`, `farInland`, `farCoastal`, `bluebell`, `woodland`. Bluebell and woodland are already **exempt** from triage (`ForecastTaskCollector.java:433`, `if (preEval.triaged() && !bluebellWoodInSeason && !woodlandTask)`), so triage removal grows only the four colour buckets — by ~17-20%, plus ~19% more from the hard-constraint gate.

Observed afternoon batch latency is **98-173 min** and `DEFAULT_SAFETY_TIMEOUT` is 4h (`PipelineOrchestrator.java:98-101`). The 14:00 UTC intraday cycle is the one that must land before the evening. **No area asks whether +40% request volume moves the p95 past the timeout.** Add a proof obligation to Phase 2: one week of `pipeline_run_phase` duration for `EVALUATE_WAIT`, compared like-for-like, before Phase 3.

### H2 The publish stage now runs on a slate built before the batch

Area 1's split moves the weather fetch to the head of the cycle. Today `refreshBriefing()` — *including* its Open-Meteo fetch (`BriefingService.java:433-437`) — runs at the **tail**, after the wait. Under the split, PUBLISH reuses a slate up to 3h older. **The Plan tab's weather gets staler by the whole batch latency**, which undercuts Area 5's staleness work and Area 6's own argument that intraday exists to be the day's second fresh slate. Not one area states this as a cost. It is the price of breaking the circularity and it should be in the design, not discovered in production.

### H3 The best-bet advisor's input distribution changes shape

Today a triaged slot has **no rating** — it reads as absent. After triage removal it has a **rating of 1-2** (the prompt forces this for solar low >60%). `BriefingBestBetAdvisor` and `BriefingRollupBuilder` consume the enriched rollup; `BriefingRatingStats` (`ratingRange`, min/max) feeds `ConfidenceDeriver`. **A region that today shows "3 scored, spread 1" will tomorrow show "8 scored, spread 3"** — which downgrades its confidence one band under Area 5's own `ratingRange >= 2` rule, on *more* information. Nobody owns this. Area 5's confidence design must be re-checked against the post-triage distribution before Phase 5.

### H4 Coverage ratio and the honesty filter — see B3

Coverage rises toward 100%, which retires `lightlyEvaluated` and most of `BriefingHonestyFilter`.

### H5 The gloss inconsistency

After triage removal every slot carries a Claude rating, but `BriefingGlossService.java:203` still suppresses gloss for an all-hard-constrained region. **Ratings with no prose** — a new user-visible inconsistency. Area 6 flagged it; nobody owns the fix.

### H6 Prompt cache

Positive, and worth stating so it is not re-litigated: more requests per homogeneous bucket amortise the `SYSTEM_PROMPT` cache write better, not worse. No action, but record it — and record that `SYSTEM_PROMPT` stays ≥15,500 chars (`PromptBuilder.java:65`) in every phase.

### H7 The verification harness population — the one thing that goes right

Area 4 is correct that `evaluation_event` fixes the selection effect. Note the refinement: `WeatherTriageEvaluator` has **three** independent rules (cloud >80, precip >2mm, visibility <5000m — `TriageReason` carries HIGH_CLOUD, PRECIPITATION, LOW_VISIBILITY, TIDE_MISALIGNED, GENERIC), so the population is a mixture, not a clean restatement of the 80 threshold. The selection argument survives; the specific inference does not.

### H8 The measurement everyone defers

Five of six areas file "how many slots are triaged per cycle?" as an open question and each proposes a slightly different query. **Run it once, now, before anything ships:**

```sql
SELECT jr.started_at::date                                         AS cycle_date,
       count(*)                                                    AS candidates,
       count(*) FILTER (WHERE d.disposition = 'SKIPPED_TRIAGED')   AS triaged,
       count(*) FILTER (WHERE d.disposition = 'SKIPPED_HARD_CONSTRAINT') AS hard_constraint,
       count(*) FILTER (WHERE d.disposition = 'SKIPPED_TRAVEL_DAY')      AS travel,
       count(*) FILTER (WHERE d.disposition IN ('EVALUATED','FORCE_EVALUATED')) AS evaluated
FROM forecast_run_disposition d
JOIN job_run jr ON jr.id = d.job_run_id
WHERE jr.started_at >= now() - interval '30 days'
GROUP BY 1 ORDER BY 1 DESC;
```

Normalise by **`job_run.started_at::date`**, never by `evaluation_date` (that is the forecast target date; over overlapping 4-5-day windows it counts ~34 distinct dates and says nothing about cycles).

**And state it plainly in the design:** the published **+£32-48/month all-in delta was computed with triage RETAINED** (design §1 and §2.3 both say so). It no longer holds. The new delta is roughly `(triaged + hard_constraint) / evaluated` × current spend on top of the previously computed figure — on the migration comment's own numbers, ~+35% more evaluations than the estimate assumed.

---

## I. Still missing — what an engineer hits on day one

1. **No global migration ledger.** A1. Ten minutes of work; a production boot failure if skipped.
2. **No replacement for `/api/metrics/disposition-breakdown`.** Area 4 deletes `JobMetricsController.java:165`, `ForecastDispositionService`, `DispositionBreakdownResponse`; Area 5 builds a component against it; `frontend/src/components/DispositionBreakdown.jsx` + two frontend test files break. Nobody designs the `evaluation_event`-backed endpoint that §4.6 ("grow the disposition view") requires. **Design it in Phase 0, alongside V139.**
3. **`ddl-auto: validate` vs the two `@Immutable` view entities and `produced_at DEFAULT clock_timestamp()`.** `application.yml:12`, `application-dev.yml:12`, `application-prod.yml:13` all validate. Hibernate view visibility on Postgres 17 must be proven before V139 ships, and `produced_at` needs `@Column(insertable = false, updatable = false)` + `@Generated(event = INSERT)` or the very first spine write fails on NOT NULL. Both surface only after V139 is applied to production.
4. **`cloud_verification` ON DELETE CASCADE.** `V129:43`. Area 6 Phase 4 drops `forecast_evaluation`; that silently destroys all 25,730 ERA5 rows — the project's only non-self-referential evidence outside the empty `actual_outcome`. Area 4's remap must land first, and the `NOT NULL UNIQUE` on `:22` must be dropped (or made `DEFERRABLE`) around the remap `UPDATE`, or it fails mid-statement.
5. **Nobody wrote the ArchUnit / repository guard** that §4.4 requires: "a test that fails on any repository query against `evaluation_event` lacking an outcome predicate". It is the mechanism that keeps the selection-effect fix durable, and it is one test.
6. **Frontend test inventory is incomplete in every area.** `frontend/src/test/JobRunsMetricsViewBatch.test.jsx:118,359` assert on the exact string `/triage and stability gates/` at `JobRunsMetricsView.jsx:883` that two areas rewrite; `ModelSelectionView.test.jsx`, `DispositionBreakdown.test.jsx` (~275), `PipelineRunsView.test.jsx:10` (path-mocks the component being renamed), `JobRunDetail.test.jsx`. `npm run test` goes red with no warning in any plan.
7. **`ModelSelectionView.jsx:466-490,525,555` is user-facing admin prose describing the deleted eligibility gate** — including the per-run call-volume estimate the operator uses to sanity-check the cost increase. After the change it is false and systematically under-states volume. Only partly listed.
8. **`RunPhase.TRIAGE` / `LocationTaskState.TRIAGED` / `RunProgress.getTriaged()`** stay live and permanently zero (`RunPhase.java:15`, `RunProgress.java:31,136,169`, serialised at `RunProgressTracker.java:258,281`). Area 2 renames `fetchWeatherAndTriage` on the grounds that "a lie in a method name is how the mechanism gets reinvented" and then leaves a phase, a task state and an admin counter all called TRIAGE.
9. **No area states the `PAST_WINDOW_DAYS = 2` vs 7-day-outcomes mismatch as accepted.** Area 5's headline goal is twenty recorded outcomes; the map's `DateStrip` only reaches T-2, so five of the seven fetched days cannot be recorded against. That is the most likely reason the count stays at zero.
10. **`lombok.config` exists** (`backend/lombok.config`, `lombok.addLombokGeneratedAnnotation = true`) — Area 4's flagged JaCoCo risk is closed. But **Area 4's `BriefingSlateEntity`/`BriefingSlateStore` and Area 1's equivalents are covered only by `IntegrationTestBase` tests**, which the CI-repro glob `-Dtest='!**/integration/**'` excludes. Those classes will fail the 80%/class gate on the first push. Plain unit tests + a Mockito `BriefingSlateStoreTest` are required, not optional.
11. **`src/test/java/**/regression/` does not exist.** Four areas cite it as the protected path. `find src/test -type d -name regression` → empty. Prompt-regression tests are selected by **JUnit tag**: `pom.xml:23` `<surefire.excludedGroups>prompt-regression</surefire.excludedGroups>`, and `@Tag("prompt-regression")` on `PromptRegressionTest`, `BestBetAuroraPromptRegressionTest`, `SkyRatingEvalTest`. **Restate the binding constraint tag-wise**, or an engineer greps the wrong thing and edits a protected file. Note that Area 3's commit 1 changes `BriefingBestBetAdvisor.advise` and `BriefingRollupBuilder.buildRollupJson` signatures, which forces edits to `BestBetAuroraPromptRegressionTest.java:111,175,219` **in the very first commit** — legal (constructor calls, not assertions) but it must be called out, not hidden behind "additive".

---

## J. Line count — the claimed total is ~45% double-counted

| Area | Claimed |
|---|---|
| 1 Circularity | ~90 |
| 2 Gate removal | ~2,250 |
| 3 Subsystem/config | ~3,059 |
| 4 Spine | ~2,310 |
| 5 Staleness/ops/calibration | ~940 |
| 6 Stage contracts | ~3,224 |
| **Sum** | **11,873** |

Measured union of **whole-file** production deletions, all 38 files verified present and summed at `d421ef5f`: **3,818 lines**. Files claimed by two or more areas include `FreshnessResolver`+`FreshnessProperties` (118, claimed 3×), the four `Optimisation*` classes + service + skip evaluator (607, claimed 3×), `DispositionCategory` (128, claimed 5×), `StabilitySnapshotProvider`+`StabilityController`+`StabilitySummaryResponse` (319, claimed 3×), the four eligibility types (233, claimed 3×), `WeatherTriageEvaluator` (65, claimed 4×), `EvaluationViewService` (629, claimed 2×), `ForecastDispositionService` (158, claimed 3×).

Adding in-file surgery, measured against actual file sizes:

| File | Now | After | Removed |
|---|---|---|---|
| `ForecastTaskCollector.java` | 899 | ~280 | ~620 |
| `BriefingEvaluationService.java` | 665 | 0 | 665 |
| `BriefingCandidateCollector.java` | 354 | ~110 | ~244 |
| `ScheduledBatchEvaluationService.java` | 621 | ~450 | ~170 |
| `PipelineOrchestrator.java` | 669 | ~500 | ~170 |
| `ForecastService.java` | 685 | ~450 | ~235 |
| `ForecastCommandExecutor.java` | 826 | ~450 | ~376 |
| controllers/models/misc | — | — | ~300 |

**Realistic union: ≈6,600 production lines** with the synchronous engine retained; **≈7,700** if Area 6 Phase 3 deletes it (`ForecastCommandExecutor` 826 + `ForecastService` 685 + `ForecastCommandFactory` 152 + `ScheduledForecastService` 141 = 1,804 whole-file instead of ~611 surgical).

**Test churn is not deletion and no area books it honestly.** The mandated re-fixturing: `BriefingServiceTest` 2,446 (split three ways), `ForecastTaskCollectorTest` 1,557, `EvaluationViewServiceTest` 1,291, `BriefingEvaluationServiceTest` 907, `PipelineOrchestratorTest` 856, `AbstractControllerTest` 267 (base class of every controller test). That is **~7,300 test lines touched**, most of it rewritten rather than removed, and it dominates the real cost of the change. Area 1's honest note that its section is *+210 production lines net* is the only place this is acknowledged, and it stops at the production boundary.

**Report the number as: ~6,600-7,700 production lines removed, ~7,300 test lines rewritten.** Not 11,873.