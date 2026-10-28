# Synchronous forecast engine retirement: the three term run types, manual runs, optimisation strategies

**Status: PLAN WRITTEN AND REVIEWED 2026-10-28 (§10). All owner decisions are made (§8). No phase is
built.** Written against `main` at `ff2e0a2d` (v2.34.1).

Owner decision (2026-10-28): remove the whole synchronous forecast engine. That includes the map
popup's admin "Run Forecast" button and the run types `VERY_SHORT_TERM`, `SHORT_TERM` and
`LONG_TERM`. Prompt Test is decided separately (§7).

### Phase log

| phase | branch | commit | date | notes |
|---|---|---|---|---|
| plan | `claude/job-runs-config-audit-2833f9` | *(fill after merge)* | 2026-10-28 | Survey: two read-only traces (backend engine reachability, frontend Operations tab). Review: four prosecutor lenses (backend deletions, data/migrations, frontend, phasing/tests/docs) with 43 charges, triaged by the planner (§10). |

---

## 1. Why

Production forecasts run only through the batch pipeline (`PipelineOrchestrator` → `service/batch/*`,
with models keyed `BATCH_NEAR_TERM` / `BATCH_FAR_TERM`). The engine behind the three term run types
has had no scheduled trigger since 2026-02-27; its `@Scheduled` triggers are commented out
(`ScheduledForecastService.java:26`). That engine is `ForecastCommandFactory` →
`ForecastCommandExecutor` → `ForecastService.runForecasts` / `evaluateAndPersist` →
`EvaluationServiceImpl.evaluateNowForecast`. No dynamic-scheduler job target, `PipelineOrchestrator`
step or batch class calls the factory or the executor. Its only remaining entry points are admin
buttons:

| Entry point | Endpoint | Run type |
|---|---|---|
| Job Runs → Very Short-Term / Short-Term / Long-Term buttons (`JobRunsMetricsView.jsx:695-718`) | `POST /api/forecast/run/{very-short-term,short-term,long-term}` | the three types |
| Map popup "Run Forecast", admin only (`MarkerPopupContent.jsx:191-237, 995-1014`) | `POST /api/forecast/run` | `SHORT_TERM` (`ForecastController.java:631`) |
| `RunProgressPanel` "retry failed" (Job Runs only) | `POST /api/forecast/run/{id}/retry-failed` | the three types (`FailedSlotRetryService.java:118`) |

**Optimisation strategies.** Sentinel Sampling and Tide Triage are read only by
`ForecastCommandExecutor:221-224`. The batch pipeline never reads them, so they die with the engine.
V153 already retired the six strategies that could no longer act.

**Notification channels.** The forecast-result channels die with the engine too.
`NotificationDispatcher` and its Email, Pushover and macOS-toast `NotificationChannel`s are consumed
only by `ForecastService` (:81, :260, :581), and `AdminAlertService`'s Javadoc (:37-41) already
calls them disabled.

**`forecast_evaluation` loses no production writer.** The batch path writes PENDING rows through
`ForecastService.persistPendingEvaluation` and scores them in place. The sync engine wrote extra rows
only on admin runs. CLAUDE.md's "load-bearing, do not clean it up" rule covers the table and the
batch path's use of it, and both stay.

## 2. What stays (do not delete)

- **Job Runs as a view.** It is the only UI trigger for Aurora Forecast and Simulate, Briefing, Tide
  refresh and backfill, Light Pollution, and Scheduled and JFDI Batch.
- **Run Config's Briefing, Aurora, Batch Near-Term and Batch Far-Term tabs**, and Briefing's
  extended-thinking toggle. The whole Operations pane is admin-gated (`App.jsx:1042`).
- **Run progress for Light Pollution.** `RunProgressTracker` (`initRun`, `setPhase`), `RunProgress`,
  `RunCompletion` (`complete`/`abort`), `RunPhase`, `LocationTaskEvent`, the SSE progress endpoint
  `/run/{id}/progress`, `subscribeToRunProgress`, `RunProgressPanel` (without retry) and the
  `run_progress_cleanup` job all stay. The Light Pollution run (`AuroraAdminController:97` →
  `BortleEnrichmentService:68,108-109`) is now the only run that shows progress. Tide returns no
  `jobRunId` (`ForecastController.java:726-727`), so it shows only a status line.
- **Batch's `ForecastService` methods:** `fetchWeatherAndTriage`, `persistPendingEvaluation` and
  `markAbandoned` (callers: `ForecastTaskCollector`, `ForceSubmitBatchService`, `BatchRetryService`).
