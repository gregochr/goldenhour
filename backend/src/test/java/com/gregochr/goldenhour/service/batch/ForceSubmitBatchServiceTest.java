package com.gregochr.goldenhour.service.batch;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.anthropic.models.messages.batches.MessageBatchRequestCounts;
import com.anthropic.models.messages.batches.MessageBatchResult;
import com.anthropic.models.messages.batches.MessageBatchSucceededResult;
import com.anthropic.services.blocking.MessageService;
import com.anthropic.services.blocking.messages.BatchService;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.TideSnapshot;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.batch.ForceSubmitBatchService.ForceResultResponse;
import com.gregochr.goldenhour.service.batch.ForceSubmitBatchService.ForceSubmitResult;
import com.gregochr.goldenhour.service.evaluation.CoastalPromptBuilder;
import com.gregochr.goldenhour.service.evaluation.PromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ForceSubmitBatchService}.
 */
@ExtendWith(MockitoExtension.class)
class ForceSubmitBatchServiceTest {

    @Mock
    private AnthropicClient anthropicClient;
    @Mock
    private MessageService messageService;
    @Mock
    private BatchService batchService;
    @Mock
    private ForecastBatchRepository batchRepository;
    @Mock
    private RegionRepository regionRepository;
    @Mock
    private LocationService locationService;
    @Mock
    private ForecastService forecastService;
    @Mock
    private PromptBuilder promptBuilder;
    @Mock
    private CoastalPromptBuilder coastalPromptBuilder;
    @Mock
    private ModelSelectionService modelSelectionService;
    @Mock
    private JobRunService jobRunService;

    private ForceSubmitBatchService service;

    @BeforeEach
    void setUp() {
        com.gregochr.goldenhour.service.evaluation.BatchRequestFactory batchRequestFactory =
                new com.gregochr.goldenhour.service.evaluation.BatchRequestFactory(
                        promptBuilder, coastalPromptBuilder);
        service = new ForceSubmitBatchService(
                anthropicClient, batchRepository, regionRepository, locationService,
                forecastService, promptBuilder, coastalPromptBuilder, modelSelectionService,
                jobRunService, batchRequestFactory);
    }

