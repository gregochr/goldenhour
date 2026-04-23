package com.gregochr.goldenhour.service.batch;

import com.anthropic.client.AnthropicClient;
import com.anthropic.services.blocking.MessageService;
import com.anthropic.services.blocking.messages.BatchService;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.CostCalculator;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.service.aurora.ClaudeAuroraInterpreter;
import com.gregochr.goldenhour.service.aurora.WeatherTriageService;
import com.gregochr.goldenhour.service.evaluation.ClaudeEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BatchResultProcessor}.
 */
@ExtendWith(MockitoExtension.class)
class BatchResultProcessorTest {

    @Mock
    private AnthropicClient anthropicClient;
    @Mock
    private MessageService messageService;
    @Mock
    private BatchService batchService;
    @Mock
    private ForecastBatchRepository batchRepository;
    @Mock
    private BriefingEvaluationService briefingEvaluationService;
    @Mock
    private Map<EvaluationModel, EvaluationStrategy> evaluationStrategies;
    @Mock
    private ModelSelectionService modelSelectionService;
    @Mock
    private ClaudeAuroraInterpreter claudeAuroraInterpreter;
    @Mock
    private AuroraStateCache auroraStateCache;
    @Mock
    private WeatherTriageService weatherTriageService;
    @Mock
    private LocationRepository locationRepository;
    @Mock
    private AuroraProperties auroraProperties;
    @Mock
    private NoaaSwpcClient noaaSwpcClient;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private JobRunService jobRunService;
    @Mock
    private CostCalculator costCalculator;