- **The Aurora sync path:** `EvaluationServiceImpl.evaluateNow` for Aurora (`AuroraOrchestrator:534`)
  and `AuroraResultHandler.handleSyncResult`.
- **`ClaudeEvaluationStrategy.checkStopReason`, package-private as it is.** Several live callers
  reach it through `ModelRequestSupport.checkStopReason` (:166-167): Aurora, Best Bet and Ask.
  `PromptReplayHarnessTest:300` calls it directly. Only its Javadoc (:137-140) changes.
- **`RunType.FORECAST_HORIZON_DAYS`** (`OutcomeRatingService:65`).
- **`AdminAlertService` and `UserEmailService`.** These are a different mail path, not the
  forecast-result channels.
- **Endpoints:** `POST /api/forecast/run/tide`, `/run/tide/backfill`, and every metrics and models
  endpoint not named below.
- **The live run types:** WEATHER, TIDE, LIGHT_POLLUTION, BRIEFING, BRIEFING_BEST_BET,
  BRIEFING_GLOSS, AURORA_EVALUATION, AURORA_GLOSS, BLUEBELL_GLOSS, SCHEDULED_BATCH, BATCH_NEAR_TERM,
  BATCH_FAR_TERM, ASK and ASK_READY. Every batch `job_run` row carries `SCHEDULED_BATCH`
  (`JobRunService:660,703`). `BATCH_NEAR_TERM` and `BATCH_FAR_TERM` are only `model_selection` keys;
  no `job_run` row is ever written with them.

**Prompt regression classes are never edited.** CLAUDE.md's `src/test/.../regression/` path does not
exist; the classes tagged `prompt-regression` live in `service/evaluation`, `service/ask` and `eval`
(see `backend/pom.xml:745-760`). No tagged class references anything this plan deletes. If any phase
breaks the compile of a tagged class, stop and ask the owner.

## 3. Phase 1: remove the entry points (frontend and endpoints, one PR)

The frontend and backend are separate images (`.github/workflows/deploy.yml:46-57`), so for a few
seconds during a deploy a stale UI may get a 404. Only admins can reach these controls, so that is
acceptable and needs no ordering.

### Frontend

**`MarkerPopupContent.jsx`**
- Delete:
  - the Run Forecast button (:995-1014) and `handleRunForecast` (:191-237);
  - the `runningForecast`, `runProgress` and `forecastError` state;
  - the `runForecast` and `createEventSource` imports;
  - the `runOutcome` imports (:14-16);
  - `apiErrorMessage` (:28), if it has no other use;
  - the `onForecastRun` prop (:176) and its PropTypes entry (:1084). No parent passes it.
- The "no forecast yet" divider (:982-993) and the admin `forecastRunAt` line (:804) stay.

**`JobRunsMetricsView.jsx`**
- Delete:
  - the three term buttons and their `handleRun*` and `running*` state;
  - `computeSlots`, `formatSlotDate` and `ukDateStrOffset` (:20);
  - the slot picker in `buildConfirmDialog`, and the Daily-Briefing and solar-event-times fetch
    that exists only for it (`getDailyBriefing`, `solarEventTimes`, `latestSolarEventTimes`,
    `hasEventPassed`; :21, :255, :276-278, :940);
  - `STRATEGY_LABELS` (:31), the `strategies` state and its `getAvailableModels` read of
    `optimisationStrategies` (:261, :267-269);
  - `activeStrategies` (:360-366, :380) and the "Active optimisations" block (:1011-1024).
- **The tide dialog's region checkboxes do nothing.** `refreshTideData()` takes no arguments
  (`forecastApi.js:161`), so the `excludedLocations` passed at :421 are discarded. Delete the
  region-exclusion UI. If `buildConfirmDialog` has nothing left to configure, the tide buttons use a
  plain confirm. Keep `getLocationSummary` if the dialog still shows it, and fix its JSDoc (:130).
- Retry plumbing: delete `promotedRunId`, `handleRetryStarted`, the `retryStartedNote` import
  (:24, :330-341), and the `retryNote` / `onRetryNoteChange` props and PropTypes (:232).
- Run-type filter `<select>` (:845-854):
  - drop VERY_SHORT_TERM, SHORT_TERM and LONG_TERM;
  - drop BATCH_NEAR_TERM and BATCH_FAR_TERM, because no `job_run` row carries them;
  - relabel SCHEDULED_BATCH from "Batch (legacy)" to "Batch".
