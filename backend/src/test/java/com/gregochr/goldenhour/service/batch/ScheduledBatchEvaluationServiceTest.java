package com.gregochr.goldenhour.service.batch;

import com.anthropic.client.AnthropicClient;
import com.anthropic.services.blocking.MessageService;
import com.anthropic.services.blocking.messages.BatchService;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.gregochr.goldenhour.client.MetOfficeSpaceWeatherScraper;
import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.GridCellStabilityResult;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.ForecastStabilityClassifier;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.aurora.AuroraOrchestrator;
import com.gregochr.goldenhour.service.aurora.ClaudeAuroraInterpreter;
import com.gregochr.goldenhour.service.aurora.WeatherTriageService;
import com.gregochr.goldenhour.service.evaluation.PromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ScheduledBatchEvaluationService}.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledBatchEvaluationServiceTest {

    @Mock
    private AnthropicClient anthropicClient;
    @Mock
    private MessageService messageService;
    @Mock
    private BatchService batchService;
    @Mock
    private ForecastBatchRepository batchRepository;
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
    private PromptBuilder promptBuilder;
    @Mock
    private ModelSelectionService modelSelectionService;
    @Mock
    private NoaaSwpcClient noaaSwpcClient;
    @Mock
    private WeatherTriageService weatherTriageService;
    @Mock
    private ClaudeAuroraInterpreter claudeAuroraInterpreter;
    @Mock
    private AuroraOrchestrator auroraOrchestrator;
    @Mock
    private LocationRepository locationRepository;
    @Mock
    private AuroraProperties auroraProperties;
    @Mock
    private MetOfficeSpaceWeatherScraper metOfficeScraper;
    @Mock
    private DynamicSchedulerService dynamicSchedulerService;

    private ScheduledBatchEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new ScheduledBatchEvaluationService(
                anthropicClient, batchRepository, locationService, briefingService,
                briefingEvaluationService, forecastService, stabilityClassifier,
                promptBuilder, modelSelectionService, noaaSwpcClient,
                weatherTriageService, claudeAuroraInterpreter, auroraOrchestrator,
                locationRepository, auroraProperties, metOfficeScraper, dynamicSchedulerService);
    }

    private void stubBatchService() {
        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.batches()).thenReturn(batchService);
    }

    @Test
    @DisplayName("registerJobTargets registers near_term_batch_evaluation and aurora_batch_evaluation")
    void registerJobTargets_registersExpectedKeys() {
        service.registerJobTargets();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(dynamicSchedulerService, org.mockito.Mockito.times(2))
                .registerJobTarget(keyCaptor.capture(), any());
        assertThat(keyCaptor.getAllValues())
                .containsExactlyInAnyOrder("near_term_batch_evaluation", "aurora_batch_evaluation");
    }

    @Test
    @DisplayName("submitForecastBatch skips when no cached briefing")
    void submitForecastBatch_noBriefing_skips() {
        when(briefingService.getCachedBriefing()).thenReturn(null);

        service.submitForecastBatch();

        verify(batchService, never()).create(any());
        verify(batchRepository, never()).save(any());
    }

    @Test
    @DisplayName("submitForecastBatch skips when all slots are STANDDOWN")
    void submitForecastBatch_allStanddown_skips() {
        DailyBriefingResponse briefing = buildBriefing(Verdict.STANDDOWN);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.SCHEDULED_BATCH))
                .thenReturn(EvaluationModel.SONNET);

        service.submitForecastBatch();

        verify(batchService, never()).create(any());
        verify(batchRepository, never()).save(any());
    }

    @Test
    @DisplayName("submitForecastBatch submits batch for GO location")
    void submitForecastBatch_goLocation_submitsBatch() {
        stubBatchService();
        DailyBriefingResponse briefing = buildBriefing(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.SCHEDULED_BATCH))
                .thenReturn(EvaluationModel.SONNET);

        LocationEntity location = buildLocation("Durham UK");
        when(locationService.findAllEnabled()).thenReturn(List.of(location));

        AtmosphericData atmosphericData = mock(AtmosphericData.class);
        when(atmosphericData.surge()).thenReturn(null);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, atmosphericData, location,
                LocalDate.of(2026, 4, 7), TargetType.SUNRISE,
                LocalDateTime.of(2026, 4, 7, 5, 30), 90, 1,
                EvaluationModel.SONNET, location.getTideType(), "task-key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any())).thenReturn(preEval);
        when(promptBuilder.buildUserMessage(any(AtmosphericData.class)))
                .thenReturn("user message");
        when(promptBuilder.getSystemPrompt()).thenReturn("system prompt");

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_test001");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.submitForecastBatch();

        ArgumentCaptor<BatchCreateParams> paramsCaptor =
                ArgumentCaptor.forClass(BatchCreateParams.class);
        verify(batchService).create(paramsCaptor.capture());

        ArgumentCaptor<ForecastBatchEntity> entityCaptor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getAnthropicBatchId()).isEqualTo("msgbatch_test001");
        assertThat(entityCaptor.getValue().getBatchType())
                .isEqualTo(ForecastBatchEntity.BatchType.FORECAST);
        assertThat(entityCaptor.getValue().getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("submitForecastBatch skips triaged locations")
    void submitForecastBatch_triagedLocation_skips() {
        DailyBriefingResponse briefing = buildBriefing(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.SCHEDULED_BATCH))
                .thenReturn(EvaluationModel.SONNET);

        LocationEntity location = buildLocation("Durham UK");
        when(locationService.findAllEnabled()).thenReturn(List.of(location));

        ForecastPreEvalResult triaged = new ForecastPreEvalResult(
                true, "Overcast", null, location,
                LocalDate.of(2026, 4, 7), TargetType.SUNRISE,
                LocalDateTime.of(2026, 4, 7, 5, 30), 90, 1,
                EvaluationModel.SONNET, location.getTideType(), "task-key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any())).thenReturn(triaged);

        service.submitForecastBatch();

        verify(batchService, never()).create(any());
    }

    @Test
    @DisplayName("submitForecastBatch skips region already cached by SSE path")
    void submitForecastBatch_cachedRegion_skipsRegion() {
        DailyBriefingResponse briefing = buildBriefing(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.SCHEDULED_BATCH))
                .thenReturn(EvaluationModel.SONNET);
        when(briefingEvaluationService.hasEvaluation(eq("North East|2026-04-07|SUNRISE")))
                .thenReturn(true);

        service.submitForecastBatch();

        verifyNoInteractions(forecastService);
        verifyNoInteractions(batchRepository);
    }

    @Test
    @DisplayName("submitForecastBatch skips T+2 location when stability is UNSETTLED (window=0)")
    void submitForecastBatch_unsettledStability_skipsLocation() {
        DailyBriefingResponse briefing = buildBriefing(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.SCHEDULED_BATCH))
                .thenReturn(EvaluationModel.SONNET);

        LocationEntity location = buildLocation("Durham UK");
        location.setGridLat(54.7753);
        location.setGridLng(-1.5849);
        when(locationService.findAllEnabled()).thenReturn(List.of(location));

        OpenMeteoForecastResponse forecastResponse = mock(OpenMeteoForecastResponse.class);
        OpenMeteoForecastResponse.Hourly hourly = mock(OpenMeteoForecastResponse.Hourly.class);
        when(forecastResponse.getHourly()).thenReturn(hourly);

        AtmosphericData atmosphericData = mock(AtmosphericData.class);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, atmosphericData, location,
                LocalDate.of(2026, 4, 7), TargetType.SUNRISE,
                LocalDateTime.of(2026, 4, 7, 5, 30), 90, 2,
                EvaluationModel.SONNET, location.getTideType(), "task-key", forecastResponse);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any())).thenReturn(preEval);

        when(stabilityClassifier.classify(
                eq("54.7753,-1.5849"), eq(54.7753), eq(-1.5849), eq(hourly)))
                .thenReturn(new GridCellStabilityResult(
                        "54.7753,-1.5849", 54.7753, -1.5849,
                        ForecastStability.UNSETTLED, "active front", 0));

        service.submitForecastBatch();

        verifyNoInteractions(batchRepository);
    }

    @Test
    @DisplayName("submitForecastBatch includes T+3 location when stability is SETTLED (window=3)")
    void submitForecastBatch_settledStability_includesT3Location() {
        stubBatchService();
        DailyBriefingResponse briefing = buildBriefing(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.SCHEDULED_BATCH))
                .thenReturn(EvaluationModel.SONNET);

        LocationEntity location = buildLocation("Durham UK");
        location.setGridLat(54.7753);
        location.setGridLng(-1.5849);
        when(locationService.findAllEnabled()).thenReturn(List.of(location));

        OpenMeteoForecastResponse forecastResponse = mock(OpenMeteoForecastResponse.class);
        OpenMeteoForecastResponse.Hourly hourly = mock(OpenMeteoForecastResponse.Hourly.class);
        when(forecastResponse.getHourly()).thenReturn(hourly);

        AtmosphericData atmosphericData = mock(AtmosphericData.class);
        when(atmosphericData.surge()).thenReturn(null);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, atmosphericData, location,
                LocalDate.of(2026, 4, 7), TargetType.SUNRISE,
                LocalDateTime.of(2026, 4, 7, 5, 30), 90, 3,
                EvaluationModel.SONNET, location.getTideType(), "task-key", forecastResponse);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any())).thenReturn(preEval);

        when(stabilityClassifier.classify(
                eq("54.7753,-1.5849"), eq(54.7753), eq(-1.5849), eq(hourly)))
                .thenReturn(new GridCellStabilityResult(
                        "54.7753,-1.5849", 54.7753, -1.5849,
                        ForecastStability.SETTLED, "high pressure", 3));

        when(promptBuilder.buildUserMessage(any(AtmosphericData.class))).thenReturn("user msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("system prompt");

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_t3");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.submitForecastBatch();

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("submitForecastBatch skips T+2 location when location has no grid cell (default window=1)")
    void submitForecastBatch_noGridCell_skipsT2Location() {
        DailyBriefingResponse briefing = buildBriefing(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.SCHEDULED_BATCH))
                .thenReturn(EvaluationModel.SONNET);

        LocationEntity location = buildLocation("Durham UK");
        // No gridLat/gridLng set — hasGridCell() returns false
        when(locationService.findAllEnabled()).thenReturn(List.of(location));

        AtmosphericData atmosphericData = mock(AtmosphericData.class);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, atmosphericData, location,
                LocalDate.of(2026, 4, 7), TargetType.SUNRISE,
                LocalDateTime.of(2026, 4, 7, 5, 30), 90, 2,
                EvaluationModel.SONNET, location.getTideType(), "task-key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any())).thenReturn(preEval);

        service.submitForecastBatch();

        verifyNoInteractions(batchRepository);
        verifyNoInteractions(stabilityClassifier);
    }

    @Test
    @DisplayName("submitForecastBatch classifies each grid cell only once across multiple locations")
    void submitForecastBatch_sharedGridCell_classifiesOnce() {
        DailyBriefingResponse briefing = buildBriefingTwoSlots(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.SCHEDULED_BATCH))
                .thenReturn(EvaluationModel.SONNET);

        LocationEntity loc1 = buildLocation("Durham UK");
        loc1.setGridLat(54.7753);
        loc1.setGridLng(-1.5849);
        LocationEntity loc2 = buildLocation("Sunderland");
        loc2.setGridLat(54.7753);  // same grid cell
        loc2.setGridLng(-1.5849);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc1, loc2));

        OpenMeteoForecastResponse forecastResponse = mock(OpenMeteoForecastResponse.class);
        OpenMeteoForecastResponse.Hourly hourly = mock(OpenMeteoForecastResponse.Hourly.class);
        when(forecastResponse.getHourly()).thenReturn(hourly);

        AtmosphericData atmosphericData = mock(AtmosphericData.class);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, atmosphericData, loc1,
                LocalDate.of(2026, 4, 7), TargetType.SUNRISE,
                LocalDateTime.of(2026, 4, 7, 5, 30), 90, 2,
                EvaluationModel.SONNET, loc1.getTideType(), "task-key", forecastResponse);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any())).thenReturn(preEval);

        when(stabilityClassifier.classify(
                eq("54.7753,-1.5849"), eq(54.7753), eq(-1.5849), eq(hourly)))
                .thenReturn(new GridCellStabilityResult(
                        "54.7753,-1.5849", 54.7753, -1.5849,
                        ForecastStability.UNSETTLED, "active front", 0));

        service.submitForecastBatch();

        // Both locations skipped (window=0 < daysAhead=2), classifier called exactly once
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(stabilityClassifier).classify(
                keyCaptor.capture(), eq(54.7753), eq(-1.5849), eq(hourly));
        assertThat(keyCaptor.getValue()).isEqualTo("54.7753,-1.5849");
        verifyNoInteractions(batchRepository);
    }

    @Test
    @DisplayName("submitAuroraBatch skips when NOAA fetch fails")
    void submitAuroraBatch_noaaFails_skips() {
        when(noaaSwpcClient.fetchAll()).thenThrow(new RuntimeException("Network error"));

        service.submitAuroraBatch();

        verify(batchService, never()).create(any());
        verify(batchRepository, never()).save(any());
    }

    @Test
    @DisplayName("submitAuroraBatch skips when alert level is QUIET")
    void submitAuroraBatch_quietLevel_skips() {
        SpaceWeatherData spaceWeather = mock(SpaceWeatherData.class);
        when(noaaSwpcClient.fetchAll()).thenReturn(spaceWeather);
        when(auroraOrchestrator.deriveAlertLevel(spaceWeather)).thenReturn(AlertLevel.QUIET);

        service.submitAuroraBatch();

        verify(batchService, never()).create(any());
        verify(batchRepository, never()).save(any());
    }

    @Test
    @DisplayName("submitAuroraBatch submits batch when viable locations exist")
    void submitAuroraBatch_viableLocations_submitsBatch() {
        stubBatchService();
        SpaceWeatherData spaceWeather = mock(SpaceWeatherData.class);
        when(noaaSwpcClient.fetchAll()).thenReturn(spaceWeather);
        when(auroraOrchestrator.deriveAlertLevel(spaceWeather)).thenReturn(AlertLevel.MODERATE);

        AuroraProperties.BortleThreshold threshold = mock(AuroraProperties.BortleThreshold.class);
        when(auroraProperties.getBortleThreshold()).thenReturn(threshold);
        when(threshold.getModerate()).thenReturn(4);

        LocationEntity loc = buildLocation("Dark Sky Site");
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(4))
                .thenReturn(List.of(loc));

        WeatherTriageService.TriageResult triage = new WeatherTriageService.TriageResult(
                List.of(loc), List.of(), java.util.Map.of());
        when(weatherTriageService.triage(List.of(loc))).thenReturn(triage);

        when(metOfficeScraper.getForecastText()).thenReturn("Met Office narrative");
        when(claudeAuroraInterpreter.buildUserMessage(any(), any(), any(), any(), any(), any(),
                any())).thenReturn("aurora user message");
        when(modelSelectionService.getActiveModel(RunType.AURORA_EVALUATION))
                .thenReturn(EvaluationModel.HAIKU);

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_aurora001");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.submitAuroraBatch();

        ArgumentCaptor<ForecastBatchEntity> entityCaptor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getAnthropicBatchId()).isEqualTo("msgbatch_aurora001");
        assertThat(entityCaptor.getValue().getBatchType())
                .isEqualTo(ForecastBatchEntity.BatchType.AURORA);
    }

    @Test
    @DisplayName("submitAuroraBatch skips when no viable locations after triage")
    void submitAuroraBatch_noViableLocations_skips() {
        SpaceWeatherData spaceWeather = mock(SpaceWeatherData.class);
        when(noaaSwpcClient.fetchAll()).thenReturn(spaceWeather);
        when(auroraOrchestrator.deriveAlertLevel(spaceWeather)).thenReturn(AlertLevel.MODERATE);

        AuroraProperties.BortleThreshold threshold = mock(AuroraProperties.BortleThreshold.class);
        when(auroraProperties.getBortleThreshold()).thenReturn(threshold);
        when(threshold.getModerate()).thenReturn(4);

        LocationEntity loc = buildLocation("Dark Sky Site");
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(4))
                .thenReturn(List.of(loc));

        WeatherTriageService.TriageResult triage = new WeatherTriageService.TriageResult(
                List.of(), List.of(loc), java.util.Map.of());
        when(weatherTriageService.triage(List.of(loc))).thenReturn(triage);

        service.submitAuroraBatch();

        verify(batchService, never()).create(any());
    }

    private DailyBriefingResponse buildBriefing(Verdict verdict) {
        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(
                20, BigDecimal.ZERO, 10000, 70, 10.0, 9.0, 1, BigDecimal.valueOf(5));
        BriefingSlot slot = new BriefingSlot("Durham UK",
                LocalDateTime.of(2026, 4, 7, 5, 30),
                verdict, weather, BriefingSlot.TideInfo.NONE, List.of());
        BriefingRegion region = new BriefingRegion(
                "North East", verdict, "Summary", List.of(), List.of(slot),
                null, null, null, null);
        BriefingEventSummary eventSummary = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(region), List.of());
        BriefingDay day = new BriefingDay(LocalDate.of(2026, 4, 7), List.of(eventSummary));
        return new DailyBriefingResponse(null, null, List.of(day), null, null, null,
                false, false, 0, null);
    }

    private DailyBriefingResponse buildBriefingTwoSlots(Verdict verdict) {
        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(
                20, BigDecimal.ZERO, 10000, 70, 10.0, 9.0, 1, BigDecimal.valueOf(5));
        BriefingSlot slot1 = new BriefingSlot("Durham UK",
                LocalDateTime.of(2026, 4, 7, 5, 30),
                verdict, weather, BriefingSlot.TideInfo.NONE, List.of());
        BriefingSlot slot2 = new BriefingSlot("Sunderland",
                LocalDateTime.of(2026, 4, 7, 5, 30),
                verdict, weather, BriefingSlot.TideInfo.NONE, List.of());
        BriefingRegion region = new BriefingRegion(
                "North East", verdict, "Summary", List.of(), List.of(slot1, slot2),
                null, null, null, null);
        BriefingEventSummary eventSummary = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(region), List.of());
        BriefingDay day = new BriefingDay(LocalDate.of(2026, 4, 7), List.of(eventSummary));
        return new DailyBriefingResponse(null, null, List.of(day), null, null, null,
                false, false, 0, null);
    }

    private LocationEntity buildLocation(String name) {
        LocationEntity location = new LocationEntity();
        location.setName(name);
        location.setLat(54.7753);
        location.setLon(-1.5849);
        RegionEntity region = new RegionEntity();
        region.setName("North East");
        location.setRegion(region);
        location.setTideType(Set.of());
        return location;
    }
}
