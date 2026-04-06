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
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.ForecastService;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    private ForecastService forecastService;
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
                forecastService, promptBuilder, modelSelectionService, noaaSwpcClient,
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
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM))
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
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM))
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
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM))
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