- The batch-type sets elsewhere (:644, `JobRunsGrid.jsx:36`, `JobRunDetail.jsx:27`,
  `MetricsSummary.jsx:219`) are harmless and stay.

**`ManageView.jsx`:** delete the `retryNote` state and props (:77-82, :207-208).

**`RunProgressPanel.jsx`:** delete the retry offer and button, the `onRetryStarted`, `focusOnMount`
and `startedNote` props (:65-73), and `NOTHING_TO_RETRY` and the other `RETRY_*` constants (:17-19).

**`runOutcome.js`:** delete `retryStartedNote`, `retryNotOfferedLine`, the `RETRY_NOT_OFFERED_*`
exports, `failureMessage` (:78) and `RUN_FAILED_FALLBACK` (:7). After Phase 1 none of them has a
production caller. Keep `failedOutright`, `stoppedEarly`, `completedWithFailures`, `runCountsLine`
and `needsAttention`. Fix the header comment (:3).

**Run-complete banner (owner decision §8a, recommended: remove).** `/run/notifications` is open to
every logged-in user, and `RunCompleteBanner` is shown to everyone (`App.jsx:293, 787-795, 870`).
After this phase the only emitter is an admin's Light Pollution run, which would show Lite and Pro
users "Forecast run completed — N locations updated" for a Bortle refresh.
- If removed:
  - delete `useRunNotifications`, `RunCompleteBanner`, their App wiring and
    `subscribeToRunNotifications`;
  - Phase 2 then deletes the backend `/run/notifications` endpoint and its subscriber plumbing in
    `RunProgressTracker`.
- If kept: gate the banner to admins and reword it.

**API modules**
- `runProgressApi.js`: delete `retryFailed`.
- `forecastApi.js`: delete `runForecast`, `runVeryShortTermForecast`, `runShortTermForecast` and
  `runLongTermForecast`.
- `modelsApi.js`: delete `updateOptimisationStrategy`, and fix the JSDoc (:22, :56).

**`ModelSelectionView.jsx`**
- Delete:
  - the three term entries in `CONFIG_TABS`; the default tab becomes `BRIEFING_BEST_BET` (:166);
  - `DAYS_PER_RUN`, `STRATEGY_INFO`, the Cost Optimisation section (:657+), the `strategies` state
    (:161, :215) and `handleStrategyToggle` / `handleParamChange` (:250-290);
  - the `forecastCalls` branch of the per-run estimate (:595-605), along with `fetchLocations`
    (:4, :209), `locationCount` (:167), `isWildlifeOnly` (:9) and the `RUN_TYPE_RANGES` import (:8).
    Left unused, the import fails lint.
- Keep the per-run estimate for the `CUSTOM_COST` tabs (Briefing, Aurora; :119-122, :598) and the
  batch tabs' cost table.
- Rewrite the stale "About" bullets (:743-746) and the comment at :127.

**Already dead, swept here:**
- `getBuildInfo` (`metricsApi.js:49`);
- `triggerAuroraRun` and `resetAuroraState` (`auroraApi.js:88,99`);
- stale JSDoc at `metricsApi.js:11`, `forecastApi.js:111,147` and `useForecasts.js:217`.

**Tests**
- Delete:
  - `MarkerPopupContentRunForecastError.test.jsx`
  - `jobRunSlotDatesAbroad.test.jsx`
  - `RunProgressPanelRetry.test.jsx`, `RunProgressPanelRetryOffer.test.jsx`,
    `RunProgressPanelRetryHttp.test.jsx`
  - `runOutcomeRetry.test.js`
- Prune:
  - `RunOutcomeSurfaces.test.jsx`: keep the banner half (:203+) only under §8a "keep", and keep the
    `needsAttention` cases (:446).
  - `RunProgressKeptAfterFailures.test.jsx`, including its `retryFailed` mocks (:54, :64, :113).
  - `RunProgressPanelReason.test.jsx`, `RunProgressPanelKeyRejected.test.jsx`,
    `runProgressFixtures.js`, `runProgressApi.test.js`.
  - `JobRunsMetricsViewBatch.test.jsx`, including its mocks of removed functions (:19-21, :70).
  - `ModelSelectionView.test.jsx`.
  - `MarkerPopupContentDetail.test.jsx`: drop the stale `runForecast` mock (:14).
- **Invert** `MarkerPopupContent.test.jsx:1090, 1218, 1581`. They expect the button for admins; they
  must now assert it is absent.
