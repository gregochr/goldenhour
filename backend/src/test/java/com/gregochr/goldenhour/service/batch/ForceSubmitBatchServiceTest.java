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
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
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
        service = new ForceSubmitBatchService(
                anthropicClient, batchRepository, regionRepository, locationService,
                forecastService, promptBuilder, coastalPromptBuilder, modelSelectionService,
                jobRunService);
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
        when(modelSelectionService.getActiveModel(RunType.SCHEDULED_BATCH))
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
        when(modelSelectionService.getActiveModel(RunType.SCHEDULED_BATCH))
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
        when(modelSelectionService.getActiveModel(RunType.SCHEDULED_BATCH))
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
        when(modelSelectionService.getActiveModel(RunType.SCHEDULED_BATCH))
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
        when(modelSelectionService.getActiveModel(RunType.SCHEDULED_BATCH))
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
        when(modelSelectionService.getActiveModel(RunType.SCHEDULED_BATCH))
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
