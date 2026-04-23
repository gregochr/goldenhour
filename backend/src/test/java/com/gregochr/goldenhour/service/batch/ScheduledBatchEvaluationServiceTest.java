package com.gregochr.goldenhour.service.batch;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.services.blocking.MessageService;
import com.anthropic.services.blocking.messages.BatchService;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
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
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.ForecastStabilityClassifier;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.OpenMeteoService;
import com.gregochr.goldenhour.service.SolarService;
import com.gregochr.goldenhour.service.aurora.AuroraOrchestrator;
import com.gregochr.goldenhour.service.aurora.ClaudeAuroraInterpreter;
import com.gregochr.goldenhour.service.aurora.WeatherTriageService;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.TideRiskLevel;
import com.gregochr.goldenhour.model.TideSnapshot;
import com.gregochr.goldenhour.service.evaluation.CoastalPromptBuilder;
import com.gregochr.goldenhour.service.evaluation.PromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.time.LocalDate.now;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ScheduledBatchEvaluationService}.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledBatchEvaluationServiceTest {

    private static final LocalDate TEST_DATE = now();
    private static final LocalDateTime TEST_EVENT_TIME = TEST_DATE.atTime(5, 30);

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
    private CoastalPromptBuilder coastalPromptBuilder;
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
    private DynamicSchedulerService dynamicSchedulerService;
    @Mock
    private JobRunService jobRunService;
    @Mock
    private OpenMeteoService openMeteoService;
    @Mock
    private SolarService solarService;

    private ScheduledBatchEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new ScheduledBatchEvaluationService(
                anthropicClient, batchRepository, locationService, briefingService,
                briefingEvaluationService, forecastService, stabilityClassifier,
                promptBuilder, coastalPromptBuilder, modelSelectionService, noaaSwpcClient,
                weatherTriageService, claudeAuroraInterpreter, auroraOrchestrator,
                locationRepository, auroraProperties, dynamicSchedulerService,
                jobRunService, openMeteoService, solarService, 18);
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
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);

        service.submitForecastBatch();

        verify(batchService, never()).create(any());
        verify(batchRepository, never()).save(any());
    }

    @Test
    @DisplayName("submitForecastBatch skips past dates from stale cached briefing")
    void submitForecastBatch_pastDatesOnly_skipsWithoutWeatherFetch() {
        LocalDate yesterday = TEST_DATE.minusDays(1);
        DailyBriefingResponse briefing = buildBriefingForDate(yesterday, Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);

        service.submitForecastBatch();

        // Past dates should be filtered before weather pre-fetch — no API calls wasted
        verifyNoInteractions(openMeteoService);
        verifyNoInteractions(forecastService);
        verify(batchService, never()).create(any());
    }

    @Test
    @DisplayName("submitForecastBatch: mixed past and future dates, only future dates reach triage")
    void submitForecastBatch_mixedPastAndFutureDates_onlyFutureDatesTriaged() {
        stubBatchService();

        LocalDate yesterday = TEST_DATE.minusDays(1);
        LocalDate tomorrow = TEST_DATE.plusDays(1);

        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(
                20, BigDecimal.ZERO, 10000, 70, 10.0, 9.0, 1, BigDecimal.valueOf(5), 0, 0);

        // Yesterday: 2 GO slots (should be filtered)
        BriefingSlot yesterdaySlot1 = new BriefingSlot("Durham UK",
                yesterday.atTime(5, 30), Verdict.GO, weather,
                BriefingSlot.TideInfo.NONE, List.of(), null);
        BriefingSlot yesterdaySlot2 = new BriefingSlot("Sunderland",
                yesterday.atTime(5, 30), Verdict.GO, weather,
                BriefingSlot.TideInfo.NONE, List.of(), null);
        BriefingRegion yesterdayRegion = new BriefingRegion(
                "North East", Verdict.GO, "Summary", List.of(),
                List.of(yesterdaySlot1, yesterdaySlot2),
                null, null, null, null, null, null);
        BriefingEventSummary yesterdayEvent = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(yesterdayRegion), List.of());
        BriefingDay yesterdayDay = new BriefingDay(yesterday, List.of(yesterdayEvent));

        // Tomorrow: 1 GO slot (should pass through)
        BriefingSlot tomorrowSlot = new BriefingSlot("Durham UK",
                tomorrow.atTime(5, 30), Verdict.GO, weather,
                BriefingSlot.TideInfo.NONE, List.of(), null);
        BriefingRegion tomorrowRegion = new BriefingRegion(
                "North East", Verdict.GO, "Summary", List.of(), List.of(tomorrowSlot),
                null, null, null, null, null, null);
        BriefingEventSummary tomorrowEvent = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(tomorrowRegion), List.of());
        BriefingDay tomorrowDay = new BriefingDay(tomorrow, List.of(tomorrowEvent));

        DailyBriefingResponse briefing = new DailyBriefingResponse(
                null, null, List.of(yesterdayDay, tomorrowDay), null, null, null,
                false, false, 0, null, List.of(), List.of());

        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);

        LocationEntity location = buildLocation("Durham UK");
        when(locationService.findAllEnabled()).thenReturn(List.of(location));

        AtmosphericData atmosphericData = mock(AtmosphericData.class);
        when(atmosphericData.surge()).thenReturn(null);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, atmosphericData, location,
                tomorrow, TargetType.SUNRISE,
                tomorrow.atTime(5, 30), 90, 1,
                EvaluationModel.SONNET, location.getTideType(), "task-key", null);
        when(forecastService.fetchWeatherAndTriage(eq(location), eq(tomorrow),
                eq(TargetType.SUNRISE), any(), eq(EvaluationModel.SONNET),
                eq(false), any(), any(), any())).thenReturn(preEval);
        when(promptBuilder.buildUserMessage(any(AtmosphericData.class))).thenReturn("msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("sys");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_mixed");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.submitForecastBatch();

        // Only tomorrow's slot should reach the batch — yesterday's 2 slots filtered
        ArgumentCaptor<BatchCreateParams> paramsCaptor =
                ArgumentCaptor.forClass(BatchCreateParams.class);
        verify(batchService).create(paramsCaptor.capture());
        assertThat(paramsCaptor.getValue().requests()).hasSize(1);

        // Triage was only called for the tomorrow slot, not for yesterday's slots
        verify(forecastService).fetchWeatherAndTriage(eq(location), eq(tomorrow),
                eq(TargetType.SUNRISE), any(), eq(EvaluationModel.SONNET),
                eq(false), any(), any(), any());
        verifyNoMoreInteractions(forecastService);
    }

    @Test
    @DisplayName("submitForecastBatch keeps today's date (not filtered as past)")
    void submitForecastBatch_todayDate_notFiltered() {
        stubBatchService();
        // buildBriefing uses TEST_DATE which is today — should pass the date filter
        DailyBriefingResponse briefing = buildBriefing(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);

        LocationEntity location = buildLocation("Durham UK");
        when(locationService.findAllEnabled()).thenReturn(List.of(location));

        AtmosphericData atmosphericData = mock(AtmosphericData.class);
        when(atmosphericData.surge()).thenReturn(null);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, atmosphericData, location,
                TEST_DATE, TargetType.SUNRISE,
                TEST_EVENT_TIME, 90, 0,
                EvaluationModel.SONNET, location.getTideType(), "task-key", null);
        when(forecastService.fetchWeatherAndTriage(eq(location), eq(TEST_DATE),
                eq(TargetType.SUNRISE), any(), eq(EvaluationModel.SONNET),
                eq(false), any(), any(), any())).thenReturn(preEval);
        when(promptBuilder.buildUserMessage(any(AtmosphericData.class))).thenReturn("msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("sys");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_today");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.submitForecastBatch();

        // Today's date should pass through the filter and reach the batch
        verify(batchService).create(any(BatchCreateParams.class));
        verify(forecastService).fetchWeatherAndTriage(eq(location), eq(TEST_DATE),
                eq(TargetType.SUNRISE), any(), eq(EvaluationModel.SONNET),
                eq(false), any(), any(), any());
    }

    @Test
    @DisplayName("submitForecastBatch submits batch for GO location")
    void submitForecastBatch_goLocation_submitsBatch() {
        stubBatchService();
        DailyBriefingResponse briefing = buildBriefing(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);

        LocationEntity location = buildLocation("Durham UK");
        when(locationService.findAllEnabled()).thenReturn(List.of(location));

        AtmosphericData atmosphericData = mock(AtmosphericData.class);
        when(atmosphericData.surge()).thenReturn(null);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, atmosphericData, location,
                TEST_DATE, TargetType.SUNRISE,
                TEST_EVENT_TIME, 90, 1,
                EvaluationModel.SONNET, location.getTideType(), "task-key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any(), any(), any())).thenReturn(preEval);
        when(promptBuilder.buildUserMessage(any(AtmosphericData.class)))
                .thenReturn("user message");
        when(promptBuilder.getSystemPrompt()).thenReturn("system prompt");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_test001");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.submitForecastBatch();

        ArgumentCaptor<BatchCreateParams> paramsCaptor =
                ArgumentCaptor.forClass(BatchCreateParams.class);
        verify(batchService).create(paramsCaptor.capture());

        // Default 5-minute TTL — cheaper writes (1.25x vs 2.0x for 1-hour) and batch
        // processing completes within minutes, so cache stays warm within each batch.
        var systemBlock = paramsCaptor.getValue().requests().get(0)
                .params().system().get().asTextBlockParams().get(0);
        assertThat(systemBlock.cacheControl()).isPresent();
        assertThat(systemBlock.cacheControl().get().ttl()).isEmpty();

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
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);

        LocationEntity location = buildLocation("Durham UK");
        when(locationService.findAllEnabled()).thenReturn(List.of(location));

        ForecastPreEvalResult triaged = new ForecastPreEvalResult(
                true, "Overcast", null, location,
                TEST_DATE, TargetType.SUNRISE,
                TEST_EVENT_TIME, 90, 1,
                EvaluationModel.SONNET, location.getTideType(), "task-key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any(), any(), any())).thenReturn(triaged);

        service.submitForecastBatch();

        verify(batchService, never()).create(any());
    }

    @Test
    @DisplayName("submitForecastBatch skips region already cached by SSE path")
    void submitForecastBatch_cachedRegion_skipsRegion() {
        DailyBriefingResponse briefing = buildBriefing(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);
        when(briefingEvaluationService.hasFreshEvaluation(
                eq("North East|" + TEST_DATE + "|SUNRISE"),
                eq(java.time.Duration.ofHours(18)))).thenReturn(true);

        service.submitForecastBatch();

        verifyNoInteractions(forecastService);
        verifyNoInteractions(batchRepository);
    }

    @Test
    @DisplayName("submitForecastBatch: two regions, only the uncached one submits a request")
    void submitForecastBatch_twoRegions_onlyUncachedRegionSubmitted() {
        stubBatchService();

        // Briefing with two regions: North East (cached) and Yorkshire (not cached)
        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(
                20, BigDecimal.ZERO, 10000, 70, 10.0, 9.0, 1, BigDecimal.valueOf(5), 0, 0);
        BriefingSlot northEastSlot = new BriefingSlot("Durham UK",
                TEST_EVENT_TIME,
                Verdict.GO, weather, BriefingSlot.TideInfo.NONE, List.of(), null);
        BriefingSlot yorkshireSlot = new BriefingSlot("Flamborough Head",
                TEST_EVENT_TIME,
                Verdict.GO, weather, BriefingSlot.TideInfo.NONE, List.of(), null);
        BriefingRegion northEastRegion = new BriefingRegion(
                "North East", Verdict.GO, "Summary", List.of(), List.of(northEastSlot),
                null, null, null, null, null, null);
        BriefingRegion yorkshireRegion = new BriefingRegion(
                "Yorkshire", Verdict.GO, "Summary", List.of(), List.of(yorkshireSlot),
                null, null, null, null, null, null);
        BriefingEventSummary es = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(northEastRegion, yorkshireRegion), List.of());
        BriefingDay day = new BriefingDay(TEST_DATE, List.of(es));
        DailyBriefingResponse briefing = new DailyBriefingResponse(
                null, null, List.of(day), null, null, null, false, false, 0, null, List.of(), List.of());

        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);

        // North East is cached (fresh); Yorkshire is not
        when(briefingEvaluationService.hasFreshEvaluation(
                eq("North East|" + TEST_DATE + "|SUNRISE"),
                eq(java.time.Duration.ofHours(18)))).thenReturn(true);
        when(briefingEvaluationService.hasFreshEvaluation(
                eq("Yorkshire|" + TEST_DATE + "|SUNRISE"),
                eq(java.time.Duration.ofHours(18)))).thenReturn(false);

        LocationEntity yorkshireLoc = buildLocation("Flamborough Head");
        RegionEntity yorkshireRegionEntity = new RegionEntity();
        yorkshireRegionEntity.setName("Yorkshire");
        yorkshireLoc.setRegion(yorkshireRegionEntity);
        when(locationService.findAllEnabled()).thenReturn(List.of(yorkshireLoc));

        AtmosphericData atmosphericData = mock(AtmosphericData.class);
        when(atmosphericData.surge()).thenReturn(null);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, atmosphericData, yorkshireLoc,
                TEST_DATE, TargetType.SUNRISE,
                TEST_EVENT_TIME, 90, 1,
                EvaluationModel.SONNET, yorkshireLoc.getTideType(), "task-key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any(), any(), any())).thenReturn(preEval);
        when(promptBuilder.buildUserMessage(any(AtmosphericData.class))).thenReturn("msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("sys");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_tworgn");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.submitForecastBatch();

        // Only Yorkshire's location reaches the batch — requestCount must be 1
        ArgumentCaptor<ForecastBatchEntity> entityCaptor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("submitForecastBatch: customId matches ^[a-zA-Z0-9_-]{1,64}$ and encodes locationId/date/targetType")
    void submitForecastBatch_customIdFormat_matchesAnthropicPatternAndBatchResultProcessorContract() {
        stubBatchService();
        DailyBriefingResponse briefing = buildBriefing(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);

        LocationEntity location = buildLocation("Durham UK"); // id=42, region="North East"
        when(locationService.findAllEnabled()).thenReturn(List.of(location));

        AtmosphericData atmosphericData = mock(AtmosphericData.class);
        when(atmosphericData.surge()).thenReturn(null);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, atmosphericData, location,
                TEST_DATE, TargetType.SUNRISE,
                TEST_EVENT_TIME, 90, 1,
                EvaluationModel.SONNET, location.getTideType(), "task-key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any(), any(), any())).thenReturn(preEval);
        when(promptBuilder.buildUserMessage(any(AtmosphericData.class))).thenReturn("msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("sys");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_customid");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.submitForecastBatch();

        ArgumentCaptor<BatchCreateParams> paramsCaptor =
                ArgumentCaptor.forClass(BatchCreateParams.class);
        verify(batchService).create(paramsCaptor.capture());

        BatchCreateParams params = paramsCaptor.getValue();
        assertThat(params.requests()).hasSize(1);
        String customId = params.requests().get(0).customId();

        // Must satisfy the Anthropic pattern ^[a-zA-Z0-9_-]{1,64}$
        assertThat(customId).matches("[a-zA-Z0-9_-]{1,64}");
        // Must encode enough for BatchResultProcessor to reconstruct the cache key
        assertThat(customId).isEqualTo("fc-42-" + TEST_DATE + "-SUNRISE");
    }

    @Test
    @DisplayName("submitAuroraBatch: customId matches ^[a-zA-Z0-9_-]{1,64}$ and encodes alert level")
    void submitAuroraBatch_customIdFormat_matchesAnthropicPattern() {
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


        when(claudeAuroraInterpreter.buildUserMessage(any(), any(), any(), any(), any(),
                any())).thenReturn("aurora user message");
        when(modelSelectionService.getActiveModel(RunType.AURORA_EVALUATION))
                .thenReturn(EvaluationModel.HAIKU);

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_aurora_fmt");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.submitAuroraBatch();

        ArgumentCaptor<BatchCreateParams> paramsCaptor =
                ArgumentCaptor.forClass(BatchCreateParams.class);
        verify(batchService).create(paramsCaptor.capture());

        BatchCreateParams params = paramsCaptor.getValue();
        assertThat(params.requests()).hasSize(1);
        String customId = params.requests().get(0).customId();

        // Must satisfy the Anthropic pattern ^[a-zA-Z0-9_-]{1,64}$
        assertThat(customId).matches("[a-zA-Z0-9_-]{1,64}");
        // Must start with "au-" and encode the alert level
        assertThat(customId).startsWith("au-MODERATE-");
    }

    @Test
    @DisplayName("submitForecastBatch skips batch when all weather fetches throw exceptions")
    void submitForecastBatch_allWeatherFetchesThrow_noBatchSubmitted() {
        DailyBriefingResponse briefing = buildBriefing(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);

        LocationEntity location = buildLocation("Durham UK");
        when(locationService.findAllEnabled()).thenReturn(List.of(location));

        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any(), any(), any()))
                .thenThrow(new RuntimeException("Open-Meteo unavailable"));

        service.submitForecastBatch();

        // Empty request list → guard fires, submitBatch never called
        verifyNoInteractions(batchRepository);
        verify(batchService, never()).create(any());
    }

    @Test
    @DisplayName("submitForecastBatch skips T+2 location when stability is UNSETTLED (window=0)")
    void submitForecastBatch_unsettledStability_skipsLocation() {
        DailyBriefingResponse briefing = buildBriefing(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
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
                TEST_DATE, TargetType.SUNRISE,
                TEST_EVENT_TIME, 90, 2,
                EvaluationModel.SONNET, location.getTideType(), "task-key", forecastResponse);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any(), any(), any())).thenReturn(preEval);

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
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);
        when(modelSelectionService.getActiveModel(RunType.BATCH_FAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

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
                TEST_DATE, TargetType.SUNRISE,
                TEST_EVENT_TIME, 90, 3,
                EvaluationModel.SONNET, location.getTideType(), "task-key", forecastResponse);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any(), any(), any())).thenReturn(preEval);

        when(stabilityClassifier.classify(
                eq("54.7753,-1.5849"), eq(54.7753), eq(-1.5849), eq(hourly)))
                .thenReturn(new GridCellStabilityResult(
                        "54.7753,-1.5849", 54.7753, -1.5849,
                        ForecastStability.SETTLED, "high pressure", 3));

        when(promptBuilder.buildUserMessage(any(AtmosphericData.class))).thenReturn("user msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("system prompt");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

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
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);

        LocationEntity location = buildLocation("Durham UK");
        // No gridLat/gridLng set — hasGridCell() returns false
        when(locationService.findAllEnabled()).thenReturn(List.of(location));

        AtmosphericData atmosphericData = mock(AtmosphericData.class);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, atmosphericData, location,
                TEST_DATE, TargetType.SUNRISE,
                TEST_EVENT_TIME, 90, 2,
                EvaluationModel.SONNET, location.getTideType(), "task-key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any(), any(), any())).thenReturn(preEval);

        service.submitForecastBatch();

        verifyNoInteractions(batchRepository);
        verifyNoInteractions(stabilityClassifier);
    }

    @Test
    @DisplayName("submitForecastBatch classifies each grid cell only once across multiple locations")
    void submitForecastBatch_sharedGridCell_classifiesOnce() {
        DailyBriefingResponse briefing = buildBriefingTwoSlots(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
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
                TEST_DATE, TargetType.SUNRISE,
                TEST_EVENT_TIME, 90, 2,
                EvaluationModel.SONNET, loc1.getTideType(), "task-key", forecastResponse);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any(), any(), any())).thenReturn(preEval);

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


        when(claudeAuroraInterpreter.buildUserMessage(any(), any(), any(), any(), any(),
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

    // ── Coastal vs Inland Builder Selection ─────────────────────────────────

    @Test
    @DisplayName("submitForecastBatch uses coastalPromptBuilder when location has tide data")
    void submitForecastBatch_coastalLocation_usesCoastalPromptBuilder() {
        stubBatchService();
        DailyBriefingResponse briefing = buildBriefing(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);

        LocationEntity location = buildLocation("Durham UK");
        location.setTideType(Set.of(TideType.HIGH));
        when(locationService.findAllEnabled()).thenReturn(List.of(location));

        TideSnapshot tide = new TideSnapshot(
                TideState.HIGH,
                TEST_DATE.atTime(6, 0),
                new BigDecimal("4.50"),
                TEST_DATE.atTime(12, 15),
                new BigDecimal("1.20"),
                true,
                TEST_DATE.atTime(6, 0),
                null, null, null, null, null);
        AtmosphericData atmosphericData = mock(AtmosphericData.class);
        when(atmosphericData.tide()).thenReturn(tide);
        when(atmosphericData.surge()).thenReturn(null);

        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, atmosphericData, location,
                TEST_DATE, TargetType.SUNRISE,
                TEST_EVENT_TIME, 90, 1,
                EvaluationModel.SONNET, location.getTideType(), "task-key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any(), any(), any())).thenReturn(preEval);
        when(coastalPromptBuilder.buildUserMessage(atmosphericData)).thenReturn("coastal msg");
        when(coastalPromptBuilder.getSystemPrompt()).thenReturn("coastal system");
        when(coastalPromptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_coastal");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.submitForecastBatch();

        verify(coastalPromptBuilder).buildUserMessage(atmosphericData);
        verify(coastalPromptBuilder).getSystemPrompt();
        verifyNoInteractions(promptBuilder);
    }

    @Test
    @DisplayName("submitForecastBatch uses base promptBuilder when location has no tide data")
    void submitForecastBatch_inlandLocation_usesBasePromptBuilder() {
        stubBatchService();
        DailyBriefingResponse briefing = buildBriefing(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);

        LocationEntity location = buildLocation("Durham UK");
        when(locationService.findAllEnabled()).thenReturn(List.of(location));

        AtmosphericData atmosphericData = mock(AtmosphericData.class);
        when(atmosphericData.tide()).thenReturn(null);
        when(atmosphericData.surge()).thenReturn(null);

        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, atmosphericData, location,
                TEST_DATE, TargetType.SUNRISE,
                TEST_EVENT_TIME, 90, 1,
                EvaluationModel.SONNET, location.getTideType(), "task-key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any(), any(), any())).thenReturn(preEval);
        when(promptBuilder.buildUserMessage(atmosphericData)).thenReturn("inland msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("inland system");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_inland");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.submitForecastBatch();

        verify(promptBuilder).buildUserMessage(atmosphericData);
        verify(promptBuilder).getSystemPrompt();
        verifyNoInteractions(coastalPromptBuilder);
    }

    @Test
    @DisplayName("submitForecastBatch uses coastalPromptBuilder surge overload when surge is present")
    void submitForecastBatch_coastalWithSurge_usesSurgeOverload() {
        stubBatchService();
        DailyBriefingResponse briefing = buildBriefing(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);

        LocationEntity location = buildLocation("Durham UK");
        location.setTideType(Set.of(TideType.HIGH));
        when(locationService.findAllEnabled()).thenReturn(List.of(location));

        TideSnapshot tide = new TideSnapshot(
                TideState.HIGH,
                TEST_DATE.atTime(6, 0),
                new BigDecimal("4.50"),
                TEST_DATE.atTime(12, 15),
                new BigDecimal("1.20"),
                true,
                TEST_DATE.atTime(6, 0),
                null, null, null, null, null);
        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.23, 0.12, 0.35, 990.0, 15.0, 60.0, 0.85,
                TideRiskLevel.MODERATE, "Moderate surge");
        AtmosphericData atmosphericData = mock(AtmosphericData.class);
        when(atmosphericData.tide()).thenReturn(tide);
        when(atmosphericData.surge()).thenReturn(surge);
        when(atmosphericData.adjustedRangeMetres()).thenReturn(4.85);
        when(atmosphericData.astronomicalRangeMetres()).thenReturn(4.50);

        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, atmosphericData, location,
                TEST_DATE, TargetType.SUNRISE,
                TEST_EVENT_TIME, 90, 1,
                EvaluationModel.SONNET, location.getTideType(), "task-key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any(), any(), any())).thenReturn(preEval);
        when(coastalPromptBuilder.buildUserMessage(atmosphericData, surge, 4.85, 4.50))
                .thenReturn("coastal surge msg");
        when(coastalPromptBuilder.getSystemPrompt()).thenReturn("coastal system");
        when(coastalPromptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_surge");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.submitForecastBatch();

        verify(coastalPromptBuilder).buildUserMessage(atmosphericData, surge, 4.85, 4.50);
        verify(coastalPromptBuilder).getSystemPrompt();
        verifyNoInteractions(promptBuilder);
    }

    // ── Batch split by prompt type ───────────────────────────────────────────

    @Test
    @DisplayName("submitForecastBatch: mixed inland and coastal locations produce two separate batches")
    void submitForecastBatch_mixedInlandAndCoastal_submitsTwoBatches() {
        stubBatchService();

        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(
                20, BigDecimal.ZERO, 10000, 70, 10.0, 9.0, 1, BigDecimal.valueOf(5), 0, 0);
        BriefingSlot inlandSlot = new BriefingSlot("Durham UK",
                TEST_EVENT_TIME, Verdict.GO, weather,
                BriefingSlot.TideInfo.NONE, List.of(), null);
        BriefingSlot coastalSlot = new BriefingSlot("Whitby",
                TEST_EVENT_TIME, Verdict.GO, weather,
                BriefingSlot.TideInfo.NONE, List.of(), null);
        BriefingRegion region = new BriefingRegion(
                "North East", Verdict.GO, "Summary", List.of(),
                List.of(inlandSlot, coastalSlot),
                null, null, null, null, null, null);
        BriefingEventSummary es = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(region), List.of());
        BriefingDay day = new BriefingDay(TEST_DATE, List.of(es));
        DailyBriefingResponse briefing = new DailyBriefingResponse(
                null, null, List.of(day), null, null, null,
                false, false, 0, null, List.of(), List.of());

        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);

        // Inland location (no tide)
        LocationEntity inlandLoc = new LocationEntity();
        inlandLoc.setId(42L);
        inlandLoc.setName("Durham UK");
        inlandLoc.setLat(54.7753);
        inlandLoc.setLon(-1.5849);
        RegionEntity regionEntity = new RegionEntity();
        regionEntity.setName("North East");
        inlandLoc.setRegion(regionEntity);
        inlandLoc.setTideType(Set.of());

        // Coastal location (with tide)
        LocationEntity coastalLoc = new LocationEntity();
        coastalLoc.setId(99L);
        coastalLoc.setName("Whitby");
        coastalLoc.setLat(54.4858);
        coastalLoc.setLon(-0.6206);
        coastalLoc.setRegion(regionEntity);
        coastalLoc.setTideType(Set.of(TideType.HIGH));

        when(locationService.findAllEnabled()).thenReturn(List.of(inlandLoc, coastalLoc));

        // Inland atmospheric data — no tide
        AtmosphericData inlandData = mock(AtmosphericData.class);
        when(inlandData.tide()).thenReturn(null);
        when(inlandData.surge()).thenReturn(null);
        ForecastPreEvalResult inlandPreEval = new ForecastPreEvalResult(
                false, null, inlandData, inlandLoc,
                TEST_DATE, TargetType.SUNRISE,
                TEST_EVENT_TIME, 90, 1,
                EvaluationModel.SONNET, inlandLoc.getTideType(), "task-key-1", null);

        // Coastal atmospheric data — with tide
        TideSnapshot tide = new TideSnapshot(
                TideState.HIGH, TEST_DATE.atTime(6, 0), new BigDecimal("4.50"),
                TEST_DATE.atTime(12, 15), new BigDecimal("1.20"),
                true, TEST_DATE.atTime(6, 0),
                null, null, null, null, null);
        AtmosphericData coastalData = mock(AtmosphericData.class);
        when(coastalData.tide()).thenReturn(tide);
        when(coastalData.surge()).thenReturn(null);
        ForecastPreEvalResult coastalPreEval = new ForecastPreEvalResult(
                false, null, coastalData, coastalLoc,
                TEST_DATE, TargetType.SUNRISE,
                TEST_EVENT_TIME, 90, 1,
                EvaluationModel.SONNET, coastalLoc.getTideType(), "task-key-2", null);

        when(forecastService.fetchWeatherAndTriage(eq(inlandLoc), eq(TEST_DATE),
                eq(TargetType.SUNRISE), any(), eq(EvaluationModel.SONNET),
                eq(false), any(), any(), any())).thenReturn(inlandPreEval);
        when(forecastService.fetchWeatherAndTriage(eq(coastalLoc), eq(TEST_DATE),
                eq(TargetType.SUNRISE), any(), eq(EvaluationModel.SONNET),
                eq(false), any(), any(), any())).thenReturn(coastalPreEval);

        when(promptBuilder.buildUserMessage(inlandData)).thenReturn("inland msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("inland system");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        when(coastalPromptBuilder.buildUserMessage(coastalData)).thenReturn("coastal msg");
        when(coastalPromptBuilder.getSystemPrompt()).thenReturn("coastal system");
        when(coastalPromptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch inlandBatch = mock(MessageBatch.class);
        when(inlandBatch.id()).thenReturn("msgbatch_inland_split");
        when(inlandBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        MessageBatch coastalBatch = mock(MessageBatch.class);
        when(coastalBatch.id()).thenReturn("msgbatch_coastal_split");
        when(coastalBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class)))
                .thenReturn(inlandBatch)
                .thenReturn(coastalBatch);

        service.submitForecastBatch();

        // Two separate batches submitted
        ArgumentCaptor<BatchCreateParams> paramsCaptor =
                ArgumentCaptor.forClass(BatchCreateParams.class);
        verify(batchService, org.mockito.Mockito.times(2))
                .create(paramsCaptor.capture());

        List<BatchCreateParams> captured = paramsCaptor.getAllValues();

        // First batch: inland — 1 request with promptBuilder's system prompt
        assertThat(captured.get(0).requests()).hasSize(1);
        assertThat(captured.get(0).requests().get(0).params()
                .system().get().asTextBlockParams().get(0).text())
                .isEqualTo("inland system");

        // Second batch: coastal — 1 request with coastalPromptBuilder's system prompt
        assertThat(captured.get(1).requests()).hasSize(1);
        assertThat(captured.get(1).requests().get(0).params()
                .system().get().asTextBlockParams().get(0).text())
                .isEqualTo("coastal system");

        // Two batch entities saved with distinct batch IDs
        ArgumentCaptor<ForecastBatchEntity> entityCaptor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository, org.mockito.Mockito.times(2))
                .save(entityCaptor.capture());
        assertThat(entityCaptor.getAllValues().get(0).getAnthropicBatchId())
                .isEqualTo("msgbatch_inland_split");
        assertThat(entityCaptor.getAllValues().get(1).getAnthropicBatchId())
                .isEqualTo("msgbatch_coastal_split");

        // Two job runs created — one per batch
        verify(jobRunService).startBatchRun(1, "msgbatch_inland_split");
        verify(jobRunService).startBatchRun(1, "msgbatch_coastal_split");
    }

    @Test
    @DisplayName("submitForecastBatch: all-coastal briefing submits exactly one batch, no empty inland batch")
    void submitForecastBatch_allCoastal_submitsSingleBatch() {
        stubBatchService();
        DailyBriefingResponse briefing = buildBriefing(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);

        LocationEntity location = buildLocation("Durham UK");
        location.setTideType(Set.of(TideType.HIGH));
        when(locationService.findAllEnabled()).thenReturn(List.of(location));

        TideSnapshot tide = new TideSnapshot(
                TideState.HIGH, TEST_DATE.atTime(6, 0), new BigDecimal("4.50"),
                TEST_DATE.atTime(12, 15), new BigDecimal("1.20"),
                true, TEST_DATE.atTime(6, 0),
                null, null, null, null, null);
        AtmosphericData atmosphericData = mock(AtmosphericData.class);
        when(atmosphericData.tide()).thenReturn(tide);
        when(atmosphericData.surge()).thenReturn(null);

        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, atmosphericData, location,
                TEST_DATE, TargetType.SUNRISE,
                TEST_EVENT_TIME, 90, 1,
                EvaluationModel.SONNET, location.getTideType(), "task-key", null);
        when(forecastService.fetchWeatherAndTriage(eq(location), eq(TEST_DATE),
                eq(TargetType.SUNRISE), any(), eq(EvaluationModel.SONNET),
                eq(false), any(), any(), any())).thenReturn(preEval);
        when(coastalPromptBuilder.buildUserMessage(atmosphericData))
                .thenReturn("coastal msg");
        when(coastalPromptBuilder.getSystemPrompt()).thenReturn("coastal system");
        when(coastalPromptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_coastal_only");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.submitForecastBatch();

        // Exactly one batch submitted — no empty inland batch
        verify(batchService).create(any(BatchCreateParams.class));
        verify(batchRepository).save(any(ForecastBatchEntity.class));
        verify(jobRunService).startBatchRun(1, "msgbatch_coastal_only");

        // Only coastal builder used
        verify(coastalPromptBuilder).buildUserMessage(atmosphericData);
        verifyNoInteractions(promptBuilder);
    }

    // ── Job run tracking ─────────────────────────────────────────────────────

    @Test
    @DisplayName("submitForecastBatch calls startBatchRun with correct requestCount and batchId")
    void submitForecastBatch_goLocation_callsStartBatchRunWithCorrectArgs() {
        stubBatchService();
        DailyBriefingResponse briefing = buildBriefing(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);
        LocationEntity location = buildLocation("Durham UK");
        when(locationService.findAllEnabled()).thenReturn(List.of(location));
        AtmosphericData atmosphericData = mock(AtmosphericData.class);
        when(atmosphericData.surge()).thenReturn(null);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, atmosphericData, location,
                TEST_DATE, TargetType.SUNRISE,
                TEST_EVENT_TIME, 90, 1,
                EvaluationModel.SONNET, location.getTideType(), "task-key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any(), any(), any())).thenReturn(preEval);
        when(promptBuilder.buildUserMessage(any(AtmosphericData.class))).thenReturn("user msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("system prompt");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_jobrun01");
        when(mockBatch.expiresAt()).thenReturn(java.time.OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        JobRunEntity createdJobRun = JobRunEntity.builder().id(55L).build();
        when(jobRunService.startBatchRun(1, "msgbatch_jobrun01")).thenReturn(createdJobRun);

        service.submitForecastBatch();

        verify(jobRunService).startBatchRun(1, "msgbatch_jobrun01");
    }

    @Test
    @DisplayName("submitForecastBatch stores the jobRunId on the saved batch entity")
    void submitForecastBatch_goLocation_storesJobRunIdOnBatchEntity() {
        stubBatchService();
        DailyBriefingResponse briefing = buildBriefing(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);
        LocationEntity location = buildLocation("Durham UK");
        when(locationService.findAllEnabled()).thenReturn(List.of(location));
        AtmosphericData atmosphericData = mock(AtmosphericData.class);
        when(atmosphericData.surge()).thenReturn(null);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, atmosphericData, location,
                TEST_DATE, TargetType.SUNRISE,
                TEST_EVENT_TIME, 90, 1,
                EvaluationModel.SONNET, location.getTideType(), "task-key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any(), any(), any())).thenReturn(preEval);
        when(promptBuilder.buildUserMessage(any(AtmosphericData.class))).thenReturn("user msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("system prompt");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_jobrun02");
        when(mockBatch.expiresAt()).thenReturn(java.time.OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        JobRunEntity createdJobRun = JobRunEntity.builder().id(66L).build();
        when(jobRunService.startBatchRun(1, "msgbatch_jobrun02")).thenReturn(createdJobRun);

        service.submitForecastBatch();

        ArgumentCaptor<ForecastBatchEntity> entityCaptor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getJobRunId()).isEqualTo(66L);
    }

    @Test
    @DisplayName("no jobRunService interaction when no GO/MARGINAL slots")
    void submitForecastBatch_allStanddown_doesNotCallJobRunService() {
        DailyBriefingResponse briefing = buildBriefing(Verdict.STANDDOWN);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);

        service.submitForecastBatch();

        verifyNoInteractions(jobRunService);
    }

    // ── Concurrent guard ──────────────────────────────────────────────────────

    @Test
    @DisplayName("submitForecastBatch drops second call when batch is already running")
    void submitForecastBatch_alreadyRunning_dropsSecondCall() throws Exception {
        Field field = ScheduledBatchEvaluationService.class
                .getDeclaredField("forecastBatchRunning");
        field.setAccessible(true);
        ((AtomicBoolean) field.get(service)).set(true);

        service.submitForecastBatch();

        verifyNoInteractions(briefingService, openMeteoService, batchRepository);
    }

    @Test
    @DisplayName("submitAuroraBatch drops second call when batch is already running")
    void submitAuroraBatch_alreadyRunning_dropsSecondCall() throws Exception {
        Field field = ScheduledBatchEvaluationService.class
                .getDeclaredField("auroraBatchRunning");
        field.setAccessible(true);
        ((AtomicBoolean) field.get(service)).set(true);

        service.submitAuroraBatch();

        verifyNoInteractions(noaaSwpcClient, batchRepository);
    }

    // ── Bulk weather pre-fetch ────────────────────────────────────────────────

    @Test
    @DisplayName("pre-fetch called once for two tasks sharing the same location coordinates")
    void submitForecastBatch_twoTasksSameLocation_prefetchCalledOnceWithOneCoord() {
        DailyBriefingResponse briefing = buildBriefingTwoSlots(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);

        // Both slots use the same location entity (same lat/lon → one unique coord)
        LocationEntity loc = buildLocation("Durham UK");
        loc.setGridLat(54.7753);
        loc.setGridLng(-1.5849);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));

        when(openMeteoService.prefetchWeatherBatch(any(), eq(null))).thenReturn(Map.of());
        when(openMeteoService.computeDirectionalCloudPoints(
                eq(54.7753), eq(-1.5849), anyInt())).thenReturn(List.of());
        when(openMeteoService.prefetchCloudBatch(any(), eq(null)))
                .thenReturn(mock(com.gregochr.goldenhour.model.CloudPointCache.class));

        service.submitForecastBatch();

        // Exactly one coord passed to the bulk fetch, not two
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<double[]>> coordCaptor = ArgumentCaptor.forClass(List.class);
        verify(openMeteoService).prefetchWeatherBatch(coordCaptor.capture(), eq(null));
        assertThat(coordCaptor.getValue()).hasSize(1);
        assertThat(coordCaptor.getValue().get(0)[0]).isEqualTo(54.7753);
        assertThat(coordCaptor.getValue().get(0)[1]).isEqualTo(-1.5849);
    }

    @Test
    @DisplayName("pre-fetch not called when all slots are STANDDOWN (empty task list)")
    void submitForecastBatch_allStanddown_prefetchNotCalled() {
        DailyBriefingResponse briefing = buildBriefing(Verdict.STANDDOWN);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);

        service.submitForecastBatch();

        verifyNoInteractions(openMeteoService);
    }

    // ── Structured output (outputConfig) on batch requests ─────────────────

    @Test
    @DisplayName("forecast batch request includes outputConfig with JSON schema (prevents chain-of-thought)")
    void submitForecastBatch_requestIncludesOutputConfig() {
        stubBatchService();
        DailyBriefingResponse briefing = buildBriefing(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);

        LocationEntity location = buildLocation("Durham UK");
        when(locationService.findAllEnabled()).thenReturn(List.of(location));

        AtmosphericData atmosphericData = mock(AtmosphericData.class);
        when(atmosphericData.surge()).thenReturn(null);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, atmosphericData, location,
                TEST_DATE, TargetType.SUNRISE,
                TEST_EVENT_TIME, 90, 1,
                EvaluationModel.SONNET, location.getTideType(), "task-key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any(), any(), any())).thenReturn(preEval);
        when(promptBuilder.buildUserMessage(any(AtmosphericData.class)))
                .thenReturn("user message");
        when(promptBuilder.getSystemPrompt()).thenReturn("system prompt");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_outputcfg");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.submitForecastBatch();

        ArgumentCaptor<BatchCreateParams> paramsCaptor =
                ArgumentCaptor.forClass(BatchCreateParams.class);
        verify(batchService).create(paramsCaptor.capture());

        BatchCreateParams.Request.Params requestParams =
                paramsCaptor.getValue().requests().get(0).params();

        // outputConfig must be present — its absence caused 100% parse failures in production
        assertThat(requestParams.outputConfig()).isPresent();

        OutputConfig outputConfig = requestParams.outputConfig().get();
        assertThat(outputConfig.format()).isPresent();

        JsonOutputFormat jsonFormat = outputConfig.format().get();
        JsonOutputFormat.Schema schema = jsonFormat.schema();
        Map<String, JsonValue> schemaProps = schema._additionalProperties();

        // Schema must declare required scoring fields so Claude returns structured JSON
        assertThat(schemaProps).containsKey("properties");
        assertThat(schemaProps).containsKey("required");
    }

    @Test
    @DisplayName("coastal batch request also includes outputConfig with JSON schema")
    void submitForecastBatch_coastalRequest_includesOutputConfig() {
        stubBatchService();
        DailyBriefingResponse briefing = buildBriefing(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);

        LocationEntity location = buildLocation("Durham UK");
        location.setTideType(Set.of(TideType.HIGH));
        when(locationService.findAllEnabled()).thenReturn(List.of(location));

        TideSnapshot tide = new TideSnapshot(
                TideState.HIGH,
                TEST_DATE.atTime(6, 0),
                new BigDecimal("4.50"),
                TEST_DATE.atTime(12, 15),
                new BigDecimal("1.20"),
                true,
                TEST_DATE.atTime(6, 0),
                null, null, null, null, null);
        AtmosphericData atmosphericData = mock(AtmosphericData.class);
        when(atmosphericData.tide()).thenReturn(tide);
        when(atmosphericData.surge()).thenReturn(null);

        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, atmosphericData, location,
                TEST_DATE, TargetType.SUNRISE,
                TEST_EVENT_TIME, 90, 1,
                EvaluationModel.SONNET, location.getTideType(), "task-key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any(), any(), any())).thenReturn(preEval);
        when(coastalPromptBuilder.buildUserMessage(atmosphericData))
                .thenReturn("coastal msg");
        when(coastalPromptBuilder.getSystemPrompt()).thenReturn("coastal system");
        when(coastalPromptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_coastal_oc");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.submitForecastBatch();

        ArgumentCaptor<BatchCreateParams> paramsCaptor =
                ArgumentCaptor.forClass(BatchCreateParams.class);
        verify(batchService).create(paramsCaptor.capture());

        BatchCreateParams.Request.Params requestParams =
                paramsCaptor.getValue().requests().get(0).params();

        assertThat(requestParams.outputConfig()).isPresent();
        assertThat(requestParams.outputConfig().get().format()).isPresent();
    }

    @Test
    @DisplayName("batch request passes the same outputConfig the promptBuilder provides (not a different schema)")
    void submitForecastBatch_outputConfigMatchesPromptBuilder() {
        stubBatchService();
        DailyBriefingResponse briefing = buildBriefing(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);

        LocationEntity location = buildLocation("Durham UK");
        when(locationService.findAllEnabled()).thenReturn(List.of(location));

        AtmosphericData atmosphericData = mock(AtmosphericData.class);
        when(atmosphericData.surge()).thenReturn(null);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, atmosphericData, location,
                TEST_DATE, TargetType.SUNRISE,
                TEST_EVENT_TIME, 90, 1,
                EvaluationModel.SONNET, location.getTideType(), "task-key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any(), any(), any())).thenReturn(preEval);
        when(promptBuilder.buildUserMessage(any(AtmosphericData.class)))
                .thenReturn("user msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("sys");
        OutputConfig expectedConfig = buildTestOutputConfig();
        when(promptBuilder.buildOutputConfig()).thenReturn(expectedConfig);

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_samecfg");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.submitForecastBatch();

        ArgumentCaptor<BatchCreateParams> paramsCaptor =
                ArgumentCaptor.forClass(BatchCreateParams.class);
        verify(batchService).create(paramsCaptor.capture());

        OutputConfig actualConfig = paramsCaptor.getValue().requests().get(0)
                .params().outputConfig().orElseThrow();

        // The batch must pass through the exact OutputConfig from the builder
        assertThat(actualConfig).isEqualTo(expectedConfig);
    }

    // ── Near-term / Far-term split ──────────────────────────────────────────

    @Test
    @DisplayName("submitForecastBatch splits T+0 near-term and T+3 SETTLED far-term with different models")
    void submitForecastBatch_nearAndFarTerm_splitIntoDifferentBatches() {
        stubBatchService();

        // Build a briefing with two dates: today (T+0) and today+3 (T+3)
        LocalDate today = TEST_DATE;
        LocalDate threeDaysAhead = TEST_DATE.plusDays(3);

        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(
                20, BigDecimal.ZERO, 10000, 70, 10.0, 9.0, 1, BigDecimal.valueOf(5), 0, 0);

        BriefingSlot todaySlot = new BriefingSlot("Durham UK",
                today.atTime(5, 30), Verdict.GO, weather,
                BriefingSlot.TideInfo.NONE, List.of(), null);
        BriefingSlot farSlot = new BriefingSlot("Sunderland",
                threeDaysAhead.atTime(5, 30), Verdict.GO, weather,
                BriefingSlot.TideInfo.NONE, List.of(), null);

        BriefingRegion todayRegion = new BriefingRegion(
                "North East", Verdict.GO, "Summary", List.of(), List.of(todaySlot),
                null, null, null, null, null, null);
        BriefingRegion farRegion = new BriefingRegion(
                "North East", Verdict.GO, "Summary", List.of(), List.of(farSlot),
                null, null, null, null, null, null);

        BriefingEventSummary todayEvent = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(todayRegion), List.of());
        BriefingEventSummary farEvent = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(farRegion), List.of());

        BriefingDay todayDay = new BriefingDay(today, List.of(todayEvent));
        BriefingDay farDay = new BriefingDay(threeDaysAhead, List.of(farEvent));
        DailyBriefingResponse briefing = new DailyBriefingResponse(
                null, null, List.of(todayDay, farDay), null, null, null,
                false, false, 0, null, List.of(), List.of());

        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);
        when(modelSelectionService.getActiveModel(RunType.BATCH_FAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        LocationEntity durhamLoc = buildLocation("Durham UK");
        durhamLoc.setGridLat(54.7753);
        durhamLoc.setGridLng(-1.5849);
        LocationEntity sunderlandLoc = new LocationEntity();
        sunderlandLoc.setId(43L);
        sunderlandLoc.setName("Sunderland");
        sunderlandLoc.setLat(54.9069);
        sunderlandLoc.setLon(-1.3838);
        sunderlandLoc.setGridLat(54.9069);
        sunderlandLoc.setGridLng(-1.3838);
        RegionEntity region = new RegionEntity();
        region.setName("North East");
        sunderlandLoc.setRegion(region);
        sunderlandLoc.setTideType(Set.of());
        when(locationService.findAllEnabled()).thenReturn(List.of(durhamLoc, sunderlandLoc));

        // T+0 near-term task — null forecastResponse so stability defaults to 1
        AtmosphericData nearData = mock(AtmosphericData.class);
        when(nearData.surge()).thenReturn(null);
        ForecastPreEvalResult nearPreEval = new ForecastPreEvalResult(
                false, null, nearData, durhamLoc,
                today, TargetType.SUNRISE,
                today.atTime(5, 30), 90, 0,
                EvaluationModel.SONNET, durhamLoc.getTideType(), "task-key-1",
                null);
        when(forecastService.fetchWeatherAndTriage(eq(durhamLoc), eq(today),
                eq(TargetType.SUNRISE), any(), eq(EvaluationModel.SONNET),
                eq(false), any(), any(), any())).thenReturn(nearPreEval);

        // T+3 far-term task — SETTLED stability
        AtmosphericData farData = mock(AtmosphericData.class);
        when(farData.surge()).thenReturn(null);
        OpenMeteoForecastResponse farForecastResponse = mock(OpenMeteoForecastResponse.class);
        OpenMeteoForecastResponse.Hourly farHourly = mock(OpenMeteoForecastResponse.Hourly.class);
        when(farForecastResponse.getHourly()).thenReturn(farHourly);
        ForecastPreEvalResult farPreEval = new ForecastPreEvalResult(
                false, null, farData, sunderlandLoc,
                threeDaysAhead, TargetType.SUNRISE,
                threeDaysAhead.atTime(5, 30), 90, 3,
                EvaluationModel.SONNET, sunderlandLoc.getTideType(), "task-key-2",
                farForecastResponse);
        when(forecastService.fetchWeatherAndTriage(eq(sunderlandLoc), eq(threeDaysAhead),
                eq(TargetType.SUNRISE), any(), eq(EvaluationModel.SONNET),
                eq(false), any(), any(), any())).thenReturn(farPreEval);

        when(stabilityClassifier.classify(
                eq("54.9069,-1.3838"), eq(54.9069), eq(-1.3838), eq(farHourly)))
                .thenReturn(new GridCellStabilityResult(
                        "54.9069,-1.3838", 54.9069, -1.3838,
                        ForecastStability.SETTLED, "high pressure", 3));

        when(promptBuilder.buildUserMessage(any(AtmosphericData.class))).thenReturn("msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("sys");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch nearBatch = mock(MessageBatch.class);
        when(nearBatch.id()).thenReturn("msgbatch_near");
        when(nearBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        MessageBatch farBatch = mock(MessageBatch.class);
        when(farBatch.id()).thenReturn("msgbatch_far");
        when(farBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class)))
                .thenReturn(nearBatch)
                .thenReturn(farBatch);

        service.submitForecastBatch();

        // Two separate batches submitted — near and far
        ArgumentCaptor<BatchCreateParams> paramsCaptor =
                ArgumentCaptor.forClass(BatchCreateParams.class);
        verify(batchService, org.mockito.Mockito.times(2))
                .create(paramsCaptor.capture());

        List<BatchCreateParams> captured = paramsCaptor.getAllValues();

        // Near-term batch uses SONNET model
        assertThat(captured.get(0).requests()).hasSize(1);
        assertThat(captured.get(0).requests().get(0).params().model().asString())
                .contains("sonnet");

        // Far-term batch uses HAIKU model
        assertThat(captured.get(1).requests()).hasSize(1);
        assertThat(captured.get(1).requests().get(0).params().model().asString())
                .contains("haiku");

        // Two job runs created with correct batch IDs
        verify(jobRunService).startBatchRun(1, "msgbatch_near");
        verify(jobRunService).startBatchRun(1, "msgbatch_far");
    }

    @Test
    @DisplayName("NEAR_TERM_MAX_DAYS constant is 1 (T+0 and T+1 are near-term)")
    void nearTermMaxDays_isOne() {
        assertThat(ScheduledBatchEvaluationService.NEAR_TERM_MAX_DAYS).isEqualTo(1);
    }

    @Test
    @DisplayName("T+1 task is near-term (boundary: daysAhead == NEAR_TERM_MAX_DAYS)")
    void submitForecastBatch_tPlusOne_isNearTerm() {
        stubBatchService();

        LocalDate tomorrow = TEST_DATE.plusDays(1);
        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(
                20, BigDecimal.ZERO, 10000, 70, 10.0, 9.0, 1, BigDecimal.valueOf(5), 0, 0);
        BriefingSlot slot = new BriefingSlot("Durham UK",
                tomorrow.atTime(5, 30), Verdict.GO, weather,
                BriefingSlot.TideInfo.NONE, List.of(), null);
        BriefingRegion region = new BriefingRegion(
                "North East", Verdict.GO, "Summary", List.of(), List.of(slot),
                null, null, null, null, null, null);
        BriefingEventSummary event = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(region), List.of());
        BriefingDay day = new BriefingDay(tomorrow, List.of(event));
        DailyBriefingResponse briefing = new DailyBriefingResponse(
                null, null, List.of(day), null, null, null,
                false, false, 0, null, List.of(), List.of());

        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);
        when(modelSelectionService.getActiveModel(RunType.BATCH_FAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        LocationEntity location = buildLocation("Durham UK");
        when(locationService.findAllEnabled()).thenReturn(List.of(location));

        AtmosphericData atmosphericData = mock(AtmosphericData.class);
        when(atmosphericData.surge()).thenReturn(null);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, atmosphericData, location,
                tomorrow, TargetType.SUNRISE,
                tomorrow.atTime(5, 30), 90, 1,
                EvaluationModel.SONNET, location.getTideType(), "task-key", null);
        when(forecastService.fetchWeatherAndTriage(eq(location), eq(tomorrow),
                eq(TargetType.SUNRISE), any(), eq(EvaluationModel.SONNET),
                eq(false), any(), any(), any())).thenReturn(preEval);
        when(promptBuilder.buildUserMessage(any(AtmosphericData.class))).thenReturn("msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("sys");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_near1");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.submitForecastBatch();

        // Exactly one batch — all near-term, no far-term
        ArgumentCaptor<BatchCreateParams> captor =
                ArgumentCaptor.forClass(BatchCreateParams.class);
        verify(batchService).create(captor.capture());
        assertThat(captor.getValue().requests()).hasSize(1);
        // Uses SONNET (near-term model), not HAIKU (far-term model)
        assertThat(captor.getValue().requests().get(0).params().model().asString())
                .contains("sonnet");
    }

    @Test
    @DisplayName("T+2 SETTLED task is far-term (boundary: daysAhead > NEAR_TERM_MAX_DAYS)")
    void submitForecastBatch_tPlusTwo_isFarTerm() {
        stubBatchService();

        LocalDate twoDaysAhead = TEST_DATE.plusDays(2);
        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(
                20, BigDecimal.ZERO, 10000, 70, 10.0, 9.0, 1, BigDecimal.valueOf(5), 0, 0);
        BriefingSlot slot = new BriefingSlot("Durham UK",
                twoDaysAhead.atTime(5, 30), Verdict.GO, weather,
                BriefingSlot.TideInfo.NONE, List.of(), null);
        BriefingRegion region = new BriefingRegion(
                "North East", Verdict.GO, "Summary", List.of(), List.of(slot),
                null, null, null, null, null, null);
        BriefingEventSummary event = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(region), List.of());
        BriefingDay day = new BriefingDay(twoDaysAhead, List.of(event));
        DailyBriefingResponse briefing = new DailyBriefingResponse(
                null, null, List.of(day), null, null, null,
                false, false, 0, null, List.of(), List.of());

        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);
        when(modelSelectionService.getActiveModel(RunType.BATCH_FAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        LocationEntity location = buildLocation("Durham UK");
        location.setGridLat(54.7753);
        location.setGridLng(-1.5849);
        when(locationService.findAllEnabled()).thenReturn(List.of(location));

        AtmosphericData atmosphericData = mock(AtmosphericData.class);
        when(atmosphericData.surge()).thenReturn(null);
        OpenMeteoForecastResponse forecastResponse = mock(OpenMeteoForecastResponse.class);
        OpenMeteoForecastResponse.Hourly hourly = mock(OpenMeteoForecastResponse.Hourly.class);
        when(forecastResponse.getHourly()).thenReturn(hourly);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, atmosphericData, location,
                twoDaysAhead, TargetType.SUNRISE,
                twoDaysAhead.atTime(5, 30), 90, 2,
                EvaluationModel.SONNET, location.getTideType(), "task-key",
                forecastResponse);
        when(forecastService.fetchWeatherAndTriage(eq(location), eq(twoDaysAhead),
                eq(TargetType.SUNRISE), any(), eq(EvaluationModel.SONNET),
                eq(false), any(), any(), any())).thenReturn(preEval);

        when(stabilityClassifier.classify(
                eq("54.7753,-1.5849"), eq(54.7753), eq(-1.5849), eq(hourly)))
                .thenReturn(new GridCellStabilityResult(
                        "54.7753,-1.5849", 54.7753, -1.5849,
                        ForecastStability.SETTLED, "high pressure", 3));

        when(promptBuilder.buildUserMessage(any(AtmosphericData.class))).thenReturn("msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("sys");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_far2");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.submitForecastBatch();

        // Exactly one batch — all far-term, no near-term
        ArgumentCaptor<BatchCreateParams> captor =
                ArgumentCaptor.forClass(BatchCreateParams.class);
        verify(batchService).create(captor.capture());
        assertThat(captor.getValue().requests()).hasSize(1);
        // Uses HAIKU (far-term model), not SONNET (near-term model)
        assertThat(captor.getValue().requests().get(0).params().model().asString())
                .contains("haiku");
    }

    @Test
    @DisplayName("triage call always uses near-term model regardless of daysAhead")
    void submitForecastBatch_triageUsesNearTermModel() {
        stubBatchService();

        LocalDate threeDaysAhead = TEST_DATE.plusDays(3);
        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(
                20, BigDecimal.ZERO, 10000, 70, 10.0, 9.0, 1, BigDecimal.valueOf(5), 0, 0);
        BriefingSlot slot = new BriefingSlot("Durham UK",
                threeDaysAhead.atTime(5, 30), Verdict.GO, weather,
                BriefingSlot.TideInfo.NONE, List.of(), null);
        BriefingRegion region = new BriefingRegion(
                "North East", Verdict.GO, "Summary", List.of(), List.of(slot),
                null, null, null, null, null, null);
        BriefingEventSummary event = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(region), List.of());
        BriefingDay day = new BriefingDay(threeDaysAhead, List.of(event));
        DailyBriefingResponse briefing = new DailyBriefingResponse(
                null, null, List.of(day), null, null, null,
                false, false, 0, null, List.of(), List.of());

        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);
        when(modelSelectionService.getActiveModel(RunType.BATCH_FAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        LocationEntity location = buildLocation("Durham UK");
        location.setGridLat(54.7753);
        location.setGridLng(-1.5849);
        when(locationService.findAllEnabled()).thenReturn(List.of(location));

        AtmosphericData atmosphericData = mock(AtmosphericData.class);
        when(atmosphericData.surge()).thenReturn(null);
        OpenMeteoForecastResponse forecastResponse = mock(OpenMeteoForecastResponse.class);
        OpenMeteoForecastResponse.Hourly hourly = mock(OpenMeteoForecastResponse.Hourly.class);
        when(forecastResponse.getHourly()).thenReturn(hourly);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, atmosphericData, location,
                threeDaysAhead, TargetType.SUNRISE,
                threeDaysAhead.atTime(5, 30), 90, 3,
                EvaluationModel.SONNET, location.getTideType(), "task-key",
                forecastResponse);
        // Triage is called with SONNET (near-term model), not HAIKU
        when(forecastService.fetchWeatherAndTriage(eq(location), eq(threeDaysAhead),
                eq(TargetType.SUNRISE), any(), eq(EvaluationModel.SONNET),
                eq(false), any(), any(), any())).thenReturn(preEval);

        when(stabilityClassifier.classify(
                eq("54.7753,-1.5849"), eq(54.7753), eq(-1.5849), eq(hourly)))
                .thenReturn(new GridCellStabilityResult(
                        "54.7753,-1.5849", 54.7753, -1.5849,
                        ForecastStability.SETTLED, "high pressure", 3));

        when(promptBuilder.buildUserMessage(any(AtmosphericData.class))).thenReturn("msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("sys");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_triage");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.submitForecastBatch();

        // Verify triage was called with SONNET, not HAIKU
        verify(forecastService).fetchWeatherAndTriage(eq(location), eq(threeDaysAhead),
                eq(TargetType.SUNRISE), any(), eq(EvaluationModel.SONNET),
                eq(false), any(), any(), any());
        // But the batch request itself uses HAIKU (far-term model)
        ArgumentCaptor<BatchCreateParams> captor =
                ArgumentCaptor.forClass(BatchCreateParams.class);
        verify(batchService).create(captor.capture());
        assertThat(captor.getValue().requests().get(0).params().model().asString())
                .contains("haiku");
    }

    @Test
    @DisplayName("coastal near-term task goes to coastal batch, not inland")
    void submitForecastBatch_coastalNearTerm_goesToCoastalBatch() {
        stubBatchService();

        DailyBriefingResponse briefing = buildBriefing(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);
        when(modelSelectionService.getActiveModel(RunType.BATCH_FAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        LocationEntity location = buildLocation("Durham UK");
        location.setTideType(Set.of(TideType.HIGH));
        when(locationService.findAllEnabled()).thenReturn(List.of(location));

        TideSnapshot tide = new TideSnapshot(
                TideState.HIGH,
                TEST_DATE.atTime(6, 0),
                new BigDecimal("4.50"),
                TEST_DATE.atTime(12, 15),
                new BigDecimal("1.20"),
                true,
                TEST_DATE.atTime(6, 0),
                null, null, null, null, null);
        AtmosphericData atmosphericData = mock(AtmosphericData.class);
        when(atmosphericData.tide()).thenReturn(tide);
        when(atmosphericData.surge()).thenReturn(null);

        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, atmosphericData, location,
                TEST_DATE, TargetType.SUNRISE,
                TEST_EVENT_TIME, 90, 0,
                EvaluationModel.SONNET, location.getTideType(), "task-key", null);
        when(forecastService.fetchWeatherAndTriage(eq(location), eq(TEST_DATE),
                eq(TargetType.SUNRISE), any(), eq(EvaluationModel.SONNET),
                eq(false), any(), any(), any())).thenReturn(preEval);
        when(coastalPromptBuilder.buildUserMessage(any(AtmosphericData.class)))
                .thenReturn("coastal-msg");
        when(coastalPromptBuilder.getSystemPrompt()).thenReturn("coastal-sys");
        when(coastalPromptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_coastal");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.submitForecastBatch();

        // Only one batch — coastal, not inland
        verify(batchService).create(any(BatchCreateParams.class));
        // Coastal prompt builder was used, not the standard one
        verify(coastalPromptBuilder).buildUserMessage(any(AtmosphericData.class));
        verify(coastalPromptBuilder).getSystemPrompt();
        verifyNoInteractions(promptBuilder);
    }

    @Test
    @DisplayName("model selection resolves both BATCH_NEAR_TERM and BATCH_FAR_TERM")
    void submitForecastBatch_resolvesBothModels() {
        stubBatchService();
        DailyBriefingResponse briefing = buildBriefing(Verdict.GO);
        when(briefingService.getCachedBriefing()).thenReturn(briefing);
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);
        when(modelSelectionService.getActiveModel(RunType.BATCH_FAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        LocationEntity location = buildLocation("Durham UK");
        when(locationService.findAllEnabled()).thenReturn(List.of(location));

        AtmosphericData atmosphericData = mock(AtmosphericData.class);
        when(atmosphericData.surge()).thenReturn(null);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, atmosphericData, location,
                TEST_DATE, TargetType.SUNRISE,
                TEST_EVENT_TIME, 90, 0,
                EvaluationModel.SONNET, location.getTideType(), "task-key", null);
        when(forecastService.fetchWeatherAndTriage(eq(location), eq(TEST_DATE),
                eq(TargetType.SUNRISE), any(), eq(EvaluationModel.SONNET),
                eq(false), any(), any(), any())).thenReturn(preEval);
        when(promptBuilder.buildUserMessage(any(AtmosphericData.class))).thenReturn("msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("sys");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_models");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.submitForecastBatch();

        // Both models resolved at startup — verify exact RunType arguments
        verify(modelSelectionService).getActiveModel(RunType.BATCH_NEAR_TERM);
        verify(modelSelectionService).getActiveModel(RunType.BATCH_FAR_TERM);
    }

    private DailyBriefingResponse buildBriefing(Verdict verdict) {
        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(
                20, BigDecimal.ZERO, 10000, 70, 10.0, 9.0, 1, BigDecimal.valueOf(5), 0, 0);
        BriefingSlot slot = new BriefingSlot("Durham UK",
                TEST_EVENT_TIME,
                verdict, weather, BriefingSlot.TideInfo.NONE, List.of(), null);
        BriefingRegion region = new BriefingRegion(
                "North East", verdict, "Summary", List.of(), List.of(slot),
                null, null, null, null, null, null);
        BriefingEventSummary eventSummary = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(region), List.of());
        BriefingDay day = new BriefingDay(TEST_DATE, List.of(eventSummary));
        return new DailyBriefingResponse(null, null, List.of(day), null, null, null,
                false, false, 0, null, List.of(), List.of());
    }

    private DailyBriefingResponse buildBriefingForDate(LocalDate date, Verdict verdict) {
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

    private DailyBriefingResponse buildBriefingTwoSlots(Verdict verdict) {
        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(
                20, BigDecimal.ZERO, 10000, 70, 10.0, 9.0, 1, BigDecimal.valueOf(5), 0, 0);
        BriefingSlot slot1 = new BriefingSlot("Durham UK",
                TEST_EVENT_TIME,
                verdict, weather, BriefingSlot.TideInfo.NONE, List.of(), null);
        BriefingSlot slot2 = new BriefingSlot("Sunderland",
                TEST_EVENT_TIME,
                verdict, weather, BriefingSlot.TideInfo.NONE, List.of(), null);
        BriefingRegion region = new BriefingRegion(
                "North East", verdict, "Summary", List.of(), List.of(slot1, slot2),
                null, null, null, null, null, null);
        BriefingEventSummary eventSummary = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(region), List.of());
        BriefingDay day = new BriefingDay(TEST_DATE, List.of(eventSummary));
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

    // ── resetBatchGuards ──────────────────────────────────────────────────

    @Test
    @DisplayName("resetBatchGuards clears forecastBatchRunning guard")
    void resetBatchGuards_clearsForecastGuard() throws Exception {
        // Set the forecast guard to true via reflection
        Field forecastField = ScheduledBatchEvaluationService.class
                .getDeclaredField("forecastBatchRunning");
        forecastField.setAccessible(true);
        ((AtomicBoolean) forecastField.get(service)).set(true);

        service.resetBatchGuards();

        // After reset, submitForecastBatch should enter (not skip)
        // Verify by checking it proceeds to call briefingService
        when(briefingService.getCachedBriefing()).thenReturn(null);
        service.submitForecastBatch();
        verify(briefingService).getCachedBriefing();
    }

    @Test
    @DisplayName("resetBatchGuards clears auroraBatchRunning guard")
    void resetBatchGuards_clearsAuroraGuard() throws Exception {
        // Set the aurora guard to true via reflection
        Field auroraField = ScheduledBatchEvaluationService.class
                .getDeclaredField("auroraBatchRunning");
        auroraField.setAccessible(true);
        ((AtomicBoolean) auroraField.get(service)).set(true);

        service.resetBatchGuards();

        // After reset, submitAuroraBatch should enter (not skip)
        // Verify by checking it proceeds to call noaaSwpcClient
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
    void submitForecastBatch_exceptionInDoSubmit_clearsGuard() throws Exception {
        when(briefingService.getCachedBriefing())
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(null);

        // First call throws but should clear guard in finally
        try {
            service.submitForecastBatch();
        } catch (RuntimeException ignored) {
            // Expected — guard must still be cleared
        }

        // Guard should be cleared — second call should enter again (not skip)
        service.submitForecastBatch();
        verify(briefingService, org.mockito.Mockito.times(2)).getCachedBriefing();
    }

    private static OutputConfig buildTestOutputConfig() {
        return OutputConfig.builder()
                .format(JsonOutputFormat.builder()
                        .schema(JsonOutputFormat.Schema.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.ofEntries(
                                        Map.entry("rating", Map.of("type", "integer")),
                                        Map.entry("fiery_sky", Map.of("type", "integer")),
                                        Map.entry("golden_hour", Map.of("type", "integer")),
                                        Map.entry("summary", Map.of("type", "string")))))
                                .putAdditionalProperty("required", JsonValue.from(
                                        List.of("rating", "fiery_sky", "golden_hour", "summary")))
                                .build())
                        .build())
                .build();
    }
}