- Each pruned test keeps what it asserts about surviving behaviour: Light Pollution progress, failure
  reason, `needsAttention`.
- `test/e2e/forecast.spec.js` touches no removed control and needs no change.

### Backend

**`ForecastController`**
- Delete:
  - `POST /run`, `/run/very-short-term`, `/run/short-term`, `/run/long-term` and
    `/run/{id}/retry-failed`;
  - the `toExcludedSlotKeys` / `toExcludedLocationNames` helpers;
  - `ForecastRunRequest`, if nothing else uses it, plus its JaCoCo exclude for `SlotFilter`
    (`pom.xml:599`);
  - the `commandFactory`, `commandExecutor` and `retryService` constructor dependencies.
- Fix the Javadoc at :569 and :598.

**Constructor callers that break.** These tests construct `ForecastController` directly and must
change in this PR: `ForecastWindowAnchorTest:119-122`, `ForecastHistoryQueryCountTest:113-116` and
`ForecastControllerAsOfEventTest:140-142`.

**Phase 1 leaves the engine classes unreferenced but compiling.** Nothing else injects them, and
their own unit tests keep them above the per-class JaCoCo gate until Phase 2.

**`ModelsController`:** delete `PUT /api/models/optimisation` and the `optimisationStrategies` key in
`GET /api/models`.

**`ModelSelectionService.CONFIGURABLE_RUN_TYPES`**
- Drop the three term types, and add the missing live `BLUEBELL_GLOSS` (read by
  `BluebellGlossService:103`).
- Then check whether `PUT /api/models/active` validates against the list. If it does not, add that
  check (now safe because the list is complete), with a test that a term type is refused.
- Leftover term rows in `model_selection` are harmless; GET only iterates the list.

**Controller tests:** delete the removed endpoints' cases in `ForecastControllerTest` and
`ModelsControllerTest`, and assert that the removed paths are no longer mapped.

**Docs, in the same PR**
- `reference/api-endpoints.md`: remove the five endpoints and the optimisation PUT.
- `reference/roles-and-gating.md:49`: remove "Run Forecast".
- `reference/map-tab.md`: if it mentions the popup button.

## 4. Phase 2: delete the engine (backend only)

**Repoint the horizon constant first.** `ForecastCommandFactory.FORECAST_HORIZON_DAYS` is read by
`ForecastController:148,175` (the public `GET /api/forecast`), `BriefingEvaluationController:95`,
and tests `BriefingEvaluationControllerTest:94,163`, `LitePickEnforcementControllerTest:200,439,555`,
`HttpCachingIntegrationTest:370,480` and `ForecastWindowAnchorTest:69`. Point them all at
`RunType.FORECAST_HORIZON_DAYS`.

### Delete these classes

- **Engine:** `ForecastCommandFactory`, `ForecastCommand` (and its JaCoCo exclude, `pom.xml:601`),
  `ForecastCommandExecutor`, `FailedSlotRetryService`, `SentinelSelector`.
- **Optimisation strategies:** `OptimisationStrategyService`, `OptimisationStrategyEntity`,
  `OptimisationStrategyRepository`, `OptimisationStrategyType`,
  `model/OptimisationStrategyUpdateRequest`. **The `optimisation_strategy` table stays until Phase 3**
  (see §5 on rollback).
- **Notification channels** (owner decision §8b, recommended: delete): `NotificationDispatcher`,
  `NotificationChannel`, `EmailNotificationService`, `PushoverNotificationService`,
  `MacOsToastNotificationService`, their properties class and config keys if nothing else reads
  them, and their tests. Update CLAUDE.md's project overview line ("notifications
  (email/Pushover/macOS toast)") to say admin alerts and user emails only.

### `ForecastService`

- **Methods:** delete `runForecasts`, `evaluateAndPersist`, `persistCannedResult`,
  `runWildlifeHourly`, and the private helpers `reportEvaluationFailure` (:721) and
  `publishEvaluationFailed` (:734).
- **Fields** that become unread (SpotBugs `URF_UNREAD_FIELD`): `evaluationService`,
  `engineEvaluationService`, `notificationDispatcher`, `runProgressTracker`. Remove them from the
  constructor and its callers.
- **Bulkheads:** delete the `@Bulkhead`s at :158 and :517 and the `forecast` / `claude` bulkhead
  instances in `application-prod.yml:134-140`, `application-local.yml` and `application-example.yml`.
  First grep that nothing else names them.
