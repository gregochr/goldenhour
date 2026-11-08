package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.service.ServedBriefingEpoch;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.anthropic.services.blocking.MessageService;
import com.anthropic.services.blocking.messages.BatchService;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.CacheDiagnostics;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.service.EvaluationFailure;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.service.CostCalculator;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.evaluation.AuroraResultHandler;
import com.gregochr.goldenhour.service.evaluation.AuroraResultHandler.AuroraBatchOutcome;
import com.gregochr.goldenhour.service.evaluation.ClaudeBatchOutcome;
import com.gregochr.goldenhour.service.evaluation.ForecastResultHandler;
import com.gregochr.goldenhour.service.evaluation.ForecastResultHandler.BatchSuccess;
import com.gregochr.goldenhour.service.evaluation.ForecastResultHandler.ForecastIdentity;
import com.gregochr.goldenhour.service.evaluation.ResultContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BatchResultProcessor} as the per-batch orchestrator.
 *
 * <p>This class no longer covers parsing, validation, or cache-write behaviours —
 * those moved to {@link com.gregochr.goldenhour.service.evaluation.ForecastResultHandler}
 * and {@link com.gregochr.goldenhour.service.evaluation.AuroraResultHandler} in Pass 3.2.
 * The integration test pyramid in {@code com.gregochr.goldenhour.integration} covers the
 * end-to-end byte-identical writes.
 *
 * <p>What this class does cover:
 * <ul>
 *   <li>Dispatch by {@link BatchType} (FORECAST → forecast handler, AURORA → aurora handler)</li>
 *   <li>Per-response loop wiring: handler delegation, error counting, cache-key flushing</li>
 *   <li>Stream-failure handling (mark batch FAILED, no handler interaction)</li>
 *   <li>Static helpers: {@link BatchResultProcessor#describeFailedResult},
 *       {@link BatchResultProcessor#resolveEvaluationModel}</li>
 * </ul>
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
    private LocationRepository locationRepository;
    @Mock
    private JobRunService jobRunService;
    @Mock
    private CostCalculator costCalculator;
    @Mock
    private ForecastResultHandler forecastResultHandler;
    @Mock
    private AuroraResultHandler auroraResultHandler;
    @Mock
    private com.gregochr.goldenhour.service.evaluation.EvaluationAbandonmentService
            evaluationAbandonmentService;
    @Mock
    private PipelineRunRepository pipelineRunRepository;

    private BatchResultProcessor processor;
    private final ServedBriefingEpoch epoch = new ServedBriefingEpoch();

    @BeforeEach
    void setUp() {
        processor = new BatchResultProcessor(
                anthropicClient, batchRepository, locationRepository,
                jobRunService, costCalculator,
                forecastResultHandler, auroraResultHandler, evaluationAbandonmentService,
                pipelineRunRepository, epoch);
    }

    private void stubBatchService() {
        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.batches()).thenReturn(batchService);
    }

    // ── Serve epoch (P1b) ────────────────────────────────────────────────────

    @Test
    @DisplayName("every processed result batch bumps the serve epoch, a failed one included")
    void processResults_bumpsServeEpoch() {
        stubBatchService();
        when(batchService.resultsStreaming("msgbatch_fail"))
                .thenThrow(new RuntimeException("Network error"));
        long before = epoch.current();

        processor.processResults(buildBatch(BatchType.FORECAST));

        assertThat(epoch.current()).isGreaterThan(before);
    }

    // ── Stream failure ───────────────────────────────────────────────────────

    @Test
    @DisplayName("FORECAST stream throws → batch marked FAILED, no handler called")
    void forecast_streamThrows_marksFailedAndSkipsHandler() {
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
        verifyNoInteractions(forecastResultHandler);
    }

    @Test
    @DisplayName("AURORA stream throws → batch marked FAILED, no handler called")
    void aurora_streamThrows_marksFailedAndSkipsHandler() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.AURORA);
        when(batchService.resultsStreaming("msgbatch_fail"))
                .thenThrow(new RuntimeException("Connection reset"));

        processor.processResults(batch);

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.FAILED);
        verifyNoInteractions(auroraResultHandler);
    }

    // ── Unknown batch type ───────────────────────────────────────────────────

    @Test
    @DisplayName("Unknown batch type → marked FAILED, no handler called")
    void unknownBatchType_marksFailed() {
        ForecastBatchEntity batch = mock(ForecastBatchEntity.class);
        when(batch.getAnthropicBatchId()).thenReturn("msgbatch_unknown");
        when(batch.getBatchType()).thenReturn(null);

        processor.processResults(batch);

        verify(batch).setStatus(BatchStatus.FAILED);
        verifyNoInteractions(forecastResultHandler);
        verifyNoInteractions(auroraResultHandler);
    }

    // ── FORECAST: delegates per-response to ForecastResultHandler ────────────

    @Test
    @DisplayName("FORECAST: succeeded response routes to ForecastResultHandler.parseBatchResponse")
    void forecast_successfulResponse_routesToForecastHandler() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);
        LocationEntity location = buildLocationWithRegion(42L, "Castlerigg", "Lake District");
        when(locationRepository.findAll()).thenReturn(List.of(location));

        MessageBatchIndividualResponse response = succeededResponse(
                "fc-42-2026-04-07-SUNRISE", "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65,\"summary\":\"X\"}");
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        BriefingEvaluationResult parsed = new BriefingEvaluationResult(
                "Castlerigg", 4, 70, 65, "X");
        when(forecastResultHandler.parseBatchResponse(
                eq(location), any(ForecastIdentity.class),
                any(ClaudeBatchOutcome.class), any(ResultContext.class)))
                .thenReturn(Optional.of(new BatchSuccess(
                        "Lake District|2026-04-07|SUNRISE", parsed)));

        processor.processResults(batch);

        verify(forecastResultHandler).mergeCacheKey(
                eq("Lake District|2026-04-07|SUNRISE"), eq(List.of(parsed)));
        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(captor.getValue().getSucceededCount()).isEqualTo(1);
        assertThat(captor.getValue().getErroredCount()).isZero();
        // R7(a): the event-driven abandonment sweep must run at the end of every forecast
        // batch's normal completion, not just on the failure path.
        verify(evaluationAbandonmentService).abandonPendingForBatch("msgbatch_fail");
    }

    @Test
    @DisplayName("round 12: a batch belonging to an orchestrated cycle stamps its results with "
            + "the cycle's PipelineRunEntity.triggerTime, not the batch's own submittedAt")
    void forecast_pipelineRunBatch_stampsResultContextWithCycleTriggerTime() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);
        batch.setPipelineRunId(7L);
        LocationEntity location = buildLocationWithRegion(42L, "Castlerigg", "Lake District");
        when(locationRepository.findAll()).thenReturn(List.of(location));

        Instant cycleTriggerTime = Instant.parse("2026-03-30T01:05:00Z");
        PipelineRunEntity pipelineRun = new PipelineRunEntity(CycleType.NIGHTLY, cycleTriggerTime);
        when(pipelineRunRepository.findById(7L)).thenReturn(Optional.of(pipelineRun));

        MessageBatchIndividualResponse response = succeededResponse(
                "fc-42-2026-04-07-SUNRISE", "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65,\"summary\":\"X\"}");
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        BriefingEvaluationResult parsed = new BriefingEvaluationResult(
                "Castlerigg", 4, 70, 65, "X");
        when(forecastResultHandler.parseBatchResponse(
                eq(location), any(ForecastIdentity.class),
                any(ClaudeBatchOutcome.class), any(ResultContext.class)))
                .thenReturn(Optional.of(new BatchSuccess(
                        "Lake District|2026-04-07|SUNRISE", parsed)));

        processor.processResults(batch);

        ArgumentCaptor<ResultContext> contextCaptor = ArgumentCaptor.forClass(ResultContext.class);
        verify(forecastResultHandler).parseBatchResponse(
                eq(location), any(ForecastIdentity.class), any(ClaudeBatchOutcome.class),
                contextCaptor.capture());
        // The cycle's OWN trigger time — NOT batch.getSubmittedAt(), which ForecastBatchEntity's
        // own field default would otherwise stamp with a fresh Instant.now() at construction.
        assertThat(contextCaptor.getValue().submissionInstant()).isEqualTo(cycleTriggerTime);
        assertThat(contextCaptor.getValue().submissionInstant())
                .isNotEqualTo(batch.getSubmittedAt());
    }

    @Test
    @DisplayName("round 12: a RETRY batch sharing its precursor's pipelineRunId resolves to the "
            + "SAME submission instant as the precursor — the retry's own (later) submittedAt is "
            + "never used")
    void forecast_retryBatch_resolvesToSameCycleTriggerTimeAsPrecursor() {
        stubBatchService();
        // The retry batch's OWN submittedAt is deliberately later than the cycle's trigger time —
        // BatchRetryService submits it only after the precursor's failures are known, minutes or
        // more after the cycle started — proving the lookup ignores it entirely.
        ForecastBatchEntity retryBatch = new ForecastBatchEntity(
                "msgbatch_retry", BatchType.FORECAST, 1, Instant.now().plusSeconds(86400));
        retryBatch.setPipelineRunId(7L);
        retryBatch.setRetry(true);
        LocationEntity location = buildLocationWithRegion(42L, "Castlerigg", "Lake District");
        when(locationRepository.findAll()).thenReturn(List.of(location));

        Instant cycleTriggerTime = Instant.parse("2026-03-30T01:05:00Z");
        PipelineRunEntity pipelineRun = new PipelineRunEntity(CycleType.NIGHTLY, cycleTriggerTime);
        when(pipelineRunRepository.findById(7L)).thenReturn(Optional.of(pipelineRun));

        MessageBatchIndividualResponse response = succeededResponse(
                "fc-42-2026-04-07-SUNRISE", "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65,\"summary\":\"X\"}");
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_retry")).thenReturn(streamResp);

        BriefingEvaluationResult parsed = new BriefingEvaluationResult(
                "Castlerigg", 4, 70, 65, "X");
        when(forecastResultHandler.parseBatchResponse(
                eq(location), any(ForecastIdentity.class),
                any(ClaudeBatchOutcome.class), any(ResultContext.class)))
                .thenReturn(Optional.of(new BatchSuccess(
                        "Lake District|2026-04-07|SUNRISE", parsed)));

        processor.processResults(retryBatch);

        ArgumentCaptor<ResultContext> contextCaptor = ArgumentCaptor.forClass(ResultContext.class);
        verify(forecastResultHandler).parseBatchResponse(
                eq(location), any(ForecastIdentity.class), any(ClaudeBatchOutcome.class),
                contextCaptor.capture());
        assertThat(contextCaptor.getValue().submissionInstant()).isEqualTo(cycleTriggerTime);
        assertThat(retryBatch.getSubmittedAt()).isAfter(cycleTriggerTime);
    }

    @Test
    @DisplayName("round 12: an ad-hoc batch with no pipelineRunId falls back to its own submittedAt")
    void forecast_adHocBatch_fallsBackToOwnSubmittedAt() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST); // pipelineRunId stays null
        LocationEntity location = buildLocationWithRegion(42L, "Castlerigg", "Lake District");
        when(locationRepository.findAll()).thenReturn(List.of(location));

        MessageBatchIndividualResponse response = succeededResponse(
                "fc-42-2026-04-07-SUNRISE", "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65,\"summary\":\"X\"}");
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        BriefingEvaluationResult parsed = new BriefingEvaluationResult(
                "Castlerigg", 4, 70, 65, "X");
        when(forecastResultHandler.parseBatchResponse(
                eq(location), any(ForecastIdentity.class),
                any(ClaudeBatchOutcome.class), any(ResultContext.class)))
                .thenReturn(Optional.of(new BatchSuccess(
                        "Lake District|2026-04-07|SUNRISE", parsed)));

        processor.processResults(batch);

        ArgumentCaptor<ResultContext> contextCaptor = ArgumentCaptor.forClass(ResultContext.class);
        verify(forecastResultHandler).parseBatchResponse(
                eq(location), any(ForecastIdentity.class), any(ClaudeBatchOutcome.class),
                contextCaptor.capture());
        assertThat(contextCaptor.getValue().submissionInstant()).isEqualTo(batch.getSubmittedAt());
        verifyNoInteractions(pipelineRunRepository);
    }

    @Test
    @DisplayName("FORECAST: handler returns Optional.empty() → erroredCount incremented, no flush")
    void forecast_handlerEmpty_incrementsErroredAndDoesNotFlush() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);
        LocationEntity location = buildLocationWithRegion(42L, "Castlerigg", "Lake District");
        when(locationRepository.findAll()).thenReturn(List.of(location));

        MessageBatchIndividualResponse response = succeededResponse(
                "fc-42-2026-04-07-SUNRISE", "garbage-not-json");
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        when(forecastResultHandler.parseBatchResponse(
                eq(location), any(ForecastIdentity.class),
                any(ClaudeBatchOutcome.class), any(ResultContext.class)))
                .thenReturn(Optional.empty());

        processor.processResults(batch);

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getErroredCount()).isEqualTo(1);
        assertThat(captor.getValue().getSucceededCount()).isZero();
        // No succeeded results → batch marked FAILED
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.FAILED);
    }

    @Test
    @DisplayName("FORECAST: SDK-errored response → handler not called, jobRunService.logBatchResult invoked")
    void forecast_sdkErroredResponse_logsInlineFailure() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_fail", 1, 99L);

        MessageBatchIndividualResponse response = erroredResponse(
                "fc-42-2026-04-07-SUNRISE", "overloaded_error", "busy");
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        verify(forecastResultHandler, never()).parseBatchResponse(
                any(), any(), any(), any());
        verify(jobRunService).logBatchResult(
                eq(99L), eq("msgbatch_fail"), eq("fc-42-2026-04-07-SUNRISE"),
                eq(false),
                eq("overloaded_error"), eq("busy"),
                any(), any(), any(), any(), eq(null), eq(null));
    }

    @Test
    @DisplayName("FORECAST: max_tokens truncation → handler not called, no cache write, "
            + "MAX_TOKENS logged as errored (not a silent partial success)")
    void forecast_maxTokensTruncation_logsInlineFailureAndDoesNotPersist() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_fail", 1, 55L);

        // The processor rejects on stop_reason alone, before ever looking at content — so this
        // fixture carries no text at all (see succeededResponseWithStopReason). That the same
        // rejection applies regardless of content is proven at the ClaudeEvaluationStrategyTest
        // level, where a MAX_TOKENS fixture holds identical content to a parseable END_TURN one.
        MessageBatchIndividualResponse response = succeededResponseWithStopReason(
                "fc-42-2026-04-07-SUNRISE", StopReason.MAX_TOKENS);
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        verify(forecastResultHandler, never()).parseBatchResponse(any(), any(), any(), any());
        verify(forecastResultHandler, never()).mergeCacheKey(any(), any());
        verify(jobRunService).logBatchResult(
                eq(55L), eq("msgbatch_fail"), eq("fc-42-2026-04-07-SUNRISE"),
                eq(false),
                eq("truncation_error"),
                eq("Claude's response was truncated at the max_tokens limit "
                        + "(stop_reason=max_tokens)"),
                eq(EvaluationModel.SONNET_55), eq(new TokenUsage(700, 123, 300, 40, 0)),
                isNull(), isNull(), eq(null), eq(CacheDiagnostics.EMPTY));

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getErroredCount()).isEqualTo(1);
        assertThat(captor.getValue().getSucceededCount()).isZero();
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.FAILED);
    }

    @Test
    @DisplayName("FORECAST: refusal (stop_reason=refusal) → handler not called, REFUSAL logged as errored")
    void forecast_refusal_logsInlineFailureAndDoesNotPersist() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_fail", 1, 56L);
        when(costCalculator.calculateCostMicroDollars(
                EvaluationModel.SONNET_55, new TokenUsage(700, 123, 300, 40, 0), true))
                .thenReturn(1_234_567L);

        MessageBatchIndividualResponse response = succeededResponseWithStopReason(
                "fc-42-2026-04-07-SUNRISE", StopReason.REFUSAL);
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        verify(forecastResultHandler, never()).parseBatchResponse(any(), any(), any(), any());
        verify(forecastResultHandler, never()).mergeCacheKey(any(), any());
        verify(jobRunService).logBatchResult(
                eq(56L), eq("msgbatch_fail"), eq("fc-42-2026-04-07-SUNRISE"),
                eq(false),
                eq(EvaluationFailure.TYPE_REFUSAL),
                eq("Claude refused to evaluate this forecast (stop_reason=refusal)"),
                eq(EvaluationModel.SONNET_55), eq(new TokenUsage(700, 123, 300, 40, 0)),
                isNull(), isNull(), eq(null), eq(CacheDiagnostics.EMPTY));

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getErroredCount()).isEqualTo(1);
        assertThat(captor.getValue().getSucceededCount()).isZero();
        // The refused response was billed: its tokens reach the batch totals.
        assertThat(captor.getValue().getTotalInputTokens()).isEqualTo(700L);
        assertThat(captor.getValue().getTotalOutputTokens()).isEqualTo(123L);
        assertThat(captor.getValue().getTotalCacheReadTokens()).isEqualTo(40L);
        assertThat(captor.getValue().getTotalCacheCreationTokens()).isEqualTo(300L);
        // ...and the job run is completed with their cost, not as free.
        verify(jobRunService).completeBatchRun(56L, 0, 1, 1_234_567L);
    }

    @Test
    @DisplayName("FORECAST: a stream failure after a billed response completes the failed job run with that cost")
    void forecast_streamFailureAfterBilledResponse_completesJobRunWithCost() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_fail", 2, 57L);
        when(costCalculator.calculateCostMicroDollars(
                EvaluationModel.SONNET_55, new TokenUsage(700, 123, 300, 40, 0), true))
                .thenReturn(1_234_567L);
        MessageBatchIndividualResponse refused = succeededResponseWithStopReason(
                "fc-42-2026-04-07-SUNRISE", StopReason.REFUSAL);
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(refused, refused).map(r -> {
            if (r == refused && refusedOnce[0]++ > 0) {
                throw new IllegalStateException("stream died");
            }
            return r;
        }));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        verify(jobRunService).completeBatchRun(57L, 0, 2, 1_234_567L);
    }

    @Test
    @DisplayName("FORECAST: location not found → handler not called, lookup_error logged")
    void forecast_locationNotFound_logsInlineFailure() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_fail", 1, 77L);
        when(locationRepository.findAll()).thenReturn(List.of());

        MessageBatchIndividualResponse response = succeededResponse(
                "fc-99-2026-04-07-SUNRISE", "{\"rating\":4}");
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        verify(forecastResultHandler, never()).parseBatchResponse(
                any(), any(), any(), any());
        verify(jobRunService).logBatchResult(
                eq(77L), eq("msgbatch_fail"), eq("fc-99-2026-04-07-SUNRISE"),
                eq(false),
                eq("lookup_error"), any(),
                any(), any(), any(), any(), eq(null), eq(null));
    }

    @Test
    @DisplayName("FORECAST: malformed customId → handler not called, parse_error logged")
    void forecast_malformedCustomId_logsInlineFailure() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_fail", 1, 88L);

        MessageBatchIndividualResponse response = succeededResponse(
                "garbage-prefix-123", "{\"rating\":4}");
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        verify(forecastResultHandler, never()).parseBatchResponse(
                any(), any(), any(), any());
        verify(jobRunService).logBatchResult(
                eq(88L), eq("msgbatch_fail"), eq("garbage-prefix-123"),
                eq(false),
                eq("parse_error"), any(),
                any(), any(), any(), any(), eq(null), eq(null));
    }

    @Test
    @DisplayName("FORECAST: aurora customId in forecast batch → handler not called")
    void forecast_auroraCustomId_logsInlineFailure() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_fail", 1, 33L);

        MessageBatchIndividualResponse response = succeededResponse(
                "au-MODERATE-2026-04-07", "{}");
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        verify(forecastResultHandler, never()).parseBatchResponse(
                any(), any(), any(), any());
        verify(jobRunService).logBatchResult(
                eq(33L), eq("msgbatch_fail"), eq("au-MODERATE-2026-04-07"),
                eq(false),
                eq("parse_error"),
                eq("aurora customId in forecast batch"),
                any(), any(), any(), any(), eq(null), eq(null));
    }

    @Test
    @DisplayName("FORECAST: two responses in different cache keys → mergeCacheKey called twice")
    void forecast_twoCacheKeys_mergedSeparately() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);
        LocationEntity loc1 = buildLocationWithRegion(10L, "Castlerigg", "Lake District");
        LocationEntity loc2 = buildLocationWithRegion(20L, "Bamburgh", "North East");
        when(locationRepository.findAll()).thenReturn(List.of(loc1, loc2));

        MessageBatchIndividualResponse r1 = succeededResponse(
                "fc-10-2026-04-07-SUNRISE", "{\"rating\":4}");
        MessageBatchIndividualResponse r2 = succeededResponse(
                "fc-20-2026-04-07-SUNRISE", "{\"rating\":5}");
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(r1, r2));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        BriefingEvaluationResult res1 = new BriefingEvaluationResult("Castlerigg", 4, 70, 65, "X");
        BriefingEvaluationResult res2 = new BriefingEvaluationResult("Bamburgh", 5, 80, 75, "Y");
        when(forecastResultHandler.parseBatchResponse(
                eq(loc1), any(), any(), any()))
                .thenReturn(Optional.of(new BatchSuccess(
                        "Lake District|2026-04-07|SUNRISE", res1)));
        when(forecastResultHandler.parseBatchResponse(
                eq(loc2), any(), any(), any()))
                .thenReturn(Optional.of(new BatchSuccess(
                        "North East|2026-04-07|SUNRISE", res2)));

        processor.processResults(batch);

        verify(forecastResultHandler).mergeCacheKey(
                eq("Lake District|2026-04-07|SUNRISE"), eq(List.of(res1)));
        verify(forecastResultHandler).mergeCacheKey(
                eq("North East|2026-04-07|SUNRISE"), eq(List.of(res2)));
        // P5e: locations are read once for the whole run, not once per result.
        verify(locationRepository, times(1)).findAll();
        // never(): any id must stay out of the per-result path, so the matcher is deliberately broad.
        verify(locationRepository, never()).findById(any());
    }

    @Test
    @DisplayName("FORECAST: mixed batch (repeat location, unknown location) → one findAll, "
            + "known results processed, unknown id logged as lookup_error")
    void forecast_mixedKnownAndMissingLocations_oneReadSameOutcomes() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_fail", 4, 78L);
        LocationEntity loc1 = buildLocationWithRegion(10L, "Castlerigg", "Lake District");
        LocationEntity loc2 = buildLocationWithRegion(20L, "Bamburgh", "North East");
        when(locationRepository.findAll()).thenReturn(List.of(loc1, loc2));

        MessageBatchIndividualResponse r1 = succeededResponse(
                "fc-10-2026-04-07-SUNRISE", "{\"rating\":4}");
        MessageBatchIndividualResponse r2 = succeededResponse(
                "fc-99-2026-04-07-SUNRISE", "{\"rating\":3}");
        MessageBatchIndividualResponse r3 = succeededResponse(
                "fc-20-2026-04-07-SUNRISE", "{\"rating\":5}");
        MessageBatchIndividualResponse r4 = succeededResponse(
                "fc-10-2026-04-07-SUNSET", "{\"rating\":2}");
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(r1, r2, r3, r4));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        BriefingEvaluationResult res1 = new BriefingEvaluationResult("Castlerigg", 4, 70, 65, "X");
        BriefingEvaluationResult res2 = new BriefingEvaluationResult("Bamburgh", 5, 80, 75, "Y");
        BriefingEvaluationResult res3 = new BriefingEvaluationResult("Castlerigg", 2, 40, 35, "Z");
        // Every stub is pinned to its response's exact identity (date, target type) and payload,
        // so a processor that forwarded the wrong identity or text would miss its stub.
        LocalDate day = LocalDate.of(2026, 4, 7);
        when(forecastResultHandler.parseBatchResponse(
                eq(loc1), eq(new ForecastIdentity(10L, day, TargetType.SUNRISE, null, false)),
                argThat(o -> "{\"rating\":4}".equals(o.rawText())
                        && "fc-10-2026-04-07-SUNRISE".equals(o.customId())), any()))
                .thenReturn(Optional.of(new BatchSuccess(
                        "Lake District|2026-04-07|SUNRISE", res1)));
        when(forecastResultHandler.parseBatchResponse(
                eq(loc1), eq(new ForecastIdentity(10L, day, TargetType.SUNSET, null, false)),
                argThat(o -> "{\"rating\":2}".equals(o.rawText())
                        && "fc-10-2026-04-07-SUNSET".equals(o.customId())), any()))
                .thenReturn(Optional.of(new BatchSuccess(
                        "Lake District|2026-04-07|SUNSET", res3)));
        when(forecastResultHandler.parseBatchResponse(
                eq(loc2), eq(new ForecastIdentity(20L, day, TargetType.SUNRISE, null, false)),
                argThat(o -> "{\"rating\":5}".equals(o.rawText())
                        && "fc-20-2026-04-07-SUNRISE".equals(o.customId())), any()))
                .thenReturn(Optional.of(new BatchSuccess(
                        "North East|2026-04-07|SUNRISE", res2)));

        processor.processResults(batch);

        verify(locationRepository, times(1)).findAll();
        // never(): any id must stay out of the per-result path, so the matcher is deliberately broad.
        verify(locationRepository, never()).findById(any());
        verify(forecastResultHandler).mergeCacheKey(
                eq("Lake District|2026-04-07|SUNRISE"), eq(List.of(res1)));
        verify(forecastResultHandler).mergeCacheKey(
                eq("North East|2026-04-07|SUNRISE"), eq(List.of(res2)));
        verify(forecastResultHandler).mergeCacheKey(
                eq("Lake District|2026-04-07|SUNSET"), eq(List.of(res3)));
        verify(jobRunService).logBatchResult(
                eq(78L), eq("msgbatch_fail"), eq("fc-99-2026-04-07-SUNRISE"),
                eq(false),
                eq("lookup_error"), eq("location 99 not found"),
                any(), any(), any(), any(), eq(null), eq(null));
    }

    @Test
    @DisplayName("FORECAST retry batch → recovered results routed through mergeCacheKey, "
            + "NEVER the destructive flush (so the region's other locations survive)")
    void forecast_retryBatch_mergesInsteadOfFlushing() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);
        batch.setRetry(true);
        LocationEntity loc = buildLocationWithRegion(10L, "Craster", "North East");
        when(locationRepository.findAll()).thenReturn(List.of(loc));

        MessageBatchIndividualResponse r1 = succeededResponse(
                "fc-10-2026-04-07-SUNRISE", "{\"rating\":5}");
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(r1));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        BriefingEvaluationResult res = new BriefingEvaluationResult("Craster", 5, 80, 75, "X");
        when(forecastResultHandler.parseBatchResponse(eq(loc), any(), any(), any()))
                .thenReturn(Optional.of(new BatchSuccess(
                        "North East|2026-04-07|SUNRISE", res)));

        processor.processResults(batch);

        verify(forecastResultHandler).mergeCacheKey(
                eq("North East|2026-04-07|SUNRISE"), eq(List.of(res)));
    }

    // ── AURORA ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AURORA: succeeded response → AuroraResultHandler.processBatchResponse invoked")
    void aurora_successfulResponse_routesToAuroraHandler() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.AURORA);

        MessageBatchIndividualResponse response = succeededResponse(
                "au-MODERATE-2026-04-07", "[{\"name\":\"X\",\"stars\":4}]");
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        when(auroraResultHandler.processBatchResponse(
                eq(AlertLevel.MODERATE),
                any(ClaudeBatchOutcome.class), any(ResultContext.class)))
                .thenReturn(AuroraBatchOutcome.ok(7));

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            processor.processResults(batch);
        } finally {
            detachAppender(appender);
        }

        assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.WARN)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getFormattedMessage()).contains("Aurora batch msgbatch_fail");
                    assertThat(event.getFormattedMessage()).contains("no api_call_log cost rows");
                });
        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(captor.getValue().getSucceededCount()).isEqualTo(7);
        assertThat(captor.getValue().getErroredCount()).isZero();
    }

    @Test
    @DisplayName("AURORA: handler returns failure → batch marked FAILED with reason")
    void aurora_handlerFailure_marksFailed() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.AURORA);

        MessageBatchIndividualResponse response = succeededResponse(
                "au-STRONG-2026-04-07", "[{\"name\":\"X\",\"stars\":4}]");
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        when(auroraResultHandler.processBatchResponse(
                eq(AlertLevel.STRONG),
                any(ClaudeBatchOutcome.class), any(ResultContext.class)))
                .thenReturn(AuroraBatchOutcome.failure("triage failed"));

        processor.processResults(batch);

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(captor.getValue().getErrorMessage()).contains("triage failed");
    }

    @Test
    @DisplayName("AURORA: refusal → batch FAILED, handler not invoked, failed row typed 'refusal' with usage")
    void aurora_refusal_failsBatchAndCostsResponse() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.AURORA, "msgbatch_fail", 1, 61L);
        when(costCalculator.calculateCostMicroDollars(
                EvaluationModel.SONNET_55, new TokenUsage(700, 123, 300, 40, 0), true))
                .thenReturn(1_234_567L);
        MessageBatchIndividualResponse response = succeededResponseWithStopReason(
                "au-MODERATE-2026-04-07", StopReason.REFUSAL);
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        verify(auroraResultHandler, never()).processBatchResponse(any(), any(), any());
        verify(jobRunService).logBatchResult(
                eq(61L), eq("msgbatch_fail"), eq("au-MODERATE-2026-04-07"),
                eq(false), eq(EvaluationFailure.TYPE_REFUSAL),
                eq("Claude refused to interpret the aurora conditions (stop_reason=refusal)"),
                eq(EvaluationModel.SONNET_55), eq(new TokenUsage(700, 123, 300, 40, 0)),
                isNull(), isNull(), eq(null), eq(CacheDiagnostics.EMPTY));
        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(captor.getValue().getTotalInputTokens()).isEqualTo(700L);
        assertThat(captor.getValue().getTotalOutputTokens()).isEqualTo(123L);
        // The billed refused call is not free on the job run: it completes with the batch cost.
        assertThat(captor.getValue().getEstimatedCostUsd()).isEqualByComparingTo("1.234567");
        verify(jobRunService).completeBatchRun(61L, 0, 1, 1_234_567L);
    }

    @Test
    @DisplayName("AURORA: max_tokens truncation → batch FAILED with 'truncation_error', never parsed")
    void aurora_maxTokens_failsBatchWithoutParsing() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.AURORA, "msgbatch_fail", 1, 62L);
        when(costCalculator.calculateCostMicroDollars(
                EvaluationModel.SONNET_55, new TokenUsage(700, 123, 300, 40, 0), true))
                .thenReturn(1_234_567L);
        MessageBatchIndividualResponse response = succeededResponseWithStopReason(
                "au-MODERATE-2026-04-07", StopReason.MAX_TOKENS);
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        verify(auroraResultHandler, never()).processBatchResponse(any(), any(), any());
        verify(jobRunService).logBatchResult(
                eq(62L), eq("msgbatch_fail"), eq("au-MODERATE-2026-04-07"),
                eq(false), eq("truncation_error"),
                eq("Claude's aurora response was truncated at the max_tokens limit "
                        + "(stop_reason=max_tokens)"),
                eq(EvaluationModel.SONNET_55), eq(new TokenUsage(700, 123, 300, 40, 0)),
                isNull(), isNull(), eq(null), eq(CacheDiagnostics.EMPTY));
        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.FAILED);
        verify(jobRunService).completeBatchRun(62L, 0, 1, 1_234_567L);
    }

    @Test
    @DisplayName("AURORA: handler failure after a billed response completes the job run with its cost")
    void aurora_handlerFailure_completesJobRunWithCost() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.AURORA, "msgbatch_fail", 1, 63L);
        MessageBatchIndividualResponse response = succeededResponse(
                "au-STRONG-2026-04-07", "[{\"name\":\"X\",\"stars\":4}]");
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);
        when(auroraResultHandler.processBatchResponse(eq(AlertLevel.STRONG),
                any(ClaudeBatchOutcome.class), any(ResultContext.class)))
                .thenReturn(AuroraBatchOutcome.failure("triage failed"));
        // succeededResponse bills 500 in / 200 out / 1000 cache read, as Sonnet 4.6
        when(costCalculator.calculateCostMicroDollars(
                EvaluationModel.SONNET, new TokenUsage(500, 200, 0, 1000, 0), true))
                .thenReturn(2_500L);

        processor.processResults(batch);

        verify(jobRunService).completeBatchRun(63L, 0, 1, 2_500L);
    }

    @Test
    @DisplayName("AURORA: errored individual response → marked FAILED, handler not invoked")
    void aurora_erroredResponse_marksFailedSkipsHandler() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.AURORA);

        MessageBatchIndividualResponse response = erroredResponse(
                "au-MODERATE-2026-04-07", "overloaded_error", "busy");
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(captor.getValue().getErrorMessage()).contains("overloaded_error");
        verifyNoInteractions(auroraResultHandler);
    }

    // ── Static helpers ───────────────────────────────────────────────────────

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

    @Test
    @DisplayName("describeFailedResult returns 'expired' for expired responses")
    void describeFailedResult_expired() {
        var result = mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        when(result.isExpired()).thenReturn(true);

        String[] detail = BatchResultProcessor.describeFailedResult(result);

        assertThat(detail[0]).isEqualTo("expired");
        assertThat(detail[1]).isEqualTo("request expired before processing");
    }

    @Test
    @DisplayName("describeFailedResult returns 'canceled' for canceled responses")
    void describeFailedResult_canceled() {
        var result = mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        when(result.isCanceled()).thenReturn(true);

        String[] detail = BatchResultProcessor.describeFailedResult(result);

        assertThat(detail[0]).isEqualTo("canceled");
    }

    @Test
    @DisplayName("describeFailedResult returns 'unknown' for unrecognised result")
    void describeFailedResult_unknown() {
        var result = mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        // All predicates default to false

        String[] detail = BatchResultProcessor.describeFailedResult(result);

        assertThat(detail[0]).isEqualTo("unknown");
    }

    @Test
    @DisplayName("resolveErrorType maps each ErrorObject predicate to its string type")
    void resolveErrorType_coversAllErrorVariants() {
        assertThat(BatchResultProcessor.resolveErrorType(typeOnly("isOverloadedError")))
                .isEqualTo("overloaded_error");
        assertThat(BatchResultProcessor.resolveErrorType(typeOnly("isInvalidRequestError")))
                .isEqualTo("invalid_request_error");
        assertThat(BatchResultProcessor.resolveErrorType(typeOnly("isRateLimitError")))
                .isEqualTo("rate_limit_error");
        assertThat(BatchResultProcessor.resolveErrorType(typeOnly("isAuthenticationError")))
                .isEqualTo("authentication_error");
        assertThat(BatchResultProcessor.resolveErrorType(typeOnly("isBillingError")))
                .isEqualTo("billing_error");
        assertThat(BatchResultProcessor.resolveErrorType(typeOnly("isPermissionError")))
                .isEqualTo("permission_error");
        assertThat(BatchResultProcessor.resolveErrorType(typeOnly("isNotFoundError")))
                .isEqualTo("not_found_error");
        assertThat(BatchResultProcessor.resolveErrorType(typeOnly("isTimeoutError")))
                .isEqualTo("timeout_error");
        assertThat(BatchResultProcessor.resolveErrorType(typeOnly("isApiError")))
                .isEqualTo("api_error");
    }

    /**
     * Builds an ErrorObject mock that returns true for exactly one is*Error() predicate.
     * No as*Error() / message() stubs — keeps strict-mode happy when only the type is needed.
     */
    private static com.anthropic.models.ErrorObject typeOnly(String predicateName) {
        com.anthropic.models.ErrorObject err = mock(com.anthropic.models.ErrorObject.class);
        switch (predicateName) {
            case "isOverloadedError" -> when(err.isOverloadedError()).thenReturn(true);
            case "isInvalidRequestError" -> when(err.isInvalidRequestError()).thenReturn(true);
            case "isRateLimitError" -> when(err.isRateLimitError()).thenReturn(true);
            case "isAuthenticationError" -> when(err.isAuthenticationError()).thenReturn(true);
            case "isBillingError" -> when(err.isBillingError()).thenReturn(true);
            case "isPermissionError" -> when(err.isPermissionError()).thenReturn(true);
            case "isNotFoundError" -> when(err.isNotFoundError()).thenReturn(true);
            case "isTimeoutError" -> when(err.isTimeoutError()).thenReturn(true);
            case "isApiError" -> when(err.isApiError()).thenReturn(true);
            default -> throw new IllegalArgumentException("Unknown predicate: " + predicateName);
        }
        return err;
    }

    @Test
    @DisplayName("resolveErrorType returns 'unknown' when no predicate matches")
    void resolveErrorType_unknown() {
        com.anthropic.models.ErrorObject err = mock(com.anthropic.models.ErrorObject.class);
        // All predicates default to false

        assertThat(BatchResultProcessor.resolveErrorType(err)).isEqualTo("unknown");
    }

    @Test
    @DisplayName("resolveErrorMessage extracts message from each typed error variant")
    void resolveErrorMessage_extractsMessageForAllVariants() {
        assertThat(BatchResultProcessor.resolveErrorMessage(buildOverloaded("busy")))
                .isEqualTo("busy");
        assertThat(BatchResultProcessor.resolveErrorMessage(buildInvalidRequest("bad json")))
                .isEqualTo("bad json");
        assertThat(BatchResultProcessor.resolveErrorMessage(buildRateLimit("slow down")))
                .isEqualTo("slow down");
        assertThat(BatchResultProcessor.resolveErrorMessage(buildAuthentication("401")))
                .isEqualTo("401");
        assertThat(BatchResultProcessor.resolveErrorMessage(buildBilling("declined")))
                .isEqualTo("declined");
        assertThat(BatchResultProcessor.resolveErrorMessage(buildPermission("forbidden")))
                .isEqualTo("forbidden");
        assertThat(BatchResultProcessor.resolveErrorMessage(buildNotFound("404")))
                .isEqualTo("404");
        assertThat(BatchResultProcessor.resolveErrorMessage(buildTimeout("504")))
                .isEqualTo("504");
        assertThat(BatchResultProcessor.resolveErrorMessage(buildApiError("500")))
                .isEqualTo("500");
    }

    @Test
    @DisplayName("resolveEvaluationModel: substring fallback when modelId is not an exact getModelId() match")
    void resolveEvaluationModel_substringFallback() {
        assertThat(BatchResultProcessor.resolveEvaluationModel("internal-haiku-shim"))
                .isEqualTo(EvaluationModel.HAIKU);
        assertThat(BatchResultProcessor.resolveEvaluationModel("internal-opus-shim"))
                .isEqualTo(EvaluationModel.OPUS);
        assertThat(BatchResultProcessor.resolveEvaluationModel("internal-sonnet-shim"))
                .isEqualTo(EvaluationModel.SONNET);
    }

    private static com.anthropic.models.ErrorObject buildOverloaded(String message) {
        com.anthropic.models.ErrorObject err = mock(com.anthropic.models.ErrorObject.class);
        var typed = mock(com.anthropic.models.OverloadedError.class);
        when(typed.message()).thenReturn(message);
        when(err.isOverloadedError()).thenReturn(true);
        when(err.asOverloadedError()).thenReturn(typed);
        return err;
    }

    private static com.anthropic.models.ErrorObject buildInvalidRequest(String message) {
        com.anthropic.models.ErrorObject err = mock(com.anthropic.models.ErrorObject.class);
        var typed = mock(com.anthropic.models.InvalidRequestError.class);
        when(typed.message()).thenReturn(message);
        when(err.isInvalidRequestError()).thenReturn(true);
        when(err.asInvalidRequestError()).thenReturn(typed);
        return err;
    }

    private static com.anthropic.models.ErrorObject buildRateLimit(String message) {
        com.anthropic.models.ErrorObject err = mock(com.anthropic.models.ErrorObject.class);
        var typed = mock(com.anthropic.models.RateLimitError.class);
        when(typed.message()).thenReturn(message);
        when(err.isRateLimitError()).thenReturn(true);
        when(err.asRateLimitError()).thenReturn(typed);
        return err;
    }

    private static com.anthropic.models.ErrorObject buildAuthentication(String message) {
        com.anthropic.models.ErrorObject err = mock(com.anthropic.models.ErrorObject.class);
        var typed = mock(com.anthropic.models.AuthenticationError.class);
        when(typed.message()).thenReturn(message);
        when(err.isAuthenticationError()).thenReturn(true);
        when(err.asAuthenticationError()).thenReturn(typed);
        return err;
    }

    private static com.anthropic.models.ErrorObject buildBilling(String message) {
        com.anthropic.models.ErrorObject err = mock(com.anthropic.models.ErrorObject.class);
        var typed = mock(com.anthropic.models.BillingError.class);
        when(typed.message()).thenReturn(message);
        when(err.isBillingError()).thenReturn(true);
        when(err.asBillingError()).thenReturn(typed);
        return err;
    }

    private static com.anthropic.models.ErrorObject buildPermission(String message) {
        com.anthropic.models.ErrorObject err = mock(com.anthropic.models.ErrorObject.class);
        var typed = mock(com.anthropic.models.PermissionError.class);
        when(typed.message()).thenReturn(message);
        when(err.isPermissionError()).thenReturn(true);
        when(err.asPermissionError()).thenReturn(typed);
        return err;
    }

    private static com.anthropic.models.ErrorObject buildNotFound(String message) {
        com.anthropic.models.ErrorObject err = mock(com.anthropic.models.ErrorObject.class);
        var typed = mock(com.anthropic.models.NotFoundError.class);
        when(typed.message()).thenReturn(message);
        when(err.isNotFoundError()).thenReturn(true);
        when(err.asNotFoundError()).thenReturn(typed);
        return err;
    }

    private static com.anthropic.models.ErrorObject buildTimeout(String message) {
        com.anthropic.models.ErrorObject err = mock(com.anthropic.models.ErrorObject.class);
        var typed = mock(com.anthropic.models.GatewayTimeoutError.class);
        when(typed.message()).thenReturn(message);
        when(err.isTimeoutError()).thenReturn(true);
        when(err.asTimeoutError()).thenReturn(typed);
        return err;
    }

    private static com.anthropic.models.ErrorObject buildApiError(String message) {
        com.anthropic.models.ErrorObject err = mock(com.anthropic.models.ErrorObject.class);
        var typed = mock(com.anthropic.models.ApiErrorObject.class);
        when(typed.message()).thenReturn(message);
        when(err.isApiError()).thenReturn(true);
        when(err.asApiError()).thenReturn(typed);
        return err;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ForecastBatchEntity buildBatch(BatchType type) {
        return new ForecastBatchEntity("msgbatch_fail", type, 1,
                Instant.now().plusSeconds(86400));
    }

    private ForecastBatchEntity buildBatchWithJobRun(BatchType type, String batchId,
            int requestCount, long jobRunId) {
        ForecastBatchEntity batch = new ForecastBatchEntity(batchId, type, requestCount,
                Instant.now().plusSeconds(86400));
        batch.setJobRunId(jobRunId);
        return batch;
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
        when(message.model())
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
     * Minimal succeeded-result fixture stubbing only what the max_tokens check reads
     * (result/succeeded/message, then {@code stopReason()}) — the processor rejects the
     * response before ever calling {@code content()}/{@code usage()}/{@code model()}, so
     * stubbing those here would trip Mockito's unnecessary-stubbing check.
     */
    private final int[] refusedOnce = new int[1];

    private MessageBatchIndividualResponse succeededResponseWithStopReason(String customId,
            StopReason stopReason) {
        MessageBatchIndividualResponse response = mock(MessageBatchIndividualResponse.class);
        com.anthropic.models.messages.batches.MessageBatchResult result =
                mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        com.anthropic.models.messages.batches.MessageBatchSucceededResult succeeded =
                mock(com.anthropic.models.messages.batches.MessageBatchSucceededResult.class);
        com.anthropic.models.messages.Message message =
                mock(com.anthropic.models.messages.Message.class);

        when(response.result()).thenReturn(result);
        when(response.customId()).thenReturn(customId);
        when(result.isSucceeded()).thenReturn(true);
        when(result.succeeded()).thenReturn(Optional.of(succeeded));
        when(succeeded.message()).thenReturn(message);
        when(message.stopReason()).thenReturn(Optional.of(stopReason));
        // A rejected response was still billed: it carries a model and real usage.
        com.anthropic.models.messages.Usage usage = mock(com.anthropic.models.messages.Usage.class);
        when(message.usage()).thenReturn(usage);
        when(message.model())
                .thenReturn(com.anthropic.models.messages.Model.of("claude-sonnet-5-5"));
        when(usage.inputTokens()).thenReturn(700L);
        when(usage.outputTokens()).thenReturn(123L);
        when(usage.cacheReadInputTokens()).thenReturn(Optional.of(40L));
        when(usage.cacheCreationInputTokens()).thenReturn(Optional.of(300L));

        return response;
    }

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

        if ("overloaded_error".equals(errorType)) {
            var typed = mock(com.anthropic.models.OverloadedError.class);
            when(errorObject.isOverloadedError()).thenReturn(true);
            when(errorObject.asOverloadedError()).thenReturn(typed);
            when(typed.message()).thenReturn(errorMessage);
        } else if ("rate_limit_error".equals(errorType)) {
            var typed = mock(com.anthropic.models.RateLimitError.class);
            when(errorObject.isRateLimitError()).thenReturn(true);
            when(errorObject.asRateLimitError()).thenReturn(typed);
            when(typed.message()).thenReturn(errorMessage);
        }

        return response;
    }

    @Test
    @DisplayName("FORECAST: a stream that dies mid-iteration still flushes everything it parsed")
    void forecast_streamFailsMidway_flushesAccumulatedResultsBeforeFailing() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);
        LocationEntity location = buildLocationWithRegion(42L, "Castlerigg", "Lake District");
        when(locationRepository.findAll()).thenReturn(List.of(location));

        MessageBatchIndividualResponse response = succeededResponse(
                "fc-42-2026-04-07-SUNRISE",
                "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65,\"summary\":\"X\"}");
        // One response arrives and parses; the connection then drops. Before the fix the catch
        // block returned straight to markFailed, so this already-paid-for result was discarded
        // along with the batch — and the batch went terminal, so nothing ever retried it.
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.concat(
                Stream.of(response),
                Stream.<MessageBatchIndividualResponse>generate(() -> {
                    throw new IllegalStateException("connection reset mid-stream");
                }).limit(1)));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        BriefingEvaluationResult parsed = new BriefingEvaluationResult(
                "Castlerigg", 4, 70, 65, "X");
        when(forecastResultHandler.parseBatchResponse(
                eq(location), any(ForecastIdentity.class),
                any(ClaudeBatchOutcome.class), any(ResultContext.class)))
                .thenReturn(Optional.of(new BatchSuccess(
                        "Lake District|2026-04-07|SUNRISE", parsed)));

        processor.processResults(batch);

        // The whole point: the parsed result reached the cache even though the batch failed.
        verify(forecastResultHandler).mergeCacheKey(
                eq("Lake District|2026-04-07|SUNRISE"), eq(List.of(parsed)));

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(captor.getValue().getSucceededCount())
                .as("the count reflects what was actually parsed and flushed")
                .isEqualTo(1);
        assertThat(captor.getValue().getErrorMessage())
                .as("the reason says how much survived, so the log is actionable")
                .contains("1 succeeded")
                .contains("1 cache keys flushed");
        // R7(a): the sweep must also run on the stream-failure path — the one response that
        // scored is fine, but the sweep still needs to close out whatever this batch's
        // still-PENDING rows are (the never-received rest of the batch).
        verify(evaluationAbandonmentService).abandonPendingForBatch("msgbatch_fail");
    }

    // ── Lanes, partial failures and bookkeeping branches ────────────────────────

    private static final LocalDate RESULT_DATE = LocalDate.of(2026, 4, 7);

    /** Wires the batch stream for {@code msgbatch_fail} to yield exactly these responses. */
    private void stubStream(MessageBatchIndividualResponse... responses) {
        stubBatchService();
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(responses));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);
    }

    /** A succeeded response whose message carries a model and usage but no content blocks. */
    private MessageBatchIndividualResponse succeededResponseWithoutText(String customId) {
        MessageBatchIndividualResponse response = mock(MessageBatchIndividualResponse.class);
        com.anthropic.models.messages.batches.MessageBatchResult result =
                mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        com.anthropic.models.messages.batches.MessageBatchSucceededResult succeeded =
                mock(com.anthropic.models.messages.batches.MessageBatchSucceededResult.class);
        com.anthropic.models.messages.Message message =
                mock(com.anthropic.models.messages.Message.class);
        com.anthropic.models.messages.Usage usage = mock(com.anthropic.models.messages.Usage.class);
        when(response.result()).thenReturn(result);
        when(response.customId()).thenReturn(customId);
        when(result.isSucceeded()).thenReturn(true);
        when(result.succeeded()).thenReturn(Optional.of(succeeded));
        when(succeeded.message()).thenReturn(message);
        when(message.content()).thenReturn(List.of());
        when(message.usage()).thenReturn(usage);
        when(message.model())
                .thenReturn(com.anthropic.models.messages.Model.of("claude-sonnet-4-6"));
        when(usage.inputTokens()).thenReturn(10L);
        when(usage.outputTokens()).thenReturn(5L);
        when(usage.cacheReadInputTokens()).thenReturn(Optional.empty());
        when(usage.cacheCreationInputTokens()).thenReturn(Optional.empty());
        return response;
    }

    /** A succeeded response that carries no message object at all. */
    private MessageBatchIndividualResponse succeededResponseWithoutMessage(String customId) {
        MessageBatchIndividualResponse response = mock(MessageBatchIndividualResponse.class);
        com.anthropic.models.messages.batches.MessageBatchResult result =
                mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        when(response.result()).thenReturn(result);
        when(response.customId()).thenReturn(customId);
        when(result.isSucceeded()).thenReturn(true);
        when(result.succeeded()).thenReturn(Optional.empty());
        return response;
    }

    /** A succeeded text response with explicit token counts (Sonnet 4.6). */
    private MessageBatchIndividualResponse succeededResponseWithUsage(String customId, String text,
            long inputTokens, long outputTokens) {
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
        com.anthropic.models.messages.Usage usage = mock(com.anthropic.models.messages.Usage.class);
        when(response.result()).thenReturn(result);
        when(response.customId()).thenReturn(customId);
        when(result.isSucceeded()).thenReturn(true);
        when(result.succeeded()).thenReturn(Optional.of(succeeded));
        when(succeeded.message()).thenReturn(message);
        when(message.content()).thenReturn(List.of(contentBlock));
        when(message.usage()).thenReturn(usage);
        when(message.model())
                .thenReturn(com.anthropic.models.messages.Model.of("claude-sonnet-4-6"));
        when(contentBlock.isText()).thenReturn(true);
        when(contentBlock.asText()).thenReturn(textBlock);
        when(textBlock.text()).thenReturn(text);
        when(usage.inputTokens()).thenReturn(inputTokens);
        when(usage.outputTokens()).thenReturn(outputTokens);
        when(usage.cacheReadInputTokens()).thenReturn(Optional.empty());
        when(usage.cacheCreationInputTokens()).thenReturn(Optional.empty());
        return response;
    }

    /** Matches the outcome handed to a result handler for exactly this custom id. */
    private static ClaudeBatchOutcome outcomeOf(String customId) {
        return argThat(outcome -> outcome != null && customId.equals(outcome.customId()));
    }

    private static BatchSuccess successFor(String cacheKey, String location, int rating) {
        return new BatchSuccess(cacheKey,
                new BriefingEvaluationResult(location, rating, 70, 65, "X"));
    }

    @Test
    @DisplayName("FORECAST: a bluebell response goes to the bluebell parser and is merged with the "
            + "location's actual exposure, never into the sky sink")
    void forecast_bluebellResponse_mergedWithExposure() {
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);
        LocationEntity location = buildLocationWithRegion(42L, "Castlerigg", "Lake District");
        location.setBluebellExposure(com.gregochr.goldenhour.entity.BluebellExposure.OPEN_FELL);
        when(locationRepository.findAll()).thenReturn(List.of(location));
        String customId = com.gregochr.goldenhour.service.evaluation.CustomIdFactory.forBluebell(
                42L, RESULT_DATE, TargetType.SUNRISE, true);
        stubStream(succeededResponse(customId, "{\"rating\":4}"));
        BatchSuccess hit = successFor("Lake District|2026-04-07|SUNRISE", "Castlerigg", 4);
        ForecastIdentity identity = new ForecastIdentity(
                42L, RESULT_DATE, TargetType.SUNRISE, null, true);
        ResultContext context = ResultContext.forBatch(
                null, "msgbatch_fail", null, batch.getSubmittedAt(), null);
        when(forecastResultHandler.parseBluebellBatchResponse(
                eq(location), eq(identity), outcomeOf(customId), eq(context)))
                .thenReturn(Optional.of(hit));

        processor.processResults(batch);

        ArgumentCaptor<ClaudeBatchOutcome> outcome = ArgumentCaptor.forClass(ClaudeBatchOutcome.class);
        verify(forecastResultHandler).parseBluebellBatchResponse(
                eq(location), eq(identity), outcome.capture(), eq(context));
        assertThat(outcome.getValue().customId()).isEqualTo(customId);
        assertThat(outcome.getValue().rawText()).isEqualTo("{\"rating\":4}");
        verify(forecastResultHandler).mergeBluebellCacheKey(
                "Lake District|2026-04-07|SUNRISE", List.of(hit.result()),
                java.util.Map.of("Castlerigg",
                        com.gregochr.goldenhour.entity.BluebellExposure.OPEN_FELL));
        verify(forecastResultHandler, never()).mergeCacheKey(any(), any());
        verify(forecastResultHandler, never()).parseBatchResponse(any(), any(), any(), any());
        assertThat(batch.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(batch.getSucceededCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("FORECAST: a woodland response goes to the woodland parser and is merged under "
            + "its own cache key")
    void forecast_woodlandResponse_mergedIntoWoodlandSink() {
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);
        LocationEntity location = buildLocationWithRegion(42L, "Hamsterley", "Durham");
        when(locationRepository.findAll()).thenReturn(List.of(location));
        String customId = com.gregochr.goldenhour.service.evaluation.CustomIdFactory.forWoodland(
                42L, RESULT_DATE, TargetType.SUNSET);
        stubStream(succeededResponse(customId, "{\"rating\":3}"));
        BatchSuccess hit = successFor("Durham|2026-04-07|SUNSET", "Hamsterley", 3);
        ForecastIdentity identity = new ForecastIdentity(
                42L, RESULT_DATE, TargetType.SUNSET, null, false);
        ResultContext context = ResultContext.forBatch(
                null, "msgbatch_fail", null, batch.getSubmittedAt(), null);
        when(forecastResultHandler.parseWoodlandBatchResponse(
                eq(location), eq(identity), outcomeOf(customId), eq(context)))
                .thenReturn(Optional.of(hit));

        processor.processResults(batch);

        verify(forecastResultHandler).mergeWoodlandCacheKey(
                "Durham|2026-04-07|SUNSET", List.of(hit.result()));
        verify(forecastResultHandler, never()).mergeCacheKey(any(), any());
        verify(forecastResultHandler, never()).mergeBluebellCacheKey(any(), any(), any());
        assertThat(batch.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    @DisplayName("FORECAST: a JFDI response is parsed with an unforced identity and no row id")
    void forecast_jfdiResponse_parsedWithPlainIdentity() {
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);
        LocationEntity location = buildLocationWithRegion(42L, "Castlerigg", "Lake District");
        when(locationRepository.findAll()).thenReturn(List.of(location));
        stubStream(succeededResponse("jfdi-42-2026-04-07-SUNRISE", "{\"rating\":4}"));
        BatchSuccess hit = successFor("Lake District|2026-04-07|SUNRISE", "Castlerigg", 4);
        ForecastIdentity identity = new ForecastIdentity(42L, RESULT_DATE, TargetType.SUNRISE, null);
        ResultContext context = ResultContext.forBatch(
                null, "msgbatch_fail", null, batch.getSubmittedAt(), null);
        when(forecastResultHandler.parseBatchResponse(
                eq(location), eq(identity), outcomeOf("jfdi-42-2026-04-07-SUNRISE"), eq(context)))
                .thenReturn(Optional.of(hit));

        processor.processResults(batch);

        verify(forecastResultHandler).mergeCacheKey(
                "Lake District|2026-04-07|SUNRISE", List.of(hit.result()));
    }

    @Test
    @DisplayName("FORECAST: a force-submit response is parsed for the location in its custom id")
    void forecast_forceSubmitResponse_parsedForItsLocation() {
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);
        LocationEntity location = buildLocationWithRegion(42L, "Castlerigg", "Lake District");
        when(locationRepository.findAll()).thenReturn(List.of(location));
        stubStream(succeededResponse("force-Lakes-42-2026-04-07-SUNSET", "{\"rating\":5}"));
        BatchSuccess hit = successFor("Lake District|2026-04-07|SUNSET", "Castlerigg", 5);
        ForecastIdentity identity = new ForecastIdentity(42L, RESULT_DATE, TargetType.SUNSET, null);
        ResultContext context = ResultContext.forBatch(
                null, "msgbatch_fail", null, batch.getSubmittedAt(), null);
        when(forecastResultHandler.parseBatchResponse(
                eq(location), eq(identity), outcomeOf("force-Lakes-42-2026-04-07-SUNSET"), eq(context)))
                .thenReturn(Optional.of(hit));

        processor.processResults(batch);

        verify(forecastResultHandler).mergeCacheKey(
                "Lake District|2026-04-07|SUNSET", List.of(hit.result()));
        assertThat(batch.getSucceededCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("FORECAST: every errored response is logged on its own row, and with no tokens "
            + "billed nothing is costed")
    void forecast_severalErroredResponses_eachLoggedNothingCosted() {
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_fail", 2, 99L);
        stubStream(
                erroredResponse("fc-42-2026-04-07-SUNRISE", "overloaded_error", "busy"),
                erroredResponse("fc-42-2026-04-07-SUNSET", "rate_limit_error", "slow down"));

        processor.processResults(batch);

        verify(jobRunService).logBatchResult(eq(99L), eq("msgbatch_fail"),
                eq("fc-42-2026-04-07-SUNRISE"), eq(false), eq("overloaded_error"), eq("busy"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull());
        verify(jobRunService).logBatchResult(eq(99L), eq("msgbatch_fail"),
                eq("fc-42-2026-04-07-SUNSET"), eq(false), eq("rate_limit_error"), eq("slow down"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull());
        assertThat(batch.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(batch.getErroredCount()).isEqualTo(2);
        assertThat(batch.getSucceededCount()).isZero();
        verifyNoInteractions(costCalculator);
        verify(jobRunService).completeBatchRun(99L, 0, 2, 0L);
    }

    @Test
    @DisplayName("FORECAST: a failed result whose error cannot be described is logged as 'unknown' "
            + "with the extraction problem")
    void forecast_undescribableFailure_loggedAsUnknown() {
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_fail", 1, 99L);
        MessageBatchIndividualResponse response = mock(MessageBatchIndividualResponse.class);
        com.anthropic.models.messages.batches.MessageBatchResult result =
                mock(com.anthropic.models.messages.batches.MessageBatchResult.class);
        when(response.result()).thenReturn(result);
        when(response.customId()).thenReturn("fc-42-2026-04-07-SUNRISE");
        when(result.isSucceeded()).thenReturn(false);
        when(result.isErrored()).thenThrow(new IllegalStateException("bad error shape"));
        stubStream(response);

        processor.processResults(batch);

        verify(jobRunService).logBatchResult(eq(99L), eq("msgbatch_fail"),
                eq("fc-42-2026-04-07-SUNRISE"), eq(false), eq("unknown"),
                eq("failed to extract error: bad error shape"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull());
        assertThat(batch.getErroredCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("FORECAST: a succeeded result with no message is an extraction_error")
    void forecast_succeededWithoutMessage_extractionError() {
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_fail", 1, 99L);
        stubStream(succeededResponseWithoutMessage("fc-42-2026-04-07-SUNRISE"));

        processor.processResults(batch);

        verify(jobRunService).logBatchResult(eq(99L), eq("msgbatch_fail"),
                eq("fc-42-2026-04-07-SUNRISE"), eq(false), eq("extraction_error"),
                eq("succeeded but no message"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull());
        assertThat(batch.getErroredCount()).isEqualTo(1);
        assertThat(batch.getStatus()).isEqualTo(BatchStatus.FAILED);
    }

    @Test
    @DisplayName("FORECAST: a message with no text block is an extraction_error, but its tokens "
            + "were billed and are costed")
    void forecast_messageWithoutText_extractionErrorButBilled() {
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_fail", 1, 99L);
        stubStream(succeededResponseWithoutText("fc-42-2026-04-07-SUNRISE"));
        when(costCalculator.calculateCostMicroDollars(
                EvaluationModel.SONNET, new TokenUsage(10, 5, 0, 0, 0), true)).thenReturn(321L);

        processor.processResults(batch);

        verify(jobRunService).logBatchResult(eq(99L), eq("msgbatch_fail"),
                eq("fc-42-2026-04-07-SUNRISE"), eq(false), eq("extraction_error"),
                eq("no text content blocks"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull());
        assertThat(batch.getTotalInputTokens()).isEqualTo(10L);
        assertThat(batch.getTotalOutputTokens()).isEqualTo(5L);
        verify(jobRunService).completeBatchRun(99L, 0, 1, 321L);
    }

    @Test
    @DisplayName("FORECAST: an inline failure on a batch with no job run writes no api_call_log row")
    void forecast_inlineFailureWithoutJobRun_writesNoLogRow() {
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);
        stubStream(erroredResponse("fc-42-2026-04-07-SUNRISE", "overloaded_error", "busy"));

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            processor.processResults(batch);
        } finally {
            detachAppender(appender);
        }

        verifyNoInteractions(jobRunService);
        assertThat(batch.getErroredCount()).isEqualTo(1);
        assertThat(batch.getStatus()).isEqualTo(BatchStatus.FAILED);
        // Once per batch at WARN, naming the batch and the consequence ...
        assertThat(appender.list)
                .filteredOn(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("no api_call_log cost rows"))
                .singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage())
                        .contains("Forecast batch msgbatch_fail"));
        // ... and at DEBUG per dropped result.
        assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.DEBUG
                && event.getFormattedMessage().contains("fc-42-2026-04-07-SUNRISE")
                && event.getFormattedMessage().contains("jobRunId is absent"));
    }

    @Test
    @DisplayName("FORECAST: a failing api_call_log write never aborts the batch")
    void forecast_logWriteFails_batchStillProcessed() {
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_fail", 1, 65L);
        stubStream(erroredResponse("fc-42-2026-04-07-SUNRISE", "overloaded_error", "busy"));
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(jobRunService)
                .logBatchResult(eq(65L), eq("msgbatch_fail"), eq("fc-42-2026-04-07-SUNRISE"),
                        eq(false), eq("overloaded_error"), eq("busy"),
                        isNull(), isNull(), isNull(), isNull(), isNull(), isNull());

        processor.processResults(batch);

        assertThat(batch.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(batch.getErroredCount()).isEqualTo(1);
        verify(jobRunService).completeBatchRun(65L, 0, 1, 0L);
    }

    @Test
    @DisplayName("AURORA: an empty result stream fails the batch as 'no text content'")
    void aurora_emptyStream_failsAsNoTextContent() {
        ForecastBatchEntity batch = buildBatch(BatchType.AURORA);
        stubStream();

        processor.processResults(batch);

        assertThat(batch.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(batch.getErrorMessage()).isEqualTo("Aurora batch returned no text content");
        verifyNoInteractions(auroraResultHandler);
    }

    @Test
    @DisplayName("AURORA: a succeeded result carrying no message fails the batch as 'no text content'")
    void aurora_succeededWithoutMessage_failsAsNoTextContent() {
        ForecastBatchEntity batch = buildBatch(BatchType.AURORA);
        stubStream(succeededResponseWithoutMessage("au-MODERATE-2026-04-07"));

        processor.processResults(batch);

        assertThat(batch.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(batch.getErrorMessage()).isEqualTo("Aurora batch returned no text content");
        verifyNoInteractions(auroraResultHandler);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "custom id {0} -> QUIET")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"auroraplain", "au-BOGUS-2026-04-07"})
    @DisplayName("AURORA: a custom id with no usable alert level scores under QUIET")
    void aurora_unusableAlertLevelInCustomId_defaultsToQuiet(String customId) {
        ForecastBatchEntity batch = buildBatch(BatchType.AURORA);
        stubStream(succeededResponse(customId, "[{\"name\":\"X\",\"stars\":4}]"));
        when(auroraResultHandler.processBatchResponse(
                eq(AlertLevel.QUIET), outcomeOf(customId), eq(ResultContext.forBatch(null, "msgbatch_fail", null))))
                .thenReturn(AuroraBatchOutcome.ok(2));

        processor.processResults(batch);

        verify(auroraResultHandler).processBatchResponse(
                eq(AlertLevel.QUIET), outcomeOf(customId), eq(ResultContext.forBatch(null, "msgbatch_fail", null)));
        assertThat(batch.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(batch.getSucceededCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("AURORA: a successful batch with a job run completes it with the scored count and cost")
    void aurora_success_completesJobRunWithCost() {
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.AURORA, "msgbatch_fail", 1, 71L);
        stubStream(succeededResponse("au-MODERATE-2026-04-07", "[{\"name\":\"X\",\"stars\":4}]"));
        when(auroraResultHandler.processBatchResponse(
                eq(AlertLevel.MODERATE), outcomeOf("au-MODERATE-2026-04-07"),
                eq(ResultContext.forBatch(71L, "msgbatch_fail", null))))
                .thenReturn(AuroraBatchOutcome.ok(7));
        when(costCalculator.calculateCostMicroDollars(
                EvaluationModel.SONNET, new TokenUsage(500, 200, 0, 1000, 0), true))
                .thenReturn(5_000L);

        processor.processResults(batch);

        verify(jobRunService).completeBatchRun(71L, 7, 0, 5_000L);
        assertThat(batch.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(batch.getEstimatedCostUsd()).isEqualByComparingTo("0.005");
    }

    @Test
    @DisplayName("AURORA: a response with output tokens but no input tokens is still costed")
    void aurora_outputOnlyUsage_stillCosted() {
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.AURORA, "msgbatch_fail", 1, 72L);
        stubStream(succeededResponseWithUsage("au-STRONG-2026-04-07", "[{\"name\":\"X\"}]", 0, 50));
        when(auroraResultHandler.processBatchResponse(
                eq(AlertLevel.STRONG), outcomeOf("au-STRONG-2026-04-07"),
                eq(ResultContext.forBatch(72L, "msgbatch_fail", null))))
                .thenReturn(AuroraBatchOutcome.failure("triage failed"));
        when(costCalculator.calculateCostMicroDollars(
                EvaluationModel.SONNET, new TokenUsage(0, 50, 0, 0, 0), true)).thenReturn(900L);

        processor.processResults(batch);

        assertThat(batch.getTotalOutputTokens()).isEqualTo(50L);
        verify(jobRunService).completeBatchRun(72L, 0, 1, 900L);
    }

    @Test
    @DisplayName("AURORA: a failing api_call_log write on a refusal never stops the batch being failed")
    void aurora_refusalLogWriteFails_batchStillFailed() {
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.AURORA, "msgbatch_fail", 1, 64L);
        stubStream(succeededResponseWithStopReason("au-MODERATE-2026-04-07", StopReason.REFUSAL));
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(jobRunService)
                .logBatchResult(eq(64L), eq("msgbatch_fail"), eq("au-MODERATE-2026-04-07"),
                        eq(false), eq(EvaluationFailure.TYPE_REFUSAL),
                        eq("Claude refused to interpret the aurora conditions "
                                + "(stop_reason=refusal)"),
                        eq(EvaluationModel.SONNET_55), eq(new TokenUsage(700, 123, 300, 40, 0)),
                        isNull(), isNull(), isNull(), eq(CacheDiagnostics.EMPTY));

        processor.processResults(batch);

        assertThat(batch.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(batch.getErrorMessage()).isEqualTo("Aurora batch request failed: Claude refused "
                + "to interpret the aurora conditions (stop_reason=refusal)");
        verifyNoInteractions(auroraResultHandler);
    }

    @Test
    @DisplayName("resolveErrorMessage falls back to the error's own description for an unrecognised variant")
    void resolveErrorMessage_unknownVariant_usesToString() {
        com.anthropic.models.ErrorObject err = mock(com.anthropic.models.ErrorObject.class);
        when(err.toString()).thenReturn("odd-error-variant");

        assertThat(BatchResultProcessor.resolveErrorMessage(err)).isEqualTo("odd-error-variant");
    }

    @Test
    @DisplayName("AURORA: a refused answer on a batch with no job run fails the batch without "
            + "touching job-run bookkeeping")
    void aurora_refusalWithoutJobRun_failsBatchQuietly() {
        ForecastBatchEntity batch = buildBatch(BatchType.AURORA);
        stubStream(succeededResponseWithStopReason("au-MODERATE-2026-04-07", StopReason.REFUSAL));

        processor.processResults(batch);

        assertThat(batch.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(batch.getErrorMessage()).isEqualTo("Aurora batch request failed: Claude refused "
                + "to interpret the aurora conditions (stop_reason=refusal)");
        verifyNoInteractions(jobRunService);
        verifyNoInteractions(auroraResultHandler);
    }

    @Test
    @DisplayName("AURORA: a stream that dies while a failing close() also throws still fails the "
            + "batch with the original stream error, not the close error")
    void aurora_streamAndCloseBothThrow_reportsStreamError() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.AURORA);
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.<MessageBatchIndividualResponse>generate(() -> {
            throw new IllegalStateException("stream died");
        }).limit(1));
        org.mockito.Mockito.doThrow(new IllegalStateException("close failed")).when(streamResp).close();
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        assertThat(batch.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(batch.getErrorMessage()).isEqualTo("Failed to stream results: stream died");
        verifyNoInteractions(auroraResultHandler);
    }

    // ── Prompt-cache diagnostics ride every logged message ──────────────────────

    private static final com.anthropic.models.messages.Diagnostics API_DIAGNOSTICS =
            com.anthropic.models.messages.Diagnostics.of(
                    com.anthropic.models.messages.CacheMissReason.ofMessagesChanged(1234L));

    private static final CacheDiagnostics EXPECTED_DIAGNOSTICS =
            new CacheDiagnostics(CacheDiagnostics.Status.MISS, "messages_changed", 1234L);

    private static void withApiDiagnostics(MessageBatchIndividualResponse response) {
        when(response.result().succeeded().orElseThrow().message().diagnostics())
                .thenReturn(Optional.of(API_DIAGNOSTICS));
    }

    @Test
    @DisplayName("FORECAST: the succeeded message's cache diagnostics reach the handler's outcome")
    void forecast_success_carriesCacheDiagnosticsToTheHandler() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.FORECAST);
        LocationEntity location = buildLocationWithRegion(42L, "Castlerigg", "Lake District");
        when(locationRepository.findAll()).thenReturn(List.of(location));
        MessageBatchIndividualResponse response = succeededResponse(
                "fc-42-2026-04-07-SUNRISE", "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65}");
        withApiDiagnostics(response);
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);
        when(forecastResultHandler.parseBatchResponse(
                eq(location), any(ForecastIdentity.class),
                any(ClaudeBatchOutcome.class), any(ResultContext.class)))
                .thenReturn(Optional.empty());

        processor.processResults(batch);

        ArgumentCaptor<ClaudeBatchOutcome> outcome = ArgumentCaptor.forClass(ClaudeBatchOutcome.class);
        verify(forecastResultHandler).parseBatchResponse(
                eq(location), any(ForecastIdentity.class), outcome.capture(), any(ResultContext.class));
        assertThat(outcome.getValue().cacheDiagnostics()).isEqualTo(EXPECTED_DIAGNOSTICS);
    }

    @Test
    @DisplayName("FORECAST: a refused response's cache diagnostics are logged on its failed row")
    void forecast_refusal_logsCacheDiagnostics() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.FORECAST, "msgbatch_fail", 1, 55L);
        MessageBatchIndividualResponse response = succeededResponseWithStopReason(
                "fc-42-2026-04-07-SUNRISE", StopReason.REFUSAL);
        withApiDiagnostics(response);
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        verify(jobRunService).logBatchResult(
                eq(55L), eq("msgbatch_fail"), eq("fc-42-2026-04-07-SUNRISE"),
                eq(false), eq(EvaluationFailure.TYPE_REFUSAL), any(),
                eq(EvaluationModel.SONNET_55), any(TokenUsage.class),
                isNull(), isNull(), eq(null), eq(EXPECTED_DIAGNOSTICS));
    }

    @Test
    @DisplayName("AURORA: the succeeded message's cache diagnostics reach the handler's outcome")
    void aurora_success_carriesCacheDiagnosticsToTheHandler() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch(BatchType.AURORA);
        MessageBatchIndividualResponse response = succeededResponse(
                "au-MODERATE-2026-04-07", "[{\"name\":\"X\",\"stars\":4}]");
        withApiDiagnostics(response);
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);
        when(auroraResultHandler.processBatchResponse(
                eq(AlertLevel.MODERATE), any(ClaudeBatchOutcome.class), any(ResultContext.class)))
                .thenReturn(AuroraBatchOutcome.ok(7));

        processor.processResults(batch);

        ArgumentCaptor<ClaudeBatchOutcome> outcome = ArgumentCaptor.forClass(ClaudeBatchOutcome.class);
        verify(auroraResultHandler).processBatchResponse(
                eq(AlertLevel.MODERATE), outcome.capture(), any(ResultContext.class));
        assertThat(outcome.getValue().cacheDiagnostics()).isEqualTo(EXPECTED_DIAGNOSTICS);
    }

    @Test
    @DisplayName("AURORA: a refused response's cache diagnostics are logged on its failed row")
    void aurora_refusal_logsCacheDiagnostics() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatchWithJobRun(BatchType.AURORA, "msgbatch_fail", 1, 61L);
        MessageBatchIndividualResponse response = succeededResponseWithStopReason(
                "au-MODERATE-2026-04-07", StopReason.REFUSAL);
        withApiDiagnostics(response);
        @SuppressWarnings("unchecked")
        StreamResponse<MessageBatchIndividualResponse> streamResp = mock(StreamResponse.class);
        when(streamResp.stream()).thenReturn(Stream.of(response));
        when(batchService.resultsStreaming("msgbatch_fail")).thenReturn(streamResp);

        processor.processResults(batch);

        verify(jobRunService).logBatchResult(
                eq(61L), eq("msgbatch_fail"), eq("au-MODERATE-2026-04-07"),
                eq(false), eq(EvaluationFailure.TYPE_REFUSAL), any(),
                eq(EvaluationModel.SONNET_55), any(TokenUsage.class),
                isNull(), isNull(), eq(null), eq(EXPECTED_DIAGNOSTICS));
    }

    /** Attaches a capturing appender and opens the logger to DEBUG, which the test config filters out. */
    private static ListAppender<ILoggingEvent> attachAppender() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(BatchResultProcessor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        return appender;
    }

    private static void detachAppender(ListAppender<ILoggingEvent> appender) {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(BatchResultProcessor.class);
        logger.detachAppender(appender);
        logger.setLevel(null);
    }
}