    private void stubBatchService() {
        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.batches()).thenReturn(batchService);
    }

    @Test
    @DisplayName("forceSubmit throws when region not found")
    void forceSubmit_unknownRegion_throws() {
        when(regionRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.forceSubmit(999L,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Region not found");
    }

    @Test
    @DisplayName("forceSubmit throws when no enabled locations in region")
    void forceSubmit_noLocationsInRegion_throws() {
        RegionEntity region = buildRegion(7L, "Northumberland");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(region));
        when(locationService.findAllEnabled()).thenReturn(List.of());

        assertThatThrownBy(() -> service.forceSubmit(7L,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No enabled locations");
    }

    @Test
    @DisplayName("forceSubmit submits batch for locations in region")
    void forceSubmit_validRegion_submitsBatch() {
        stubBatchService();
        RegionEntity region = buildRegion(7L, "Northumberland");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(region));

        LocationEntity loc = buildLocation(10L, "Bamburgh Castle", region);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        AtmosphericData data = mock(AtmosphericData.class);
        when(data.surge()).thenReturn(null);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, loc,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET,
                LocalDateTime.of(2026, 4, 16, 19, 30), 270, 1,
                EvaluationModel.HAIKU, loc.getTideType(), "key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any())).thenReturn(preEval);
        when(promptBuilder.buildUserMessage(any(AtmosphericData.class))).thenReturn("msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("sys");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_force001");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        ForceSubmitResult result = service.forceSubmit(7L,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET);

        assertThat(result.batchId()).isEqualTo("msgbatch_force001");
        assertThat(result.requestCount()).isEqualTo(1);
        assertThat(result.locationsAttempted()).isEqualTo(1);
        assertThat(result.locationsIncluded()).isEqualTo(1);
        assertThat(result.locationsFailedData()).isEqualTo(0);
        assertThat(result.failedLocations()).isEmpty();

        verify(batchRepository).save(any(ForecastBatchEntity.class));
    }

    @Test
    @DisplayName("forceSubmit returns null batchId when all data assembly fails")
    void forceSubmit_allDataFails_returnsNullBatchId() {
        RegionEntity region = buildRegion(7L, "Northumberland");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(region));

        LocationEntity loc = buildLocation(10L, "Bamburgh Castle", region);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any()))
                .thenThrow(new RuntimeException("Open-Meteo timeout"));

        ForceSubmitResult result = service.forceSubmit(7L,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET);

        assertThat(result.batchId()).isNull();
        assertThat(result.requestCount()).isEqualTo(0);
        assertThat(result.locationsFailedData()).isEqualTo(1);
        assertThat(result.failedLocations()).containsExactly("Bamburgh Castle");

        verify(batchService, never()).create(any());
    }

    @Test
    @DisplayName("forceSubmit handles null atmospheric data gracefully")
    void forceSubmit_nullAtmosphericData_recordsFailure() {
        RegionEntity region = buildRegion(7L, "Northumberland");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(region));

        LocationEntity loc = buildLocation(10L, "Bamburgh Castle", region);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, null, loc,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET,
                LocalDateTime.of(2026, 4, 16, 19, 30), 270, 1,
                EvaluationModel.HAIKU, loc.getTideType(), "key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any())).thenReturn(preEval);

        ForceSubmitResult result = service.forceSubmit(7L,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET);

        assertThat(result.batchId()).isNull();
        assertThat(result.locationsFailedData()).isEqualTo(1);
        assertThat(result.failedLocations()).containsExactly("Bamburgh Castle");
    }

    @Test
    @DisplayName("forceSubmit filters locations to correct region only")
    void forceSubmit_multipleRegions_onlyIncludesTargetRegion() {
        stubBatchService();
        RegionEntity northumberland = buildRegion(7L, "Northumberland");
        RegionEntity yorkshire = buildRegion(8L, "Yorkshire");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(northumberland));

        LocationEntity loc1 = buildLocation(10L, "Bamburgh Castle", northumberland);
        LocationEntity loc2 = buildLocation(20L, "Flamborough Head", yorkshire);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc1, loc2));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        AtmosphericData data = mock(AtmosphericData.class);
        when(data.surge()).thenReturn(null);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, loc1,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET,
                LocalDateTime.of(2026, 4, 16, 19, 30), 270, 1,
                EvaluationModel.HAIKU, loc1.getTideType(), "key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any())).thenReturn(preEval);
        when(promptBuilder.buildUserMessage(any(AtmosphericData.class))).thenReturn("msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("sys");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_filter");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        ForceSubmitResult result = service.forceSubmit(7L,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET);

        assertThat(result.locationsAttempted()).isEqualTo(1);
        assertThat(result.requestCount()).isEqualTo(1);

        ArgumentCaptor<BatchCreateParams> paramsCaptor =
                ArgumentCaptor.forClass(BatchCreateParams.class);
        verify(batchService).create(paramsCaptor.capture());
        assertThat(paramsCaptor.getValue().requests()).hasSize(1);
    }

    @Test
    @DisplayName("forceSubmit uses coastal prompt builder when tide data present")
    void forceSubmit_coastalLocation_usesCoastalPromptBuilder() {
        stubBatchService();
        RegionEntity region = buildRegion(7L, "Northumberland");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(region));

        LocationEntity loc = buildLocation(10L, "Bamburgh Castle", region);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        AtmosphericData data = mock(AtmosphericData.class);
        when(data.tide()).thenReturn(mock(com.gregochr.goldenhour.model.TideSnapshot.class));
        when(data.surge()).thenReturn(null);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, loc,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET,
                LocalDateTime.of(2026, 4, 16, 19, 30), 270, 1,
                EvaluationModel.HAIKU, loc.getTideType(), "key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any())).thenReturn(preEval);
        when(coastalPromptBuilder.buildUserMessage(any(AtmosphericData.class))).thenReturn("msg");
        when(coastalPromptBuilder.getSystemPrompt()).thenReturn("sys");
        when(coastalPromptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_coastal");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.forceSubmit(7L, LocalDate.of(2026, 4, 16), TargetType.SUNSET);

        verify(coastalPromptBuilder).buildUserMessage(any(AtmosphericData.class));
        verify(coastalPromptBuilder).getSystemPrompt();
    }

    @Test
    @DisplayName("getResult returns in-progress status when batch not ended")
    void getResult_inProgress_returnsStatus() {
        stubBatchService();
        MessageBatch status = mock(MessageBatch.class);
        when(status.processingStatus())
                .thenReturn(MessageBatch.ProcessingStatus.IN_PROGRESS);
        MessageBatchRequestCounts counts = mock(MessageBatchRequestCounts.class);
        when(counts.processing()).thenReturn(3L);
        when(counts.succeeded()).thenReturn(2L);
        when(counts.errored()).thenReturn(0L);
        when(status.requestCounts()).thenReturn(counts);
        when(batchService.retrieve("msgbatch_test")).thenReturn(status);

        ForceResultResponse result = service.getResult("msgbatch_test");

        assertThat(result.status()).isEqualTo("in_progress");
        assertThat(result.processing()).isEqualTo(3);
        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.results()).isNull();
    }

    @Test
    @DisplayName("getResult streams results when batch has ended")
    @SuppressWarnings("unchecked")
    void getResult_ended_returnsResults() {
        stubBatchService();
        MessageBatch status = mock(MessageBatch.class);
        when(status.processingStatus()).thenReturn(MessageBatch.ProcessingStatus.ENDED);
        when(batchService.retrieve("msgbatch_done")).thenReturn(status);

        // Build a mock streaming response with one succeeded result
        MessageBatchIndividualResponse response = mock(MessageBatchIndividualResponse.class);
        when(response.customId()).thenReturn("force-test-10-2026-04-16-SUNSET");
        MessageBatchResult batchResult = mock(MessageBatchResult.class);
        when(batchResult.isSucceeded()).thenReturn(true);
        MessageBatchSucceededResult succeeded = mock(MessageBatchSucceededResult.class);
        when(batchResult.succeeded()).thenReturn(Optional.of(succeeded));
        Message message = mock(Message.class);
        when(succeeded.message()).thenReturn(message);
        TextBlock textBlock = mock(TextBlock.class);
        when(textBlock.text()).thenReturn("{\"rating\":4,\"summary\":\"test\"}");
        ContentBlock contentBlock = mock(ContentBlock.class);
        when(contentBlock.isText()).thenReturn(true);
        when(contentBlock.asText()).thenReturn(textBlock);
        when(message.content()).thenReturn(List.of(contentBlock));
        when(response.result()).thenReturn(batchResult);

        StreamResponse<MessageBatchIndividualResponse> streamResponse =
                mock(StreamResponse.class);
        when(streamResponse.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_done")).thenReturn(streamResponse);

        ForceResultResponse result = service.getResult("msgbatch_done");

        assertThat(result.status()).isEqualTo("ended");
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.totalResults()).isEqualTo(1);
        assertThat(result.results()).hasSize(1);
        assertThat(result.results().get(0).customId())
                .isEqualTo("force-test-10-2026-04-16-SUNSET");
        assertThat(result.results().get(0).responsePreview())
                .contains("rating");
    }

    @Test
    @DisplayName("forceSubmit customId is truncated to 64 chars")
    void forceSubmit_longRegionName_customIdTruncated() {
        stubBatchService();
        RegionEntity region = buildRegion(7L, "VeryLongRegionNameThatExceedsLimits");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(region));

        LocationEntity loc = buildLocation(10L, "Location", region);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        AtmosphericData data = mock(AtmosphericData.class);
        when(data.surge()).thenReturn(null);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, loc,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET,
                LocalDateTime.of(2026, 4, 16, 19, 30), 270, 1,
                EvaluationModel.HAIKU, loc.getTideType(), "key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any())).thenReturn(preEval);
        when(promptBuilder.buildUserMessage(any(AtmosphericData.class))).thenReturn("msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("sys");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_trunc");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.forceSubmit(7L, LocalDate.of(2026, 4, 16), TargetType.SUNSET);

        ArgumentCaptor<BatchCreateParams> captor =
                ArgumentCaptor.forClass(BatchCreateParams.class);
        verify(batchService).create(captor.capture());
        String customId = captor.getValue().requests().get(0).customId();
        assertThat(customId.length()).isLessThanOrEqualTo(64);
    }

    // ── forceSubmit: verify exact args passed to fetchWeatherAndTriage ─────

    @Test
    @DisplayName("forceSubmit passes correct location, date, event, tideType, model to forecastService")
    void forceSubmit_verifiesFetchWeatherArgs() {
        stubBatchService();
        RegionEntity region = buildRegion(7L, "Northumberland");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(region));

        LocationEntity loc = buildLocation(10L, "Bamburgh Castle", region);
        loc.setTideType(Set.of(TideType.HIGH));
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);

        AtmosphericData data = mock(AtmosphericData.class);
        when(data.surge()).thenReturn(null);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, loc,
                LocalDate.of(2026, 4, 16), TargetType.SUNRISE,
                LocalDateTime.of(2026, 4, 16, 5, 30), 90, 1,
                EvaluationModel.SONNET, loc.getTideType(), "key", null);
        when(forecastService.fetchWeatherAndTriage(
                eq(loc), eq(LocalDate.of(2026, 4, 16)), eq(TargetType.SUNRISE),
                eq(Set.of(TideType.HIGH)), eq(EvaluationModel.SONNET),
                eq(false), isNull()))
                .thenReturn(preEval);
        when(promptBuilder.buildUserMessage(data)).thenReturn("msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("sys");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_args");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.forceSubmit(7L, LocalDate.of(2026, 4, 16), TargetType.SUNRISE);

        verify(forecastService).fetchWeatherAndTriage(
                eq(loc), eq(LocalDate.of(2026, 4, 16)), eq(TargetType.SUNRISE),
                eq(Set.of(TideType.HIGH)), eq(EvaluationModel.SONNET),
                eq(false), isNull());
    }

    // ── forceSubmit: verify BatchCreateParams content ────────────────────

    @Test
    @DisplayName("forceSubmit builds BatchCreateParams with correct model, maxTokens, system prompt, user message")
    void forceSubmit_verifiesBatchCreateParamsContent() {
        stubBatchService();
        RegionEntity region = buildRegion(7L, "Northumberland");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(region));

        LocationEntity loc = buildLocation(10L, "Bamburgh Castle", region);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);

        AtmosphericData data = mock(AtmosphericData.class);
        when(data.surge()).thenReturn(null);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, loc,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET,
                LocalDateTime.of(2026, 4, 16, 19, 30), 270, 1,
                EvaluationModel.SONNET, loc.getTideType(), "key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any())).thenReturn(preEval);
        when(promptBuilder.buildUserMessage(data)).thenReturn("weather data for Bamburgh");
        when(promptBuilder.getSystemPrompt()).thenReturn("You are a photographer assistant");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_params");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.forceSubmit(7L, LocalDate.of(2026, 4, 16), TargetType.SUNSET);

        ArgumentCaptor<BatchCreateParams> captor =
                ArgumentCaptor.forClass(BatchCreateParams.class);
        verify(batchService).create(captor.capture());
        BatchCreateParams.Request request = captor.getValue().requests().get(0);

        assertThat(request.params().model().toString())
                .isEqualTo(EvaluationModel.SONNET.getModelId());
        assertThat(request.params().maxTokens()).isEqualTo(512);

        // Default 5-minute TTL — cheaper writes (1.25x vs 2.0x for 1-hour) and batch
        // processing completes within minutes, so cache stays warm within each batch.
        var systemBlock = request.params().system().get().asTextBlockParams().get(0);
        assertThat(systemBlock.cacheControl()).isPresent();
        assertThat(systemBlock.cacheControl().get().ttl()).isEmpty();
    }

    // ── forceSubmit: verify persisted entity fields ──────────────────────

    @Test
    @DisplayName("forceSubmit persists ForecastBatchEntity with correct batchId, type, requestCount, and jobRunId")
    void forceSubmit_verifiesPersistedEntity() {
        stubBatchService();
        RegionEntity region = buildRegion(7L, "Northumberland");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(region));

        LocationEntity loc1 = buildLocation(10L, "Bamburgh Castle", region);
        LocationEntity loc2 = buildLocation(11L, "Dunstanburgh", region);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc1, loc2));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        AtmosphericData data = mock(AtmosphericData.class);
        when(data.surge()).thenReturn(null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any()))
                .thenAnswer(inv -> {
                    LocationEntity l = inv.getArgument(0);
                    return new ForecastPreEvalResult(
                            false, null, data, l,
                            LocalDate.of(2026, 4, 16), TargetType.SUNSET,
                            LocalDateTime.of(2026, 4, 16, 19, 30), 270, 1,
                            EvaluationModel.HAIKU, l.getTideType(), "key", null);
                });
        when(promptBuilder.buildUserMessage(data)).thenReturn("msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("sys");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_persist");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        JobRunEntity jobRun = mock(JobRunEntity.class);
        when(jobRun.getId()).thenReturn(42L);
        when(jobRunService.startBatchRun(2, "msgbatch_persist")).thenReturn(jobRun);

        ForceSubmitResult result = service.forceSubmit(7L,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET);

        assertThat(result.requestCount()).isEqualTo(2);
        assertThat(result.status()).isEqualTo("in_progress");
        assertThat(result.locationsAttempted()).isEqualTo(2);
        assertThat(result.locationsIncluded()).isEqualTo(2);

        ArgumentCaptor<ForecastBatchEntity> entityCaptor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(entityCaptor.capture());
        ForecastBatchEntity saved = entityCaptor.getValue();
        assertThat(saved.getAnthropicBatchId()).isEqualTo("msgbatch_persist");
        assertThat(saved.getBatchType()).isEqualTo(BatchType.FORECAST);
        assertThat(saved.getRequestCount()).isEqualTo(2);
        assertThat(saved.getJobRunId()).isEqualTo(42L);
    }

    // ── forceSubmit: jobRunId null path ──────────────────────────────────

    @Test
    @DisplayName("forceSubmit does not set jobRunId when startBatchRun returns null")
    void forceSubmit_nullJobRun_noJobRunId() {
        stubBatchService();
        RegionEntity region = buildRegion(7L, "Northumberland");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(region));

        LocationEntity loc = buildLocation(10L, "Bamburgh Castle", region);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        AtmosphericData data = mock(AtmosphericData.class);
        when(data.surge()).thenReturn(null);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, loc,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET,
                LocalDateTime.of(2026, 4, 16, 19, 30), 270, 1,
                EvaluationModel.HAIKU, loc.getTideType(), "key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any())).thenReturn(preEval);
        when(promptBuilder.buildUserMessage(data)).thenReturn("msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("sys");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_nojob");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);
        when(jobRunService.startBatchRun(1, "msgbatch_nojob")).thenReturn(null);

        service.forceSubmit(7L, LocalDate.of(2026, 4, 16), TargetType.SUNSET);

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getJobRunId()).isNull();
    }

    // ── forceSubmit: surge data triggers 4-arg buildUserMessage ──────────

    @Test
    @DisplayName("forceSubmit calls 4-arg buildUserMessage when surge data is present")
    void forceSubmit_surgePresent_callsSurgeBuildUserMessage() {
        stubBatchService();
        RegionEntity region = buildRegion(7L, "Northumberland");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(region));

        LocationEntity loc = buildLocation(10L, "Bamburgh Castle", region);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        AtmosphericData data = mock(AtmosphericData.class);
        StormSurgeBreakdown surge = mock(StormSurgeBreakdown.class);
        when(data.tide()).thenReturn(mock(TideSnapshot.class));
        when(data.surge()).thenReturn(surge);
        when(data.adjustedRangeMetres()).thenReturn(3.5);
        when(data.astronomicalRangeMetres()).thenReturn(2.8);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, loc,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET,
                LocalDateTime.of(2026, 4, 16, 19, 30), 270, 1,
                EvaluationModel.HAIKU, loc.getTideType(), "key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any())).thenReturn(preEval);
        when(coastalPromptBuilder.buildUserMessage(data, surge, 3.5, 2.8))
                .thenReturn("surge msg");
        when(coastalPromptBuilder.getSystemPrompt()).thenReturn("sys");
        when(coastalPromptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_surge");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.forceSubmit(7L, LocalDate.of(2026, 4, 16), TargetType.SUNSET);

        verify(coastalPromptBuilder).buildUserMessage(data, surge, 3.5, 2.8);
        verify(coastalPromptBuilder, never()).buildUserMessage(data);
    }

    // ── forceSubmit: locations with null region are filtered out ─────────

    @Test
    @DisplayName("forceSubmit excludes locations with null region")
    void forceSubmit_locationWithNullRegion_excluded() {
        RegionEntity region = buildRegion(7L, "Northumberland");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(region));

        LocationEntity locWithRegion = buildLocation(10L, "Bamburgh Castle", region);
        LocationEntity locNoRegion = buildLocation(20L, "Orphan Location", null);
        when(locationService.findAllEnabled())
                .thenReturn(List.of(locWithRegion, locNoRegion));

        // Only locWithRegion should be processed → but let it fail data assembly
        // so we can verify only 1 was attempted, not 2
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any()))
                .thenThrow(new RuntimeException("data fail"));

        ForceSubmitResult result = service.forceSubmit(7L,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET);

        assertThat(result.locationsAttempted()).isEqualTo(1);
        assertThat(result.failedLocations()).containsExactly("Bamburgh Castle");
    }

    // ── forceSubmit: customId format and sanitisation ────────────────────

    @Test
    @DisplayName("forceSubmit customId encodes region, locationId, date, event and strips non-alphanumeric")
    void forceSubmit_customIdFormat_encodesAllFieldsAndSanitises() {
        stubBatchService();
        RegionEntity region = buildRegion(7L, "North East");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(region));

        LocationEntity loc = buildLocation(42L, "Durham", region);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        AtmosphericData data = mock(AtmosphericData.class);
        when(data.surge()).thenReturn(null);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, loc,
                LocalDate.of(2026, 4, 16), TargetType.SUNRISE,
                LocalDateTime.of(2026, 4, 16, 5, 30), 90, 1,
                EvaluationModel.HAIKU, loc.getTideType(), "key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any())).thenReturn(preEval);
        when(promptBuilder.buildUserMessage(data)).thenReturn("msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("sys");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_cid");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.forceSubmit(7L, LocalDate.of(2026, 4, 16), TargetType.SUNRISE);

        ArgumentCaptor<BatchCreateParams> captor =
                ArgumentCaptor.forClass(BatchCreateParams.class);
        verify(batchService).create(captor.capture());
        String customId = captor.getValue().requests().get(0).customId();

        // "North East" → "NorthEast" (spaces stripped)
        assertThat(customId).isEqualTo("force-NorthEast-42-2026-04-16-SUNRISE");
        assertThat(customId).matches("[a-zA-Z0-9_-]{1,64}");
    }

    // ── forceSubmit: mixed outcome (some succeed, some fail) ─────────────

    @Test
    @DisplayName("forceSubmit with mixed outcomes reports correct attempted/included/failed counts")
    void forceSubmit_mixedOutcome_correctCounts() {
        stubBatchService();
        RegionEntity region = buildRegion(7L, "Northumberland");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(region));

        LocationEntity loc1 = buildLocation(10L, "Bamburgh Castle", region);
        LocationEntity loc2 = buildLocation(11L, "Dunstanburgh", region);
        LocationEntity loc3 = buildLocation(12L, "Holy Island", region);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc1, loc2, loc3));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        AtmosphericData data = mock(AtmosphericData.class);
        when(data.surge()).thenReturn(null);

        // loc1 succeeds, loc2 throws, loc3 returns null atmospheric data
        when(forecastService.fetchWeatherAndTriage(
                eq(loc1), any(), any(), any(), any(), any(Boolean.class), any()))
                .thenReturn(new ForecastPreEvalResult(
                        false, null, data, loc1,
                        LocalDate.of(2026, 4, 16), TargetType.SUNSET,
                        LocalDateTime.of(2026, 4, 16, 19, 30), 270, 1,
                        EvaluationModel.HAIKU, loc1.getTideType(), "key", null));
        when(forecastService.fetchWeatherAndTriage(
                eq(loc2), any(), any(), any(), any(), any(Boolean.class), any()))
                .thenThrow(new RuntimeException("timeout"));
        when(forecastService.fetchWeatherAndTriage(
                eq(loc3), any(), any(), any(), any(), any(Boolean.class), any()))
                .thenReturn(new ForecastPreEvalResult(
                        false, null, null, loc3,
                        LocalDate.of(2026, 4, 16), TargetType.SUNSET,
                        LocalDateTime.of(2026, 4, 16, 19, 30), 270, 1,
                        EvaluationModel.HAIKU, loc3.getTideType(), "key", null));
        when(promptBuilder.buildUserMessage(data)).thenReturn("msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("sys");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_mixed");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        ForceSubmitResult result = service.forceSubmit(7L,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET);

        assertThat(result.batchId()).isEqualTo("msgbatch_mixed");
        assertThat(result.locationsAttempted()).isEqualTo(3);
        assertThat(result.locationsIncluded()).isEqualTo(1);
        assertThat(result.locationsFailedData()).isEqualTo(2);
        assertThat(result.failedLocations())
                .containsExactlyInAnyOrder("Dunstanburgh", "Holy Island");
        assertThat(result.requestCount()).isEqualTo(1);

        ArgumentCaptor<BatchCreateParams> captor =
                ArgumentCaptor.forClass(BatchCreateParams.class);
        verify(batchService).create(captor.capture());
        assertThat(captor.getValue().requests()).hasSize(1);
    }

    // ── getResult: verify batchId passed to retrieve ─────────────────────

    @Test
    @DisplayName("getResult calls retrieve with exact batchId")
    void getResult_verifyRetrieveCalledWithExactBatchId() {
        stubBatchService();
        MessageBatch status = mock(MessageBatch.class);
        when(status.processingStatus())
                .thenReturn(MessageBatch.ProcessingStatus.IN_PROGRESS);
        MessageBatchRequestCounts counts = mock(MessageBatchRequestCounts.class);
        when(counts.processing()).thenReturn(0L);
        when(counts.succeeded()).thenReturn(0L);
        when(counts.errored()).thenReturn(0L);
        when(status.requestCounts()).thenReturn(counts);
        when(batchService.retrieve("msgbatch_exact")).thenReturn(status);

        service.getResult("msgbatch_exact");

        verify(batchService).retrieve("msgbatch_exact");
    }

    // ── getResult: in-progress errored field ─────────────────────────────

    @Test
    @DisplayName("getResult returns errored count from request counts when in progress")
    void getResult_inProgress_returnsErroredCount() {
        stubBatchService();
        MessageBatch status = mock(MessageBatch.class);
        when(status.processingStatus())
                .thenReturn(MessageBatch.ProcessingStatus.IN_PROGRESS);
        MessageBatchRequestCounts counts = mock(MessageBatchRequestCounts.class);
        when(counts.processing()).thenReturn(1L);
        when(counts.succeeded()).thenReturn(4L);
        when(counts.errored()).thenReturn(2L);
        when(status.requestCounts()).thenReturn(counts);
        when(batchService.retrieve("msgbatch_err")).thenReturn(status);

        ForceResultResponse result = service.getResult("msgbatch_err");

        assertThat(result.batchId()).isEqualTo("msgbatch_err");
        assertThat(result.processing()).isEqualTo(1);
        assertThat(result.succeeded()).isEqualTo(4);
        assertThat(result.errored()).isEqualTo(2);
        assertThat(result.cancelled()).isEqualTo(0);
        assertThat(result.totalResults()).isEqualTo(0);
        assertThat(result.results()).isNull();
    }

    // ── getResult: errored result in stream ──────────────────────────────

    @Test
    @DisplayName("getResult counts errored results and records them with null preview")
    @SuppressWarnings("unchecked")
    void getResult_erroredResult_countsAndRecordsNullPreview() {
        stubBatchService();
        MessageBatch status = mock(MessageBatch.class);
        when(status.processingStatus()).thenReturn(MessageBatch.ProcessingStatus.ENDED);
        when(batchService.retrieve("msgbatch_errs")).thenReturn(status);

        MessageBatchIndividualResponse response = mock(MessageBatchIndividualResponse.class);
        when(response.customId()).thenReturn("force-err-10-2026-04-16-SUNSET");
        MessageBatchResult batchResult = mock(MessageBatchResult.class);
        when(batchResult.isSucceeded()).thenReturn(false);
        when(response.result()).thenReturn(batchResult);

        StreamResponse<MessageBatchIndividualResponse> streamResponse =
                mock(StreamResponse.class);
        when(streamResponse.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_errs")).thenReturn(streamResponse);

        ForceResultResponse result = service.getResult("msgbatch_errs");

        assertThat(result.status()).isEqualTo("ended");
        assertThat(result.succeeded()).isEqualTo(0);
        assertThat(result.errored()).isEqualTo(1);
        assertThat(result.totalResults()).isEqualTo(1);
        assertThat(result.results()).hasSize(1);
        assertThat(result.results().get(0).status()).isEqualTo("errored");
        assertThat(result.results().get(0).responsePreview()).isNull();
        assertThat(result.results().get(0).customId())
                .isEqualTo("force-err-10-2026-04-16-SUNSET");
    }

    // ── getResult: response preview truncation at 500 chars ──────────────

    @Test
    @DisplayName("getResult truncates response preview to 500 chars")
    @SuppressWarnings("unchecked")
    void getResult_longResponse_truncatesPreviewTo500() {
        stubBatchService();
        MessageBatch status = mock(MessageBatch.class);
        when(status.processingStatus()).thenReturn(MessageBatch.ProcessingStatus.ENDED);
        when(batchService.retrieve("msgbatch_long")).thenReturn(status);

        String longText = "x".repeat(800);

        MessageBatchIndividualResponse response = mock(MessageBatchIndividualResponse.class);
        when(response.customId()).thenReturn("force-long-1");
        MessageBatchResult batchResult = mock(MessageBatchResult.class);
        when(batchResult.isSucceeded()).thenReturn(true);
        MessageBatchSucceededResult succeeded = mock(MessageBatchSucceededResult.class);
        when(batchResult.succeeded()).thenReturn(Optional.of(succeeded));
        Message message = mock(Message.class);
        when(succeeded.message()).thenReturn(message);
        TextBlock textBlock = mock(TextBlock.class);
        when(textBlock.text()).thenReturn(longText);
        ContentBlock contentBlock = mock(ContentBlock.class);
        when(contentBlock.isText()).thenReturn(true);
        when(contentBlock.asText()).thenReturn(textBlock);
        when(message.content()).thenReturn(List.of(contentBlock));
        when(response.result()).thenReturn(batchResult);

        StreamResponse<MessageBatchIndividualResponse> streamResponse =
                mock(StreamResponse.class);
        when(streamResponse.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_long")).thenReturn(streamResponse);

        ForceResultResponse result = service.getResult("msgbatch_long");

        assertThat(result.results().get(0).responsePreview()).hasSize(500);
    }

    // ── getResult: text exactly 500 chars should NOT be truncated ────────

    @Test
    @DisplayName("getResult does not truncate response preview at exactly 500 chars")
    @SuppressWarnings("unchecked")
    void getResult_exact500CharResponse_noTruncation() {
        stubBatchService();
        MessageBatch status = mock(MessageBatch.class);
        when(status.processingStatus()).thenReturn(MessageBatch.ProcessingStatus.ENDED);
        when(batchService.retrieve("msgbatch_500")).thenReturn(status);

        String exact500 = "y".repeat(500);

        MessageBatchIndividualResponse response = mock(MessageBatchIndividualResponse.class);
        when(response.customId()).thenReturn("force-500");
        MessageBatchResult batchResult = mock(MessageBatchResult.class);
        when(batchResult.isSucceeded()).thenReturn(true);
        MessageBatchSucceededResult succeeded = mock(MessageBatchSucceededResult.class);
        when(batchResult.succeeded()).thenReturn(Optional.of(succeeded));
        Message message = mock(Message.class);
        when(succeeded.message()).thenReturn(message);
        TextBlock textBlock = mock(TextBlock.class);
        when(textBlock.text()).thenReturn(exact500);
        ContentBlock contentBlock = mock(ContentBlock.class);
        when(contentBlock.isText()).thenReturn(true);
        when(contentBlock.asText()).thenReturn(textBlock);
        when(message.content()).thenReturn(List.of(contentBlock));
        when(response.result()).thenReturn(batchResult);

        StreamResponse<MessageBatchIndividualResponse> streamResponse =
                mock(StreamResponse.class);
        when(streamResponse.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_500")).thenReturn(streamResponse);

        ForceResultResponse result = service.getResult("msgbatch_500");

        assertThat(result.results().get(0).responsePreview()).hasSize(500);
        assertThat(result.results().get(0).responsePreview()).isEqualTo(exact500);
    }

    // ── getResult: results capped at 5 entries ──────────────────────────

    @Test
    @DisplayName("getResult caps results list at 5 entries even with more results")
    @SuppressWarnings("unchecked")
    void getResult_manyResults_capsAt5() {
        stubBatchService();
        MessageBatch status = mock(MessageBatch.class);
        when(status.processingStatus()).thenReturn(MessageBatch.ProcessingStatus.ENDED);
        when(batchService.retrieve("msgbatch_many")).thenReturn(status);

        // Build 8 succeeded responses
        List<MessageBatchIndividualResponse> responses = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            MessageBatchIndividualResponse response =
                    mock(MessageBatchIndividualResponse.class);
            when(response.customId()).thenReturn("force-many-" + i);
            MessageBatchResult batchResult = mock(MessageBatchResult.class);
            when(batchResult.isSucceeded()).thenReturn(true);
            MessageBatchSucceededResult succeeded = mock(MessageBatchSucceededResult.class);
            when(batchResult.succeeded()).thenReturn(Optional.of(succeeded));
            Message message = mock(Message.class);
            when(succeeded.message()).thenReturn(message);
            TextBlock textBlock = mock(TextBlock.class);
            when(textBlock.text()).thenReturn("{\"rating\":" + i + "}");
            ContentBlock contentBlock = mock(ContentBlock.class);
            when(contentBlock.isText()).thenReturn(true);
            when(contentBlock.asText()).thenReturn(textBlock);
            when(message.content()).thenReturn(List.of(contentBlock));
            when(response.result()).thenReturn(batchResult);
            responses.add(response);
        }

        StreamResponse<MessageBatchIndividualResponse> streamResponse =
                mock(StreamResponse.class);
        when(streamResponse.stream()).thenReturn(responses.stream());
        when(batchService.resultsStreaming("msgbatch_many")).thenReturn(streamResponse);

        ForceResultResponse result = service.getResult("msgbatch_many");

        assertThat(result.results()).hasSize(5);
        assertThat(result.totalResults()).isEqualTo(8);
        assertThat(result.succeeded()).isEqualTo(8);
        // First 5 entries should have customIds force-many-0 through force-many-4
        assertThat(result.results().get(0).customId()).isEqualTo("force-many-0");
        assertThat(result.results().get(4).customId()).isEqualTo("force-many-4");
    }

    // ── getResult: mixed succeeded + errored in same stream ────────────

    @Test
    @DisplayName("getResult counts both succeeded and errored results in same stream")
    @SuppressWarnings("unchecked")
    void getResult_mixedSucceededAndErrored_correctCounts() {
        stubBatchService();
        MessageBatch status = mock(MessageBatch.class);
        when(status.processingStatus()).thenReturn(MessageBatch.ProcessingStatus.ENDED);
        when(batchService.retrieve("msgbatch_mix")).thenReturn(status);

        // Build one succeeded + one errored response
        MessageBatchIndividualResponse ok = mock(MessageBatchIndividualResponse.class);
        when(ok.customId()).thenReturn("force-ok-1");
        MessageBatchResult okResult = mock(MessageBatchResult.class);
        when(okResult.isSucceeded()).thenReturn(true);
        MessageBatchSucceededResult okSucceeded = mock(MessageBatchSucceededResult.class);
        when(okResult.succeeded()).thenReturn(Optional.of(okSucceeded));
        Message okMessage = mock(Message.class);
        when(okSucceeded.message()).thenReturn(okMessage);
        TextBlock okText = mock(TextBlock.class);
        when(okText.text()).thenReturn("{\"rating\":4}");
        ContentBlock okBlock = mock(ContentBlock.class);
        when(okBlock.isText()).thenReturn(true);
        when(okBlock.asText()).thenReturn(okText);
        when(okMessage.content()).thenReturn(List.of(okBlock));
        when(ok.result()).thenReturn(okResult);

        MessageBatchIndividualResponse err = mock(MessageBatchIndividualResponse.class);
        when(err.customId()).thenReturn("force-err-2");
        MessageBatchResult errResult = mock(MessageBatchResult.class);
        when(errResult.isSucceeded()).thenReturn(false);
        when(err.result()).thenReturn(errResult);

        StreamResponse<MessageBatchIndividualResponse> streamResponse =
                mock(StreamResponse.class);
        when(streamResponse.stream()).thenReturn(Stream.of(ok, err));
        when(batchService.resultsStreaming("msgbatch_mix")).thenReturn(streamResponse);

        ForceResultResponse result = service.getResult("msgbatch_mix");

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.errored()).isEqualTo(1);
        assertThat(result.totalResults()).isEqualTo(2);
        assertThat(result.results()).hasSize(2);
        assertThat(result.results().get(0).status()).isEqualTo("succeeded");
        assertThat(result.results().get(0).responsePreview()).contains("rating");
        assertThat(result.results().get(1).status()).isEqualTo("errored");
        assertThat(result.results().get(1).responsePreview()).isNull();
    }

    // ── getResult: verify resultsStreaming called with exact batchId ─────

    @Test
    @DisplayName("getResult calls resultsStreaming with exact batchId when ENDED")
    @SuppressWarnings("unchecked")
    void getResult_ended_callsResultsStreamingWithExactBatchId() {
        stubBatchService();
        MessageBatch status = mock(MessageBatch.class);
        when(status.processingStatus()).thenReturn(MessageBatch.ProcessingStatus.ENDED);
        when(batchService.retrieve("msgbatch_stream123")).thenReturn(status);

        StreamResponse<MessageBatchIndividualResponse> streamResponse =
                mock(StreamResponse.class);
        when(streamResponse.stream()).thenReturn(Stream.empty());
        when(batchService.resultsStreaming("msgbatch_stream123")).thenReturn(streamResponse);

        service.getResult("msgbatch_stream123");

        verify(batchService).resultsStreaming("msgbatch_stream123");
    }

    // ── getResult: response preview at 501 chars (boundary just above 500) ──

    @Test
    @DisplayName("getResult truncates response preview at 501 chars to exactly 500")
    @SuppressWarnings("unchecked")
    void getResult_501CharResponse_truncatesTo500() {
        stubBatchService();
        MessageBatch status = mock(MessageBatch.class);
        when(status.processingStatus()).thenReturn(MessageBatch.ProcessingStatus.ENDED);
        when(batchService.retrieve("msgbatch_501")).thenReturn(status);

        String text501 = "z".repeat(501);

        MessageBatchIndividualResponse response = mock(MessageBatchIndividualResponse.class);
        when(response.customId()).thenReturn("force-501");
        MessageBatchResult batchResult = mock(MessageBatchResult.class);
        when(batchResult.isSucceeded()).thenReturn(true);
        MessageBatchSucceededResult succeeded = mock(MessageBatchSucceededResult.class);
        when(batchResult.succeeded()).thenReturn(Optional.of(succeeded));
        Message message = mock(Message.class);
        when(succeeded.message()).thenReturn(message);
        TextBlock textBlock = mock(TextBlock.class);
        when(textBlock.text()).thenReturn(text501);
        ContentBlock contentBlock = mock(ContentBlock.class);
        when(contentBlock.isText()).thenReturn(true);
        when(contentBlock.asText()).thenReturn(textBlock);
        when(message.content()).thenReturn(List.of(contentBlock));
        when(response.result()).thenReturn(batchResult);

        StreamResponse<MessageBatchIndividualResponse> streamResponse =
                mock(StreamResponse.class);
        when(streamResponse.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_501")).thenReturn(streamResponse);

        ForceResultResponse result = service.getResult("msgbatch_501");

        assertThat(result.results().get(0).responsePreview()).hasSize(500);
    }

    // ── forceSubmit: verify jobRunService.startBatchRun exact args ───────

    @Test
    @DisplayName("forceSubmit calls startBatchRun with exact request count and batch ID")
    void forceSubmit_verifiesStartBatchRunArgs() {
        stubBatchService();
        RegionEntity region = buildRegion(7L, "Northumberland");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(region));

        LocationEntity loc = buildLocation(10L, "Bamburgh Castle", region);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        AtmosphericData data = mock(AtmosphericData.class);
        when(data.surge()).thenReturn(null);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, loc,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET,
                LocalDateTime.of(2026, 4, 16, 19, 30), 270, 1,
                EvaluationModel.HAIKU, loc.getTideType(), "key", null);
        when(forecastService.fetchWeatherAndTriage(
                eq(loc), eq(LocalDate.of(2026, 4, 16)), eq(TargetType.SUNSET),
                eq(Set.of()), eq(EvaluationModel.HAIKU),
                eq(false), isNull()))
                .thenReturn(preEval);
        when(promptBuilder.buildUserMessage(data)).thenReturn("msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("sys");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_jrun");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.forceSubmit(7L, LocalDate.of(2026, 4, 16), TargetType.SUNSET);

        verify(jobRunService).startBatchRun(1, "msgbatch_jrun");
    }

    // ── forceSubmit: inland path uses promptBuilder, NOT coastalPromptBuilder ──

    @Test
    @DisplayName("forceSubmit uses inland promptBuilder when no tide data, never coastalPromptBuilder")
    void forceSubmit_inlandLocation_usesInlandPromptBuilder() {
        stubBatchService();
        RegionEntity region = buildRegion(7L, "Northumberland");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(region));

        LocationEntity loc = buildLocation(10L, "Hadrian's Wall", region);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        AtmosphericData data = mock(AtmosphericData.class);
        when(data.tide()).thenReturn(null);
        when(data.surge()).thenReturn(null);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, loc,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET,
                LocalDateTime.of(2026, 4, 16, 19, 30), 270, 1,
                EvaluationModel.HAIKU, loc.getTideType(), "key", null);
        when(forecastService.fetchWeatherAndTriage(
                eq(loc), eq(LocalDate.of(2026, 4, 16)), eq(TargetType.SUNSET),
                eq(Set.of()), eq(EvaluationModel.HAIKU),
                eq(false), isNull()))
                .thenReturn(preEval);
        when(promptBuilder.buildUserMessage(data)).thenReturn("inland msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("sys");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_inland");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.forceSubmit(7L, LocalDate.of(2026, 4, 16), TargetType.SUNSET);

        verify(promptBuilder).buildUserMessage(data);
        verify(promptBuilder).getSystemPrompt();
        verify(coastalPromptBuilder, never()).buildUserMessage(any(AtmosphericData.class));
        verify(coastalPromptBuilder, never()).getSystemPrompt();
    }

    // ── getResult: empty stream returns zero counts ─────────────────────

    @Test
    @DisplayName("getResult with empty stream returns zero succeeded, errored, totalResults")
    @SuppressWarnings("unchecked")
    void getResult_emptyStream_zeroCountsAndEmptyResults() {
        stubBatchService();
        MessageBatch status = mock(MessageBatch.class);
        when(status.processingStatus()).thenReturn(MessageBatch.ProcessingStatus.ENDED);
        when(batchService.retrieve("msgbatch_empty")).thenReturn(status);

        StreamResponse<MessageBatchIndividualResponse> streamResponse =
                mock(StreamResponse.class);
        when(streamResponse.stream()).thenReturn(Stream.empty());
        when(batchService.resultsStreaming("msgbatch_empty")).thenReturn(streamResponse);

        ForceResultResponse result = service.getResult("msgbatch_empty");

        assertThat(result.status()).isEqualTo("ended");
        assertThat(result.succeeded()).isEqualTo(0);
        assertThat(result.errored()).isEqualTo(0);
        assertThat(result.totalResults()).isEqualTo(0);
        assertThat(result.results()).isEmpty();
    }

    // ── forceSubmit: batch entity has BatchType.FORECAST ────────────────

    @Test
    @DisplayName("forceSubmit persists batch entity with BatchType.FORECAST regardless of content")
    void forceSubmit_persistedEntityHasForecastBatchType() {
        stubBatchService();
        RegionEntity region = buildRegion(7L, "Northumberland");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(region));

        LocationEntity loc = buildLocation(10L, "Bamburgh", region);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        AtmosphericData data = mock(AtmosphericData.class);
        when(data.surge()).thenReturn(null);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, loc,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET,
                LocalDateTime.of(2026, 4, 16, 19, 30), 270, 1,
                EvaluationModel.HAIKU, loc.getTideType(), "key", null);
        when(forecastService.fetchWeatherAndTriage(
                eq(loc), eq(LocalDate.of(2026, 4, 16)), eq(TargetType.SUNSET),
                eq(Set.of()), eq(EvaluationModel.HAIKU),
                eq(false), isNull()))
                .thenReturn(preEval);
        when(promptBuilder.buildUserMessage(data)).thenReturn("msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("sys");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_type");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.forceSubmit(7L, LocalDate.of(2026, 4, 16), TargetType.SUNSET);

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getBatchType()).isEqualTo(BatchType.FORECAST);
    }

    // ── getResult: CANCELING status ──────────────────────────────────────

    @Test
    @DisplayName("getResult returns canceling status when batch is canceling")
    void getResult_canceling_returnsCancelingStatus() {
        stubBatchService();
        MessageBatch status = mock(MessageBatch.class);
        when(status.processingStatus())
                .thenReturn(MessageBatch.ProcessingStatus.CANCELING);
        MessageBatchRequestCounts counts = mock(MessageBatchRequestCounts.class);
        when(counts.processing()).thenReturn(0L);
        when(counts.succeeded()).thenReturn(5L);
        when(counts.errored()).thenReturn(1L);
        when(status.requestCounts()).thenReturn(counts);
        when(batchService.retrieve("msgbatch_cancel")).thenReturn(status);

        ForceResultResponse result = service.getResult("msgbatch_cancel");

        assertThat(result.status()).isEqualTo("canceling");
        assertThat(result.succeeded()).isEqualTo(5);
        assertThat(result.errored()).isEqualTo(1);
        assertThat(result.results()).isNull();
    }

    @Test
    @DisplayName("forceSubmit resolves model from BATCH_NEAR_TERM, not SCHEDULED_BATCH")
    void forceSubmit_resolvesModelFromBatchNearTerm() {
        stubBatchService();
        RegionEntity region = buildRegion(7L, "Northumberland");
        when(regionRepository.findById(7L)).thenReturn(Optional.of(region));

        LocationEntity loc = buildLocation(10L, "Bamburgh Castle", region);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.SONNET);

        AtmosphericData data = mock(AtmosphericData.class);
        when(data.surge()).thenReturn(null);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, loc,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET,
                LocalDateTime.of(2026, 4, 16, 19, 30), 270, 1,
                EvaluationModel.SONNET, loc.getTideType(), "key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(),
                any(Boolean.class), any())).thenReturn(preEval);
        when(promptBuilder.buildUserMessage(any(AtmosphericData.class))).thenReturn("msg");
        when(promptBuilder.getSystemPrompt()).thenReturn("sys");
        when(promptBuilder.buildOutputConfig()).thenReturn(buildTestOutputConfig());

        MessageBatch mockBatch = mock(MessageBatch.class);
        when(mockBatch.id()).thenReturn("msgbatch_rt");
        when(mockBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusDays(1));
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(mockBatch);

        service.forceSubmit(7L, LocalDate.of(2026, 4, 16), TargetType.SUNSET);

        // Verify the exact RunType used to resolve the model
        verify(modelSelectionService).getActiveModel(RunType.BATCH_NEAR_TERM);
        // Batch request uses SONNET (the resolved model)
        ArgumentCaptor<BatchCreateParams> captor =
                ArgumentCaptor.forClass(BatchCreateParams.class);
        verify(batchService).create(captor.capture());
        assertThat(captor.getValue().requests().get(0).params().model().asString())
                .contains("sonnet");
    }

    private RegionEntity buildRegion(Long id, String name) {
        RegionEntity region = new RegionEntity();
        region.setId(id);
        region.setName(name);
        return region;
    }

    private LocationEntity buildLocation(Long id, String name, RegionEntity region) {
        LocationEntity location = new LocationEntity();
        location.setId(id);
        location.setName(name);
        location.setLat(55.6);
        location.setLon(-1.7);
        location.setRegion(region);
        location.setTideType(Set.of());
        return location;
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