- **`tideAlignmentEnabled`:** if every remaining caller of `fetchWeatherAndTriage` passes `false`,
  drop the parameter, and with it `TideAlignmentEvaluator` and the `tideAlignmentEvaluator` field
  (:465).
- **SpotBugs:** delete the exclusion at `spotbugs-exclude.xml:100-109`, which cites `runForecasts`.
- **Javadoc:** fix :97-109 and :309-311.

### Stop and failure plumbing (sync-only; Bortle does not use it)

- `RunStoppedException`;
- `RunProgressTracker.isStopped`, `stopRun` (:269-283) and `noteFailure` (:252);
- `RunCompletion.reasonForForecastRun` (:132);
- `EvaluationFailure.stopsRun` (:137);
- `RunPhase.EARLY_STOP` and `RunPhase.SENTINEL_SAMPLING`.

`RunProgressTracker` is already excluded from JaCoCo (`pom.xml:592`). Under §8a "remove", also delete
`/run/notifications` and its subscriber plumbing.

### `EvaluationServiceImpl`

- Delete `evaluateNowForecast`, the `clock` field and constructor parameter (read only at :274), and
  `ResultContext.forSync(Long, Instant, BatchTriggerSource)` (:115, whose only caller is :275).
- `evaluateNow`'s `EvaluationTask.Forecast` arm then throws `UnsupportedOperationException`
  ("forecasts evaluate through the batch path only"). The switch is over a sealed type, so the arm
  cannot simply be dropped.
- Update the Javadoc at `EvaluationService:112` and `EvaluationServiceImpl:75`.

### `ForecastResultHandler`

- **`handleSyncResult`:** the `ResultHandler` interface declares it for both handlers, so keep the
  method and make the forecast implementation throw. Give the throwing stub a one-line test so the
  class stays at 80%.
- **Delete:**
  - the private `persistSyncLog` (:949), which would otherwise trip `UPM_UNCALLED_PRIVATE_METHOD`;
  - the dead BRIEFING_CACHE branch;
  - `EvaluationTask.Forecast.WriteTarget`, if its only remaining value is the one every batch caller
    passes and the batch path ignores it (EvaluationTask:111). Otherwise delete only `NONE`.
- **Fix the stale references** at `BriefingEvaluationService.java:437` and `PromptGoldenMasterTest:48`
  (Javadoc only; that test needs no other change).

### `ModelSelectionService`

Delete the no-argument `getActiveModel()` and `setActiveModel(model)`; nothing in main calls them.

### Tests

- **Delete:**
  - `ForecastCommandExecutor*Test` (Completion, EvaluationFailure, ReadingsSeam, SkyFilter, Slots,
    base) and `ForecastCommandFactoryTest`;
  - `FailedSlotRetryServiceTest` and `FailedSlotRetryChainTest`;
  - `SentinelSelectorTest`;
  - `OptimisationStrategyServiceTest` and `OptimisationStrategyRepositoryTest`;
  - the notification-channel tests (§8b).
- **Prune:**
  - `ForecastServiceTest`, keeping its `fetchWeatherAndTriage`, `persistPendingEvaluation` and
    `markAbandoned` cases;
  - `EvaluationServiceImplTest`, keeping the Aurora and submit cases;
  - `ForecastResultHandlerTest` (:536, :1117, :1494, :1523);
  - `ModelSelectionServiceTest`, `SyncFailureApiCallLogTest`, and `ScheduledForecastServiceTest`.
- **`AbstractControllerTest`:** drop its mocks of the four deleted classes (:29-31, :40, :146, :149,
  :187, :215).
- **`RetireDeadOptimisationStrategiesMigrationTest`** imports `OptimisationStrategyType` (:3, :117).
  Replace the enum lookup with a literal list of the two surviving names (`SENTINEL_SAMPLING`,
  `TIDE_ALIGNMENT`), and pin its migrate step to `flywayTo("153")` now, so the Phase 3 drop cannot
  break it.
- **Coverage check:** before opening the PR, read `target/site/jacoco/jacoco.csv` from the full
  `clean verify` for `EvaluationServiceImpl`, `ForecastService`, `ForecastResultHandler` and
  `ModelSelectionService`. Each shrinks a lot. Add tests for surviving behaviour if any falls below
  80%; do not add exclusions.

### Docs, in the same PR

- **`reference/pipeline.md`:** the command-pattern and optimisation-strategy sections become a short
  "retired 2026-10" note pointing here.
