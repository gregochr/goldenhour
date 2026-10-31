package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.model.ForecastDtoMapper;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AstroConditionsRepository;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.RefreshTokenRepository;
import com.gregochr.goldenhour.repository.WaitlistEmailRepository;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.HotTopicSimulationService;
import com.gregochr.goldenhour.service.BatchSummaryDeriver;
import com.gregochr.goldenhour.service.AlmanacService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.ReachService;
import com.gregochr.goldenhour.service.TodaysLightService;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.SolarSchedulePreviewService;
import com.gregochr.goldenhour.service.EvaluationViewService;
import com.gregochr.goldenhour.service.CloudVerificationBackfillRunner;
import com.gregochr.goldenhour.service.CloudVerificationService;
import com.gregochr.goldenhour.service.ForecastCalibrationService;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.GitInfoService;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.LocationEnrichmentService;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.NlcSightingService;
import com.gregochr.goldenhour.service.PasswordResetService;
import com.gregochr.goldenhour.service.RegistrationService;
import com.gregochr.goldenhour.service.RegionDriveDurationService;
import com.gregochr.goldenhour.service.RegionService;
import com.gregochr.goldenhour.service.RunProgressTracker;
import com.gregochr.goldenhour.service.ScheduledForecastService;
import com.gregochr.goldenhour.service.StabilitySnapshotProvider;
import com.gregochr.goldenhour.service.TideService;
import com.gregochr.goldenhour.service.TravelDayService;
import com.gregochr.goldenhour.service.AuthAttemptLimiter;
import com.gregochr.goldenhour.service.TurnstileGate;
import com.gregochr.goldenhour.service.TurnstileService;
import com.gregochr.goldenhour.service.UserService;
import com.gregochr.goldenhour.service.UserSettingsService;
import com.gregochr.goldenhour.service.batch.ForceSubmitBatchService;
import com.gregochr.goldenhour.service.batch.ForecastDispositionService;
import com.gregochr.goldenhour.service.batch.ScheduledBatchEvaluationService;
import com.gregochr.goldenhour.service.aurora.AuroraAlertSnapshotStore;
import com.gregochr.goldenhour.service.aurora.AuroraForecastRunService;
import com.gregochr.goldenhour.service.aurora.AuroraOrchestrator;
import com.gregochr.goldenhour.service.aurora.AuroraPollingJob;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.service.aurora.BortleEnrichmentService;
import com.gregochr.goldenhour.service.evaluation.BriefingBestBetAdvisor;
import com.gregochr.goldenhour.service.notification.UserEmailService;
import com.gregochr.goldenhour.service.pipeline.PipelineRunService;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Shared base class for Spring Boot controller integration tests.
 *
 * <p>Declares the full superset of {@link MockitoBean} mocks needed across all controller tests,
 * enabling a single application context to be shared across all subclasses.
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class AbstractControllerTest {

    // ── Repositories ──────────────────────────────────────────────────────────

    @MockitoBean
    protected ApiCallLogRepository apiCallLogRepository;

    @MockitoBean
    protected AppUserRepository userRepository;

    @MockitoBean
    protected AstroConditionsRepository astroConditionsRepository;

    @MockitoBean
    protected ForecastBatchRepository batchRepository;

    @MockitoBean
    protected ForecastEvaluationRepository forecastEvaluationRepository;

    @MockitoBean
    protected LocationRepository locationRepository;

    @MockitoBean
    protected RefreshTokenRepository refreshTokenRepository;

    @MockitoBean
    protected WaitlistEmailRepository waitlistEmailRepository;

    // ── Services ──────────────────────────────────────────────────────────────

    @MockitoBean
    protected BatchSummaryDeriver batchSummaryDeriver;

    @MockitoBean
    protected BriefingEvaluationService evaluationService;

    @MockitoBean
    protected HotTopicSimulationService hotTopicSimulationService;

    @MockitoBean
    protected BriefingService briefingService;

    @MockitoBean
    protected BriefingBestBetAdvisor bestBetAdvisor;

    @MockitoBean
    protected DriveTimeResolver driveTimeResolver;

    @MockitoBean
    protected ReachService reachService;

    @MockitoBean
    protected TodaysLightService todaysLightService;

    @MockitoBean
    protected DynamicSchedulerService schedulerService;

    @MockitoBean
    protected SolarSchedulePreviewService solarSchedulePreviewService;

    @MockitoBean
    protected EvaluationViewService evaluationViewService;

    @MockitoBean
    protected ForecastDtoMapper dtoMapper;

    @MockitoBean
    protected ForecastService forecastService;

    @MockitoBean
    protected GitInfoService gitInfoService;

    @MockitoBean
    protected JobRunService jobRunService;

    @MockitoBean
    protected LocationEnrichmentService locationEnrichmentService;

    @MockitoBean
    protected LocationService locationService;

    /** Backs {@code AreaController}. */
    @MockitoBean
    protected com.gregochr.goldenhour.service.AreaService areaService;

    /** Backs {@code LocationAliasController}. */
    @MockitoBean
    protected com.gregochr.goldenhour.service.LocationAliasService locationAliasService;

    @MockitoBean
    protected ModelSelectionService modelSelectionService;

    @MockitoBean
    protected NlcSightingService nlcSightingService;

    @MockitoBean
    protected PipelineRunService pipelineRunService;

    /** Builds the merged Runs list for {@code AdminRunsController}. */
    @MockitoBean
    protected com.gregochr.goldenhour.service.runs.AdminRunsService adminRunsService;

    @MockitoBean
    protected com.gregochr.goldenhour.service.pipeline.PipelineRunComparisonService
            pipelineRunComparisonService;

    @MockitoBean
    protected com.gregochr.goldenhour.service.SkyRatingEvalService skyRatingEvalService;

    @MockitoBean
    protected RegistrationService registrationService;

    @MockitoBean
    protected RegionService regionService;

    @MockitoBean
    protected RegionDriveDurationService regionDriveDurationService;

    @MockitoBean
    protected RunProgressTracker progressTracker;

    @MockitoBean
    protected ForceSubmitBatchService forceSubmitBatchService;

    @MockitoBean
    protected ForecastDispositionService dispositionService;

    @MockitoBean
    protected ScheduledBatchEvaluationService scheduledBatchEvaluationService;

    @MockitoBean
    protected ScheduledForecastService scheduledForecastService;

    @MockitoBean
    protected StabilitySnapshotProvider stabilitySnapshotProvider;

    @MockitoBean
    protected AlmanacService almanacService;

    @MockitoBean
    protected TideService tideService;

    @MockitoBean
    protected TravelDayService travelDayService;

    @MockitoBean
    protected TurnstileService turnstileService;

    /**
     * The Turnstile gate for login and change-password. Mocked because a real one holds counters
     * across test classes in this shared context, which would make results order-dependent; an
     * unstubbed mock answers null, so {@code AuthControllerTest} stubs its outcome in {@code setUp}.
     */
    @MockitoBean
    protected TurnstileGate turnstileGate;

    /**
     * The attempt counters. Mocked for the same reason; an unstubbed {@code tryAcquire} answers
     * false (refused), so the tests that reach a bound stub it explicitly.
     */
    @MockitoBean
    protected AuthAttemptLimiter attemptLimiter;

    /** The forgot/reset flow. Mocked: its own sequence runs in {@code PasswordResetFlowTest}. */
    @MockitoBean
    protected PasswordResetService passwordResetService;

    @MockitoBean
    protected UserEmailService userEmailService;

    @MockitoBean
    protected UserService userService;

    @MockitoBean
    protected UserSettingsService settingsService;

    /** The caller's sub-region picks and the admin tools over them. */
    @MockitoBean
    protected com.gregochr.goldenhour.service.UserSubRegionService userSubRegionService;

    /**
     * Enforcement flag for {@code LitePickShaper} (sub-regions-plan S3). A mock so a test can turn enforcement
     * on with {@code when(subRegionProperties.isEnforce()).thenReturn(true)}; it answers false (off) otherwise,
     * which is the committed default, and Mockito resets it after each test.
     */
    @MockitoBean
    protected com.gregochr.goldenhour.config.SubRegionProperties subRegionProperties;

    /** The admin-set Lite pick limit. */
    @MockitoBean
    protected com.gregochr.goldenhour.service.SubRegionPolicyService subRegionPolicyService;

    /**
     * Each location's sub-region, read in one query for {@code GET /api/locations}. A mock so a test can plant
     * memberships without a database; unstubbed it answers an empty map, so every place reads as unassigned.
     */
    @MockitoBean
    protected com.gregochr.goldenhour.service.SubRegionMembershipService subRegionMembershipService;

    @MockitoBean
    protected com.gregochr.goldenhour.service.ask.AskService askService;

    /** What a reader had been shown for a passed slot at its event — {@code ForecastController}'s serve path. */
    @MockitoBean
    protected com.gregochr.goldenhour.service.AsOfEventResolver asOfEventResolver;

    /** Records and reads per-user outcome ratings for {@code OutcomeRatingController}. */
    @MockitoBean
    protected com.gregochr.goldenhour.service.OutcomeRatingService outcomeRatingService;

    // ── Aurora services ───────────────────────────────────────────────────────

    /**
     * Aurora forecast run service.
     */
    @MockitoBean
    protected AuroraForecastRunService forecastRunService;

    @MockitoBean
    protected AuroraOrchestrator orchestrator;

    @MockitoBean
    protected AuroraPollingJob pollingJob;

    @MockitoBean
    protected AuroraStateCache stateCache;

    @MockitoBean
    protected AuroraAlertSnapshotStore auroraAlertSnapshotStore;

    /**
     * Bortle enrichment service — exposed as {@code enrichmentService} to match the field name
     * used in {@link AuroraAdminControllerTest} test assertions.
     */
    @MockitoBean
    protected BortleEnrichmentService enrichmentService;

    // ── Config ────────────────────────────────────────────────────────────────

    @MockitoBean
    protected AuroraProperties auroraProperties;

    // ── Clients ───────────────────────────────────────────────────────────────

    @MockitoBean
    protected NoaaSwpcClient noaaClient;

    /** Scores forecasts against recorded outcomes for {@code CalibrationController}. */
    @MockitoBean
    protected ForecastCalibrationService calibrationService;

    /** Scores forecasts against reanalysed cloud for {@code CloudVerificationController}. */
    @MockitoBean
    protected CloudVerificationService cloudVerificationService;

    /** Drives the background verification backfill for {@code CloudVerificationController}. */
    @MockitoBean
    protected CloudVerificationBackfillRunner cloudVerificationBackfillRunner;
}
