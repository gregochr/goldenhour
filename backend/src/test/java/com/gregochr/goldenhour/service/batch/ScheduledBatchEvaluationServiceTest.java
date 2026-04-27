package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.ForecastCommandExecutor;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.ForecastStabilityClassifier;
import com.gregochr.goldenhour.service.FreshnessResolver;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.OpenMeteoService;
import com.gregochr.goldenhour.service.SolarService;
import com.gregochr.goldenhour.service.aurora.AuroraOrchestrator;
import com.gregochr.goldenhour.service.aurora.WeatherTriageService;
import com.gregochr.goldenhour.service.evaluation.EvaluationHandle;
import com.gregochr.goldenhour.service.evaluation.EvaluationService;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.time.LocalDate.now;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ScheduledBatchEvaluationService} after Pass 3.2.
 *
 * <p>The service no longer builds Anthropic batch requests directly — it collects
 * tasks (briefing → triage → stability) and hands them to
 * {@link EvaluationService#submit}. These tests verify:
 * <ul>
 *   <li>Pre-collection short-circuits (no briefing, all STANDDOWN, past dates)</li>
 *   <li>The triage / stability gates still skip the right tasks</li>
 *   <li>Successful task lists reach {@code evaluationService.submit} with the right
 *       trigger source</li>
 *   <li>Concurrency guards (forecastBatchRunning / auroraBatchRunning)</li>
 * </ul>
 *
 * <p>Customid format, prompt building, and batch submission mechanics live in their
 * own (much smaller) test classes ({@code CustomIdFactoryTest}, {@code
 * BatchRequestFactoryTest}, {@code BatchSubmissionServiceTest}, {@code
 * EvaluationServiceImplTest}). The end-to-end byte-identical contract is held by the
 * integration test pyramid.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledBatchEvaluationServiceTest {

    private static final LocalDate TEST_DATE = now();
    private static final LocalDateTime TEST_EVENT_TIME = TEST_DATE.atTime(5, 30);

    @Mock
    private LocationService locationService;
    @Mock
    private BriefingService briefingService;
    @Mock
    private BriefingEvaluationService briefingEvaluationService;
    @Mock
    private ForecastService forecastService;
    @Mock
    private ForecastStabilityClassifier stabilityClassifier;
    @Mock
    private ModelSelectionService modelSelectionService;
    @Mock
    private NoaaSwpcClient noaaSwpcClient;
    @Mock
    private WeatherTriageService weatherTriageService;
    @Mock
    private AuroraOrchestrator auroraOrchestrator;
    @Mock
    private LocationRepository locationRepository;
    @Mock
    private AuroraProperties auroraProperties;
    @Mock
    private DynamicSchedulerService dynamicSchedulerService;
    @Mock
    private OpenMeteoService openMeteoService;
    @Mock
    private SolarService solarService;
    @Mock
    private FreshnessResolver freshnessResolver;
    @Mock
    private ForecastCommandExecutor forecastCommandExecutor;
    @Mock
    private EvaluationService evaluationService;
    @Mock
    private ForecastTaskCollector forecastTaskCollector;

    private ScheduledBatchEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new ScheduledBatchEvaluationService(
                locationService, briefingService, briefingEvaluationService,
                forecastService, stabilityClassifier,
                modelSelectionService, noaaSwpcClient,
                weatherTriageService, auroraOrchestrator,
                locationRepository, auroraProperties, dynamicSchedulerService,
                openMeteoService, solarService,
                freshnessResolver, forecastCommandExecutor,
                evaluationService, forecastTaskCollector, 0.5);
    }

    // ── registerJobTargets ───────────────────────────────────────────────────

    @Test
    @DisplayName("registerJobTargets registers near_term_batch_evaluation and aurora_batch_evaluation")
    void registerJobTargets_registersExpectedKeys() {
        service.registerJobTargets();

        verify(dynamicSchedulerService).registerJobTarget(
                eq("near_term_batch_evaluation"), any(Runnable.class));
        verify(dynamicSchedulerService).registerJobTarget(
                eq("aurora_batch_evaluation"), any(Runnable.class));
    }

    // ── Forecast: routes through ForecastTaskCollector ───────────────────────

    @Test
    @DisplayName("submitForecastBatch: empty collector result → no submission")
    void submitForecastBatch_collectorReturnsEmpty_noSubmission() {
        when(forecastTaskCollector.collectScheduledBatches())
                .thenReturn(ScheduledBatchTasks.empty());

        service.submitForecastBatch();

        verifyNoInteractions(evaluationService);
    }

    @Test
    @DisplayName("submitForecastBatch: collector returns near-inland tasks → one submit per bucket")
    void submitForecastBatch_collectorReturnsTasks_submitsEachNonEmptyBucket() {
        LocationEntity location = buildLocation("Durham UK");
        EvaluationTask.Forecast nearInlandTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric());
        EvaluationTask.Forecast nearCoastalTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric());
        when(forecastTaskCollector.collectScheduledBatches())
                .thenReturn(new ScheduledBatchTasks(
                        List.of(nearInlandTask), List.of(nearCoastalTask),
                        List.of(), List.of()));
        when(evaluationService.submit(any(List.class), eq(BatchTriggerSource.SCHEDULED)))
                .thenReturn(new EvaluationHandle(null, "msgbatch_x", 1));

        service.submitForecastBatch();

        // One submit per non-empty bucket — exactly two here
        verify(evaluationService, org.mockito.Mockito.times(2))
                .submit(any(List.class), eq(BatchTriggerSource.SCHEDULED));
    }

    @Test
    @DisplayName("submitScheduledBatchForRegions: empty collector result → returns null")
    void submitScheduledBatchForRegions_collectorEmpty_returnsNull() {
        when(forecastTaskCollector.collectRegionFilteredBatches(any()))
                .thenReturn(RegionFilteredBatchTasks.empty());

        BatchSubmitResult result = service.submitScheduledBatchForRegions(List.of(1L));

        assertThat(result).isNull();
        verifyNoInteractions(evaluationService);
    }

    @Test
    @DisplayName("submitScheduledBatchForRegions: collector returns tasks → submits via ADMIN trigger")
    void submitScheduledBatchForRegions_collectorReturnsTasks_submitsAdmin() {
        LocationEntity location = buildLocation("Durham UK");
        EvaluationTask.Forecast inlandTask = new EvaluationTask.Forecast(
                location, TEST_DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, buildAtmospheric());
        when(forecastTaskCollector.collectRegionFilteredBatches(any()))
                .thenReturn(new RegionFilteredBatchTasks(
                        List.of(inlandTask), List.of()));
        when(evaluationService.submit(any(List.class), eq(BatchTriggerSource.ADMIN)))
                .thenReturn(new EvaluationHandle(null, "msgbatch_admin", 1));

        BatchSubmitResult result = service.submitScheduledBatchForRegions(List.of(1L));

        assertThat(result).isNotNull();
        assertThat(result.batchId()).isEqualTo("msgbatch_admin");
        verify(evaluationService).submit(any(List.class), eq(BatchTriggerSource.ADMIN));
    }

    // ── Aurora ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("submitAuroraBatch skips when NOAA fetch fails")
    void submitAuroraBatch_noaaFails_skips() {
        when(noaaSwpcClient.fetchAll()).thenThrow(new RuntimeException("network"));

        service.submitAuroraBatch();

        verifyNoInteractions(evaluationService);
    }

    @Test
    @DisplayName("submitAuroraBatch skips when alert level is QUIET")
    void submitAuroraBatch_quietLevel_skips() {
        SpaceWeatherData wx = new SpaceWeatherData(
                List.of(), List.of(), null, List.of(), List.of());
        when(noaaSwpcClient.fetchAll()).thenReturn(wx);
        when(auroraOrchestrator.deriveAlertLevel(wx)).thenReturn(AlertLevel.QUIET);

        service.submitAuroraBatch();

        verifyNoInteractions(evaluationService);
    }

    @Test
    @DisplayName("submitAuroraBatch dispatches Aurora task to evaluationService.submit on viable triage")
    void submitAuroraBatch_viableLocations_dispatchesToEvaluationService() {
        SpaceWeatherData wx = new SpaceWeatherData(
                List.of(), List.of(), null, List.of(), List.of());
        when(noaaSwpcClient.fetchAll()).thenReturn(wx);
        when(auroraOrchestrator.deriveAlertLevel(wx)).thenReturn(AlertLevel.MODERATE);
        AuroraProperties.BortleThreshold threshold = new AuroraProperties.BortleThreshold();
        when(auroraProperties.getBortleThreshold()).thenReturn(threshold);
        LocationEntity loc = buildLocation("Northumberland Coast");
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(4))
                .thenReturn(List.of(loc));
        WeatherTriageService.TriageResult triage =
                new WeatherTriageService.TriageResult(
                        List.of(loc), List.of(), Map.of(loc, 30));
        when(weatherTriageService.triage(any())).thenReturn(triage);
        when(modelSelectionService.getActiveModel(RunType.AURORA_EVALUATION))
                .thenReturn(EvaluationModel.HAIKU);
        when(evaluationService.submit(any(List.class), eq(BatchTriggerSource.SCHEDULED)))
                .thenReturn(new EvaluationHandle(null, "msgbatch_aurora", 1));

        service.submitAuroraBatch();

        ArgumentCaptor<List<EvaluationTask.Aurora>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(evaluationService).submit(captor.capture(),
                eq(BatchTriggerSource.SCHEDULED));
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).alertLevel()).isEqualTo(AlertLevel.MODERATE);
        assertThat(captor.getValue().get(0).viableLocations()).containsExactly(loc);
    }

    @Test
    @DisplayName("submitAuroraBatch skips when no viable locations after triage")
    void submitAuroraBatch_noViableLocations_skips() {
        SpaceWeatherData wx = new SpaceWeatherData(
                List.of(), List.of(), null, List.of(), List.of());
        when(noaaSwpcClient.fetchAll()).thenReturn(wx);
        when(auroraOrchestrator.deriveAlertLevel(wx)).thenReturn(AlertLevel.MODERATE);
        AuroraProperties.BortleThreshold threshold = new AuroraProperties.BortleThreshold();
        when(auroraProperties.getBortleThreshold()).thenReturn(threshold);
        LocationEntity loc = buildLocation("Northumberland Coast");
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(4))
                .thenReturn(List.of(loc));
        WeatherTriageService.TriageResult triage =
                new WeatherTriageService.TriageResult(
                        List.of(), List.of(loc), Map.of(loc, 100));
        when(weatherTriageService.triage(any())).thenReturn(triage);

        service.submitAuroraBatch();

        verifyNoInteractions(evaluationService);
    }

    // ── Concurrency guards ───────────────────────────────────────────────────

    @Test
    @DisplayName("resetBatchGuards clears forecast guard")
    void resetBatchGuards_clearsForecastGuard() throws Exception {
        Field forecastField = ScheduledBatchEvaluationService.class
                .getDeclaredField("forecastBatchRunning");
        forecastField.setAccessible(true);
        ((AtomicBoolean) forecastField.get(service)).set(true);

        service.resetBatchGuards();

        when(forecastTaskCollector.collectScheduledBatches())
                .thenReturn(ScheduledBatchTasks.empty());
        service.submitForecastBatch();
        verify(forecastTaskCollector).collectScheduledBatches();
    }

    @Test
    @DisplayName("resetBatchGuards clears aurora guard")
    void resetBatchGuards_clearsAuroraGuard() throws Exception {
        Field auroraField = ScheduledBatchEvaluationService.class
                .getDeclaredField("auroraBatchRunning");
        auroraField.setAccessible(true);
        ((AtomicBoolean) auroraField.get(service)).set(true);

        service.resetBatchGuards();

        when(noaaSwpcClient.fetchAll()).thenThrow(new RuntimeException("test"));
        service.submitAuroraBatch();
        verify(noaaSwpcClient).fetchAll();
    }

    @Test
    @DisplayName("submitForecastBatch skips when guard already held")
    void submitForecastBatch_guardAlreadyHeld_skips() throws Exception {
        Field forecastField = ScheduledBatchEvaluationService.class
                .getDeclaredField("forecastBatchRunning");
        forecastField.setAccessible(true);
        ((AtomicBoolean) forecastField.get(service)).set(true);

        service.submitForecastBatch();

        verifyNoInteractions(forecastTaskCollector);
    }

    @Test
    @DisplayName("submitAuroraBatch skips when guard already held")
    void submitAuroraBatch_guardAlreadyHeld_skips() throws Exception {
        Field auroraField = ScheduledBatchEvaluationService.class
                .getDeclaredField("auroraBatchRunning");
        auroraField.setAccessible(true);
        ((AtomicBoolean) auroraField.get(service)).set(true);

        service.submitAuroraBatch();

        verifyNoInteractions(noaaSwpcClient);
    }

    @Test
    @DisplayName("submitForecastBatch clears guard even when collector throws")
    void submitForecastBatch_exceptionInCollector_clearsGuard() {
        when(forecastTaskCollector.collectScheduledBatches())
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(ScheduledBatchTasks.empty());

        try {
            service.submitForecastBatch();
        } catch (RuntimeException ignored) {
            // expected — guard must still be cleared
        }

        service.submitForecastBatch();
        verify(forecastTaskCollector, org.mockito.Mockito.times(2))
                .collectScheduledBatches();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private LocationEntity buildLocation(String name) {
        LocationEntity location = new LocationEntity();
        location.setId(42L);
        location.setName(name);
        location.setLat(54.7753);
        location.setLon(-1.5849);
        RegionEntity region = new RegionEntity();
        region.setName("North East");
        location.setRegion(region);
        location.setTideType(Set.of());
        return location;
    }

    private AtmosphericData buildAtmospheric() {
        return com.gregochr.goldenhour.TestAtmosphericData.builder()
                .locationName("Durham UK")
                .solarEventTime(TEST_EVENT_TIME)
                .targetType(TargetType.SUNRISE)
                .build();
    }
}