- **`reference/ratings-stores.md:25, 28, 67`:** remove "the synchronous engine" as a writer.
- **`reference/architecture-decisions.md` and `reference/conditions.md`.**
- **CLAUDE.md:**
  - :92 — reword `forecast_evaluation` as the batch path's PENDING-then-scored table, keeping
    "load-bearing, do not clean it up".
  - :66 — drop "command pattern, optimisation strategies" from the pipeline row.
  - The notifications line, under §8b.
- **Code comments that name the engine:**
  - services: `ScheduledForecastService.java:26-30`, `StabilitySnapshotProvider.java:25`,
    `WildlifeComfortRefreshJob`, `AstroConditionsRefreshJob`;
  - batch: `GridCellStabilityService.java:65, 121`, `NightlyEligibilityPolicy.java:73`;
  - entities and utilities: `util/ForecastHorizon.java:35-84`, `LocationEntity.java:279, 328, 341`,
    `ForecastStability.java:21, 54`, `RunType.java:73-79`;
  - evaluation: `EvaluationFailedException.java:14`, `SlotAtmosphereWriter.java:39`;
  - build: the comment at `pom.xml:621`;
  - tests: `NightlyEligibilityPolicyTest.java:13`.
- **`intraday-settled-refresh-plan.md`** cites executor line numbers (:226-232, :446, :490). If that
  plan is complete, leave it as history. If it is open, add a note that the executor is gone.

## 5. Phase 3: remove the three enum constants and their rows

### Why one PR

`RunType` maps through `@Enumerated(STRING)` in `job_run`, `model_selection` and `prompt_test_run`.
A row still holding a removed value makes Hibernate throw on read, which would break the Job Runs
list, `GET /api/models` and Prompt Test history. So the constants and the migration that clears
their rows land together.

No other column stores these values. Main has no native or JPQL query with a term string; the only
literals are in immutable historic migrations.

### Rollback rule

Prod runs `ddl-auto: validate` (`application-prod.yml:13`). An older image must still validate
against the newer schema, so a migration may drop only what the **previous** phase's image no longer
maps.

- **Allowed in Phase 3:** dropping `optimisation_strategy`, because Phase 2's image has no entity
  for it.
- **Not allowed in Phase 3:** dropping `prompt_test_run.run_type`, because Phase 2's image still
  maps it. That drop is Phase 4.

### Migration `V196__retire_term_run_types.sql` (latest is V195; renumber if `main` has moved)

- `DELETE FROM model_selection WHERE run_type IN ('VERY_SHORT_TERM','SHORT_TERM','LONG_TERM')`.
- `DELETE FROM job_run WHERE run_type IN (...)`.
  - The only FK to `job_run` is `api_call_log ... ON DELETE CASCADE` (V20:20), so the cost-log rows
    of past manual runs go too. These were admin experiments.
  - Three columns reference `job_run` with no FK: `forecast_batch.job_run_id` (V85:9),
    `forecast_run_disposition.job_run_id` (V101:21) and `pipeline_run.disposition_job_run_id`
    (V163:19). The migration test asserts that no seeded batch-side row points at a term-type run.
- `DROP TABLE optimisation_strategy`.
- `prompt_test_run`: per §7.

### Migration test

Shape it on `RetireDeadOptimisationStrategiesMigrationTest`: plain Flyway against
`postgres:17-alpine`, no Spring boot.

1. Run `flywayTo("195")`.
2. Seed with raw SQL:
   - one `job_run` per term type, each with an `api_call_log` child;
   - one live-type control row (`SCHEDULED_BATCH`) with its own child;
   - `prompt_test_run` rows for all three types.
3. Run `flywayTo(null)`.
4. Assert:
   - the term rows and their children are gone, and the control row and its child remain;
   - `optimisation_strategy` is absent from `information_schema.tables`;
   - **(load-bearing)** every `DISTINCT run_type` in `job_run` and `model_selection` is in
     `RunType.values()`;
   - `prompt_test_run` rows match §7.

### Code changes

- **`RunType`:** delete the three constants. Delete `defaultDateRange` entirely; its only remaining
  caller is `PromptTestService`, which §7 changes. Fix the Javadoc at :73-79.
- **Stale Javadoc:** `ModelSelectionRepository.java:26`, `ModelSelectionEntity.java:21`,
  `JobMetricsController.java:73`.

### Local H2

