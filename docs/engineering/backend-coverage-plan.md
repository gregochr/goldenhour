# Backend coverage: close the branch gap (plan, 2026-11-05)

This follows the frontend series (#1685, #1689, #1696, #1697, #1699), which ended at every file ≥95% lines
and ≥90% branches plus a changed-lines check. It applies the same standard to the backend.

## Where it stands

Measured from CI's `jacoco-report` artifact on `main` (run 41984352848). That run includes the Testcontainers
integration tests, which local runs exclude, so local figures are slightly lower than CI's.

- 1,443 classes: 97.62% lines, 90.72% branches, 97.91% instructions.
- The only gate today is `jacoco:check`: CLASS, LINE, COVEREDRATIO ≥ 0.80. There is no branch rule.
- 201 classes are below 95% lines or below 90% branches. Between them they miss 1,288 branches and 768 lines.

## Rules for every test phase

- Follow `docs/engineering/test-improvement-standards.md`. Every test asserts behaviour, with exact inputs and
  outputs. A test that only executes lines does not count.
- A branch that guards real behaviour must be tested: a retry, a fallback, a stale or partial response, a
  null from an external API, a race, a guard on a role or a state.
- A branch that truly cannot be reached is recorded rather than "covered". Examples are a defensive null that
  the type system rules out, or a `default` in an exhaustive switch. Record it in the PR body, with the class,
  branch and reason; it becomes an override in phase B7. Don't add tests that reach into private internals
  just to light a branch.
- No production code changes. If a test exposes a real bug, report it and don't fix it in a test PR.
- No changes to prompt-regression tests (`src/test/java/.../regression/`).
- Gate: the full local ladder (`./mvnw clean verify ... -Dtest='!**/integration/**'`), exit 0.

## Phases (one PR each)

Classes are grouped by package so that phases don't touch the same test files. B1–B6 are test-only and can
be written in parallel, at most two at a time, because each `mvn verify` is CPU-heavy. Each is rebased onto
`main` before merge.

- **B1**: `service.evaluation`, `service.evaluation.visitor`.
- **B2**: `service.ask`, `service.runs`, `service.comingup`, `service.notification`.
- **B3**: `service.batch`, `service.pipeline`, `service.aurora`.
- **B4**: `client`, `controller`, `config`, `entity`, `model`, `model.comingup`, `repository`, `util`, `eval`
  and the root package.
- **B5 and B6**: the flat `service` package (80 classes), split alphabetically by missed-branch count.
- **B7: the floor.**
  - Add a CLASS BRANCH COVEREDRATIO rule to `jacoco:check`.
  - Raise CLASS LINE from 0.80 to the highest figure the data honestly supports. The target is 95% lines and
    90% branches per class.
  - Classes with recorded unreachable branches get their own `<rule>` at their measured figure, each with a
    one-line reason in an XML comment. Don't lower the default to fit them.
  - Prove the gate bites: a class falling below its floor must fail `verify`.
  - Add a BUNDLE rule at the measured totals, rounded down.
- **B8: backend changed-lines check.** Run `diff-cover==10.0.0` over `backend/target/site/jacoco/jacoco.xml`
  in the backend CI job on pull requests, `--fail-under=90`, as the frontend does in #1697. Check how
  diff-cover maps JaCoCo's package and source paths to `backend/src/main/java`, because the frontend needed an
  `SF:` rewrite. Prove it in CI with a throwaway commit. Also update `dev-and-testing.md`, CLAUDE.md's backend
  gate line and the "How it's built" landing text (server: 80% per class becomes the new figures), in the
  page's existing voice.
  **Done.** The series' final figures: per class 95% lines / 90% branches with 7 overrides, bundle 99.3% lines /
  95.8% branches measured locally, and changed lines 90%. The step runs `diff-cover` from the repo root with
  `--src-roots backend/src/main/java` (JaCoCo's sourcefile paths are package-relative).

## Classes below 95% lines or 90% branches, by phase (CI figures)

### B1 (19 classes)

- `com.gregochr.goldenhour.service.evaluation.visitor.BluebellVisitor`: lines 91.7% (1 missed), branches 83.3% (2 missed)
- `com.gregochr.goldenhour.service.evaluation.ForecastResultHandler`: lines 90.6% (22 missed), branches 77.6% (22 missed)
- `com.gregochr.goldenhour.service.evaluation.SupersedingDispositionService`: lines 93.2% (4 missed), branches 77.8% (8 missed)
- `com.gregochr.goldenhour.service.evaluation.BestBetPickValidator`: lines 97.4% (1 missed), branches 85.3% (5 missed)
- `com.gregochr.goldenhour.service.evaluation.CustomIdFactory`: lines 95.3% (6 missed), branches 82.6% (8 missed)
- `com.gregochr.goldenhour.service.evaluation.BestBetEnricher`: lines 88.2% (6 missed), branches 77.8% (4 missed)
- `com.gregochr.goldenhour.service.evaluation.AuroraGlossService`: lines 92.9% (7 missed), branches 93.3% (2 missed)
- `com.gregochr.goldenhour.service.evaluation.BestBetResponseParser`: lines 92.3% (6 missed), branches 81.2% (9 missed)
- `com.gregochr.goldenhour.service.evaluation.EvaluationTask.Forecast`: lines 95.2% (1 missed), branches 80.0% (1 missed)
- `com.gregochr.goldenhour.service.evaluation.MetricsLoggingDecorator`: lines 88.6% (5 missed), branches 50.0% (6 missed)
- `com.gregochr.goldenhour.service.evaluation.EvaluationAbandonmentService`: lines 97.3% (1 missed), branches 80.0% (2 missed)
- `com.gregochr.goldenhour.service.evaluation.PromptBuilder`: lines 100.0% (0 missed), branches 88.8% (18 missed)
- `com.gregochr.goldenhour.service.evaluation.AuroraResultHandler`: lines 90.7% (8 missed), branches 76.7% (7 missed)
- `com.gregochr.goldenhour.service.evaluation.EvaluationServiceImpl`: lines 95.4% (7 missed), branches 84.8% (7 missed)
- `com.gregochr.goldenhour.service.evaluation.AuroraRegionSelector`: lines 100.0% (0 missed), branches 75.0% (5 missed)
- `com.gregochr.goldenhour.service.evaluation.BestBetRanker`: lines 94.3% (6 missed), branches 79.0% (21 missed)
- `com.gregochr.goldenhour.service.evaluation.BriefingRollupBuilder`: lines 99.0% (3 missed), branches 89.3% (18 missed)
- `com.gregochr.goldenhour.service.evaluation.ParallelGlossExecutor`: lines 90.3% (3 missed), branches 100.0% (0 missed)
- `com.gregochr.goldenhour.service.evaluation.WoodlandPromptBuilder`: lines 98.4% (1 missed), branches 66.7% (6 missed)

### B2 (25 classes)

- `com.gregochr.goldenhour.service.notification.UserEmailService`: lines 93.4% (13 missed), branches 96.9% (1 missed)
- `com.gregochr.goldenhour.service.ask.AskThreadContext`: lines 98.7% (2 missed), branches 87.9% (14 missed)
- `com.gregochr.goldenhour.service.ask.AskTools.NightRow`: lines 100.0% (0 missed), branches 75.0% (2 missed)
- `com.gregochr.goldenhour.service.ask.AskSnapshotBuilder`: lines 98.5% (4 missed), branches 88.7% (22 missed)
- `com.gregochr.goldenhour.service.ask.AskSnapshot.Nights`: lines 87.5% (1 missed), branches 87.5% (1 missed)
- `com.gregochr.goldenhour.service.ask.AskReadyResponse.Answer`: lines 100.0% (0 missed), branches 50.0% (1 missed)
- `com.gregochr.goldenhour.service.ask.KeywordAskIntentMatcher`: lines 100.0% (0 missed), branches 80.4% (11 missed)
- `com.gregochr.goldenhour.service.ask.AskRun`: lines 100.0% (0 missed), branches 50.0% (1 missed)
- `com.gregochr.goldenhour.service.ask.ClaudeAskEngine`: lines 92.0% (17 missed), branches 85.7% (15 missed)
- `com.gregochr.goldenhour.service.ask.AskResponse`: lines 100.0% (0 missed), branches 50.0% (4 missed)
- `com.gregochr.goldenhour.service.ask.AskSnapshot.ComingUp`: lines 100.0% (0 missed), branches 50.0% (1 missed)
- `com.gregochr.goldenhour.service.ask.AskScope`: lines 100.0% (0 missed), branches 87.5% (3 missed)
- `com.gregochr.goldenhour.service.ask.AskSpendGuard`: lines 84.2% (6 missed), branches 87.5% (2 missed)
- `com.gregochr.goldenhour.service.ask.AskSnapshot.Topic`: lines 94.7% (1 missed), branches 91.7% (2 missed)
- `com.gregochr.goldenhour.service.ask.AskLocationNames.new AskLocationNames() {...}`: lines 100.0% (0 missed), branches 50.0% (1 missed)
- `com.gregochr.goldenhour.service.ask.AskSnapshot.LaterWindow`: lines 100.0% (0 missed), branches 50.0% (1 missed)
- `com.gregochr.goldenhour.service.ask.AskSnapshot.Night`: lines 100.0% (0 missed), branches 50.0% (1 missed)
- `com.gregochr.goldenhour.service.ask.AskOutcome`: lines 100.0% (0 missed), branches 50.0% (1 missed)
- `com.gregochr.goldenhour.service.ask.AskEvidence.ThreadFact`: lines 100.0% (0 missed), branches 88.5% (3 missed)
- `com.gregochr.goldenhour.service.ask.StubAskEngine.Found`: lines 100.0% (0 missed), branches 70.0% (3 missed)
- `com.gregochr.goldenhour.service.runs.RunStatsLoader`: lines 98.0% (2 missed), branches 73.9% (12 missed)
- `com.gregochr.goldenhour.service.runs.RunsCostService`: lines 100.0% (0 missed), branches 89.7% (8 missed)
- `com.gregochr.goldenhour.service.runs.RunsLoader`: lines 96.8% (5 missed), branches 88.0% (13 missed)
- `com.gregochr.goldenhour.service.runs.CycleFactsLoader`: lines 97.6% (1 missed), branches 87.5% (3 missed)
- `com.gregochr.goldenhour.service.comingup.ComingUpRoleShaper`: lines 95.5% (1 missed), branches 88.9% (2 missed)

### B3 (16 classes)

- `com.gregochr.goldenhour.service.pipeline.PipelineOrchestrator`: lines 93.1% (24 missed), branches 86.9% (16 missed)
- `com.gregochr.goldenhour.service.pipeline.PipelineOrchestrator.BatchCompletionResult`: lines 87.5% (1 missed), branches 87.5% (1 missed)
- `com.gregochr.goldenhour.service.pipeline.BestBetFallbackService`: lines 84.9% (13 missed), branches 68.0% (16 missed)
- `com.gregochr.goldenhour.service.pipeline.PipelineRunComparisonService`: lines 95.2% (3 missed), branches 68.8% (15 missed)
- `com.gregochr.goldenhour.service.pipeline.PipelineRunPickService`: lines 98.8% (1 missed), branches 83.3% (6 missed)
- `com.gregochr.goldenhour.service.batch.BatchRetryService`: lines 95.4% (6 missed), branches 86.4% (6 missed)
- `com.gregochr.goldenhour.service.batch.ForceSubmitBatchService`: lines 94.4% (3 missed), branches 76.9% (6 missed)
- `com.gregochr.goldenhour.service.batch.BatchResultProcessor`: lines 87.0% (57 missed), branches 82.8% (27 missed)
- `com.gregochr.goldenhour.service.batch.ForceEvalHeadlineSelector`: lines 95.6% (2 missed), branches 87.5% (4 missed)
- `com.gregochr.goldenhour.service.batch.IntradayCandidateCollectionStrategy`: lines 100.0% (0 missed), branches 88.9% (2 missed)
- `com.gregochr.goldenhour.service.batch.BatchSubmissionService`: lines 96.2% (2 missed), branches 87.5% (1 missed)
- `com.gregochr.goldenhour.service.batch.ScheduledBatchTasks`: lines 100.0% (0 missed), branches 78.6% (3 missed)
- `com.gregochr.goldenhour.service.batch.ForecastDispositionService`: lines 100.0% (0 missed), branches 87.5% (4 missed)
- `com.gregochr.goldenhour.service.batch.AnthropicBatchClient`: lines 95.5% (5 missed), branches 87.5% (4 missed)
- `com.gregochr.goldenhour.service.batch.ScheduledBatchEvaluationService`: lines 92.9% (28 missed), branches 86.0% (23 missed)
- `com.gregochr.goldenhour.service.aurora.ClaudeAuroraInterpreter`: lines 90.8% (17 missed), branches 83.9% (10 missed)

### B4 (61 classes)

- `com.gregochr.goldenhour.util.Rewind`: lines 90.0% (1 missed), branches 75.0% (1 missed)
- `com.gregochr.goldenhour.util.DayScopedMemo`: lines 100.0% (0 missed), branches 87.5% (1 missed)
- `com.gregochr.goldenhour.client.NoaaSwpcClient`: lines 88.7% (39 missed), branches 73.8% (45 missed)
- `com.gregochr.goldenhour.client.EnvironmentAgencyGaugeClient`: lines 100.0% (0 missed), branches 75.0% (3 missed)
- `com.gregochr.goldenhour.client.NlcSightingClient`: lines 97.9% (3 missed), branches 75.9% (27 missed)
- `com.gregochr.goldenhour.client.OpenRouteServiceClient`: lines 100.0% (0 missed), branches 85.7% (2 missed)
- `com.gregochr.goldenhour.eval.SkyRatingEvalFixtures`: lines 91.9% (3 missed), branches 91.7% (1 missed)
- `com.gregochr.goldenhour.config.HttpCachingConfig.RevalidatableReadEtagFilter`: lines 83.3% (2 missed), branches 50.0% (6 missed)
- `com.gregochr.goldenhour.config.OpenMeteoHealthIndicator`: lines 96.8% (1 missed), branches 50.0% (3 missed)
- `com.gregochr.goldenhour.config.OrsProperties`: lines 88.9% (1 missed), branches 66.7% (2 missed)
- `com.gregochr.goldenhour.config.SecurityConfig`: lines 100.0% (0 missed), branches 50.0% (2 missed)
- `com.gregochr.goldenhour.config.AskBodyLimitFilter.LimitedStream`: lines 90.0% (2 missed), branches 100.0% (0 missed)
- `com.gregochr.goldenhour.config.AskAdmissionInterceptor`: lines 100.0% (0 missed), branches 75.0% (2 missed)
- `com.gregochr.goldenhour.config.RequestLoggingInterceptor`: lines 100.0% (0 missed), branches 78.6% (3 missed)
- `com.gregochr.goldenhour.config.AskBodyLimitFilter.LimitedRequest`: lines 92.3% (1 missed), branches 100.0% (0 missed)
- `com.gregochr.goldenhour.config.ClaudeApiHealthIndicator`: lines 91.7% (3 missed), branches 100.0% (0 missed)
- `com.gregochr.goldenhour.config.RewindAwareClock`: lines 90.9% (1 missed), branches 100.0% (0 missed)
- `com.gregochr.goldenhour.controller.AuthController`: lines 92.6% (16 missed), branches 83.1% (15 missed)
- `com.gregochr.goldenhour.controller.AuroraForecastController`: lines 100.0% (0 missed), branches 50.0% (1 missed)
- `com.gregochr.goldenhour.controller.SkyRatingEvalController`: lines 93.5% (2 missed), branches 80.0% (2 missed)
- `com.gregochr.goldenhour.controller.JobMetricsController`: lines 100.0% (0 missed), branches 88.9% (2 missed)
- `com.gregochr.goldenhour.controller.StatusController`: lines 96.3% (5 missed), branches 84.5% (9 missed)
- `com.gregochr.goldenhour.controller.AskController`: lines 100.0% (0 missed), branches 75.0% (1 missed)
- `com.gregochr.goldenhour.controller.AdvisorReplayController`: lines 93.3% (2 missed), branches 83.3% (3 missed)
- `com.gregochr.goldenhour.controller.HotTopicSimulationController`: lines 100.0% (0 missed), branches 75.0% (1 missed)
- `com.gregochr.goldenhour.controller.BatchAdminController`: lines 100.0% (0 missed), branches 87.5% (2 missed)
- `com.gregochr.goldenhour.controller.UserController`: lines 100.0% (0 missed), branches 82.9% (13 missed)
- `com.gregochr.goldenhour.controller.PipelineRunController`: lines 100.0% (0 missed), branches 83.3% (1 missed)
- `com.gregochr.goldenhour.controller.SchedulerController`: lines 100.0% (0 missed), branches 89.1% (5 missed)
- `com.gregochr.goldenhour.controller.AuroraAdminController`: lines 100.0% (0 missed), branches 87.5% (2 missed)
- `com.gregochr.goldenhour.controller.GlobalExceptionHandler`: lines 95.8% (2 missed), branches 66.7% (2 missed)
- `com.gregochr.goldenhour.controller.AccountController`: lines 96.2% (1 missed), branches 85.0% (3 missed)
- `com.gregochr.goldenhour.entity.SchedulerJobConfigEntity`: lines 93.8% (1 missed), branches 81.2% (3 missed)
- `com.gregochr.goldenhour.entity.UserDriveTimeId`: lines 15.4% (11 missed), branches 0.0% (8 missed)
- `com.gregochr.goldenhour.entity.AreaEntity`: lines 100.0% (0 missed), branches 66.7% (2 missed)
- `com.gregochr.goldenhour.entity.ForecastScoreEntity`: lines 100.0% (0 missed), branches 50.0% (2 missed)
- `com.gregochr.goldenhour.entity.SchedulerJobConfigEntity.SchedulerJobConfigEntityBuilder`: lines 100.0% (0 missed), branches 75.0% (1 missed)
- `com.gregochr.goldenhour.entity.LocationAliasId`: lines 91.7% (1 missed), branches 87.5% (1 missed)
- `com.gregochr.goldenhour.repository.AppUserRepository.LockedAccount`: lines 100.0% (0 missed), branches 75.0% (3 missed)
- `com.gregochr.goldenhour.repository.AppUserRepository.DormancyState`: lines 100.0% (0 missed), branches 78.6% (3 missed)
- `com.gregochr.goldenhour.GoldenHourApplication`: lines 33.3% (2 missed), branches 100.0% (0 missed)
- `com.gregochr.goldenhour.model.comingup.ComingUpResponse`: lines 100.0% (0 missed), branches 50.0% (1 missed)
- `com.gregochr.goldenhour.model.comingup.ComingUpEntry`: lines 100.0% (0 missed), branches 50.0% (6 missed)
- `com.gregochr.goldenhour.model.comingup.ComingUpFact`: lines 100.0% (0 missed), branches 50.0% (1 missed)
- `com.gregochr.goldenhour.model.CacheDiagnostics`: lines 89.5% (2 missed), branches 89.5% (2 missed)
- `com.gregochr.goldenhour.model.SolarCloudTrend.SolarCloudSlot`: lines 100.0% (0 missed), branches 75.0% (1 missed)
- `com.gregochr.goldenhour.model.AuroraForecastRunRequest`: lines 100.0% (0 missed), branches 50.0% (1 missed)
- `com.gregochr.goldenhour.model.AuroraForecastPreview`: lines 100.0% (0 missed), branches 50.0% (1 missed)
- `com.gregochr.goldenhour.model.BriefingDayPeak`: lines 100.0% (0 missed), branches 50.0% (2 missed)
- `com.gregochr.goldenhour.model.CalibrationPair`: lines 81.8% (2 missed), branches 100.0% (0 missed)
- `com.gregochr.goldenhour.model.BestBetResult`: lines 100.0% (0 missed), branches 50.0% (1 missed)
- `com.gregochr.goldenhour.model.ForecastDtoMapper`: lines 96.7% (8 missed), branches 80.9% (29 missed)
- `com.gregochr.goldenhour.model.AuroraForecastRunResponse`: lines 100.0% (0 missed), branches 50.0% (1 missed)
- `com.gregochr.goldenhour.model.SlotSignals.Readings`: lines 84.6% (2 missed), branches 100.0% (0 missed)
- `com.gregochr.goldenhour.model.CloudVerificationReport`: lines 100.0% (0 missed), branches 50.0% (5 missed)
- `com.gregochr.goldenhour.model.CalibrationReport`: lines 100.0% (0 missed), branches 50.0% (5 missed)
- `com.gregochr.goldenhour.model.BackfillBatch`: lines 100.0% (0 missed), branches 75.0% (1 missed)
- `com.gregochr.goldenhour.model.MarineWaveSample`: lines 100.0% (0 missed), branches 50.0% (1 missed)
- `com.gregochr.goldenhour.model.ExpandedHotTopicDetail`: lines 100.0% (0 missed), branches 50.0% (1 missed)
- `com.gregochr.goldenhour.model.SurgeCurve`: lines 88.9% (1 missed), branches 62.5% (3 missed)
- `com.gregochr.goldenhour.model.ForecastPreEvalResult`: lines 100.0% (0 missed), branches 50.0% (1 missed)

### B5 (34 classes)

- `com.gregochr.goldenhour.service.AstroConditionsService`: lines 90.6% (20 missed), branches 85.0% (15 missed)
- `com.gregochr.goldenhour.service.AuroraWeatherEnricher`: lines 100.0% (0 missed), branches 81.8% (4 missed)
- `com.gregochr.goldenhour.service.AuthAttemptLimiter`: lines 100.0% (0 missed), branches 85.7% (2 missed)
- `com.gregochr.goldenhour.service.BatchSummaryDeriver`: lines 89.2% (8 missed), branches 56.8% (19 missed)
- `com.gregochr.goldenhour.service.BluebellHotTopicStrategy`: lines 96.8% (4 missed), branches 84.0% (13 missed)
- `com.gregochr.goldenhour.service.BriefingAuroraSummaryBuilder`: lines 90.7% (21 missed), branches 73.6% (28 missed)
- `com.gregochr.goldenhour.service.BriefingEvaluationService`: lines 94.6% (17 missed), branches 90.8% (11 missed)
- `com.gregochr.goldenhour.service.BriefingHonestyFilter`: lines 100.0% (0 missed), branches 86.7% (6 missed)
- `com.gregochr.goldenhour.service.BriefingRegionSnapshotService`: lines 98.5% (1 missed), branches 86.1% (5 missed)
- `com.gregochr.goldenhour.service.BriefingService`: lines 88.8% (36 missed), branches 84.0% (17 missed)
- `com.gregochr.goldenhour.service.BriefingService.InFlight`: lines 100.0% (0 missed), branches 66.7% (2 missed)
- `com.gregochr.goldenhour.service.BriefingSlotBuilder`: lines 98.6% (3 missed), branches 89.9% (13 missed)
- `com.gregochr.goldenhour.service.BriefingWeatherLoader`: lines 98.3% (2 missed), branches 85.3% (5 missed)
- `com.gregochr.goldenhour.service.BriefingWeatherLoader.HorizonCloudData`: lines 83.3% (1 missed), branches 50.0% (2 missed)
- `com.gregochr.goldenhour.service.CloudVerificationBackfillRunner`: lines 94.4% (4 missed), branches 94.4% (1 missed)
- `com.gregochr.goldenhour.service.CloudVerificationService`: lines 96.7% (10 missed), branches 83.9% (28 missed)
- `com.gregochr.goldenhour.service.CoastalTideFactsBuilder`: lines 96.1% (3 missed), branches 72.5% (11 missed)
- `com.gregochr.goldenhour.service.CostCalculator`: lines 91.5% (5 missed), branches 89.1% (5 missed)
- `com.gregochr.goldenhour.service.CycleLocationOutcomeResolver`: lines 92.0% (8 missed), branches 95.2% (2 missed)
- `com.gregochr.goldenhour.service.DustFactsBuilder`: lines 96.9% (1 missed), branches 75.0% (4 missed)
- `com.gregochr.goldenhour.service.DynamicSchedulerService`: lines 98.0% (9 missed), branches 87.9% (24 missed)
- `com.gregochr.goldenhour.service.EclipseAlmanacSource`: lines 98.0% (1 missed), branches 87.5% (2 missed)
- `com.gregochr.goldenhour.service.EclipseHotTopicStrategy`: lines 96.6% (3 missed), branches 76.9% (6 missed)
- `com.gregochr.goldenhour.service.EclipseSightAssembler`: lines 98.4% (1 missed), branches 89.3% (3 missed)
- `com.gregochr.goldenhour.service.EquinoxHotTopicStrategy`: lines 94.7% (2 missed), branches 93.3% (2 missed)
- `com.gregochr.goldenhour.service.ExchangeRateService`: lines 97.4% (1 missed), branches 70.0% (3 missed)
- `com.gregochr.goldenhour.service.ForecastCycleWatchdog`: lines 98.9% (2 missed), branches 89.8% (12 missed)
- `com.gregochr.goldenhour.service.ForecastDataAugmentor`: lines 94.1% (12 missed), branches 81.2% (26 missed)
- `com.gregochr.goldenhour.service.ForecastService`: lines 100.0% (0 missed), branches 88.6% (5 missed)
- `com.gregochr.goldenhour.service.ForecastStabilityClassifier`: lines 98.9% (1 missed), branches 87.9% (8 missed)
- `com.gregochr.goldenhour.service.GitInfoService`: lines 88.9% (6 missed), branches 86.4% (3 missed)
- `com.gregochr.goldenhour.service.HotTopicAggregator`: lines 96.9% (1 missed), branches 80.0% (2 missed)
- `com.gregochr.goldenhour.service.HotTopicEventEnricher`: lines 93.3% (7 missed), branches 82.1% (15 missed)
- `com.gregochr.goldenhour.service.HotTopicGlanceDecorator`: lines 98.8% (1 missed), branches 87.5% (11 missed)

### B6 (46 classes)

- `com.gregochr.goldenhour.service.InversionHotTopicStrategy`: lines 96.7% (1 missed), branches 83.3% (2 missed)
- `com.gregochr.goldenhour.service.InversionProfileFetcher`: lines 100.0% (0 missed), branches 87.5% (3 missed)
- `com.gregochr.goldenhour.service.JobRunService`: lines 92.5% (22 missed), branches 93.5% (3 missed)
- `com.gregochr.goldenhour.service.JwtService`: lines 94.7% (2 missed), branches 100.0% (0 missed)
- `com.gregochr.goldenhour.service.KingTideHotTopicStrategy`: lines 97.3% (4 missed), branches 84.1% (14 missed)
- `com.gregochr.goldenhour.service.LitePickShaper`: lines 98.4% (2 missed), branches 85.3% (15 missed)
- `com.gregochr.goldenhour.service.MarineClient`: lines 98.5% (1 missed), branches 86.8% (9 missed)
- `com.gregochr.goldenhour.service.MeteorHotTopicStrategy`: lines 100.0% (0 missed), branches 87.5% (4 missed)
- `com.gregochr.goldenhour.service.NlcClarityService`: lines 100.0% (0 missed), branches 80.0% (4 missed)
- `com.gregochr.goldenhour.service.NlcHotTopicStrategy`: lines 100.0% (0 missed), branches 86.4% (3 missed)
- `com.gregochr.goldenhour.service.NlcSightingService`: lines 98.3% (1 missed), branches 87.5% (3 missed)
- `com.gregochr.goldenhour.service.NlcTwilightWindowCalculator`: lines 92.0% (6 missed), branches 76.2% (10 missed)
- `com.gregochr.goldenhour.service.NlcTwilightWindowCalculator.NlcWindows`: lines 100.0% (0 missed), branches 25.0% (3 missed)
- `com.gregochr.goldenhour.service.NorthwardTransectSampler`: lines 95.9% (5 missed), branches 88.2% (9 missed)
- `com.gregochr.goldenhour.service.OpenMeteoArchiveClient`: lines 87.3% (10 missed), branches 84.6% (4 missed)
- `com.gregochr.goldenhour.service.OpenMeteoResponseParser`: lines 99.4% (1 missed), branches 88.3% (14 missed)
- `com.gregochr.goldenhour.service.OpenMeteoService`: lines 97.7% (9 missed), branches 85.3% (17 missed)
- `com.gregochr.goldenhour.service.OverheadCloudSampler`: lines 98.6% (1 missed), branches 78.9% (8 missed)
- `com.gregochr.goldenhour.service.PasswordResetIssuer`: lines 100.0% (0 missed), branches 87.5% (1 missed)
- `com.gregochr.goldenhour.service.PendingRegistrationWriter`: lines 100.0% (0 missed), branches 85.7% (2 missed)
- `com.gregochr.goldenhour.service.PickScope`: lines 100.0% (0 missed), branches 87.5% (2 missed)
- `com.gregochr.goldenhour.service.RunCompletion.new LinkedHashMap() {...}`: lines 100.0% (0 missed), branches 50.0% (1 missed)
- `com.gregochr.goldenhour.service.RunProgressTracker`: lines 93.8% (14 missed), branches 86.2% (8 missed)
- `com.gregochr.goldenhour.service.SkyRatingEvalBatchService`: lines 95.0% (11 missed), branches 90.0% (8 missed)
- `com.gregochr.goldenhour.service.SlotSignalReader.Accumulator`: lines 100.0% (0 missed), branches 85.7% (2 missed)
- `com.gregochr.goldenhour.service.SnowTopsHotTopicStrategy`: lines 96.4% (1 missed), branches 72.2% (5 missed)
- `com.gregochr.goldenhour.service.SolarOffsetTrigger`: lines 91.6% (7 missed), branches 86.4% (6 missed)
- `com.gregochr.goldenhour.service.SolarSchedulePreviewService`: lines 100.0% (0 missed), branches 88.6% (5 missed)
- `com.gregochr.goldenhour.service.SpringTideHotTopicStrategy`: lines 91.7% (6 missed), branches 70.6% (10 missed)
- `com.gregochr.goldenhour.service.StormSurgeFactsBuilder`: lines 96.7% (2 missed), branches 85.4% (7 missed)
- `com.gregochr.goldenhour.service.SubRegionMembershipService`: lines 96.7% (2 missed), branches 81.2% (6 missed)
- `com.gregochr.goldenhour.service.SupermoonAlmanacSource`: lines 100.0% (0 missed), branches 89.3% (3 missed)
- `com.gregochr.goldenhour.service.SupermoonHotTopicStrategy`: lines 94.9% (3 missed), branches 84.6% (4 missed)
- `com.gregochr.goldenhour.service.SurgeCurveService`: lines 92.6% (6 missed), branches 75.0% (16 missed)
- `com.gregochr.goldenhour.service.SurgeRunDayBuilder`: lines 94.7% (4 missed), branches 77.1% (11 missed)
- `com.gregochr.goldenhour.service.TideFetchRecorder`: lines 88.2% (2 missed), branches 50.0% (1 missed)
- `com.gregochr.goldenhour.service.TideFetchRecorder.new TransactionSynchronization() {...}`: lines 100.0% (0 missed), branches 75.0% (1 missed)
- `com.gregochr.goldenhour.service.TideRunBuilder`: lines 94.9% (13 missed), branches 86.9% (22 missed)
- `com.gregochr.goldenhour.service.TideSizeIndex`: lines 96.1% (3 missed), branches 80.0% (10 missed)
- `com.gregochr.goldenhour.service.TodaysLightService`: lines 98.5% (1 missed), branches 64.7% (12 missed)
- `com.gregochr.goldenhour.service.TopicDailyLogJob`: lines 95.3% (11 missed), branches 83.9% (18 missed)
- `com.gregochr.goldenhour.service.TravelDayService`: lines 100.0% (0 missed), branches 81.2% (3 missed)
- `com.gregochr.goldenhour.service.UserSettingsService.PendingAttempt`: lines 100.0% (0 missed), branches 75.0% (1 missed)
- `com.gregochr.goldenhour.service.VerificationTokenIssuer`: lines 100.0% (0 missed), branches 87.5% (1 missed)
- `com.gregochr.goldenhour.service.WindowTideRollupBuilder`: lines 96.2% (6 missed), branches 85.9% (11 missed)
- `com.gregochr.goldenhour.service.WoodlandVerdictEvaluator`: lines 93.9% (2 missed), branches 93.3% (2 missed)

## B7 done (2026-11-05)

`jacoco:check` now holds every class to 95% of lines and 90% of branches, with a BUNDLE floor of 99.3% lines
and 95.8% branches (measured locally without the integration tests, rounded down). All the old `<excludes>`
are gone: with B1 to B6 in, every class they hid clears the default.

Overrides, each its own `<rule>` in `backend/pom.xml` at the measured figure rounded down, excluded from the default:

| Class | Lines / branches floor | Reason |
|---|---|---|
| `util.DayScopedMemo` | 100 / 87 | `generation` is bumped before `today.equals(day)`, so the false arm never runs |
| `config.OpenMeteoHealthIndicator` | 100 / 83 | the older-than-five-minutes path needs a real wait or the private `cachedAt` |
| `controller.AuroraForecastController` | 100 / 50 | the request constructor turns a null `nights` into an empty list |
| `service.SpringTideHotTopicStrategy` | 94 / 76 | all misses are in `isSpringNotKing`, a private method nothing calls |
| `service.JwtService` | 94 / 100 | the SHA-256 `NoSuchAlgorithmException` catch cannot run on a standard JVM |
| `service.SnowTopsHotTopicStrategy` | 96 / 77 | `attachFacts` guards repeat checks the detection filter already made |
| `service.InversionHotTopicStrategy` | 96 / 83 | `attachFacts` guards repeat checks the detection filter already made |
