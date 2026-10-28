package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.aurora.ClaudeAuroraInterpreter;
import com.gregochr.goldenhour.service.aurora.TriggerType;
import com.gregochr.goldenhour.service.batch.BatchSubmissionService;
import com.gregochr.goldenhour.service.batch.BatchSubmitResult;
import com.gregochr.goldenhour.service.batch.BatchTriggerSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EvaluationServiceImpl}.
 *
 * <p>Mocks the two Anthropic transports ({@link BatchSubmissionService},
 * {@link com.gregochr.goldenhour.service.evaluation.AnthropicApiClient}) and the per-task
 * handlers; these are tested standalone in their own test classes.
 *
 * <p>The synchronous-path tests for forecast are limited because the production sync path
 * builds a real {@link com.anthropic.models.messages.MessageCreateParams} that's awkward to
 * mock without a live Anthropic client. We cover the dispatch logic and error-classification
 * through the aurora task type, which has a simpler request shape.
 */
@ExtendWith(MockitoExtension.class)
class EvaluationServiceImplTest {

    private static final LocalDate DATE = LocalDate.of(2026, 4, 16);
    private static final AtmosphericData ATMOSPHERIC = TestAtmosphericData.defaults();
    private static final SpaceWeatherData SPACE_WEATHER = new SpaceWeatherData(
            List.of(), List.of(), null, List.of(), List.of());

    @Mock
    private BatchSubmissionService batchSubmissionService;
    @Mock
    private BatchRequestFactory batchRequestFactory;
    @Mock
    private AnthropicApiClient anthropicApiClient;
    @Mock
    private ClaudeAuroraInterpreter claudeAuroraInterpreter;
    @Mock
    private ForecastPromptStore forecastPromptStore;
    @Mock
    private JobRunService jobRunService;
    @Mock
    private ForecastResultHandler forecastResultHandler;
    @Mock
    private AuroraResultHandler auroraResultHandler;

    private EvaluationServiceImpl service;

    @BeforeEach
    void setUp() {
        when(forecastResultHandler.taskType())
                .thenReturn(EvaluationTask.Forecast.class);
        when(auroraResultHandler.taskType())
                .thenReturn(EvaluationTask.Aurora.class);
        service = new EvaluationServiceImpl(
                batchSubmissionService, batchRequestFactory, anthropicApiClient,
                claudeAuroraInterpreter, jobRunService,
                List.of(forecastResultHandler, auroraResultHandler), forecastPromptStore);
    }

    // ── submit() ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("submit: empty list → returns EvaluationHandle.empty(), no submission")
    void submit_emptyList_returnsEmptyHandle() {
        EvaluationHandle handle = service.submit(List.of(), BatchTriggerSource.SCHEDULED);

        assertThat(handle).isEqualTo(EvaluationHandle.empty());
        verifyNoInteractions(batchSubmissionService);
        verifyNoInteractions(batchRequestFactory);
    }

    @Test
    @DisplayName("submit: null list → returns EvaluationHandle.empty()")
    void submit_nullList_returnsEmptyHandle() {
        EvaluationHandle handle = service.submit(null, BatchTriggerSource.SCHEDULED);

        assertThat(handle).isEqualTo(EvaluationHandle.empty());
        verifyNoInteractions(batchSubmissionService);
    }

    @Test
    @DisplayName("submit: null trigger → throws NullPointerException")
    void submit_nullTrigger_throws() {
        EvaluationTask task = forecastTask(42L, "Castlerigg", "Lake District");
        assertThatNullPointerException().isThrownBy(() -> service.submit(List.of(task), null));
    }

    @Test
    @DisplayName("submit: forecast tasks → builds requests via factory and submits FORECAST type")
    void submit_forecastTasks_routesThroughForecastFactory() {
        EvaluationTask.Forecast t1 = forecastTask(42L, "Castlerigg", "Lake District");
        EvaluationTask.Forecast t2 = forecastTask(43L, "Bamburgh", "North East");
        BatchCreateParams.Request req1 = mock(BatchCreateParams.Request.class);
        BatchCreateParams.Request req2 = mock(BatchCreateParams.Request.class);
        when(batchRequestFactory.buildForecastRequestAndPrompt(
                eq("fc-42-2026-04-16-SUNRISE"), eq(EvaluationModel.HAIKU),
                eq(t1.data()), eq(EvaluationModel.HAIKU.getMaxTokens())))
                .thenReturn(new BatchRequestFactory.ForecastRequest(req1, "msg1", null));
        when(batchRequestFactory.buildForecastRequestAndPrompt(
                eq("fc-43-2026-04-16-SUNRISE"), eq(EvaluationModel.HAIKU),
                eq(t2.data()), eq(EvaluationModel.HAIKU.getMaxTokens())))
                .thenReturn(new BatchRequestFactory.ForecastRequest(req2, "msg2", null));
        when(batchSubmissionService.submit(
                any(), eq(BatchType.FORECAST), eq(BatchTriggerSource.SCHEDULED), anyString(),
                org.mockito.ArgumentMatchers.isNull(), eq(false)))
                .thenReturn(new BatchSubmitResult(777L, "msgbatch_x", 2));

        EvaluationHandle handle = service.submit(
                List.of(t1, t2), BatchTriggerSource.SCHEDULED);

        // Guard against the V101 seam bug: jobRunId from BatchSubmitResult must
        // propagate to the returned EvaluationHandle. Prior to the fix this
        // assertion would have failed (handle.jobRunId() was hard-coded null),
        // but the test was instead asserting only batchId and submittedCount —
        // letting the seam bug hide in production for two days.
        assertThat(handle.jobRunId()).isEqualTo(777L);
        assertThat(handle.batchId()).isEqualTo("msgbatch_x");
        assertThat(handle.submittedCount()).isEqualTo(2);
        ArgumentCaptor<List<BatchCreateParams.Request>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(batchSubmissionService).submit(
                captor.capture(), eq(BatchType.FORECAST),
                eq(BatchTriggerSource.SCHEDULED), anyString(),
                org.mockito.ArgumentMatchers.isNull(), eq(false));
        assertThat(captor.getValue()).containsExactly(req1, req2);
    }