Local runs never apply Flyway (`ddl-auto: update`), so V196 never reaches `backend/data/`. H2 created
native `ENUM(...)` columns that `update` never alters (see `LocalH2EnumWidener`'s Javadoc :27-35). The
old labels therefore stay valid in H2, and reading them throws in Hibernate.

Add `LocalH2TermRunTypeCleanup` under `backend/src/local/java`, modelled on `LocalH2EnumWidener`:
- `@Profile("local")`, `SmartInitializingSingleton`;
- acts only when the database product is H2;
- logs a WARN on failure and never fails startup.

It deletes the same `job_run` and `model_selection` rows V196 deletes, and fills `prompt_test_run`'s
new column per §7. Tests go in `src/local-test`, shaped on `LocalH2EnumWidenerJpaTest`: seed an
old-schema file through `LocalH2OldSchemaFixtures.OLD_RUN_TYPES`, run the cleanup, and load every row
through JPA. Add a guard test that it does nothing on a non-H2 database. Name the bean in
`reference/dev-and-testing.md`.

### Backend tests holding the constants

Swap the fixture value to a live type in `JobRunServiceTest`, `JobMetricsControllerTest`,
`ApiCallLogCacheDiagnosticsMigrationTest:56` (it uses the constant through Spring, so swap it to
`SCHEDULED_BATCH`), `LocalH2OldSchemaFixtures` (check whether `OLD_RUN_TYPES` must keep the old
labels as strings for the H2 reproduction tests; it probably must) and any others the compiler
names.

### Frontend

- Delete `utils/runTypeRanges.js` and `test/runTypeRanges.test.js`, or reshape them per §7.
- Fix the comments at `AuroraStatusContext.jsx:21`, `useAuroraStatus.js:5` and
  `locationTypes.js:149`.

### Docs

- `reference/pipeline.md:12`: remove the three types from the `RunType` list.
- `reference/database-migrations.md`: add V196.
- `reference/api-endpoints.md:501`: per §7.

## 6. Phase 4: drop the Prompt Test tables (Option B, chosen)

This is one migration, shipped one release after Phase 3. By then the Phase 3 image, which has no
prompt-test entities, is the rollback target. The migration is
`DROP TABLE prompt_test_result; DROP TABLE prompt_test_run;` (results first, in case an FK points
from results to runs). Its Testcontainers test asserts that both tables are absent from
`information_schema.tables`. Docs: `reference/database-migrations.md`.

## 7. Prompt Test (owner decision 2026-10-28: **Option B, delete it**)

Option B lands in Phase 3, because the harness uses the constants:
- V196 deletes all `prompt_test_result` and `prompt_test_run` rows, results first.
- The local cleanup bean does the same.
- Phase 4 drops the tables.
- Phase 3 also:
  - deletes the Operations "Prompt Test" sub-tab (`ManageView.jsx` `prompttest`) and its e2e or
    ManageView test references;
  - deletes `utils/runTypeRanges.js` and its test;
  - removes `reference/api-endpoints.md:501` and the Prompt Test endpoints from that doc.

Option A is kept below as the record of the alternative.

`PromptTestService` uses `RunType` only to choose dates (`resolveDates` :438-443,
`PROMPT_TESTABLE_RUN_TYPES` :99), and `prompt_test_run.run_type` stores the choice. It evaluates
through `evaluationService.evaluateWithDetails`, which is not part of the deleted engine.

### Option A (recommended): keep the harness, keyed to the batch horizons

- **New type:** add `PromptTestRange { NEAR_TERM /* T, T+1 */, FAR_TERM /* T+2, T+3 */ }`, which
  matches what production evaluates. LONG_TERM swept T+3 to T+5, past Gate 4. Dates come from
  `ForecastHorizon` (UK civil date), which retires the UTC-versus-UK note on `defaultDateRange`.
- **V196 migration:**
  - add a nullable `date_range VARCHAR(20)` column;
  - backfill it: VERY_SHORT_TERM and SHORT_TERM become NEAR_TERM, LONG_TERM becomes FAR_TERM;
  - `ALTER COLUMN run_type DROP NOT NULL`, so the new image (which no longer writes `run_type`) can
    insert;
  - then make `date_range` NOT NULL.
- **Entity and API:**
  - `PromptTestRunEntity` maps `date_range`.
  - The controller param becomes `@RequestParam PromptTestRange range`.
  - `PromptTestRunDto` carries the range.
- **Local cleanup bean:** copies and remaps `run_type` into `date_range` for local rows.
- **Frontend:** `PromptTestView.jsx` swaps its `RUN_TYPES` picker for the two ranges, and
  `runTypeRanges.js` is deleted (the two ranges live in the view or a two-entry util).
- **Then Phase 4.**

### Option B: delete Prompt Test

- **Delete:** `PromptTestService`, `PromptTestController`, both prompt-test entities and
  repositories, `PromptTestView`, its API module, and their tests.
- **V196:** clears the prompt-test rows and leaves the tables.
- **Next release:** a later migration drops `prompt_test_result` and `prompt_test_run` (the rollback
  rule).
- **When to choose it:** if the harness has not been used since the move to batch.

## 8. Owner decisions (all made 2026-10-28)

- **(a) Run-complete banner: remove it.** Phase 1 deletes the frontend, and Phase 2 deletes
  `/run/notifications`. Follow the "If removed" branches in §3 and §4.
- **(b) Forecast-result notification channels: delete them**, in Phase 2 (§4), including the
  CLAUDE.md overview line.
- **(c) Prompt Test: Option B, delete it.** The code goes in Phase 3, the tables in Phase 4 (§6, §7).
- **Also accepted:**
  - The map popup loses ad-hoc single-slot re-evaluation; JFDI Batch is the remaining way to force
    fresh scores.
  - Phase 3's cascade deletes the `api_call_log` rows of past manual runs.

## 9. Phase order, PRs, gates

Phase 1 → 2 → 3 → 4, one PR each. Phase 4 ships in the release after Phase 3. Each branches from the updated `main` after the
previous one merges, under CLAUDE.md's build-and-merge rule: Sonnet builds; local gates; ready PR; CI
green; Codex 👍 on the head commit.

Each phase is independently shippable:
- Phase 1 leaves the engine dormant.
- Phase 2 leaves only enum constants and rows behind.
- Phase 3 is the first with irreversible data deletion, and comes when nothing writes the old values.

Each phase adds a `changelog.d/` entry:
- Phase 1: `### Removed — admin manual forecast runs and optimisation strategy controls`
- Phase 2: `### Removed — synchronous forecast engine`
- Phase 3: `### Removed — VERY_SHORT_TERM, SHORT_TERM and LONG_TERM run types`
- Phase 3 adds a second entry: `### Removed — Prompt Test harness`
- Phase 4: `### Removed — prompt test tables`

**Gates**
- Backend: the full ladder, ending with `clean verify` excluding `integration/**`. Migrations are
  proven only in CI.
- Frontend: `npm run lint && npm test && npm audit --audit-level=high && npm run build`.

**Phase 1 browser check** (UI work), against a local backend on 8083:
- Job Runs:
  - it renders, and its filter works and lists no term or `BATCH_*` options;
  - Tide shows its status line;
  - Light Pollution shows progress in `RunProgressPanel` with no retry button.
- Run Config:
  - it opens on Briefing;
  - its remaining tabs save;
  - no Cost Optimisation section shows.
- The map popup renders for an admin with no Run Forecast button.
- Under §8a "remove", no run-complete banner appears.

Say plainly which of these were seen in the browser and which were only tested.

## 10. Adversarial review (2026-10-28)

Four prosecutor lenses ran read-only against the first draft: backend deletion correctness, data and
migrations, frontend, and phasing/tests/docs. They raised 43 charges. The planner re-verified the
consequential ones in the tree: tide has no `jobRunId`; `PromptReplayHarnessTest:300` calls
`checkStopReason`; three tests construct `ForecastController` directly; the migration test imports
`OptimisationStrategyType`; prod runs `ddl-auto: validate`; and `NotificationDispatcher` has one
consumer. All charges were accepted and are folded in above. Duplicate charges across lenses were
merged.

**Blocking finds in the first draft:**
1. "Narrow or delete" `ClaudeEvaluationStrategy.checkStopReason` would have broken Aurora, Ask and
   Best Bet, and a prompt-regression class.
2. Deleting `OptimisationStrategyType` and dropping its table would have broken
   `RetireDeadOptimisationStrategiesMigrationTest`, both at compile time and in CI.
3. Phase 1 changes `ForecastController`'s constructor, which three tests call directly; the first
   draft listed them only under Phase 3.
4. Two frontend test files that exercise the removed button were missing from the plan.
5. The browser check claimed Tide shows progress; it cannot.

**Two finds changed the design:**
- Rollback safety under `ddl-auto: validate` moved the `optimisation_strategy` drop to Phase 3, and
  the `prompt_test_run.run_type` drop to a new Phase 4.
- The dead notification channels and the everyone-visible run-complete banner became owner decisions
  §8a and §8b.

**Corrected premises.** The prompt-regression classes live in `service/evaluation`, `service/ask` and
`eval`, not in a `regression/` directory.
