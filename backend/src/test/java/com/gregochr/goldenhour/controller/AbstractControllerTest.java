package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.model.ForecastDtoMapper;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AstroConditionsRepository;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.RefreshTokenRepository;
import com.gregochr.goldenhour.repository.WaitlistEmailRepository;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.HotTopicSimulationService;
import com.gregochr.goldenhour.service.BriefingModelTestService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.ForecastCommandExecutor;
import com.gregochr.goldenhour.service.ForecastCommandFactory;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.GitInfoService;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.LocationEnrichmentService;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.ModelTestService;
import com.gregochr.goldenhour.service.OptimisationStrategyService;
import com.gregochr.goldenhour.service.OutcomeService;
import com.gregochr.goldenhour.service.PromptTestService;
import com.gregochr.goldenhour.service.RegistrationService;
import com.gregochr.goldenhour.service.RegionService;
import com.gregochr.goldenhour.service.RunProgressTracker;
import com.gregochr.goldenhour.service.ScheduledForecastService;
import com.gregochr.goldenhour.service.TideService;
import com.gregochr.goldenhour.service.TurnstileService;
import com.gregochr.goldenhour.service.UserService;
import com.gregochr.goldenhour.service.UserSettingsService;
import com.gregochr.goldenhour.service.batch.ScheduledBatchEvaluationService;
import com.gregochr.goldenhour.service.aurora.AuroraForecastRunService;
import com.gregochr.goldenhour.service.aurora.AuroraOrchestrator;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.service.aurora.BortleEnrichmentService;
import com.gregochr.goldenhour.service.notification.UserEmailService;
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
    protected BriefingEvaluationService evaluationService;

    @MockitoBean
    protected HotTopicSimulationService hotTopicSimulationService;

    @MockitoBean
    protected BriefingModelTestService briefingModelTestService;

    @MockitoBean
    protected BriefingService briefingService;

    @MockitoBean
    protected DriveTimeResolver driveTimeResolver;

    @MockitoBean
    protected DynamicSchedulerService schedulerService;

    @MockitoBean
    protected ForecastCommandExecutor forecastCommandExecutor;

    @MockitoBean
    protected ForecastCommandFactory commandFactory;

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

    @MockitoBean
    protected ModelSelectionService modelSelectionService;

    @MockitoBean
    protected ModelTestService modelTestService;

    @MockitoBean
    protected OptimisationStrategyService optimisationStrategyService;

    @MockitoBean
    protected OutcomeService outcomeService;

    @MockitoBean
    protected PromptTestService promptTestService;

    @MockitoBean
    protected RegistrationService registrationService;

    @MockitoBean
    protected RegionService regionService;

    @MockitoBean
    protected RunProgressTracker progressTracker;

    @MockitoBean
    protected ScheduledBatchEvaluationService scheduledBatchEvaluationService;

    @MockitoBean
    protected ScheduledForecastService scheduledForecastService;

    @MockitoBean
    protected TideService tideService;

    @MockitoBean
    protected TurnstileService turnstileService;

    @MockitoBean
    protected UserEmailService userEmailService;

    @MockitoBean
    protected UserService userService;

    @MockitoBean
    protected UserSettingsService settingsService;

    // ── Aurora services ───────────────────────────────────────────────────────

    /**
     * Aurora forecast run service.
     */
    @MockitoBean
    protected AuroraForecastRunService forecastRunService;

    @MockitoBean
    protected AuroraOrchestrator orchestrator;

    @MockitoBean
    protected AuroraStateCache stateCache;

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
}