    @Test
    @DisplayName("submit: bluebell tasks → bb- custom id + buildBluebellRequest")
    void submit_bluebellTasks_routesThroughBluebellFactory() {
        EvaluationTask.Forecast bb = bluebellTask(53L, "Bluebell Wood", "Lake District");
        BatchCreateParams.Request req = mock(BatchCreateParams.Request.class);
        when(batchRequestFactory.buildBluebellRequest(
                eq("bb-53-2026-04-16-SUNRISE"), eq(EvaluationModel.HAIKU),
                eq(bb.data()), eq(EvaluationModel.HAIKU.getMaxTokens())))
                .thenReturn(req);
        when(batchSubmissionService.submit(
                any(), eq(BatchType.FORECAST), eq(BatchTriggerSource.SCHEDULED), anyString(),
                org.mockito.ArgumentMatchers.isNull(), eq(false)))
                .thenReturn(new BatchSubmitResult(901L, "msgbatch_bb", 1));

        EvaluationHandle handle = service.submit(List.of(bb), BatchTriggerSource.SCHEDULED);

        assertThat(handle.batchId()).isEqualTo("msgbatch_bb");
        // The colour request builder is never consulted for a bluebell task.
        verify(batchRequestFactory).buildBluebellRequest(
                eq("bb-53-2026-04-16-SUNRISE"), eq(EvaluationModel.HAIKU),
                eq(bb.data()), eq(EvaluationModel.HAIKU.getMaxTokens()));
        ArgumentCaptor<List<BatchCreateParams.Request>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(batchSubmissionService).submit(
                captor.capture(), eq(BatchType.FORECAST),
                eq(BatchTriggerSource.SCHEDULED), anyString(),
                org.mockito.ArgumentMatchers.isNull(), eq(false));
        assertThat(captor.getValue()).containsExactly(req);
    }

    @Test
    @DisplayName("submit: aurora tasks → uses ClaudeAuroraInterpreter.buildUserMessage and AURORA type")
    void submit_auroraTasks_routesThroughAuroraInterpreter() {
        EvaluationTask.Aurora task = auroraTask(AlertLevel.MODERATE);
        when(claudeAuroraInterpreter.buildUserMessage(
                eq(AlertLevel.MODERATE), eq(task.viableLocations()),
                eq(task.cloudByLocation()), eq(SPACE_WEATHER),
                eq(TriggerType.REALTIME), eq(null)))
                .thenReturn("user-message");
        when(batchSubmissionService.submit(
                any(), eq(BatchType.AURORA), eq(BatchTriggerSource.SCHEDULED), anyString()))
                .thenReturn(new BatchSubmitResult(888L, "msgbatch_aurora", 1));

        EvaluationHandle handle = service.submit(
                List.of(task), BatchTriggerSource.SCHEDULED);

        // Same V101 seam guard for the aurora path.
        assertThat(handle.jobRunId()).isEqualTo(888L);
        assertThat(handle.batchId()).isEqualTo("msgbatch_aurora");
        verify(batchSubmissionService).submit(
                any(), eq(BatchType.AURORA),
                eq(BatchTriggerSource.SCHEDULED), anyString());
    }

    @Test
    @DisplayName("submit: mixed forecast + aurora tasks → IllegalArgumentException")
    void submit_mixedTaskTypes_throws() {
        EvaluationTask forecast = forecastTask(42L, "Castlerigg", "Lake District");
        EvaluationTask aurora = auroraTask(AlertLevel.MODERATE);

        assertThatIllegalArgumentException().isThrownBy(() -> service.submit(
                List.of(forecast, aurora), BatchTriggerSource.SCHEDULED))
                .withMessageContaining("Mixed-type submit");
    }