    private BatchResultProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new BatchResultProcessor(
                anthropicClient, batchRepository, briefingEvaluationService,
                evaluationStrategies, modelSelectionService, claudeAuroraInterpreter,
                auroraStateCache, weatherTriageService, locationRepository,
                auroraProperties, noaaSwpcClient, objectMapper, jobRunService, costCalculator);
    }

    private void stubBatchService() {
        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.batches()).thenReturn(batchService);
    }

    // ── FORECAST: stream failure ─────────────────────────────────────────────

    @Test
    @DisplayName("processResults marks FAILED when stream throws exception")
    void processResults_streamThrows_marksFailed() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);
        when(batchService.resultsStreaming("msgbatch_fail"))
                .thenThrow(new RuntimeException("Network error"));

        processor.processResults(batch);

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(captor.getValue().getErrorMessage()).contains("Network error");
    }

    // ── FORECAST: non-succeeded response ────────────────────────────────────

    @Test
    @DisplayName("processResults for FORECAST marks FAILED when all responses errored")
    void processResults_allErrored_marksFailed() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);

        MessageBatchIndividualResponse response = mock(MessageBatchIndividualResponse.class);
        com.anthropic.models.messages.batches.MessageBatchResult result =
                mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        when(response.result()).thenReturn(result);
        when(response.customId()).thenReturn("fc-42-2026-04-07-SUNRISE");
        when(result.isSucceeded()).thenReturn(false);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(captor.getValue().getSucceededCount()).isEqualTo(0);
    }

    // ── FORECAST: success path ───────────────────────────────────────────────

    @Test
    @DisplayName("processResults for FORECAST writes results to evaluation cache")
    void processResults_forecastBatch_writesResultsToCache() throws Exception {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);

        String responseText = "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":68,"
                + "\"summary\":\"Good conditions\"}";

        LocationEntity location = buildLocationWithRegion(42L, "Durham UK", "North East");
        when(locationRepository.findById(42L)).thenReturn(Optional.of(location));

        MessageBatchIndividualResponse response = succeededResponse(
                "fc-42-2026-04-07-SUNRISE", responseText);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        when(modelSelectionService.getActiveModel(eq(RunType.SHORT_TERM)))
                .thenReturn(EvaluationModel.SONNET);
        ClaudeEvaluationStrategy strategy = mock(ClaudeEvaluationStrategy.class);
        when(evaluationStrategies.get(EvaluationModel.SONNET)).thenReturn(strategy);
        when(strategy.parseEvaluation(responseText, objectMapper))
                .thenReturn(new com.gregochr.goldenhour.model.SunsetEvaluation(
                        4, 72, 68, "Good conditions"));

        processor.processResults(batch);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BriefingEvaluationResult>> resultsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(briefingEvaluationService).writeFromBatch(
                eq("North East|2026-04-07|SUNRISE"), resultsCaptor.capture());
        assertThat(resultsCaptor.getValue()).hasSize(1);
        assertThat(resultsCaptor.getValue().get(0).locationName()).isEqualTo("Durham UK");

        ArgumentCaptor<ForecastBatchEntity> entityCaptor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(entityCaptor.getValue().getSucceededCount()).isEqualTo(1);
        assertThat(entityCaptor.getValue().getErroredCount()).isEqualTo(0);
        assertThat(entityCaptor.getValue().getEndedAt()).isNotNull();
    }

    @Test
    @DisplayName("writeFromBatch receives BriefingEvaluationResult with correct location name and scores")
    void processResults_forecastBatch_resultContainsCorrectLocationNameAndScores() throws Exception {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);

        String responseText = "{\"rating\":3,\"fiery_sky\":55,\"golden_hour\":60,"
                + "\"summary\":\"Decent chance\"}";

        LocationEntity location = buildLocationWithRegion(7L, "Bamburgh Castle", "North East");
        when(locationRepository.findById(7L)).thenReturn(Optional.of(location));

        MessageBatchIndividualResponse response = succeededResponse(
                "fc-7-2026-04-10-SUNSET", responseText);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        when(modelSelectionService.getActiveModel(eq(RunType.SHORT_TERM)))
                .thenReturn(EvaluationModel.SONNET);
        ClaudeEvaluationStrategy strategy = mock(ClaudeEvaluationStrategy.class);
        when(evaluationStrategies.get(EvaluationModel.SONNET)).thenReturn(strategy);
        when(strategy.parseEvaluation(responseText, objectMapper))
                .thenReturn(new com.gregochr.goldenhour.model.SunsetEvaluation(
                        3, 55, 60, "Decent chance"));

        processor.processResults(batch);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BriefingEvaluationResult>> resultsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(briefingEvaluationService).writeFromBatch(
                eq("North East|2026-04-10|SUNSET"), resultsCaptor.capture());

        List<BriefingEvaluationResult> results = resultsCaptor.getValue();
        assertThat(results).hasSize(1);
        BriefingEvaluationResult result = results.get(0);
        assertThat(result.locationName()).isEqualTo("Bamburgh Castle");
        assertThat(result.rating()).isEqualTo(3);
        assertThat(result.fierySkyPotential()).isEqualTo(55);
        assertThat(result.goldenHourPotential()).isEqualTo(60);
        assertThat(result.summary()).isEqualTo("Decent chance");
    }

    @Test
    @DisplayName("processResults for FORECAST groups two responses under the same cache key")
    void processResults_forecastBatch_twoLocationsOneRegion_groupedUnderSameKey() throws Exception {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);

        String text1 = "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65,\"summary\":\"Good\"}";
        String text2 = "{\"rating\":3,\"fiery_sky\":55,\"golden_hour\":50,\"summary\":\"OK\"}";

        LocationEntity loc1 = buildLocationWithRegion(10L, "Durham UK", "North East");
        LocationEntity loc2 = buildLocationWithRegion(11L, "Sunderland", "North East");
        when(locationRepository.findById(10L)).thenReturn(Optional.of(loc1));
        when(locationRepository.findById(11L)).thenReturn(Optional.of(loc2));

        // Build responses before the outer when() to avoid Mockito unfinished-stubbing issues
        MessageBatchIndividualResponse resp1 = succeededResponse("fc-10-2026-04-07-SUNRISE", text1);
        MessageBatchIndividualResponse resp2 = succeededResponse("fc-11-2026-04-07-SUNRISE", text2);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(resp1, resp2));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        when(modelSelectionService.getActiveModel(eq(RunType.SHORT_TERM)))
                .thenReturn(EvaluationModel.SONNET);
        ClaudeEvaluationStrategy strategy = mock(ClaudeEvaluationStrategy.class);
        when(evaluationStrategies.get(EvaluationModel.SONNET)).thenReturn(strategy);
        when(strategy.parseEvaluation(text1, objectMapper))
                .thenReturn(new com.gregochr.goldenhour.model.SunsetEvaluation(4, 70, 65, "Good"));
        when(strategy.parseEvaluation(text2, objectMapper))
                .thenReturn(new com.gregochr.goldenhour.model.SunsetEvaluation(3, 55, 50, "OK"));

        processor.processResults(batch);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BriefingEvaluationResult>> resultsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(briefingEvaluationService).writeFromBatch(
                eq("North East|2026-04-07|SUNRISE"), resultsCaptor.capture());
        List<BriefingEvaluationResult> results = resultsCaptor.getValue();
        assertThat(results).hasSize(2);
        assertThat(results).extracting(BriefingEvaluationResult::locationName)
                .containsExactlyInAnyOrder("Durham UK", "Sunderland");

        ArgumentCaptor<ForecastBatchEntity> entityCaptor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getSucceededCount()).isEqualTo(2);
        assertThat(entityCaptor.getValue().getErroredCount()).isEqualTo(0);
    }

    // ── FORECAST: null text ──────────────────────────────────────────────────

    @Test
    @DisplayName("processResults for FORECAST increments errored when response has no text content")
    void processResults_forecastBatch_nullText_incrementsErrored() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);

        MessageBatchIndividualResponse response = mock(MessageBatchIndividualResponse.class);
        com.anthropic.models.messages.batches.MessageBatchResult result =
                mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        com.anthropic.models.messages.batches.MessageBatchSucceededResult succeeded =
                mock(com.anthropic.models.messages.batches.MessageBatchSucceededResult.class);
        com.anthropic.models.messages.Message message =
                mock(com.anthropic.models.messages.Message.class);

        when(response.result()).thenReturn(result);
        when(response.customId()).thenReturn("fc-42-2026-04-07-SUNRISE");
        when(result.isSucceeded()).thenReturn(true);
        when(result.succeeded()).thenReturn(Optional.of(succeeded));
        when(succeeded.message()).thenReturn(message);
        // Empty content — extractText returns null
        when(message.content()).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        verifyNoInteractions(briefingEvaluationService);
        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(captor.getValue().getErroredCount()).isEqualTo(1);
        assertThat(captor.getValue().getSucceededCount()).isEqualTo(0);
    }

    // ── FORECAST: malformed customId ─────────────────────────────────────────

    @Test
    @DisplayName("processResults for FORECAST increments errored when customId has wrong prefix")
    void processResults_forecastBatch_wrongPrefix_incrementsErrored() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);

        MessageBatchIndividualResponse response = succeededResponse(
                "xx-42-2026-04-07-SUNRISE",
                "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":68,\"summary\":\"Good\"}");

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        verifyNoInteractions(briefingEvaluationService);
        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(captor.getValue().getErroredCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("processResults for FORECAST increments errored when customId has too few parts")
    void processResults_forecastBatch_tooFewParts_incrementsErrored() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);

        // Only 4 parts when split on "-" (missing targetType segment)
        MessageBatchIndividualResponse response = succeededResponse(
                "fc-42-2026-04",
                "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":68,\"summary\":\"Good\"}");

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        verifyNoInteractions(briefingEvaluationService);
        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getErroredCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("processResults for FORECAST increments errored when locationId is non-numeric")
    void processResults_forecastBatch_nonNumericLocationId_incrementsErrored() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);

        MessageBatchIndividualResponse response = succeededResponse(
                "fc-abc-2026-04-07-SUNRISE",
                "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":68,\"summary\":\"Good\"}");

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        verifyNoInteractions(briefingEvaluationService);
        verifyNoInteractions(locationRepository);
        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getErroredCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("processResults for FORECAST increments errored when location no longer exists")
    void processResults_forecastBatch_locationNotFound_incrementsErrored() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);

        when(locationRepository.findById(999L)).thenReturn(Optional.empty());

        MessageBatchIndividualResponse response = succeededResponse(
                "fc-999-2026-04-07-SUNRISE",
                "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":68,\"summary\":\"Good\"}");

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        verifyNoInteractions(briefingEvaluationService);
        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getErroredCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("processResults for FORECAST falls back to location name when region is null")
    void processResults_forecastBatch_noRegion_usesLocationNameAsCacheKeyPrefix() throws Exception {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);

        String responseText = "{\"rating\":2,\"fiery_sky\":30,\"golden_hour\":25,\"summary\":\"Poor\"}";

        // Location with no region set
        LocationEntity location = new LocationEntity();
        location.setId(55L);
        location.setName("Isolated Viewpoint");
        // region intentionally null
        when(locationRepository.findById(55L)).thenReturn(Optional.of(location));

        MessageBatchIndividualResponse response = succeededResponse(
                "fc-55-2026-04-07-SUNRISE", responseText);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        when(modelSelectionService.getActiveModel(eq(RunType.SHORT_TERM)))
                .thenReturn(EvaluationModel.SONNET);
        ClaudeEvaluationStrategy strategy = mock(ClaudeEvaluationStrategy.class);
        when(evaluationStrategies.get(EvaluationModel.SONNET)).thenReturn(strategy);
        when(strategy.parseEvaluation(responseText, objectMapper))
                .thenReturn(new com.gregochr.goldenhour.model.SunsetEvaluation(2, 30, 25, "Poor"));

        processor.processResults(batch);

        ArgumentCaptor<String> cacheKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(briefingEvaluationService).writeFromBatch(cacheKeyCaptor.capture(), any());
        // Falls back to location name when region is null
        assertThat(cacheKeyCaptor.getValue())
                .isEqualTo("Isolated Viewpoint|2026-04-07|SUNRISE");
    }

    // ── FORECAST: force-submit customId format ────────────────────────────────

    @Test
    @DisplayName("processResults for FORECAST parses force-submit customId and writes to cache")
    void processResults_forecastBatch_forceSubmitCustomId_writesToCache() throws Exception {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);

        String responseText = "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":68,"
                + "\"summary\":\"Good conditions\"}";

        LocationEntity location = buildLocationWithRegion(93L, "Whitby Abbey", "North York Moors");
        when(locationRepository.findById(93L)).thenReturn(Optional.of(location));

        MessageBatchIndividualResponse response = succeededResponse(
                "force-TheNorthYorkMoors-93-2026-04-16-SUNSET", responseText);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        when(modelSelectionService.getActiveModel(eq(RunType.SHORT_TERM)))
                .thenReturn(EvaluationModel.SONNET);
        ClaudeEvaluationStrategy strategy = mock(ClaudeEvaluationStrategy.class);
        when(evaluationStrategies.get(EvaluationModel.SONNET)).thenReturn(strategy);
        when(strategy.parseEvaluation(responseText, objectMapper))
                .thenReturn(new com.gregochr.goldenhour.model.SunsetEvaluation(
                        4, 72, 68, "Good conditions"));

        processor.processResults(batch);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BriefingEvaluationResult>> resultsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(briefingEvaluationService).writeFromBatch(
                eq("North York Moors|2026-04-16|SUNSET"), resultsCaptor.capture());
        assertThat(resultsCaptor.getValue()).hasSize(1);
        assertThat(resultsCaptor.getValue().get(0).locationName()).isEqualTo("Whitby Abbey");

        ArgumentCaptor<ForecastBatchEntity> entityCaptor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(entityCaptor.getValue().getSucceededCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("processResults for FORECAST rejects force-submit customId with wrong part count")
    void processResults_forecastBatch_forceSubmitWrongPartCount_incrementsErrored() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);

        // Only 6 parts but starts with "force" — missing event type
        MessageBatchIndividualResponse response = succeededResponse(
                "force-Region-93-2026-04-16",
                "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":68,\"summary\":\"Good\"}");

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        verifyNoInteractions(briefingEvaluationService);
        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getErroredCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("processResults for FORECAST rejects force-submit customId with non-numeric locationId")
    void processResults_forecastBatch_forceSubmitNonNumericLocationId_incrementsErrored() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);

        MessageBatchIndividualResponse response = succeededResponse(
                "force-Region-abc-2026-04-16-SUNSET",
                "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":68,\"summary\":\"Good\"}");

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        verifyNoInteractions(briefingEvaluationService);
        verifyNoInteractions(locationRepository);
        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getErroredCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("processResults for FORECAST handles mix of fc and force-submit customIds")
    void processResults_forecastBatch_mixedFcAndForceCustomIds() throws Exception {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);

        String text1 = "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65,\"summary\":\"Good\"}";
        String text2 = "{\"rating\":3,\"fiery_sky\":55,\"golden_hour\":50,\"summary\":\"OK\"}";

        LocationEntity loc1 = buildLocationWithRegion(10L, "Durham UK", "North East");
        LocationEntity loc2 = buildLocationWithRegion(93L, "Whitby Abbey", "North York Moors");
        when(locationRepository.findById(10L)).thenReturn(Optional.of(loc1));
        when(locationRepository.findById(93L)).thenReturn(Optional.of(loc2));

        MessageBatchIndividualResponse resp1 = succeededResponse(
                "fc-10-2026-04-16-SUNRISE", text1);
        MessageBatchIndividualResponse resp2 = succeededResponse(
                "force-NorthYorkMoors-93-2026-04-16-SUNSET", text2);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(resp1, resp2));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        when(modelSelectionService.getActiveModel(eq(RunType.SHORT_TERM)))
                .thenReturn(EvaluationModel.SONNET);
        ClaudeEvaluationStrategy strategy = mock(ClaudeEvaluationStrategy.class);
        when(evaluationStrategies.get(EvaluationModel.SONNET)).thenReturn(strategy);
        when(strategy.parseEvaluation(text1, objectMapper))
                .thenReturn(new com.gregochr.goldenhour.model.SunsetEvaluation(4, 70, 65, "Good"));
        when(strategy.parseEvaluation(text2, objectMapper))
                .thenReturn(new com.gregochr.goldenhour.model.SunsetEvaluation(3, 55, 50, "OK"));

        processor.processResults(batch);

        verify(briefingEvaluationService).writeFromBatch(
                eq("North East|2026-04-16|SUNRISE"), any());
        verify(briefingEvaluationService).writeFromBatch(
                eq("North York Moors|2026-04-16|SUNSET"), any());

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getSucceededCount()).isEqualTo(2);
        assertThat(captor.getValue().getErroredCount()).isEqualTo(0);
    }

    // ── FORECAST: jfdi- customId format ──────────────────────────────────────

    @Test
    @DisplayName("processResults for FORECAST parses jfdi- customId and writes to cache")
    void processResults_forecastBatch_jfdiCustomId_writesToCache() throws Exception {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);

        String responseText = "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":68,"
                + "\"summary\":\"Good conditions\"}";

        LocationEntity location = buildLocationWithRegion(64L, "St Bees Head", "Cumbria");
        when(locationRepository.findById(64L)).thenReturn(Optional.of(location));

        MessageBatchIndividualResponse response = succeededResponse(
                "jfdi-64-2026-04-19-SUNRISE", responseText);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        when(modelSelectionService.getActiveModel(eq(RunType.SHORT_TERM)))
                .thenReturn(EvaluationModel.SONNET);
        ClaudeEvaluationStrategy strategy = mock(ClaudeEvaluationStrategy.class);
        when(evaluationStrategies.get(EvaluationModel.SONNET)).thenReturn(strategy);
        when(strategy.parseEvaluation(responseText, objectMapper))
                .thenReturn(new com.gregochr.goldenhour.model.SunsetEvaluation(
                        4, 72, 68, "Good conditions"));

        processor.processResults(batch);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BriefingEvaluationResult>> resultsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(briefingEvaluationService).writeFromBatch(
                eq("Cumbria|2026-04-19|SUNRISE"), resultsCaptor.capture());
        assertThat(resultsCaptor.getValue()).hasSize(1);
        assertThat(resultsCaptor.getValue().get(0).locationName()).isEqualTo("St Bees Head");

        ArgumentCaptor<ForecastBatchEntity> entityCaptor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(entityCaptor.getValue().getSucceededCount()).isEqualTo(1);
        assertThat(entityCaptor.getValue().getErroredCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("processResults for FORECAST handles mix of fc, jfdi, and force customIds")
    void processResults_forecastBatch_mixedFcJfdiAndForce() throws Exception {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);

        String text1 = "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65,\"summary\":\"Good\"}";
        String text2 = "{\"rating\":3,\"fiery_sky\":55,\"golden_hour\":50,\"summary\":\"OK\"}";
        String text3 = "{\"rating\":5,\"fiery_sky\":90,\"golden_hour\":85,\"summary\":\"Superb\"}";

        LocationEntity loc1 = buildLocationWithRegion(10L, "Durham UK", "North East");
        LocationEntity loc2 = buildLocationWithRegion(64L, "St Bees Head", "Cumbria");
        LocationEntity loc3 = buildLocationWithRegion(93L, "Whitby Abbey", "North York Moors");
        when(locationRepository.findById(10L)).thenReturn(Optional.of(loc1));
        when(locationRepository.findById(64L)).thenReturn(Optional.of(loc2));
        when(locationRepository.findById(93L)).thenReturn(Optional.of(loc3));

        MessageBatchIndividualResponse resp1 = succeededResponse(
                "fc-10-2026-04-19-SUNRISE", text1);
        MessageBatchIndividualResponse resp2 = succeededResponse(
                "jfdi-64-2026-04-19-SUNSET", text2);
        MessageBatchIndividualResponse resp3 = succeededResponse(
                "force-NorthYorkMoors-93-2026-04-19-SUNSET", text3);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(resp1, resp2, resp3));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        when(modelSelectionService.getActiveModel(eq(RunType.SHORT_TERM)))
                .thenReturn(EvaluationModel.SONNET);
        ClaudeEvaluationStrategy strategy = mock(ClaudeEvaluationStrategy.class);
        when(evaluationStrategies.get(EvaluationModel.SONNET)).thenReturn(strategy);
        when(strategy.parseEvaluation(text1, objectMapper))
                .thenReturn(new com.gregochr.goldenhour.model.SunsetEvaluation(4, 70, 65, "Good"));
        when(strategy.parseEvaluation(text2, objectMapper))
                .thenReturn(new com.gregochr.goldenhour.model.SunsetEvaluation(3, 55, 50, "OK"));
        when(strategy.parseEvaluation(text3, objectMapper))
                .thenReturn(new com.gregochr.goldenhour.model.SunsetEvaluation(5, 90, 85, "Superb"));

        processor.processResults(batch);

        verify(briefingEvaluationService).writeFromBatch(
                eq("North East|2026-04-19|SUNRISE"), any());
        verify(briefingEvaluationService).writeFromBatch(
                eq("Cumbria|2026-04-19|SUNSET"), any());
        verify(briefingEvaluationService).writeFromBatch(
                eq("North York Moors|2026-04-19|SUNSET"), any());

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getSucceededCount()).isEqualTo(3);
        assertThat(captor.getValue().getErroredCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("processResults for FORECAST: jfdi- with wrong part count is rejected")
    void processResults_forecastBatch_jfdiWrongPartCount_incrementsErrored() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);

        // 5 parts — missing event type
        MessageBatchIndividualResponse response = succeededResponse(
                "jfdi-64-2026-04-19",
                "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":68,\"summary\":\"Good\"}");

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        verifyNoInteractions(briefingEvaluationService);
        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getErroredCount()).isEqualTo(1);
    }

    // ── FORECAST: parse exception ────────────────────────────────────────────

    @Test
    @DisplayName("processResults for FORECAST increments errored when response JSON cannot be parsed")
    void processResults_forecastBatch_parseException_incrementsErrored() throws Exception {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);

        String malformedText = "not valid json at all";

        LocationEntity location = buildLocationWithRegion(42L, "Durham UK", "North East");
        when(locationRepository.findById(42L)).thenReturn(Optional.of(location));

        MessageBatchIndividualResponse response = succeededResponse(
                "fc-42-2026-04-07-SUNRISE", malformedText);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        when(modelSelectionService.getActiveModel(eq(RunType.SHORT_TERM)))
                .thenReturn(EvaluationModel.SONNET);
        ClaudeEvaluationStrategy strategy = mock(ClaudeEvaluationStrategy.class);
        when(evaluationStrategies.get(EvaluationModel.SONNET)).thenReturn(strategy);
        when(strategy.parseEvaluation(eq(malformedText), eq(objectMapper)))
                .thenThrow(new IllegalArgumentException("malformed JSON"));

        processor.processResults(batch);

        verifyNoInteractions(briefingEvaluationService);
        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(captor.getValue().getErroredCount()).isEqualTo(1);
    }

    // ── Unknown batch type ───────────────────────────────────────────────────

    @Test
    @DisplayName("processResults marks FAILED for unknown batch type")
    void processResults_unknownBatchType_marksFailed() {
        ForecastBatchEntity batch = mock(ForecastBatchEntity.class);
        when(batch.getBatchType()).thenReturn(null);

        processor.processResults(batch);

        verify(batch).setStatus(BatchStatus.FAILED);
        verify(batchRepository).save(batch);
    }

    // ── AURORA: no Bortle locations ──────────────────────────────────────────

    @Test
    @DisplayName("processResults for AURORA marks FAILED when no Bortle locations")
    void processResults_auroraBatch_noBortleLocations_marksFailed() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.AURORA);

        MessageBatchIndividualResponse response = succeededResponse(
                "au-MODERATE-2026-04-14", "[{\"name\":\"Dark Sky\",\"stars\":3}]");

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        AuroraProperties.BortleThreshold threshold = mock(AuroraProperties.BortleThreshold.class);
        when(auroraProperties.getBortleThreshold()).thenReturn(threshold);
        when(threshold.getModerate()).thenReturn(4);
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(4))
                .thenReturn(List.of());

        processor.processResults(batch);

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.FAILED);
    }

    // ── AURORA: alert level parsed from new customId format ─────────────────

    @Test
    @DisplayName("processResults for AURORA parses MODERATE alert level from new customId format")
    void processResults_auroraBatch_alertLevelParsedFromCustomId() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.AURORA);

        MessageBatchIndividualResponse response = succeededResponse(
                "au-MODERATE-2026-04-14", "[{\"name\":\"Dark Sky\",\"stars\":3}]");

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        AuroraProperties.BortleThreshold threshold = mock(AuroraProperties.BortleThreshold.class);
        when(auroraProperties.getBortleThreshold()).thenReturn(threshold);
        // MODERATE → getModerate(), NOT getStrong()
        when(threshold.getModerate()).thenReturn(4);
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(4))
                .thenReturn(List.of());

        processor.processResults(batch);

        // getStrong() must never be called — the level was MODERATE, not STRONG
        verify(threshold).getModerate();
        verify(threshold, org.mockito.Mockito.never()).getStrong();
    }

    @Test
    @DisplayName("processResults for AURORA uses STRONG Bortle threshold when customId encodes STRONG")
    void processResults_auroraBatch_strongAlertLevel_usesStrongBortleThreshold() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.AURORA);

        MessageBatchIndividualResponse response = succeededResponse(
                "au-STRONG-2026-04-14", "[{\"name\":\"Dark Sky\",\"stars\":5}]");

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        AuroraProperties.BortleThreshold threshold = mock(AuroraProperties.BortleThreshold.class);
        when(auroraProperties.getBortleThreshold()).thenReturn(threshold);
        when(threshold.getStrong()).thenReturn(6);
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(6))
                .thenReturn(List.of());

        processor.processResults(batch);

        verify(threshold).getStrong();
        verify(threshold, org.mockito.Mockito.never()).getModerate();
    }

    // ── AURORA: success path ─────────────────────────────────────────────────

    @Test
    @DisplayName("processResults for AURORA writes scores to state cache on success")
    void processResults_auroraBatch_success() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.AURORA);

        String rawResponse = "[{\"name\":\"Dark Sky\",\"stars\":3}]";
        MessageBatchIndividualResponse response = succeededResponse(
                "au-MODERATE-2026-04-14", rawResponse);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        AuroraProperties.BortleThreshold threshold = mock(AuroraProperties.BortleThreshold.class);
        when(auroraProperties.getBortleThreshold()).thenReturn(threshold);
        when(threshold.getModerate()).thenReturn(4);

        LocationEntity loc = new LocationEntity();
        loc.setName("Dark Sky Site");
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(4))
                .thenReturn(List.of(loc));

        WeatherTriageService.TriageResult triage = new WeatherTriageService.TriageResult(
                List.of(loc), List.of(), Map.of());
        when(weatherTriageService.triage(List.of(loc))).thenReturn(triage);

        AuroraForecastScore score = mock(AuroraForecastScore.class);
        when(claudeAuroraInterpreter.parseBatchResponse(
                eq(rawResponse), eq(AlertLevel.MODERATE), eq(List.of(loc)), eq(Map.of())))
                .thenReturn(List.of(score));

        processor.processResults(batch);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuroraForecastScore>> scoresCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(auroraStateCache).updateScores(scoresCaptor.capture());
        assertThat(scoresCaptor.getValue()).containsExactly(score);

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(captor.getValue().getSucceededCount()).isEqualTo(1);
        assertThat(captor.getValue().getEndedAt()).isNotNull();
    }

    @Test
    @DisplayName("processResults for AURORA passes correct AlertLevel to parseBatchResponse")
    void processResults_auroraBatch_correctAlertLevelPassedToParseBatchResponse() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.AURORA);

        String rawResponse = "[{\"name\":\"Dark Sky\",\"stars\":4}]";
        // STRONG alert level in customId
        MessageBatchIndividualResponse response = succeededResponse(
                "au-STRONG-2026-04-14", rawResponse);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        AuroraProperties.BortleThreshold threshold = mock(AuroraProperties.BortleThreshold.class);
        when(auroraProperties.getBortleThreshold()).thenReturn(threshold);
        when(threshold.getStrong()).thenReturn(6);

        LocationEntity loc = new LocationEntity();
        loc.setName("Dark Sky Site");
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(6))
                .thenReturn(List.of(loc));

        WeatherTriageService.TriageResult triage = new WeatherTriageService.TriageResult(
                List.of(loc), List.of(), Map.of());
        when(weatherTriageService.triage(List.of(loc))).thenReturn(triage);

        when(claudeAuroraInterpreter.parseBatchResponse(
                eq(rawResponse), eq(AlertLevel.STRONG), any(), any()))
                .thenReturn(List.of(mock(AuroraForecastScore.class)));

        processor.processResults(batch);

        // Verify STRONG was passed — not MODERATE or QUIET
        verify(claudeAuroraInterpreter).parseBatchResponse(
                eq(rawResponse), eq(AlertLevel.STRONG), any(), any());
    }

    // ── AURORA: triage failures ──────────────────────────────────────────────

    @Test
    @DisplayName("processResults for AURORA marks FAILED when triage throws")
    void processResults_auroraBatch_triageFails_marksFailed() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.AURORA);

        MessageBatchIndividualResponse response = succeededResponse(
                "au-MODERATE-2026-04-14", "[{\"name\":\"Dark Sky\",\"stars\":3}]");

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        AuroraProperties.BortleThreshold threshold = mock(AuroraProperties.BortleThreshold.class);
        when(auroraProperties.getBortleThreshold()).thenReturn(threshold);
        when(threshold.getModerate()).thenReturn(4);

        LocationEntity loc = new LocationEntity();
        loc.setName("Dark Sky Site");
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(4))
                .thenReturn(List.of(loc));

        when(weatherTriageService.triage(List.of(loc)))
                .thenThrow(new RuntimeException("Weather API down"));

        processor.processResults(batch);

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.FAILED);
    }

    // ── FORECAST: mixed success/error ───────────────────────────────────────

    @Test
    @DisplayName("processResults for FORECAST: one succeeds and one has wrong prefix → COMPLETED with counts 1/1")
    void processResults_forecastBatch_mixedSuccessAndError_countsAndStatusCompleted()
            throws Exception {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);

        String responseText = "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65,\"summary\":\"Good\"}";
        LocationEntity location = buildLocationWithRegion(42L, "Durham UK", "North East");
        when(locationRepository.findById(42L)).thenReturn(Optional.of(location));

        // valid response first, then a response with wrong prefix
        MessageBatchIndividualResponse valid = succeededResponse("fc-42-2026-04-07-SUNRISE",
                responseText);
        MessageBatchIndividualResponse malformed = succeededResponse("xx-99-2026-04-07-SUNRISE",
                responseText);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(valid, malformed));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        when(modelSelectionService.getActiveModel(eq(RunType.SHORT_TERM)))
                .thenReturn(EvaluationModel.SONNET);
        ClaudeEvaluationStrategy strategy = mock(ClaudeEvaluationStrategy.class);
        when(evaluationStrategies.get(EvaluationModel.SONNET)).thenReturn(strategy);
        when(strategy.parseEvaluation(responseText, objectMapper))
                .thenReturn(new com.gregochr.goldenhour.model.SunsetEvaluation(4, 70, 65, "Good"));

        processor.processResults(batch);

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        // succeeded > 0 → COMPLETED even when there are also errors
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(captor.getValue().getSucceededCount()).isEqualTo(1);
        assertThat(captor.getValue().getErroredCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("processResults for FORECAST: two responses in different regions write two separate cache entries")
    void processResults_forecastBatch_twoRegions_writesTwoCacheEntries() throws Exception {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);

        String text1 = "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65,\"summary\":\"Good\"}";
        String text2 = "{\"rating\":3,\"fiery_sky\":50,\"golden_hour\":45,\"summary\":\"OK\"}";

        LocationEntity loc1 = buildLocationWithRegion(10L, "Durham UK", "North East");
        LocationEntity loc2 = buildLocationWithRegion(20L, "Malham Cove", "Yorkshire");
        when(locationRepository.findById(10L)).thenReturn(Optional.of(loc1));
        when(locationRepository.findById(20L)).thenReturn(Optional.of(loc2));

        MessageBatchIndividualResponse resp1 = succeededResponse("fc-10-2026-04-07-SUNRISE", text1);
        MessageBatchIndividualResponse resp2 = succeededResponse("fc-20-2026-04-07-SUNRISE", text2);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(resp1, resp2));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        when(modelSelectionService.getActiveModel(eq(RunType.SHORT_TERM)))
                .thenReturn(EvaluationModel.SONNET);
        ClaudeEvaluationStrategy strategy = mock(ClaudeEvaluationStrategy.class);
        when(evaluationStrategies.get(EvaluationModel.SONNET)).thenReturn(strategy);
        when(strategy.parseEvaluation(text1, objectMapper))
                .thenReturn(new com.gregochr.goldenhour.model.SunsetEvaluation(4, 70, 65, "Good"));
        when(strategy.parseEvaluation(text2, objectMapper))
                .thenReturn(new com.gregochr.goldenhour.model.SunsetEvaluation(3, 50, 45, "OK"));

        processor.processResults(batch);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BriefingEvaluationResult>> captor1 =
                ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BriefingEvaluationResult>> captor2 =
                ArgumentCaptor.forClass(List.class);
        verify(briefingEvaluationService).writeFromBatch(
                eq("North East|2026-04-07|SUNRISE"), captor1.capture());
        verify(briefingEvaluationService).writeFromBatch(
                eq("Yorkshire|2026-04-07|SUNRISE"), captor2.capture());

        assertThat(captor1.getValue()).hasSize(1);
        assertThat(captor1.getValue().get(0).locationName()).isEqualTo("Durham UK");
        assertThat(captor2.getValue()).hasSize(1);
        assertThat(captor2.getValue().get(0).locationName()).isEqualTo("Malham Cove");
    }

    // ── AURORA: rejected locations ───────────────────────────────────────────

    @Test
    @DisplayName("processResults for AURORA adds 1-star score for each rejected location")
    void processResults_auroraBatch_rejectedLocationsAddedWithOneStarScore() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.AURORA);

        String rawResponse = "[{\"name\":\"Dark Sky\",\"stars\":4}]";
        MessageBatchIndividualResponse response = succeededResponse(
                "au-MODERATE-2026-04-14", rawResponse);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        AuroraProperties.BortleThreshold threshold = mock(AuroraProperties.BortleThreshold.class);
        when(auroraProperties.getBortleThreshold()).thenReturn(threshold);
        when(threshold.getModerate()).thenReturn(4);

        LocationEntity viable = new LocationEntity();
        viable.setName("Clear Skies");
        LocationEntity rejected = new LocationEntity();
        rejected.setName("Overcast Hill");

        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(4))
                .thenReturn(List.of(viable, rejected));

        Map<LocationEntity, Integer> cloudByLocation = Map.of(viable, 10, rejected, 95);
        WeatherTriageService.TriageResult triage = new WeatherTriageService.TriageResult(
                List.of(viable), List.of(rejected), cloudByLocation);
        when(weatherTriageService.triage(List.of(viable, rejected))).thenReturn(triage);

        AuroraForecastScore viableScore = mock(AuroraForecastScore.class);
        when(claudeAuroraInterpreter.parseBatchResponse(
                eq(rawResponse), eq(AlertLevel.MODERATE),
                eq(List.of(viable)), eq(cloudByLocation)))
                .thenReturn(List.of(viableScore));

        processor.processResults(batch);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuroraForecastScore>> scoresCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(auroraStateCache).updateScores(scoresCaptor.capture());

        List<AuroraForecastScore> allScores = scoresCaptor.getValue();
        assertThat(allScores).hasSize(2);
        // The viable location's score comes from Claude
        assertThat(allScores).contains(viableScore);
        // The rejected location always gets 1-star (cloud cover override)
        AuroraForecastScore rejectedScore = allScores.stream()
                .filter(s -> s != viableScore)
                .findFirst()
                .orElseThrow();
        assertThat(rejectedScore.stars()).isEqualTo(1);
        assertThat(rejectedScore.location()).isEqualTo(rejected);
        assertThat(rejectedScore.cloudPercent()).isEqualTo(95);
    }

    @Test
    @DisplayName("processResults for AURORA succeededCount equals total scores written (viable + rejected)")
    void processResults_auroraBatch_succeededCountIncludesRejectedLocations() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.AURORA);

        String rawResponse = "[{\"name\":\"Dark Sky\",\"stars\":3}]";
        MessageBatchIndividualResponse response = succeededResponse(
                "au-MODERATE-2026-04-14", rawResponse);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        AuroraProperties.BortleThreshold threshold = mock(AuroraProperties.BortleThreshold.class);
        when(auroraProperties.getBortleThreshold()).thenReturn(threshold);
        when(threshold.getModerate()).thenReturn(4);

        LocationEntity viable = new LocationEntity();
        viable.setName("Clear Skies");
        LocationEntity rejected1 = new LocationEntity();
        rejected1.setName("Cloudy A");
        LocationEntity rejected2 = new LocationEntity();
        rejected2.setName("Cloudy B");

        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(4))
                .thenReturn(List.of(viable, rejected1, rejected2));

        Map<LocationEntity, Integer> cloudByLocation =
                Map.of(viable, 20, rejected1, 90, rejected2, 85);
        WeatherTriageService.TriageResult triage = new WeatherTriageService.TriageResult(
                List.of(viable), List.of(rejected1, rejected2), cloudByLocation);
        when(weatherTriageService.triage(List.of(viable, rejected1, rejected2))).thenReturn(triage);

        AuroraForecastScore score = mock(AuroraForecastScore.class);
        when(claudeAuroraInterpreter.parseBatchResponse(
                eq(rawResponse), eq(AlertLevel.MODERATE),
                eq(List.of(viable)), eq(cloudByLocation)))
                .thenReturn(List.of(score));

        processor.processResults(batch);

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        // 1 viable + 2 rejected = 3 total scores
        assertThat(captor.getValue().getSucceededCount()).isEqualTo(3);
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    // ── Token usage + cost tracking ────────────────────────────────────────

    @Test
    @DisplayName("processResults for FORECAST persists token totals and estimated cost on batch entity")
    void processResults_forecastBatch_persistsTokenTotalsAndCost() throws Exception {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);

        String responseText = "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":68,"
                + "\"summary\":\"Good conditions\"}";

        LocationEntity location = buildLocationWithRegion(42L, "Durham UK", "North East");
        when(locationRepository.findById(42L)).thenReturn(Optional.of(location));

        MessageBatchIndividualResponse response = succeededResponse(
                "fc-42-2026-04-07-SUNRISE", responseText);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        when(modelSelectionService.getActiveModel(eq(RunType.SHORT_TERM)))
                .thenReturn(EvaluationModel.SONNET);
        ClaudeEvaluationStrategy strategy = mock(ClaudeEvaluationStrategy.class);
        when(evaluationStrategies.get(EvaluationModel.SONNET)).thenReturn(strategy);
        when(strategy.parseEvaluation(responseText, objectMapper))
                .thenReturn(new com.gregochr.goldenhour.model.SunsetEvaluation(
                        4, 72, 68, "Good conditions"));

        // CostCalculator returns 1500 micro-dollars = $0.001500
        when(costCalculator.calculateCostMicroDollars(any(EvaluationModel.class),
                any(com.gregochr.goldenhour.model.TokenUsage.class), eq(true)))
                .thenReturn(1500L);

        processor.processResults(batch);

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        ForecastBatchEntity saved = captor.getValue();

        assertThat(saved.getTotalInputTokens()).isEqualTo(500L);
        assertThat(saved.getTotalOutputTokens()).isEqualTo(200L);
        assertThat(saved.getTotalCacheReadTokens()).isEqualTo(1000L);
        assertThat(saved.getTotalCacheCreationTokens()).isEqualTo(0L);
        assertThat(saved.getEstimatedCostUsd()).isEqualByComparingTo(new BigDecimal("0.001500"));
    }

    @Test
    @DisplayName("resolveEvaluationModel maps known model IDs correctly")
    void resolveEvaluationModel_knownModels() {
        assertThat(BatchResultProcessor.resolveEvaluationModel("claude-sonnet-4-6"))
                .isEqualTo(EvaluationModel.SONNET);
        assertThat(BatchResultProcessor.resolveEvaluationModel("claude-haiku-4-5-20251001"))
                .isEqualTo(EvaluationModel.HAIKU);
        assertThat(BatchResultProcessor.resolveEvaluationModel("claude-opus-4-6"))
                .isEqualTo(EvaluationModel.OPUS);
        assertThat(BatchResultProcessor.resolveEvaluationModel(null))
                .isEqualTo(EvaluationModel.SONNET);
    }

    @Test
    @DisplayName("resolveEvaluationModel falls back to Sonnet for unknown model strings")
    void resolveEvaluationModel_unknownModel_fallsBackToSonnet() {
        assertThat(BatchResultProcessor.resolveEvaluationModel("claude-future-99"))
                .isEqualTo(EvaluationModel.SONNET);
    }

    // ── describeFailedResult + resolveErrorType ────────────────────────────

    @Test
    @DisplayName("describeFailedResult returns overloaded_error type and message for overloaded response")
    void describeFailedResult_overloaded_returnsTypeAndMessage() {
        var result = mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        var erroredResult =
                mock(com.anthropic.models.messages.batches.MessageBatchErroredResult.class);
        var errorResponse = mock(com.anthropic.models.ErrorResponse.class);
        var errorObject = mock(com.anthropic.models.ErrorObject.class);
        var overloadedError = mock(com.anthropic.models.OverloadedError.class);

        when(result.isErrored()).thenReturn(true);
        when(result.asErrored()).thenReturn(erroredResult);
        when(erroredResult.error()).thenReturn(errorResponse);
        when(errorResponse.error()).thenReturn(errorObject);
        when(errorObject.isOverloadedError()).thenReturn(true);
        when(errorObject.asOverloadedError()).thenReturn(overloadedError);
        when(overloadedError.message()).thenReturn("Overloaded");

        String[] detail = BatchResultProcessor.describeFailedResult(result);

        assertThat(detail[0]).isEqualTo("overloaded_error");
        assertThat(detail[1]).isEqualTo("Overloaded");
    }

    @Test
    @DisplayName("describeFailedResult returns invalid_request_error for invalid request response")
    void describeFailedResult_invalidRequest_returnsTypeAndMessage() {
        var result = mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        var erroredResult =
                mock(com.anthropic.models.messages.batches.MessageBatchErroredResult.class);
        var errorResponse = mock(com.anthropic.models.ErrorResponse.class);
        var errorObject = mock(com.anthropic.models.ErrorObject.class);
        var invalidRequestError = mock(com.anthropic.models.InvalidRequestError.class);

        when(result.isErrored()).thenReturn(true);
        when(result.asErrored()).thenReturn(erroredResult);
        when(erroredResult.error()).thenReturn(errorResponse);
        when(errorResponse.error()).thenReturn(errorObject);
        when(errorObject.isInvalidRequestError()).thenReturn(true);
        when(errorObject.asInvalidRequestError()).thenReturn(invalidRequestError);
        when(invalidRequestError.message()).thenReturn("model not found");

        String[] detail = BatchResultProcessor.describeFailedResult(result);

        assertThat(detail[0]).isEqualTo("invalid_request_error");
        assertThat(detail[1]).isEqualTo("model not found");
    }

    @Test
    @DisplayName("describeFailedResult returns rate_limit_error for rate-limited response")
    void describeFailedResult_rateLimitError_returnsTypeAndMessage() {
        var result = mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        var erroredResult =
                mock(com.anthropic.models.messages.batches.MessageBatchErroredResult.class);
        var errorResponse = mock(com.anthropic.models.ErrorResponse.class);
        var errorObject = mock(com.anthropic.models.ErrorObject.class);
        var rateLimitError = mock(com.anthropic.models.RateLimitError.class);

        when(result.isErrored()).thenReturn(true);
        when(result.asErrored()).thenReturn(erroredResult);
        when(erroredResult.error()).thenReturn(errorResponse);
        when(errorResponse.error()).thenReturn(errorObject);
        when(errorObject.isRateLimitError()).thenReturn(true);
        when(errorObject.asRateLimitError()).thenReturn(rateLimitError);
        when(rateLimitError.message()).thenReturn("Rate limit exceeded");

        String[] detail = BatchResultProcessor.describeFailedResult(result);

        assertThat(detail[0]).isEqualTo("rate_limit_error");
        assertThat(detail[1]).isEqualTo("Rate limit exceeded");
    }

    @Test
    @DisplayName("describeFailedResult returns 'expired' for expired responses")
    void describeFailedResult_expired_returnsExpiredStatus() {
        var result = mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        when(result.isExpired()).thenReturn(true);

        String[] detail = BatchResultProcessor.describeFailedResult(result);

        assertThat(detail[0]).isEqualTo("expired");
        assertThat(detail[1]).isEqualTo("request expired before processing");
    }

    @Test
    @DisplayName("describeFailedResult returns 'canceled' for canceled responses")
    void describeFailedResult_canceled_returnsCanceledStatus() {
        var result = mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        when(result.isCanceled()).thenReturn(true);

        String[] detail = BatchResultProcessor.describeFailedResult(result);

        assertThat(detail[0]).isEqualTo("canceled");
    }

    @Test
    @DisplayName("describeFailedResult returns 'unknown' when no status predicates match")
    void describeFailedResult_noPredicatesMatch_returnsUnknown() {
        var result = mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        // All predicates default to false

        String[] detail = BatchResultProcessor.describeFailedResult(result);

        assertThat(detail[0]).isEqualTo("unknown");
    }

    // ── FORECAST: SDK-errored response tracking ────────────────────────────

    @Test
    @DisplayName("processResults for FORECAST counts SDK-errored response and sets erroredCount")
    void processResults_forecastBatch_sdkErroredResponse_setsErroredCount() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);

        MessageBatchIndividualResponse response = erroredResponse(
                "fc-42-2026-04-07-SUNRISE", "overloaded_error", "Server overloaded");

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        verifyNoInteractions(briefingEvaluationService);
        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getErroredCount()).isEqualTo(1);
        assertThat(captor.getValue().getSucceededCount()).isEqualTo(0);
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.FAILED);
    }

    @Test
    @DisplayName("processResults for FORECAST: mixed succeeded + SDK-errored"
            + " — correct counts and only succeeded writes to cache")
    void processResults_forecastBatch_mixedSucceededAndSdkErrored_correctCountsAndCacheWrites()
            throws Exception {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);

        String responseText = "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65,\"summary\":\"Good\"}";
        LocationEntity location = buildLocationWithRegion(42L, "Durham UK", "North East");
        when(locationRepository.findById(42L)).thenReturn(Optional.of(location));

        MessageBatchIndividualResponse succeeded = succeededResponse(
                "fc-42-2026-04-07-SUNRISE", responseText);
        MessageBatchIndividualResponse errored = erroredResponse(
                "fc-99-2026-04-07-SUNSET", "rate_limit_error", "Rate limit exceeded");

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(succeeded, errored));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        when(modelSelectionService.getActiveModel(eq(RunType.SHORT_TERM)))
                .thenReturn(EvaluationModel.SONNET);
        ClaudeEvaluationStrategy strategy = mock(ClaudeEvaluationStrategy.class);
        when(evaluationStrategies.get(EvaluationModel.SONNET)).thenReturn(strategy);
        when(strategy.parseEvaluation(responseText, objectMapper))
                .thenReturn(new com.gregochr.goldenhour.model.SunsetEvaluation(4, 70, 65, "Good"));

        processor.processResults(batch);

        // Only the succeeded response writes to cache
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BriefingEvaluationResult>> resultsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(briefingEvaluationService).writeFromBatch(
                eq("North East|2026-04-07|SUNRISE"), resultsCaptor.capture());
        assertThat(resultsCaptor.getValue()).hasSize(1);
        assertThat(resultsCaptor.getValue().get(0).locationName()).isEqualTo("Durham UK");

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getSucceededCount()).isEqualTo(1);
        assertThat(captor.getValue().getErroredCount()).isEqualTo(1);
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    // ── AURORA: SDK-errored response ────────────────────────────────────────

    @Test
    @DisplayName("processResults for AURORA: errored response stores error detail in failure message and skips triage")
    void processResults_auroraBatch_sdkErroredResponse_storesErrorDetailAndSkipsTriage() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.AURORA);

        MessageBatchIndividualResponse response = erroredResponse(
                "au-MODERATE-2026-04-14", "overloaded_error", "Server is overloaded");

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        // Failure message includes the error type and message
        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(captor.getValue().getErrorMessage()).contains("overloaded_error");
        assertThat(captor.getValue().getErrorMessage()).contains("Server is overloaded");

        // Short-circuits before triage or score updates
        verifyNoInteractions(weatherTriageService);
        verifyNoInteractions(auroraStateCache);
        verifyNoInteractions(claudeAuroraInterpreter);
    }

    // ── Job run tracking: FORECAST ───────────────────────────────────────────

    @Test
    @DisplayName("stream exception: markFailed calls completeBatchRun with (0, requestCount) when jobRunId set")
    void processResults_streamThrows_completesJobRunAsFailed() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_jrt01", 8, 77L);
        when(batchService.resultsStreaming("msgbatch_jrt01"))
                .thenThrow(new RuntimeException("timeout"));

        processor.processResults(batch);

        verify(jobRunService).completeBatchRun(77L, 0, 8);
    }

    @Test
    @DisplayName("stream exception: no jobRunId → jobRunService not called")
    void processResults_streamThrows_noJobRunId_doesNotCallJobRunService() {
        stubBatchService();
        ForecastBatchEntity batch = new ForecastBatchEntity("msgbatch_jrt02", BatchType.FORECAST, 3,
                Instant.now().plusSeconds(86400));
        when(batchService.resultsStreaming("msgbatch_jrt02"))
                .thenThrow(new RuntimeException("timeout"));

        processor.processResults(batch);

        verifyNoInteractions(jobRunService);
    }

    @Test
    @DisplayName("all-errored forecast: completeBatchRun called with (jobRunId, 0, requestCount, 0) and zero cost")
    void processResults_allErrored_completesJobRunAsFailed() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_jrt03", 4, 88L);

        MessageBatchIndividualResponse response = mock(MessageBatchIndividualResponse.class);
        com.anthropic.models.messages.batches.MessageBatchResult result =
                mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        when(response.result()).thenReturn(result);
        when(response.customId()).thenReturn("fc-42-2026-04-07-SUNRISE");
        when(result.isSucceeded()).thenReturn(false);

        StreamResponse<MessageBatchIndividualResponse> streamResponse =
                mock(StreamResponse.class);
        when(streamResponse.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_jrt03")).thenReturn(streamResponse);

        processor.processResults(batch);

        verify(jobRunService).completeBatchRun(88L, 0, 4, 0L);
    }

    // ── Job run tracking: AURORA ─────────────────────────────────────────────

    @Test
    @DisplayName("aurora stream exception: completeBatchRun called with (0, requestCount)")
    void processResults_auroraStreamThrows_completesJobRunAsFailed() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.AURORA, "msgbatch_jrt04", 1, 101L);
        when(batchService.resultsStreaming("msgbatch_jrt04"))
                .thenThrow(new RuntimeException("aurora error"));

        processor.processResults(batch);

        verify(jobRunService).completeBatchRun(101L, 0, 1);
    }

    @Test
    @DisplayName("aurora no viable locations: completeBatchRun called with failed counts")
    void processResults_auroraNoViableLocations_completesJobRunAsFailed() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.AURORA, "msgbatch_jrt05", 1, 102L);

        MessageBatchIndividualResponse response = mock(MessageBatchIndividualResponse.class);
        com.anthropic.models.messages.batches.MessageBatchResult result =
                mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        com.anthropic.models.messages.batches.MessageBatchSucceededResult succeeded =
                mock(com.anthropic.models.messages.batches.MessageBatchSucceededResult.class);
        com.anthropic.models.messages.Message message =
                mock(com.anthropic.models.messages.Message.class);
        com.anthropic.models.messages.TextBlock textBlock =
                mock(com.anthropic.models.messages.TextBlock.class);
        com.anthropic.models.messages.ContentBlock contentBlock =
                mock(com.anthropic.models.messages.ContentBlock.class);
        com.anthropic.models.messages.Usage usage =
                mock(com.anthropic.models.messages.Usage.class);

        when(response.result()).thenReturn(result);
        when(response.customId()).thenReturn("au-MODERATE-2026-04-14");
        when(result.isSucceeded()).thenReturn(true);
        when(result.succeeded()).thenReturn(Optional.of(succeeded));
        when(succeeded.message()).thenReturn(message);
        when(message.content()).thenReturn(List.of(contentBlock));
        when(message.usage()).thenReturn(usage);
        when(message.model()).thenReturn(com.anthropic.models.messages.Model.of("claude-haiku-4-5"));
        when(usage.inputTokens()).thenReturn(100L);
        when(usage.outputTokens()).thenReturn(50L);
        when(usage.cacheReadInputTokens()).thenReturn(Optional.of(0L));
        when(usage.cacheCreationInputTokens()).thenReturn(Optional.of(0L));
        when(contentBlock.isText()).thenReturn(true);
        when(contentBlock.asText()).thenReturn(textBlock);
        when(textBlock.text()).thenReturn("aurora response text");

        StreamResponse<MessageBatchIndividualResponse> streamResponse =
                mock(StreamResponse.class);
        when(streamResponse.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_jrt05")).thenReturn(streamResponse);

        AuroraProperties.BortleThreshold threshold = mock(AuroraProperties.BortleThreshold.class);
        when(auroraProperties.getBortleThreshold()).thenReturn(threshold);
        when(threshold.getModerate()).thenReturn(4);
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(4)).thenReturn(List.of());

        processor.processResults(batch);

        verify(jobRunService).completeBatchRun(102L, 0, 1);
    }

    // ── Job run tracking: success path with cost ────────────────────────────

    @Test
    @DisplayName("forecast success: completeBatchRun receives micro-dollar cost from batch estimatedCostUsd")
    void processResults_forecastSuccess_propagatesCostToJobRun() throws Exception {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(
                BatchType.FORECAST, "msgbatch_cost01", 1, 200L);

        String responseText = "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":68,"
                + "\"summary\":\"Good conditions\"}";

        LocationEntity location = buildLocationWithRegion(42L, "Durham UK", "North East");
        when(locationRepository.findById(42L)).thenReturn(Optional.of(location));

        MessageBatchIndividualResponse response = succeededResponse(
                "fc-42-2026-04-07-SUNRISE", responseText);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_cost01")).thenReturn(streamResp);

        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM))
                .thenReturn(EvaluationModel.SONNET);
        ClaudeEvaluationStrategy strategy = mock(ClaudeEvaluationStrategy.class);
        when(evaluationStrategies.get(EvaluationModel.SONNET)).thenReturn(strategy);
        when(strategy.parseEvaluation(responseText, objectMapper))
                .thenReturn(new com.gregochr.goldenhour.model.SunsetEvaluation(
                        4, 72, 68, "Good conditions"));

        // CostCalculator returns 45000 micro-dollars = $0.045000
        when(costCalculator.calculateCostMicroDollars(EvaluationModel.SONNET,
                new com.gregochr.goldenhour.model.TokenUsage(500, 200, 0, 1000), true))
                .thenReturn(45000L);

        processor.processResults(batch);

        // $0.045000 → 45000 micro-dollars
        verify(jobRunService).completeBatchRun(200L, 1, 0, 45000L);
    }

    @Test
    @DisplayName("aurora success: completeBatchRun receives micro-dollar cost from batch estimatedCostUsd")
    void processResults_auroraSuccess_propagatesCostToJobRun() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(
                BatchType.AURORA, "msgbatch_cost02", 1, 201L);

        String rawResponse = "[{\"name\":\"Dark Sky\",\"stars\":3}]";
        MessageBatchIndividualResponse response = succeededResponse(
                "au-MODERATE-2026-04-14", rawResponse);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_cost02")).thenReturn(streamResp);

        AuroraProperties.BortleThreshold threshold = mock(AuroraProperties.BortleThreshold.class);
        when(auroraProperties.getBortleThreshold()).thenReturn(threshold);
        when(threshold.getModerate()).thenReturn(4);

        LocationEntity loc = new LocationEntity();
        loc.setName("Dark Sky Site");
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(4))
                .thenReturn(List.of(loc));

        WeatherTriageService.TriageResult triage = new WeatherTriageService.TriageResult(
                List.of(loc), List.of(), Map.of());
        when(weatherTriageService.triage(List.of(loc))).thenReturn(triage);

        AuroraForecastScore score = mock(AuroraForecastScore.class);
        when(claudeAuroraInterpreter.parseBatchResponse(
                eq(rawResponse), eq(AlertLevel.MODERATE), eq(List.of(loc)), eq(Map.of())))
                .thenReturn(List.of(score));

        // CostCalculator returns 8200 micro-dollars = $0.008200
        when(costCalculator.calculateCostMicroDollars(EvaluationModel.SONNET,
                new com.gregochr.goldenhour.model.TokenUsage(500, 200, 0, 1000), true))
                .thenReturn(8200L);

        processor.processResults(batch);

        // $0.008200 → 8200 micro-dollars
        verify(jobRunService).completeBatchRun(201L, 1, 0, 8200L);
    }

    // ── Job run tracking: markFailed partial success ─────────────────────────

    @Test
    @DisplayName("markFailed with partial succeeded count passes correct values to completeBatchRun")
    void processResults_markFailedWithPartialSuccess_usesCorrectCounts() {
        stubBatchService();
        // batch has 6 requests, and suppose 2 succeeded before the failure
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_jrt06", 6, 103L);
        batch.setSucceededCount(2);
        when(batchService.resultsStreaming("msgbatch_jrt06"))
                .thenThrow(new RuntimeException("partial failure"));

        processor.processResults(batch);

        // markFailed is called: succeeded=2 (from entity), failed=6-2=4
        verify(jobRunService).completeBatchRun(103L, 2, 4);
    }

    // ── api_call_log persistence ────────────────────────────────────────────

    @Test
    @DisplayName("succeeded forecast result persists api_call_log row via logBatchResult")
    void processResults_succeededResult_persistsApiCallLog() throws Exception {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_obs", 1, 200L);

        String responseText = "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":68,"
                + "\"summary\":\"Good conditions\"}";
        LocationEntity location = buildLocationWithRegion(42L, "Durham UK", "North East");
        when(locationRepository.findById(42L)).thenReturn(Optional.of(location));

        MessageBatchIndividualResponse response = succeededResponse(
                "fc-42-2026-04-16-SUNRISE", responseText);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_obs")).thenReturn(streamResp);

        when(modelSelectionService.getActiveModel(eq(RunType.SHORT_TERM)))
                .thenReturn(EvaluationModel.SONNET);
        ClaudeEvaluationStrategy strategy = mock(ClaudeEvaluationStrategy.class);
        when(evaluationStrategies.get(EvaluationModel.SONNET)).thenReturn(strategy);
        when(strategy.parseEvaluation(responseText, objectMapper))
                .thenReturn(new com.gregochr.goldenhour.model.SunsetEvaluation(
                        4, 72, 68, "Good conditions"));

        processor.processResults(batch);

        verify(jobRunService).logBatchResult(
                eq(200L), eq("msgbatch_obs"), eq("fc-42-2026-04-16-SUNRISE"),
                eq(true), eq("SUCCESS"),
                eq(null), eq(null),
                eq(EvaluationModel.SONNET),
                eq(new TokenUsage(500, 200, 0, 1000)),
                eq(LocalDate.of(2026, 4, 16)),
                eq(TargetType.SUNRISE));
    }

    @Test
    @DisplayName("errored forecast result persists api_call_log row with error details")
    void processResults_erroredResult_persistsApiCallLogWithErrorDetails() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_obs", 1, 201L);

        MessageBatchIndividualResponse response = erroredResponse(
                "fc-42-2026-04-16-SUNSET", "overloaded_error", "Server is overloaded");

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_obs")).thenReturn(streamResp);

        processor.processResults(batch);

        verify(jobRunService).logBatchResult(
                eq(201L), eq("msgbatch_obs"), eq("fc-42-2026-04-16-SUNSET"),
                eq(false), eq("OVERLOADED_ERROR"),
                eq("overloaded_error"), eq("Server is overloaded"),
                eq(null), eq(null), eq(null), eq(null));
    }

    @Test
    @DisplayName("api_call_log persistence failure does not break batch processing")
    void processResults_apiCallLogFailure_doesNotBreakProcessing() throws Exception {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_obs", 1, 202L);

        String responseText = "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":68,"
                + "\"summary\":\"Good conditions\"}";
        LocationEntity location = buildLocationWithRegion(42L, "Durham UK", "North East");
        when(locationRepository.findById(42L)).thenReturn(Optional.of(location));

        MessageBatchIndividualResponse response = succeededResponse(
                "fc-42-2026-04-16-SUNRISE", responseText);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_obs")).thenReturn(streamResp);

        when(modelSelectionService.getActiveModel(eq(RunType.SHORT_TERM)))
                .thenReturn(EvaluationModel.SONNET);
        ClaudeEvaluationStrategy strategy = mock(ClaudeEvaluationStrategy.class);
        when(evaluationStrategies.get(EvaluationModel.SONNET)).thenReturn(strategy);
        when(strategy.parseEvaluation(responseText, objectMapper))
                .thenReturn(new com.gregochr.goldenhour.model.SunsetEvaluation(
                        4, 72, 68, "Good conditions"));

        // Simulate DB failure on api_call_log write
        org.mockito.Mockito.doThrow(new RuntimeException("DB connection lost"))
                .when(jobRunService).logBatchResult(any(), any(), any(), any(boolean.class),
                        any(), any(), any(), any(), any(), any(), any());

        processor.processResults(batch);

        // Batch should still complete successfully despite api_call_log failure
        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(captor.getValue().getSucceededCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("no api_call_log persistence when batch has no jobRunId")
    void processResults_noJobRunId_skipsApiCallLogPersistence() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST); // no jobRunId

        MessageBatchIndividualResponse response = erroredResponse(
                "fc-42-2026-04-16-SUNSET", "overloaded_error", "Server is overloaded");

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        // logBatchResult should never be called when jobRunId is null
        verify(jobRunService, org.mockito.Mockito.never()).logBatchResult(
                any(), any(), any(), any(boolean.class), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("parse failure persists api_call_log row with PARSE_FAILED status")
    void processResults_parseFailure_persistsApiCallLogWithParseError() throws Exception {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_obs", 1, 203L);

        String responseText = "not valid json";
        LocationEntity location = buildLocationWithRegion(42L, "Durham UK", "North East");
        when(locationRepository.findById(42L)).thenReturn(Optional.of(location));

        MessageBatchIndividualResponse response = succeededResponse(
                "fc-42-2026-04-16-SUNRISE", responseText);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_obs")).thenReturn(streamResp);

        when(modelSelectionService.getActiveModel(eq(RunType.SHORT_TERM)))
                .thenReturn(EvaluationModel.SONNET);
        ClaudeEvaluationStrategy strategy = mock(ClaudeEvaluationStrategy.class);
        when(evaluationStrategies.get(EvaluationModel.SONNET)).thenReturn(strategy);
        when(strategy.parseEvaluation(responseText, objectMapper))
                .thenThrow(new RuntimeException("Invalid JSON"));

        processor.processResults(batch);

        verify(jobRunService).logBatchResult(
                eq(203L), eq("msgbatch_obs"), eq("fc-42-2026-04-16-SUNRISE"),
                eq(false), eq("PARSE_FAILED"),
                eq("parse_error"), eq("Invalid JSON"),
                eq(null), eq(null), eq(null), eq(null));
    }

    @Test
    @DisplayName("describeFailedResult NPE is caught and does not abort the loop")
    void processResults_describeFailedResultNpe_caughtAndContinues() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_obs", 2, 204L);

        // First response: errored with null error chain (triggers NPE in describeFailedResult)
        MessageBatchIndividualResponse npeResponse = mock(MessageBatchIndividualResponse.class);
        com.anthropic.models.messages.batches.MessageBatchResult npeResult =
                mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        com.anthropic.models.messages.batches.MessageBatchErroredResult npeErroredResult =
                mock(com.anthropic.models.messages.batches.MessageBatchErroredResult.class);
        when(npeResponse.result()).thenReturn(npeResult);
        when(npeResponse.customId()).thenReturn("fc-1-2026-04-16-SUNRISE");
        when(npeResult.isSucceeded()).thenReturn(false);
        when(npeResult.isErrored()).thenReturn(true);
        when(npeResult.asErrored()).thenReturn(npeErroredResult);
        when(npeErroredResult.error()).thenReturn(null); // triggers NPE

        // Second response: normal errored
        MessageBatchIndividualResponse normalResponse = erroredResponse(
                "fc-2-2026-04-16-SUNSET", "overloaded_error", "overloaded");

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(npeResponse, normalResponse));
        when(batchService.resultsStreaming("msgbatch_obs")).thenReturn(streamResp);

        processor.processResults(batch);

        // Both errors should be counted (loop was not aborted by the NPE)
        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getErroredCount()).isEqualTo(2);

        // Verify logBatchResult was called twice — once per response
        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> errorTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(jobRunService, times(2)).logBatchResult(
                eq(204L), eq("msgbatch_obs"), any(String.class),
                eq(false), statusCaptor.capture(),
                errorTypeCaptor.capture(), any(String.class),
                eq(null), eq(null), eq(null), eq(null));

        // First call: NPE response — detail falls through to catch block: "unknown"
        assertThat(statusCaptor.getAllValues().get(0)).isEqualTo("UNKNOWN");
        assertThat(errorTypeCaptor.getAllValues().get(0)).isEqualTo("unknown");

        // Second call: normal errored response — overloaded_error
        assertThat(statusCaptor.getAllValues().get(1)).isEqualTo("OVERLOADED_ERROR");
        assertThat(errorTypeCaptor.getAllValues().get(1)).isEqualTo("overloaded_error");
    }

    @Test
    @DisplayName("malformed customId prefix persists MALFORMED_ID status with parse_error errorType")
    void processResults_malformedCustomId_persistsMalformedIdStatus() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_obs", 1, 205L);

        MessageBatchIndividualResponse response = succeededResponse(
                "xx-42-2026-04-07-SUNRISE",
                "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":68,\"summary\":\"Good\"}");

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_obs")).thenReturn(streamResp);

        processor.processResults(batch);

        verify(jobRunService).logBatchResult(
                eq(205L), eq("msgbatch_obs"), eq("xx-42-2026-04-07-SUNRISE"),
                eq(false), eq("MALFORMED_ID"),
                eq("parse_error"), eq("malformed customId"),
                eq(null), eq(null), eq(null), eq(null));
    }

    @Test
    @DisplayName("location not found persists LOCATION_NOT_FOUND status with lookup_error errorType")
    void processResults_locationNotFound_persistsLocationNotFoundStatus() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_obs", 1, 206L);

        when(locationRepository.findById(999L)).thenReturn(Optional.empty());

        MessageBatchIndividualResponse response = succeededResponse(
                "fc-999-2026-04-16-SUNRISE",
                "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":68,\"summary\":\"Good\"}");

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_obs")).thenReturn(streamResp);

        processor.processResults(batch);

        verify(jobRunService).logBatchResult(
                eq(206L), eq("msgbatch_obs"), eq("fc-999-2026-04-16-SUNRISE"),
                eq(false), eq("LOCATION_NOT_FOUND"),
                eq("lookup_error"), eq("location 999 not found"),
                eq(null), eq(null), eq(null), eq(null));
    }

    @Test
    @DisplayName("succeeded response with no message persists NO_MESSAGE status")
    void processResults_noMessage_persistsNoMessageStatus() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_obs", 1, 207L);

        // Build a response where result.isSucceeded() == true but succeeded() returns empty
        MessageBatchIndividualResponse response = mock(MessageBatchIndividualResponse.class);
        com.anthropic.models.messages.batches.MessageBatchResult result =
                mock(com.anthropic.models.messages.batches.MessageBatchResult.class);

        when(response.result()).thenReturn(result);
        when(response.customId()).thenReturn("fc-42-2026-04-16-SUNRISE");
        when(result.isSucceeded()).thenReturn(true);
        when(result.succeeded()).thenReturn(Optional.empty());

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_obs")).thenReturn(streamResp);

        processor.processResults(batch);

        verify(jobRunService).logBatchResult(
                eq(207L), eq("msgbatch_obs"), eq("fc-42-2026-04-16-SUNRISE"),
                eq(false), eq("NO_MESSAGE"),
                eq("extraction_error"), eq("succeeded but no message"),
                eq(null), eq(null), eq(null), eq(null));
    }

    @Test
    @DisplayName("force-submit customId persists with correct targetDate and targetType")
    void processResults_forceSubmitCustomId_persistsWithCorrectDateAndType() throws Exception {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(
                BatchType.FORECAST, "msgbatch_obs", 1, 208L);

        String responseText = "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":68,"
                + "\"summary\":\"Good conditions\"}";

        LocationEntity location = buildLocationWithRegion(93L, "Whitby Abbey", "North East");
        when(locationRepository.findById(93L)).thenReturn(Optional.of(location));

        MessageBatchIndividualResponse response = succeededResponse(
                "force-NorthEast-93-2026-04-16-SUNSET", responseText);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_obs")).thenReturn(streamResp);

        when(modelSelectionService.getActiveModel(eq(RunType.SHORT_TERM)))
                .thenReturn(EvaluationModel.SONNET);
        ClaudeEvaluationStrategy strategy = mock(ClaudeEvaluationStrategy.class);
        when(evaluationStrategies.get(EvaluationModel.SONNET)).thenReturn(strategy);
        when(strategy.parseEvaluation(responseText, objectMapper))
                .thenReturn(new com.gregochr.goldenhour.model.SunsetEvaluation(
                        4, 72, 68, "Good conditions"));

        processor.processResults(batch);

        verify(jobRunService).logBatchResult(
                eq(208L), eq("msgbatch_obs"), eq("force-NorthEast-93-2026-04-16-SUNSET"),
                eq(true), eq("SUCCESS"),
                eq(null), eq(null),
                eq(EvaluationModel.SONNET),
                eq(new TokenUsage(500, 200, 0, 1000)),
                eq(LocalDate.of(2026, 4, 16)),
                eq(TargetType.SUNSET));
    }

    @Test
    @DisplayName("JFDI customId persists with correct targetDate and targetType")
    void processResults_jfdiCustomId_persistsWithCorrectDateAndType() throws Exception {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(
                BatchType.FORECAST, "msgbatch_obs", 1, 209L);

        String responseText = "{\"rating\":3,\"fiery_sky\":55,\"golden_hour\":60,"
                + "\"summary\":\"Decent chance\"}";

        LocationEntity location = buildLocationWithRegion(42L, "Durham UK", "North East");
        when(locationRepository.findById(42L)).thenReturn(Optional.of(location));

        MessageBatchIndividualResponse response = succeededResponse(
                "jfdi-42-2026-04-16-SUNRISE", responseText);

        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_obs")).thenReturn(streamResp);

        when(modelSelectionService.getActiveModel(eq(RunType.SHORT_TERM)))
                .thenReturn(EvaluationModel.SONNET);
        ClaudeEvaluationStrategy strategy = mock(ClaudeEvaluationStrategy.class);
        when(evaluationStrategies.get(EvaluationModel.SONNET)).thenReturn(strategy);
        when(strategy.parseEvaluation(responseText, objectMapper))
                .thenReturn(new com.gregochr.goldenhour.model.SunsetEvaluation(
                        3, 55, 60, "Decent chance"));

        processor.processResults(batch);

        verify(jobRunService).logBatchResult(
                eq(209L), eq("msgbatch_obs"), eq("jfdi-42-2026-04-16-SUNRISE"),
                eq(true), eq("SUCCESS"),
                eq(null), eq(null),
                eq(EvaluationModel.SONNET),
                eq(new TokenUsage(500, 200, 0, 1000)),
                eq(LocalDate.of(2026, 4, 16)),
                eq(TargetType.SUNRISE));
    }

    /**
     * Builds a {@link ForecastBatchEntity} pre-populated with a job run ID.
     */
    private ForecastBatchEntity buildBatchWithJobRun(BatchType type, String batchId,
            int requestCount, long jobRunId) {
        ForecastBatchEntity batch = new ForecastBatchEntity(batchId, type, requestCount,
                Instant.now().plusSeconds(86400));
        batch.setJobRunId(jobRunId);
        return batch;
    }

    /**
     * Builds a minimal succeeded {@link MessageBatchIndividualResponse} returning the given text.
     */
    private MessageBatchIndividualResponse succeededResponse(String customId, String text) {
        MessageBatchIndividualResponse response = mock(MessageBatchIndividualResponse.class);
        com.anthropic.models.messages.batches.MessageBatchResult result =
                mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        com.anthropic.models.messages.batches.MessageBatchSucceededResult succeeded =
                mock(com.anthropic.models.messages.batches.MessageBatchSucceededResult.class);
        com.anthropic.models.messages.Message message =
                mock(com.anthropic.models.messages.Message.class);
        com.anthropic.models.messages.TextBlock textBlock =
                mock(com.anthropic.models.messages.TextBlock.class);
        com.anthropic.models.messages.ContentBlock contentBlock =
                mock(com.anthropic.models.messages.ContentBlock.class);
        com.anthropic.models.messages.Usage usage =
                mock(com.anthropic.models.messages.Usage.class);

        when(response.result()).thenReturn(result);
        when(response.customId()).thenReturn(customId);
        when(result.isSucceeded()).thenReturn(true);
        when(result.succeeded()).thenReturn(Optional.of(succeeded));
        when(succeeded.message()).thenReturn(message);
        when(message.content()).thenReturn(List.of(contentBlock));
        when(message.usage()).thenReturn(usage);
        org.mockito.Mockito.lenient().when(message.model())
                .thenReturn(com.anthropic.models.messages.Model.of("claude-sonnet-4-6"));
        when(contentBlock.isText()).thenReturn(true);
        when(contentBlock.asText()).thenReturn(textBlock);
        when(textBlock.text()).thenReturn(text);
        when(usage.inputTokens()).thenReturn(500L);
        when(usage.outputTokens()).thenReturn(200L);
        when(usage.cacheReadInputTokens()).thenReturn(Optional.of(1000L));
        when(usage.cacheCreationInputTokens()).thenReturn(Optional.of(0L));

        return response;
    }

    /**
     * Builds a minimal errored {@link MessageBatchIndividualResponse} with an overloaded error.
     */
    private MessageBatchIndividualResponse erroredResponse(String customId,
            String errorType, String errorMessage) {
        MessageBatchIndividualResponse response = mock(MessageBatchIndividualResponse.class);
        com.anthropic.models.messages.batches.MessageBatchResult result =
                mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        com.anthropic.models.messages.batches.MessageBatchErroredResult erroredResult =
                mock(com.anthropic.models.messages.batches.MessageBatchErroredResult.class);
        com.anthropic.models.ErrorResponse errorResponse =
                mock(com.anthropic.models.ErrorResponse.class);
        com.anthropic.models.ErrorObject errorObject =
                mock(com.anthropic.models.ErrorObject.class);

        when(response.result()).thenReturn(result);
        when(response.customId()).thenReturn(customId);
        when(result.isSucceeded()).thenReturn(false);
        when(result.isErrored()).thenReturn(true);
        when(result.asErrored()).thenReturn(erroredResult);
        when(erroredResult.error()).thenReturn(errorResponse);
        when(errorResponse.error()).thenReturn(errorObject);

        // Wire up the specific error type based on the errorType parameter
        switch (errorType) {
            case "overloaded_error" -> {
                var typed = mock(com.anthropic.models.OverloadedError.class);
                when(errorObject.isOverloadedError()).thenReturn(true);
                when(errorObject.asOverloadedError()).thenReturn(typed);
                when(typed.message()).thenReturn(errorMessage);
            }
            case "rate_limit_error" -> {
                var typed = mock(com.anthropic.models.RateLimitError.class);
                when(errorObject.isRateLimitError()).thenReturn(true);
                when(errorObject.asRateLimitError()).thenReturn(typed);
                when(typed.message()).thenReturn(errorMessage);
            }
            case "invalid_request_error" -> {
                var typed = mock(com.anthropic.models.InvalidRequestError.class);
                when(errorObject.isInvalidRequestError()).thenReturn(true);
                when(errorObject.asInvalidRequestError()).thenReturn(typed);
                when(typed.message()).thenReturn(errorMessage);
            }
            default -> {
                // Falls through to "unknown" in resolveErrorType
            }
        }

        return response;
    }

    private LocationEntity buildLocationWithRegion(long id, String name, String regionName) {
        LocationEntity location = new LocationEntity();
        location.setId(id);
        location.setName(name);
        RegionEntity region = new RegionEntity();
        region.setName(regionName);
        location.setRegion(region);
        return location;
    }

    private ForecastBatchEntity buildBatch(BatchType type) {
        return new ForecastBatchEntity("msgbatch_fail", type, 1,
                Instant.now().plusSeconds(86400));
    }
}
