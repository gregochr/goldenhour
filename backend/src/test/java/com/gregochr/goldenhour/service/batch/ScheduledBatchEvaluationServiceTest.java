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
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
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
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.time.LocalDate.now;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
                evaluationService, 0.5);
    }

    private void stubWeatherPrefetch() {
        String coordKey = OpenMeteoService.coordKey(54.7753, -1.5849);
        WeatherExtractionResult dummy = new WeatherExtractionResult(
                null, new OpenMeteoForecastResponse(), new OpenMeteoAirQualityResponse());
        when(openMeteoService.prefetchWeatherBatchResilient(any()))
                .thenReturn(Map.of(coordKey, dummy));
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

    // ── Forecast: pre-collection short-circuits ──────────────────────────────

    @Test
    @DisplayName("submitForecastBatch skips when no cached briefing")
    void submitForecastBatch_noBriefing_skips() {
        when(briefingService.getCachedBriefing()).thenReturn(null);

        service.submitForecastBatch();

        verifyNoInteractions(evaluationService);
    }

    @Test
    @DisplayName("submitForecastBatch skips when all slots are STANDDOWN")
    void submitForecastBatch_allStanddown_skips() {
        when(briefingService.getCachedBriefing())
                .thenReturn(buildBriefingForVerdict(Verdict.STANDDOWN));

        service.submitForecastBatch();

        verifyNoInteractions(evaluationService);
    }

    @Test
    @DisplayName("submitForecastBatch skips past dates without weather fetch")
    void submitForecastBatch_pastDatesOnly_skipsWithoutWeatherFetch() {
        DailyBriefingResponse briefing = buildBriefingWithDate(
                TEST_DATE.minusDays(1), Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);

        service.submitForecastBatch();

        verifyNoInteractions(openMeteoService);
        verifyNoInteractions(evaluationService);
    }

    // ── Forecast: dispatch through EvaluationService ─────────────────────────

    @Test
    @DisplayName("submitForecastBatch: viable GO location dispatches a task to evaluationService.submit")
    void submitForecastBatch_goLocation_dispatchesToEvaluationService() {
        DailyBriefingResponse briefing = buildBriefingForVerdict(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);
        when(modelSelectionService.getActiveModel(RunType.BATCH_FAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);
        LocationEntity location = buildLocation("Durham UK");
        when(locationService.findAllEnabled()).thenReturn(List.of(location));

        AtmosphericData data = buildAtmospheric();
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, location, TEST_DATE, TargetType.SUNRISE,
                TEST_EVENT_TIME, 60, 0, EvaluationModel.HAIKU, Set.of(),
                "k", null);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(preEval);
        when(freshnessResolver.maxAgeFor(any())).thenReturn(Duration.ofHours(6));
        stubWeatherPrefetch();
        when(evaluationService.submit(any(List.class), eq(BatchTriggerSource.SCHEDULED)))
                .thenReturn(new EvaluationHandle(null, "msgbatch_x", 1));

        service.submitForecastBatch();

        ArgumentCaptor<List<EvaluationTask.Forecast>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(evaluationService).submit(captor.capture(),
                eq(BatchTriggerSource.SCHEDULED));
        List<EvaluationTask.Forecast> tasks = captor.getValue();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).location().getName()).isEqualTo("Durham UK");
        assertThat(tasks.get(0).model()).isEqualTo(EvaluationModel.HAIKU);
    }

    @Test
    @DisplayName("submitForecastBatch: triaged location skipped — not in submitted task list")
    void submitForecastBatch_triagedLocation_skipsTask() {
        DailyBriefingResponse briefing = buildBriefingForVerdict(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(any())).thenReturn(EvaluationModel.HAIKU);
        LocationEntity location = buildLocation("Durham UK");
        when(locationService.findAllEnabled()).thenReturn(List.of(location));

        ForecastPreEvalResult triaged = new ForecastPreEvalResult(
                true, "cloud", null, location, TEST_DATE, TargetType.SUNRISE,
                TEST_EVENT_TIME, 60, 0, EvaluationModel.HAIKU, Set.of(),
                "k", null);
        when(forecastService.fetchWeatherAndTriage(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(triaged);
        when(freshnessResolver.maxAgeFor(any())).thenReturn(Duration.ofHours(6));
        stubWeatherPrefetch();

        service.submitForecastBatch();

        verify(evaluationService, never()).submit(any(List.class), any());
    }

    @Test
    @DisplayName("submitForecastBatch: cached region skipped — task collection short-circuits")
    void submitForecastBatch_cachedRegion_skipsRegion() {
        DailyBriefingResponse briefing = buildBriefingForVerdict(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(any())).thenReturn(EvaluationModel.HAIKU);
        when(briefingEvaluationService.hasFreshEvaluation(any(), any())).thenReturn(true);
        when(freshnessResolver.maxAgeFor(any())).thenReturn(Duration.ofHours(6));

        service.submitForecastBatch();

        verifyNoInteractions(evaluationService);
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

        when(briefingService.getCachedBriefing()).thenReturn(null);
        service.submitForecastBatch();
        verify(briefingService).getCachedBriefing();
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

        verifyNoInteractions(briefingService);
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
    @DisplayName("submitForecastBatch clears guard even when inner method throws")
    void submitForecastBatch_exceptionInDoSubmit_clearsGuard() {
        when(briefingService.getCachedBriefing())
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(null);

        try {
            service.submitForecastBatch();
        } catch (RuntimeException ignored) {
            // expected — guard must still be cleared
        }

        service.submitForecastBatch();
        verify(briefingService, org.mockito.Mockito.times(2)).getCachedBriefing();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private DailyBriefingResponse buildBriefingForVerdict(Verdict verdict) {
        return buildBriefingWithDate(TEST_DATE, verdict);
    }

    private DailyBriefingResponse buildBriefingWithDate(LocalDate date, Verdict verdict) {
        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(
                20, BigDecimal.ZERO, 10000, 70, 10.0, 9.0, 1, BigDecimal.valueOf(5), 0, 0);
        BriefingSlot slot = new BriefingSlot("Durham UK",
                date.atTime(5, 30),
                verdict, weather, BriefingSlot.TideInfo.NONE, List.of(), null);
        BriefingRegion region = new BriefingRegion(
                "North East", verdict, "Summary", List.of(), List.of(slot),
                null, null, null, null, null, null);
        BriefingEventSummary eventSummary = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(region), List.of());
        BriefingDay day = new BriefingDay(date, List.of(eventSummary));
        return new DailyBriefingResponse(null, null, List.of(day), null, null, null,
                false, false, 0, null, List.of(), List.of());
    }

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