    @Test
    @DisplayName("submit: sky tasks store each message against its evalRowId; a task with no "
            + "evalRowId stores nothing")
    void submit_skyTasks_storePromptsKeyedByEvalRowId() {
        EvaluationTask.Forecast withRow = forecastTaskWithRow(42L, "Castlerigg", 901L);
        EvaluationTask.Forecast noRow = forecastTask(43L, "Bamburgh", "North East");
        BatchCreateParams.Request req = mock(BatchCreateParams.Request.class);
        when(batchRequestFactory.buildForecastRequestAndPrompt(
                eq("fc-42-2026-04-16-SUNRISE-r901"), any(), any(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new BatchRequestFactory.ForecastRequest(req, "message-for-901", "hash-901"));
        when(batchRequestFactory.buildForecastRequestAndPrompt(
                eq("fc-43-2026-04-16-SUNRISE"), any(), any(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new BatchRequestFactory.ForecastRequest(req, "message-no-row", "hash-none"));
        when(batchSubmissionService.submit(any(), any(), any(), anyString(),
                org.mockito.ArgumentMatchers.isNull(), eq(false)))
                .thenReturn(new BatchSubmitResult(1L, "msgbatch_x", 2));

        service.submit(List.of(withRow, noRow), BatchTriggerSource.SCHEDULED);

        ArgumentCaptor<Map<Long, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(forecastPromptStore).store(captor.capture());
        assertThat(captor.getValue()).containsOnly(Map.entry(901L, "message-for-901"));
    }

    @Test
    @DisplayName("V182: submit stamps each pending row with the hash of the prompt its request carried; "
            + "a task with no evalRowId stamps nothing")
    void submit_skyTasks_stampPromptHashPerEvalRow() {
        EvaluationTask.Forecast withRow = forecastTaskWithRow(42L, "Castlerigg", 901L);
        EvaluationTask.Forecast otherRow = forecastTaskWithRow(44L, "Whitby", 902L);
        EvaluationTask.Forecast noRow = forecastTask(43L, "Bamburgh", "North East");
        BatchCreateParams.Request req = mock(BatchCreateParams.Request.class);
        when(batchRequestFactory.buildForecastRequestAndPrompt(
                eq("fc-42-2026-04-16-SUNRISE-r901"), any(), any(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new BatchRequestFactory.ForecastRequest(req, "m1", "inland-hash"));
        when(batchRequestFactory.buildForecastRequestAndPrompt(
                eq("fc-44-2026-04-16-SUNRISE-r902"), any(), any(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new BatchRequestFactory.ForecastRequest(req, "m2", "coastal-hash"));
        when(batchRequestFactory.buildForecastRequestAndPrompt(
                eq("fc-43-2026-04-16-SUNRISE"), any(), any(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new BatchRequestFactory.ForecastRequest(req, "m3", "no-row-hash"));
        when(batchSubmissionService.submit(any(), any(), any(), anyString(),
                org.mockito.ArgumentMatchers.isNull(), eq(false)))
                .thenReturn(new BatchSubmitResult(1L, "msgbatch_x", 3));

        service.submit(List.of(withRow, otherRow, noRow), BatchTriggerSource.SCHEDULED);

        ArgumentCaptor<Map<String, List<Long>>> captor = ArgumentCaptor.forClass(Map.class);
        verify(forecastPromptStore).stampPromptHashes(captor.capture());
        assertThat(captor.getValue()).containsOnly(
                Map.entry("inland-hash", List.of(901L)), Map.entry("coastal-hash", List.of(902L)));
    }

    @Test
    @DisplayName("V182: the hashes are stamped BEFORE the batch is submitted, so a fast-completing or "
            + "adopted batch can never be scored against an unstamped row")
    void submit_stampsPromptHashesBeforeSubmittingTheBatch() {
        EvaluationTask.Forecast withRow = forecastTaskWithRow(42L, "Castlerigg", 901L);
        when(batchRequestFactory.buildForecastRequestAndPrompt(any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new BatchRequestFactory.ForecastRequest(
                        mock(BatchCreateParams.Request.class), "m", "inland-hash"));
        when(batchSubmissionService.submit(any(), any(), any(), anyString(),
                org.mockito.ArgumentMatchers.isNull(), eq(false)))
                .thenReturn(new BatchSubmitResult(1L, "msgbatch_x", 1));

        service.submit(List.of(withRow), BatchTriggerSource.SCHEDULED);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(forecastPromptStore, batchSubmissionService);
        order.verify(forecastPromptStore).stampPromptHashes(any());
        order.verify(batchSubmissionService).submit(any(), any(), any(), anyString(),
                org.mockito.ArgumentMatchers.isNull(), eq(false));
    }

    @Test
    @DisplayName("submit: woodland and bluebell tasks store nothing")
    void submit_woodlandAndBluebellTasks_storeNothing() {
        EvaluationTask.Forecast bb = bluebellTask(53L, "Bluebell Wood", "Lake District");
        when(batchRequestFactory.buildBluebellRequest(any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(mock(BatchCreateParams.Request.class));
        when(batchSubmissionService.submit(any(), any(), any(), anyString(),
                org.mockito.ArgumentMatchers.isNull(), eq(false)))
                .thenReturn(new BatchSubmitResult(1L, "msgbatch_x", 1));

        service.submit(List.of(bb), BatchTriggerSource.SCHEDULED);

        ArgumentCaptor<Map<Long, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(forecastPromptStore).store(captor.capture());
        assertThat(captor.getValue()).isEmpty();
        ArgumentCaptor<Map<String, List<Long>>> hashes = ArgumentCaptor.forClass(Map.class);
        verify(forecastPromptStore).stampPromptHashes(hashes.capture());
        assertThat(hashes.getValue()).as("no sky request, so no prompt fingerprint").isEmpty();
    }

    @Test
    @DisplayName("submit: a failed submission (null result) stores nothing")
    void submit_nullResult_storesNothing() {
        EvaluationTask.Forecast withRow = forecastTaskWithRow(42L, "Castlerigg", 901L);
        when(batchRequestFactory.buildForecastRequestAndPrompt(any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new BatchRequestFactory.ForecastRequest(
                        mock(BatchCreateParams.Request.class), "m", null));
        when(batchSubmissionService.submit(any(), any(), any(), anyString(),
                org.mockito.ArgumentMatchers.isNull(), eq(false)))
                .thenReturn(null);

        service.submit(List.of(withRow), BatchTriggerSource.SCHEDULED);

        verify(forecastPromptStore, org.mockito.Mockito.never()).store(any());
    }

    @Test
    @DisplayName("submit: BatchSubmissionService returns null → returns EvaluationHandle.empty()")
    void submit_submissionFailureReturnsNull_returnsEmptyHandle() {
        EvaluationTask.Forecast task = forecastTask(42L, "Castlerigg", "Lake District");
        when(batchRequestFactory.buildForecastRequestAndPrompt(any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new BatchRequestFactory.ForecastRequest(
                        mock(BatchCreateParams.Request.class), "msg", null));
        when(batchSubmissionService.submit(
                any(), any(), any(), anyString(),
                org.mockito.ArgumentMatchers.isNull(), eq(false)))
                .thenReturn(null);

        EvaluationHandle handle = service.submit(List.of(task), BatchTriggerSource.SCHEDULED);

        assertThat(handle).isEqualTo(EvaluationHandle.empty());
    }

    // ── evaluateNow() ────────────────────────────────────────────────────────

    @Test
    @DisplayName("evaluateNow: aurora task delegates to AuroraResultHandler.handleSyncResult")
    void evaluateNow_auroraTask_delegatesToAuroraHandler() {
        EvaluationTask.Aurora task = auroraTask(AlertLevel.MODERATE);
        when(claudeAuroraInterpreter.buildUserMessage(
                any(), any(), any(), any(), any(), any()))
                .thenReturn("user-message");
        // Force an Anthropic call failure so we don't have to mock the SDK Message:
        when(anthropicApiClient.createMessage(any()))
                .thenThrow(new RuntimeException("Anthropic outage"));
        when(auroraResultHandler.handleSyncResult(eq(task),
                any(ClaudeSyncOutcome.class), any(ResultContext.class)))
                .thenReturn(new EvaluationResult.Errored("RuntimeException", "Anthropic outage"));

        EvaluationResult result = service.evaluateNow(task, BatchTriggerSource.SCHEDULED);

        assertThat(result).isInstanceOf(EvaluationResult.Errored.class);
        ArgumentCaptor<ClaudeSyncOutcome> outcomeCaptor =
                ArgumentCaptor.forClass(ClaudeSyncOutcome.class);
        verify(auroraResultHandler).handleSyncResult(
                eq(task), outcomeCaptor.capture(), any(ResultContext.class));
        assertThat(outcomeCaptor.getValue().succeeded()).isFalse();
        assertThat(outcomeCaptor.getValue().errorMessage()).contains("Anthropic outage");
    }

    @Test
    @DisplayName("evaluateNow: a forecast task is refused, forecasts evaluate through the batch path only")
    void evaluateNow_forecastTask_isRefused() {
        EvaluationTask.Forecast task = forecastTask(42L, "Castlerigg", "Lake District");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.evaluateNow(task, BatchTriggerSource.SCHEDULED))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("batch path only");
        verifyNoInteractions(anthropicApiClient, jobRunService);
    }

    @Test
    @DisplayName("evaluateNow: null task → NPE")
    void evaluateNow_nullTask_throws() {
        assertThatNullPointerException().isThrownBy(() ->
                service.evaluateNow(null, BatchTriggerSource.SCHEDULED));
    }

    @Test
    @DisplayName("evaluateNow: null trigger → NPE")
    void evaluateNow_nullTrigger_throws() {
        EvaluationTask task = auroraTask(AlertLevel.MODERATE);
        assertThatNullPointerException().isThrownBy(() ->
                service.evaluateNow(task, null));
    }

    /** Runs the forecast sync path against a failing Claude call and returns the errorType it reported. */
    private String forecastErrorTypeWhen(java.util.function.Consumer<EvaluationTask.Forecast> stubClaude) {
        EvaluationTask.Forecast task = forecastTask(42L, "Castlerigg", "Lake District");
        when(batchRequestFactory.selectBuilder(eq(task.data()))).thenReturn(new PromptBuilder());
        stubClaude.accept(task);
        when(forecastResultHandler.handleSyncResult(eq(task), any(ClaudeSyncOutcome.class),
                any(ResultContext.class))).thenReturn(new EvaluationResult.Errored("x", "x"));

        service.evaluateNow(task, BatchTriggerSource.ADMIN);

        ArgumentCaptor<ClaudeSyncOutcome> outcomeCaptor = ArgumentCaptor.forClass(ClaudeSyncOutcome.class);
        ArgumentCaptor<ResultContext> contextCaptor = ArgumentCaptor.forClass(ResultContext.class);
        verify(forecastResultHandler).handleSyncResult(eq(task), outcomeCaptor.capture(), contextCaptor.capture());
        assertThat(outcomeCaptor.getValue().succeeded()).isFalse();
        assertThat(contextCaptor.getValue().triggerSource()).isEqualTo(BatchTriggerSource.ADMIN);
        return outcomeCaptor.getValue().errorType();
    }

    private static AnthropicServiceException serviceError(int status, String message) {
        AnthropicServiceException svc = mock(AnthropicServiceException.class);
        when(svc.statusCode()).thenReturn(status);
        when(svc.getMessage()).thenReturn(message);
        return svc;
    }

    @Test
    @DisplayName("evaluateNow: an aurora reply with no text block is reported as reply_unreadable, like a forecast's")
    void evaluateNow_auroraNoText_isReplyUnreadable() {
        EvaluationTask.Aurora task = auroraTask(AlertLevel.MODERATE);
        when(claudeAuroraInterpreter.buildUserMessage(any(), any(), any(), any(), any(), any()))
                .thenReturn("user-message");
        Message noText = mock(Message.class);
        when(noText.content()).thenReturn(List.of());
        when(anthropicApiClient.createMessage(any())).thenReturn(noText);
        when(auroraResultHandler.handleSyncResult(eq(task), any(ClaudeSyncOutcome.class), any(ResultContext.class)))
                .thenReturn(new EvaluationResult.Errored("x", "x"));

        service.evaluateNow(task, BatchTriggerSource.SCHEDULED);

        ArgumentCaptor<ClaudeSyncOutcome> outcomeCaptor = ArgumentCaptor.forClass(ClaudeSyncOutcome.class);
        ArgumentCaptor<ResultContext> contextCaptor = ArgumentCaptor.forClass(ResultContext.class);
        verify(auroraResultHandler).handleSyncResult(eq(task), outcomeCaptor.capture(), contextCaptor.capture());
        assertThat(outcomeCaptor.getValue().errorType()).isEqualTo("reply_unreadable");
        assertThat(contextCaptor.getValue().triggerSource()).isEqualTo(BatchTriggerSource.SCHEDULED);
    }

    @Test
    @DisplayName("evaluateNow: aurora success path drains SDK Message into ClaudeSyncOutcome.success")
    void evaluateNow_auroraSuccess_drainsResponseIntoOutcome() {
        EvaluationTask.Aurora task = auroraTask(AlertLevel.MODERATE);
        when(claudeAuroraInterpreter.buildUserMessage(
                any(), any(), any(), any(), any(), any())).thenReturn("user-message");
        Message message = mockMessageWithText("[{\"name\":\"X\",\"stars\":4}]",
                400L, 150L, 50L, 800L);
        when(anthropicApiClient.createMessage(any())).thenReturn(message);
        when(auroraResultHandler.handleSyncResult(eq(task),
                any(ClaudeSyncOutcome.class), any(ResultContext.class)))
                .thenReturn(new EvaluationResult.Scored(List.of()));

        EvaluationResult result = service.evaluateNow(task, BatchTriggerSource.ADMIN);

        assertThat(result).isInstanceOf(EvaluationResult.Scored.class);
        ArgumentCaptor<ClaudeSyncOutcome> outcomeCaptor =
                ArgumentCaptor.forClass(ClaudeSyncOutcome.class);
        verify(auroraResultHandler).handleSyncResult(
                eq(task), outcomeCaptor.capture(), any(ResultContext.class));
        ClaudeSyncOutcome outcome = outcomeCaptor.getValue();
        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.rawText()).isEqualTo("[{\"name\":\"X\",\"stars\":4}]");
        assertThat(outcome.tokenUsage().cacheCreationInputTokens()).isEqualTo(50L);
        assertThat(outcome.tokenUsage().cacheReadInputTokens()).isEqualTo(800L);
    }

    @Test
    @DisplayName("evaluateNow: AnthropicServiceException → errorType formatted as anthropic_<status>")
    void evaluateNow_anthropicServiceException_classifiedWithStatusCode() {
        EvaluationTask.Aurora task = auroraTask(AlertLevel.MODERATE);
        when(claudeAuroraInterpreter.buildUserMessage(
                any(), any(), any(), any(), any(), any())).thenReturn("user-message");
        AnthropicServiceException svc = mock(AnthropicServiceException.class);
        when(svc.statusCode()).thenReturn(529);
        when(svc.getMessage()).thenReturn("overloaded");
        when(anthropicApiClient.createMessage(any())).thenThrow(svc);
        when(auroraResultHandler.handleSyncResult(any(), any(), any()))
                .thenReturn(new EvaluationResult.Errored("anthropic_529", "overloaded"));

        service.evaluateNow(task, BatchTriggerSource.SCHEDULED);

        ArgumentCaptor<ClaudeSyncOutcome> outcomeCaptor =
                ArgumentCaptor.forClass(ClaudeSyncOutcome.class);
        verify(auroraResultHandler).handleSyncResult(
                eq(task), outcomeCaptor.capture(), any(ResultContext.class));
        assertThat(outcomeCaptor.getValue().errorType()).isEqualTo("anthropic_529");
        assertThat(outcomeCaptor.getValue().errorMessage()).isEqualTo("overloaded");
    }

    @Test
    @DisplayName("evaluateNow: jobRunService.startRun throws → swallowed, handler still invoked")
    void evaluateNow_jobRunStartFails_handlerStillInvoked() {
        EvaluationTask.Aurora task = auroraTask(AlertLevel.MODERATE);
        when(jobRunService.startRun(any(), eq(false), any()))
                .thenThrow(new RuntimeException("DB down"));
        when(claudeAuroraInterpreter.buildUserMessage(
                any(), any(), any(), any(), any(), any())).thenReturn("user-message");
        when(anthropicApiClient.createMessage(any()))
                .thenThrow(new RuntimeException("Anthropic outage"));
        when(auroraResultHandler.handleSyncResult(any(), any(), any()))
                .thenReturn(new EvaluationResult.Errored("RuntimeException", "Anthropic outage"));

        EvaluationResult result = service.evaluateNow(task, BatchTriggerSource.SCHEDULED);

        assertThat(result).isInstanceOf(EvaluationResult.Errored.class);
        verify(auroraResultHandler).handleSyncResult(eq(task), any(), any());
        // No completeRun call because jobRun is null after start failure
        verify(jobRunService, org.mockito.Mockito.never())
                .completeRun(any(), org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("evaluateNow: jobRunService.completeRun throws → swallowed, result still returned")
    void evaluateNow_jobRunCompleteFails_resultStillReturned() {
        EvaluationTask.Aurora task = auroraTask(AlertLevel.MODERATE);
        com.gregochr.goldenhour.entity.JobRunEntity jobRun =
                mock(com.gregochr.goldenhour.entity.JobRunEntity.class);
        when(jobRun.getId()).thenReturn(7L);
        when(jobRunService.startRun(any(), eq(false), any())).thenReturn(jobRun);
        org.mockito.Mockito.doThrow(new RuntimeException("DB write failure"))
                .when(jobRunService).completeRun(eq(jobRun),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt());
        when(claudeAuroraInterpreter.buildUserMessage(
                any(), any(), any(), any(), any(), any())).thenReturn("user-message");
        when(anthropicApiClient.createMessage(any()))
                .thenThrow(new RuntimeException("err"));
        EvaluationResult.Errored handlerResult =
                new EvaluationResult.Errored("RuntimeException", "err");
        when(auroraResultHandler.handleSyncResult(any(), any(), any()))
                .thenReturn(handlerResult);

        EvaluationResult result = service.evaluateNow(task, BatchTriggerSource.SCHEDULED);

        assertThat(result).isSameAs(handlerResult);
        verify(jobRunService).completeRun(eq(jobRun), eq(0), eq(1));
    }

    @Test
    @DisplayName("evaluateNow: aurora handler missing → IllegalStateException")
    void evaluateNow_auroraHandlerMissing_throws() {
        EvaluationServiceImpl noAuroraHandler = new EvaluationServiceImpl(
                batchSubmissionService, batchRequestFactory, anthropicApiClient,
                claudeAuroraInterpreter, jobRunService,
                List.of(forecastResultHandler), forecastPromptStore);
        EvaluationTask.Aurora task = auroraTask(AlertLevel.MODERATE);

        org.assertj.core.api.Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> noAuroraHandler.evaluateNow(task, BatchTriggerSource.SCHEDULED))
                .withMessageContaining("AuroraResultHandler");
    }

    @Test
    @DisplayName("evaluateNow: starts and completes job_run via JobRunService")
    void evaluateNow_tracksJobRun() {
        EvaluationTask.Aurora task = auroraTask(AlertLevel.MODERATE);
        com.gregochr.goldenhour.entity.JobRunEntity jobRun =
                mock(com.gregochr.goldenhour.entity.JobRunEntity.class);
        when(jobRun.getId()).thenReturn(101L);
        when(jobRunService.startRun(any(), eq(false), any())).thenReturn(jobRun);
        when(claudeAuroraInterpreter.buildUserMessage(
                any(), any(), any(), any(), any(), any())).thenReturn("user-message");
        when(anthropicApiClient.createMessage(any()))
                .thenThrow(new RuntimeException("err"));
        when(auroraResultHandler.handleSyncResult(any(), any(), any()))
                .thenReturn(new EvaluationResult.Errored("err", "err"));

        service.evaluateNow(task, BatchTriggerSource.SCHEDULED);

        verify(jobRunService).startRun(any(), eq(false), eq(EvaluationModel.HAIKU));
        verify(jobRunService).completeRun(eq(jobRun), eq(0), eq(1));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static <T> T mock(Class<T> type) {
        return org.mockito.Mockito.mock(type);
    }

    private static Message mockMessageWithText(String text, long input, long output,
            long cacheCreate, long cacheRead) {
        Message message = mock(Message.class);
        ContentBlock block = mock(ContentBlock.class);
        TextBlock textBlock = mock(TextBlock.class);
        Usage usage = mock(Usage.class);
        when(message.content()).thenReturn(List.of(block));
        when(block.isText()).thenReturn(true);
        when(block.asText()).thenReturn(textBlock);
        when(textBlock.text()).thenReturn(text);
        when(message.usage()).thenReturn(usage);
        when(usage.inputTokens()).thenReturn(input);
        when(usage.outputTokens()).thenReturn(output);
        when(usage.cacheCreationInputTokens()).thenReturn(Optional.of(cacheCreate));
        when(usage.cacheReadInputTokens()).thenReturn(Optional.of(cacheRead));
        return message;
    }

    /**
     * Minimal fixture stubbing only {@code stopReason()} — {@code checkStopReason} rejects
     * before this engine ever reads {@code content()}/{@code usage()}/{@code model()}, so
     * stubbing those here would trip Mockito's unnecessary-stubbing check (the same lesson as
     * {@code BatchResultProcessorTest.succeededResponseWithStopReason}).
     */
    private static Message mockMessageWithStopReason(StopReason stopReason) {
        Message message = mock(Message.class);
        when(message.stopReason()).thenReturn(Optional.of(stopReason));
        return message;
    }

    private EvaluationTask.Forecast forecastTask(long id, String name, String regionName) {
        LocationEntity loc = new LocationEntity();
        loc.setId(id);
        loc.setName(name);
        RegionEntity region = new RegionEntity();
        region.setName(regionName);
        loc.setRegion(region);
        return new EvaluationTask.Forecast(
                loc, DATE, TargetType.SUNRISE, EvaluationModel.HAIKU, ATMOSPHERIC);
    }

    private EvaluationTask.Forecast forecastTaskWithRow(long id, String name, Long evalRowId) {
        LocationEntity loc = new LocationEntity();
        loc.setId(id);
        loc.setName(name);
        return new EvaluationTask.Forecast(
                loc, DATE, TargetType.SUNRISE, EvaluationModel.HAIKU, ATMOSPHERIC,
                EvaluationTask.Forecast.PromptKind.SKY, evalRowId);
    }

    private EvaluationTask.Forecast bluebellTask(long id, String name, String regionName) {
        LocationEntity loc = new LocationEntity();
        loc.setId(id);
        loc.setName(name);
        RegionEntity region = new RegionEntity();
        region.setName(regionName);
        loc.setRegion(region);
        return new EvaluationTask.Forecast(
                loc, DATE, TargetType.SUNRISE, EvaluationModel.HAIKU, ATMOSPHERIC,
                EvaluationTask.Forecast.PromptKind.BLUEBELL);
    }

    private EvaluationTask.Aurora auroraTask(AlertLevel level) {
        LocationEntity loc = new LocationEntity();
        loc.setId(1L);
        loc.setName("X");
        return new EvaluationTask.Aurora(
                level, DATE, EvaluationModel.HAIKU,
                List.of(loc), Map.of(loc, 30),
                SPACE_WEATHER, TriggerType.REALTIME, null);
    }

    // ── Per-model request shape (literal expectations) and response handling ──

    private static final String AURORA_JSON = "[{\"name\":\"X\",\"stars\":4}]";

    private EvaluationTask.Aurora auroraTaskFor(EvaluationModel model) {
        LocationEntity loc = new LocationEntity();
        loc.setId(1L);
        loc.setName("X");
        return new EvaluationTask.Aurora(
                AlertLevel.MODERATE, DATE, model,
                List.of(loc), Map.of(loc, 30),
                SPACE_WEATHER, TriggerType.REALTIME, null);
    }

    private EvaluationTask.Forecast forecastTaskFor(EvaluationModel model) {
        LocationEntity loc = new LocationEntity();
        loc.setId(42L);
        loc.setName("Castlerigg");
        return new EvaluationTask.Forecast(
                loc, DATE, TargetType.SUNRISE, model, ATMOSPHERIC);
    }

    private static long skyCeiling(EvaluationModel model) {
        return switch (model) {
            case HAIKU, HAIKU_55 -> 512;
            case SONNET -> 1024;
            default -> 4096;
        };
    }

    /** Aurora answer budget for one location (256 + 220), plus the thinking allowance for 5.5. */
    private static long auroraCeiling(EvaluationModel model) {
        return model == EvaluationModel.SONNET_55 ? 476 + 4096 : 476;
    }

    @Test
    @DisplayName("aurora batch request: literal model id, effort only for Sonnet 5.5, additive ceiling")
    void submit_auroraRequestShape_perModel() {
        for (EvaluationModel model : List.of(EvaluationModel.HAIKU, EvaluationModel.SONNET,
                EvaluationModel.SONNET_55, EvaluationModel.HAIKU_55)) {
            org.mockito.Mockito.clearInvocations(batchSubmissionService);
            EvaluationTask.Aurora task = auroraTaskFor(model);
            when(claudeAuroraInterpreter.buildUserMessage(any(), any(), any(), any(), any(), any()))
                    .thenReturn("user-message");
            when(batchSubmissionService.submit(
                    any(), eq(BatchType.AURORA), eq(BatchTriggerSource.SCHEDULED), anyString()))
                    .thenReturn(new BatchSubmitResult(888L, "msgbatch_aurora", 1));

            service.submit(List.of(task), BatchTriggerSource.SCHEDULED);

            ArgumentCaptor<List<BatchCreateParams.Request>> captor = ArgumentCaptor.forClass(List.class);
            verify(batchSubmissionService).submit(captor.capture(), eq(BatchType.AURORA),
                    eq(BatchTriggerSource.SCHEDULED), anyString());
            ModelRequestAssertions.assertBatch(captor.getValue().get(0).params(), model,
                    auroraCeiling(model), Optional.empty());
        }
    }

    @Test
    @DisplayName("sync aurora request: literal model id, effort-only config for Sonnet 5.5, additive ceiling")
    void evaluateNow_auroraRequestShape_perModel() {
        for (EvaluationModel model : List.of(EvaluationModel.HAIKU, EvaluationModel.SONNET,
                EvaluationModel.SONNET_55, EvaluationModel.HAIKU_55)) {
            org.mockito.Mockito.clearInvocations(anthropicApiClient);
            EvaluationTask.Aurora task = auroraTaskFor(model);
            when(claudeAuroraInterpreter.buildUserMessage(any(), any(), any(), any(), any(), any()))
                    .thenReturn("user-message");
            when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                    .thenReturn(ModelRequestAssertions.message(
                            List.of(ModelRequestAssertions.text(AURORA_JSON)), StopReason.END_TURN));
            when(auroraResultHandler.handleSyncResult(eq(task), any(ClaudeSyncOutcome.class),
                    any(ResultContext.class))).thenReturn(new EvaluationResult.Errored("x", "x"));

            service.evaluateNow(task, BatchTriggerSource.SCHEDULED);

            ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
            verify(anthropicApiClient).createMessage(captor.capture());
            ModelRequestAssertions.assertMessage(captor.getValue(), model, auroraCeiling(model),
                    Optional.empty());
        }
    }

    // ── Aurora system prompt (2026-10-10) ────────────────────────────────────
    //
    // Both EvaluationServiceImpl aurora requests used to send the user message ALONE. Without the
    // interpreter's system prompt (the JSON-array contract and star guidance) Claude answered in
    // prose, parseResponse failed, and every viable location fell to the 1★ "could not be
    // assessed" fallback — a G3 storm under clear skies drew 247 one-star pins. These two tests
    // pin the prompt onto each transport; a request with no system block, or with any other text
    // in it, fails them.

    @Test
    @DisplayName("sync aurora request carries ClaudeAuroraInterpreter's system prompt")
    void evaluateNow_auroraRequest_carriesSystemPrompt() {
        EvaluationTask.Aurora task = auroraTaskFor(EvaluationModel.HAIKU);
        when(claudeAuroraInterpreter.buildUserMessage(any(), any(), any(), any(), any(), any()))
                .thenReturn("user-message");
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(ModelRequestAssertions.message(
                        List.of(ModelRequestAssertions.text(AURORA_JSON)), StopReason.END_TURN));
        when(auroraResultHandler.handleSyncResult(eq(task), any(ClaudeSyncOutcome.class),
                any(ResultContext.class))).thenReturn(new EvaluationResult.Errored("x", "x"));

        service.evaluateNow(task, BatchTriggerSource.SCHEDULED);

        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicApiClient).createMessage(captor.capture());
        assertThat(captor.getValue().system()).isPresent();
        List<com.anthropic.models.messages.TextBlockParam> blocks =
                captor.getValue().system().get().asTextBlockParams();
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).text()).isEqualTo(ClaudeAuroraInterpreter.systemPrompt());
        assertThat(blocks.get(0).text()).contains("Output ONLY valid JSON");
    }

    @Test
    @DisplayName("aurora batch request carries ClaudeAuroraInterpreter's system prompt")
    void submit_auroraRequest_carriesSystemPrompt() {
        EvaluationTask.Aurora task = auroraTaskFor(EvaluationModel.HAIKU);
        when(claudeAuroraInterpreter.buildUserMessage(any(), any(), any(), any(), any(), any()))
                .thenReturn("user-message");
        when(batchSubmissionService.submit(
                any(), eq(BatchType.AURORA), eq(BatchTriggerSource.SCHEDULED), anyString()))
                .thenReturn(new BatchSubmitResult(888L, "msgbatch_aurora", 1));

        service.submit(List.of(task), BatchTriggerSource.SCHEDULED);

        ArgumentCaptor<List<BatchCreateParams.Request>> captor = ArgumentCaptor.forClass(List.class);
        verify(batchSubmissionService).submit(captor.capture(), eq(BatchType.AURORA),
                eq(BatchTriggerSource.SCHEDULED), anyString());
        BatchCreateParams.Request.Params params = captor.getValue().get(0).params();
        assertThat(params.system()).isPresent();
        List<com.anthropic.models.messages.TextBlockParam> blocks = params.system().get().asTextBlockParams();
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).text()).isEqualTo(ClaudeAuroraInterpreter.systemPrompt());
        assertThat(blocks.get(0).text()).contains("Output ONLY valid JSON");
    }

    private ClaudeSyncOutcome syncAuroraOutcomeFor(Message reply) {
        EvaluationTask.Aurora task = auroraTaskFor(EvaluationModel.SONNET_55);
        when(claudeAuroraInterpreter.buildUserMessage(any(), any(), any(), any(), any(), any()))
                .thenReturn("user-message");
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(reply);
        when(auroraResultHandler.handleSyncResult(eq(task), any(ClaudeSyncOutcome.class),
                any(ResultContext.class))).thenReturn(new EvaluationResult.Errored("x", "x"));

        service.evaluateNow(task, BatchTriggerSource.SCHEDULED);

        ArgumentCaptor<ClaudeSyncOutcome> captor = ArgumentCaptor.forClass(ClaudeSyncOutcome.class);
        verify(auroraResultHandler).handleSyncResult(eq(task), captor.capture(), any(ResultContext.class));
        return captor.getValue();
    }

    @Test
    @DisplayName("sync aurora: a thinking block ahead of the text block still yields the text")
    void evaluateNow_auroraThinkingFirst_returnsText() {
        ClaudeSyncOutcome outcome = syncAuroraOutcomeFor(ModelRequestAssertions.message(
                List.of(ModelRequestAssertions.thinking("hmm"), ModelRequestAssertions.text(AURORA_JSON)),
                StopReason.END_TURN));

        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.rawText()).isEqualTo(AURORA_JSON);
    }

    @Test
    @DisplayName("sync aurora: a refusal is reported as refusal")
    void evaluateNow_auroraRefusal_isNamed() {
        ClaudeSyncOutcome outcome = syncAuroraOutcomeFor(
                ModelRequestAssertions.message(List.of(), StopReason.REFUSAL));

        assertThat(outcome.succeeded()).isFalse();
        assertThat(outcome.errorType()).isEqualTo("refusal");
    }

    @Test
    @DisplayName("sync aurora: a max_tokens truncation fails as reply_unreadable, never parsed")
    void evaluateNow_auroraTruncated_isReplyUnreadable() {
        ClaudeSyncOutcome outcome = syncAuroraOutcomeFor(ModelRequestAssertions.message(
                List.of(ModelRequestAssertions.text("[{\"name\":\"X\",\"sta")), StopReason.MAX_TOKENS));

        assertThat(outcome.succeeded()).isFalse();
        assertThat(outcome.errorType()).isEqualTo("reply_unreadable");
    }
}
