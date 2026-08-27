package com.gregochr.goldenhour.service.batch;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.AsyncStreamResponse;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.services.blocking.MessageService;
import com.anthropic.services.blocking.messages.BatchService;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.service.JobRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the unified batch submission service that replaced {@code submitBatch} and
 * {@code submitBatchWithResult} in Pass 2.
 */
@ExtendWith(MockitoExtension.class)
class BatchSubmissionServiceTest {

    @Mock
    private AnthropicClient anthropicClient;
    @Mock
    private MessageService messageService;
    @Mock
    private BatchService batchService;
    @Mock
    private ForecastBatchRepository batchRepository;
    @Mock
    private JobRunService jobRunService;
    @Mock
    private MessageBatch messageBatch;
    @Mock
    private AsyncStreamResponse<String> streamResponse;

    private BatchSubmissionService service;

    @BeforeEach
    void setUp() {
        service = new BatchSubmissionService(anthropicClient, batchRepository, jobRunService);
    }

    private void stubBatchCreate(String batchId) {
        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.batches()).thenReturn(batchService);
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(messageBatch);
        when(messageBatch.id()).thenReturn(batchId);
        when(messageBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusHours(24));
    }

    /**
     * Opt-in stub for the tests whose submission actually reaches persistence: save() returns the
     * instance it was given, because the service keeps that instance to link the job run by id.
     * Deliberately not in stubBatchCreate — the tests that make save throw would then carry a stub
     * they never use, which strict stubbing correctly rejects.
     */
    private void stubSaveEchoes() {
        when(batchRepository.save(any(ForecastBatchEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("submit: empty request list returns null without contacting Anthropic")
    void submit_emptyRequests_returnsNull() {
        BatchSubmitResult result = service.submit(List.of(), BatchType.FORECAST,
                BatchTriggerSource.SCHEDULED, "Test");

        assertThat(result).isNull();
        verify(anthropicClient, never()).messages();
        verify(batchRepository, never()).save(any());
        verify(jobRunService, never()).startBatchRun(anyInt(), anyString());
    }

    @Test
    @DisplayName("submit: successful scheduled submission persists entity and returns result")
    void submit_scheduledSucceeds_persistsAndReturns() {
        stubBatchCreate("msgbatch_scheduled");
        stubSaveEchoes();
        JobRunEntity jobRun = new JobRunEntity();
        jobRun.setId(42L);
        when(jobRunService.startBatchRun(anyInt(), anyString())).thenReturn(jobRun);

        BatchCreateParams.Request request = BatchCreateParams.Request.builder()
                .customId("fc-1-2026-04-16-SUNRISE")
                .params(BatchCreateParams.Request.Params.builder()
                        .model("claude-sonnet-4-6")
                        .maxTokens(1024)
                        .addUserMessage("test")
                        .build())
                .build();

        BatchSubmitResult result = service.submit(List.of(request), BatchType.FORECAST,
                BatchTriggerSource.SCHEDULED, "Test scheduled");

        assertThat(result).isNotNull();
        assertThat(result.batchId()).isEqualTo("msgbatch_scheduled");
        assertThat(result.requestCount()).isEqualTo(1);
        // V101 disposition write fix: the BatchSubmitResult must carry the
        // jobRunId of the JobRunEntity that JobRunService.startBatchRun created.
        // Before the fix this field did not exist and the disposition write
        // downstream silently no-opped on a null jobRunId in EvaluationHandle.
        assertThat(result.jobRunId()).isEqualTo(42L);

        ArgumentCaptor<ForecastBatchEntity> entityCaptor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        // ONE save — the row is written the moment Anthropic returns, which is the only way the
        // poller ever discovers the batch. The job-run link that follows is a TARGETED update, not
        // a second save: by then the row is already pollable, and merging this in-memory instance
        // back would revert a batch the poller had just completed to SUBMITTED.
        verify(batchRepository).save(entityCaptor.capture());
        verify(batchRepository).linkJobRun(any(), eq(42L));
        assertThat(entityCaptor.getValue().getAnthropicBatchId())
                .isEqualTo("msgbatch_scheduled");
        assertThat(entityCaptor.getValue().getBatchType()).isEqualTo(BatchType.FORECAST);
        assertThat(entityCaptor.getValue().getJobRunId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("submit: force trigger is accepted and flagged through logs/result the same way")
    void submit_forceTrigger_producesResultSameShape() {
        stubBatchCreate("msgbatch_force");
        stubSaveEchoes();
        when(jobRunService.startBatchRun(anyInt(), anyString())).thenReturn(null);

        BatchCreateParams.Request request = BatchCreateParams.Request.builder()
                .customId("force-LakeDist-1-2026-04-16-SUNRISE")
                .params(BatchCreateParams.Request.Params.builder()
                        .model("claude-sonnet-4-6")
                        .maxTokens(512)
                        .addUserMessage("test")
                        .build())
                .build();

        BatchSubmitResult result = service.submit(List.of(request), BatchType.FORECAST,
                BatchTriggerSource.FORCE, "Force test");

        assertThat(result).isNotNull();
        assertThat(result.batchId()).isEqualTo("msgbatch_force");
    }

    @Test
    @DisplayName("submit: Anthropic exception returns null without rethrowing")
    void submit_anthropicThrows_returnsNull() {
        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.batches()).thenReturn(batchService);
        when(batchService.create(any(BatchCreateParams.class)))
                .thenThrow(new RuntimeException("Anthropic 529"));

        BatchCreateParams.Request request = BatchCreateParams.Request.builder()
                .customId("fc-1-2026-04-16-SUNRISE")
                .params(BatchCreateParams.Request.Params.builder()
                        .model("claude-sonnet-4-6")
                        .maxTokens(1024)
                        .addUserMessage("test")
                        .build())
                .build();

        BatchSubmitResult result = service.submit(List.of(request), BatchType.FORECAST,
                BatchTriggerSource.JFDI, "JFDI test");

        assertThat(result).isNull();
        verify(batchRepository, never()).save(any());
    }

    @Test
    @DisplayName("submit: null job run still persists batch entity without linking")
    void submit_nullJobRun_persistsWithoutLink() {
        stubBatchCreate("msgbatch_nojobrun");
        stubSaveEchoes();
        when(jobRunService.startBatchRun(anyInt(), anyString())).thenReturn(null);

        BatchCreateParams.Request request = BatchCreateParams.Request.builder()
                .customId("au-MODERATE-2026-04-16")
                .params(BatchCreateParams.Request.Params.builder()
                        .model("claude-sonnet-4-6")
                        .maxTokens(1024)
                        .addUserMessage("test")
                        .build())
                .build();

        BatchSubmitResult result = service.submit(List.of(request), BatchType.AURORA,
                BatchTriggerSource.SCHEDULED, "Aurora");

        assertThat(result).isNotNull();
        // Null JobRun → BatchSubmitResult.jobRunId is also null (the corner
        // case where JobRunService.startBatchRun failed; the batch still went
        // out but no run was created to anchor dispositions against).
        assertThat(result.jobRunId()).isNull();
        ArgumentCaptor<ForecastBatchEntity> entityCaptor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getJobRunId()).isNull();
        assertThat(entityCaptor.getValue().getBatchType()).isEqualTo(BatchType.AURORA);
    }

    @Test
    @DisplayName("submit: a tracking-row failure throws rather than returning the null that means "
            + "'nothing was submitted'")
    void submit_trackingRowFails_throwsOrphanedBatchRatherThanNull() {
        stubBatchCreate("msgbatch_orphan");
        when(batchRepository.save(any())).thenThrow(new RuntimeException("connection reset"));

        assertThatThrownBy(() -> service.submit(List.of(aRequest()), BatchType.FORECAST,
                BatchTriggerSource.SCHEDULED, "Test orphan"))
                .isInstanceOf(OrphanedBatchException.class)
                .hasMessageContaining("msgbatch_orphan")
                .extracting(e -> ((OrphanedBatchException) e).getAnthropicBatchId())
                .isEqualTo("msgbatch_orphan");

        // The batch is real, running and billable. Returning null would have been reported to the
        // orchestrator as an empty cycle — terminal — and it would have briefed from stale cache.
    }

    @Test
    @DisplayName("submit: the tracking row is persisted BEFORE job-run bookkeeping, so a job-run "
            + "failure cannot strand a paid batch")
    void submit_persistsTrackingRowBeforeJobRun() {
        stubBatchCreate("msgbatch_order");
        stubSaveEchoes();
        JobRunEntity jobRun = new JobRunEntity();
        jobRun.setId(7L);
        when(jobRunService.startBatchRun(anyInt(), anyString())).thenReturn(jobRun);

        service.submit(List.of(aRequest()), BatchType.FORECAST,
                BatchTriggerSource.SCHEDULED, "Test ordering");

        // Ordering is the entire fix: forecast_batch is the only row the poller discovers work
        // through, so it must exist before anything that merely feeds metrics is attempted.
        InOrder inOrder = inOrder(batchRepository, jobRunService);
        inOrder.verify(batchRepository).save(any(ForecastBatchEntity.class));
        inOrder.verify(jobRunService).startBatchRun(anyInt(), anyString());
    }

    private static BatchCreateParams.Request aRequest() {
        return BatchCreateParams.Request.builder()
                .customId("fc-1-2026-04-16-SUNRISE")
                .params(BatchCreateParams.Request.Params.builder()
                        .model("claude-sonnet-4-6")
                        .maxTokens(1024)
                        .addUserMessage("test")
                        .build())
                .build();
    }
}
