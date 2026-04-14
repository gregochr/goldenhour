package com.gregochr.goldenhour.service.batch;

import com.anthropic.client.AnthropicClient;
import com.anthropic.services.blocking.MessageService;
import com.anthropic.services.blocking.messages.BatchService;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchRequestCounts;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.JobRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BatchPollingService}.
 */
@ExtendWith(MockitoExtension.class)
class BatchPollingServiceTest {

    @Mock
    private AnthropicClient anthropicClient;
    @Mock
    private MessageService messageService;
    @Mock
    private BatchService batchService;
    @Mock
    private ForecastBatchRepository batchRepository;
    @Mock
    private BatchResultProcessor resultProcessor;
    @Mock
    private DynamicSchedulerService dynamicSchedulerService;
    @Mock
    private JobRunService jobRunService;

    private BatchPollingService pollingService;

    @BeforeEach
    void setUp() {
        pollingService = new BatchPollingService(
                anthropicClient, batchRepository, resultProcessor,
                dynamicSchedulerService, jobRunService);
    }

    private void stubBatchService() {
        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.batches()).thenReturn(batchService);
    }

    @Test
    @DisplayName("registerJobTarget registers batch_result_polling target")
    void registerJobTarget_registersPollingTarget() {
        pollingService.registerJobTarget();

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(dynamicSchedulerService).registerJobTarget(
                eq("batch_result_polling"), runnableCaptor.capture());
        assertThat(runnableCaptor.getValue()).isNotNull();
    }

    @Test
    @DisplayName("pollPendingBatches does nothing when no pending batches")
    void pollPendingBatches_noPendingBatches_doesNothing() {
        when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                .thenReturn(List.of());

        pollingService.pollPendingBatches();

        verify(batchService, never()).retrieve(any(String.class));
        verify(resultProcessor, never()).processResults(any());
    }

    @Test
    @DisplayName("pollPendingBatches does not process IN_PROGRESS batch")
    void pollPendingBatches_inProgress_doesNotProcess() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch("msgbatch_001");
        when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                .thenReturn(List.of(batch));

        MessageBatch status = mockBatchStatus(MessageBatch.ProcessingStatus.IN_PROGRESS);
        MessageBatchRequestCounts counts = mock(MessageBatchRequestCounts.class);
        when(status.requestCounts()).thenReturn(counts);
        when(counts.processing()).thenReturn(1L);
        when(counts.succeeded()).thenReturn(0L);
        when(counts.errored()).thenReturn(0L);
        when(batchService.retrieve("msgbatch_001")).thenReturn(status);

        pollingService.pollPendingBatches();

        verify(resultProcessor, never()).processResults(any());
        verify(batchRepository).save(batch);
    }

    @Test
    @DisplayName("pollPendingBatches delegates to processor when batch is ENDED")
    void pollPendingBatches_ended_delegatesToProcessor() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch("msgbatch_002");
        when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                .thenReturn(List.of(batch));

        MessageBatch status = mockBatchStatus(MessageBatch.ProcessingStatus.ENDED);
        when(batchService.retrieve("msgbatch_002")).thenReturn(status);

        pollingService.pollPendingBatches();

        verify(resultProcessor).processResults(batch);
    }

    @Test
    @DisplayName("pollPendingBatches marks batch EXPIRED when expiresAt is in the past and ENDED")
    void pollPendingBatches_endedButExpired_marksExpired() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch("msgbatch_003");
        // Set expiresAt in the past
        batch = new ForecastBatchEntity("msgbatch_003", BatchType.FORECAST, 5,
                Instant.now().minusSeconds(3600));

        when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                .thenReturn(List.of(batch));

        MessageBatch status = mockBatchStatus(MessageBatch.ProcessingStatus.ENDED);
        when(batchService.retrieve("msgbatch_003")).thenReturn(status);

        pollingService.pollPendingBatches();

        verify(resultProcessor, never()).processResults(any());
        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.EXPIRED);
    }

    @Test
    @DisplayName("pollPendingBatches handles API error gracefully")
    void pollPendingBatches_apiError_doesNotThrow() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch("msgbatch_004");
        when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                .thenReturn(List.of(batch));
        when(batchService.retrieve("msgbatch_004"))
                .thenThrow(new RuntimeException("API unavailable"));

        pollingService.pollPendingBatches();

        verify(resultProcessor, never()).processResults(any());
        verify(batchRepository).save(batch);
    }

    @Test
    @DisplayName("IN_PROGRESS with jobRunId updates progress counts with exact succeeded/errored values")
    void pollPendingBatches_inProgressWithJobRunId_updatesProgressCounts() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch("msgbatch_005");
        batch.setJobRunId(42L);
        when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                .thenReturn(List.of(batch));

        MessageBatch status = mockBatchStatus(MessageBatch.ProcessingStatus.IN_PROGRESS);
        MessageBatchRequestCounts counts = mock(MessageBatchRequestCounts.class);
        when(status.requestCounts()).thenReturn(counts);
        when(counts.processing()).thenReturn(0L);
        when(counts.succeeded()).thenReturn(7L);
        when(counts.errored()).thenReturn(2L);
        when(batchService.retrieve("msgbatch_005")).thenReturn(status);

        pollingService.pollPendingBatches();

        verify(jobRunService).updateBatchRunProgress(42L, 7, 2);
    }

    @Test
    @DisplayName("IN_PROGRESS without jobRunId does not call jobRunService")
    void pollPendingBatches_inProgressNoJobRunId_doesNotCallJobRunService() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch("msgbatch_006");
        // jobRunId is null by default
        when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                .thenReturn(List.of(batch));

        MessageBatch status = mockBatchStatus(MessageBatch.ProcessingStatus.IN_PROGRESS);
        MessageBatchRequestCounts counts = mock(MessageBatchRequestCounts.class);
        when(status.requestCounts()).thenReturn(counts);
        when(counts.processing()).thenReturn(1L);
        when(counts.succeeded()).thenReturn(0L);
        when(counts.errored()).thenReturn(0L);
        when(batchService.retrieve("msgbatch_006")).thenReturn(status);

        pollingService.pollPendingBatches();

        verifyNoInteractions(jobRunService);
    }

    @Test
    @DisplayName("EXPIRED with jobRunId calls completeBatchRun with (0, requestCount)")
    void pollPendingBatches_expiredWithJobRunId_completesJobRunAsFailed() {
        stubBatchService();
        ForecastBatchEntity batch = new ForecastBatchEntity("msgbatch_007", BatchType.FORECAST, 5,
                Instant.now().minusSeconds(3600));
        batch.setJobRunId(99L);
        when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                .thenReturn(List.of(batch));

        MessageBatch status = mockBatchStatus(MessageBatch.ProcessingStatus.ENDED);
        when(batchService.retrieve("msgbatch_007")).thenReturn(status);

        pollingService.pollPendingBatches();

        verify(jobRunService).completeBatchRun(99L, 0, 5);
    }

    @Test
    @DisplayName("EXPIRED without jobRunId does not call jobRunService")
    void pollPendingBatches_expiredNoJobRunId_doesNotCallJobRunService() {
        stubBatchService();
        ForecastBatchEntity batch = new ForecastBatchEntity("msgbatch_008", BatchType.FORECAST, 5,
                Instant.now().minusSeconds(3600));
        // jobRunId is null by default
        when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                .thenReturn(List.of(batch));

        MessageBatch status = mockBatchStatus(MessageBatch.ProcessingStatus.ENDED);
        when(batchService.retrieve("msgbatch_008")).thenReturn(status);

        pollingService.pollPendingBatches();

        verifyNoInteractions(jobRunService);
    }

    @Test
    @DisplayName("API error does not call jobRunService (retry next poll)")
    void pollPendingBatches_apiError_doesNotCallJobRunService() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch("msgbatch_009");
        batch.setJobRunId(55L);
        when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                .thenReturn(List.of(batch));
        when(batchService.retrieve("msgbatch_009"))
                .thenThrow(new RuntimeException("Network timeout"));

        pollingService.pollPendingBatches();

        verifyNoInteractions(jobRunService);
    }

    @Test
    @DisplayName("CANCELING batch is not processed and is saved with updated lastPolledAt")
    void pollPendingBatches_canceling_doesNotProcessAndSaves() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch("msgbatch_010");
        when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                .thenReturn(List.of(batch));

        MessageBatch status = mockBatchStatus(MessageBatch.ProcessingStatus.CANCELING);
        MessageBatchRequestCounts counts = mock(MessageBatchRequestCounts.class);
        when(status.requestCounts()).thenReturn(counts);
        when(counts.processing()).thenReturn(1L);
        when(counts.succeeded()).thenReturn(0L);
        when(counts.errored()).thenReturn(0L);
        when(batchService.retrieve("msgbatch_010")).thenReturn(status);

        pollingService.pollPendingBatches();

        verify(resultProcessor, never()).processResults(batch);
        verify(batchRepository).save(batch);
        assertThat(batch.getLastPolledAt()).isNotNull();
    }

    @Test
    @DisplayName("CANCELING batch with jobRunId updates progress with exact counts")
    void pollPendingBatches_cancelingWithJobRunId_updatesProgress() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch("msgbatch_011");
        batch.setJobRunId(77L);
        when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                .thenReturn(List.of(batch));

        MessageBatch status = mockBatchStatus(MessageBatch.ProcessingStatus.CANCELING);
        MessageBatchRequestCounts counts = mock(MessageBatchRequestCounts.class);
        when(status.requestCounts()).thenReturn(counts);
        when(counts.processing()).thenReturn(3L);
        when(counts.succeeded()).thenReturn(4L);
        when(counts.errored()).thenReturn(1L);
        when(batchService.retrieve("msgbatch_011")).thenReturn(status);

        pollingService.pollPendingBatches();

        verify(jobRunService).updateBatchRunProgress(77L, 4, 1);
    }

    @Test
    @DisplayName("ENDED batch has lastPolledAt set before processResults is called")
    void pollPendingBatches_ended_setsLastPolledAt() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch("msgbatch_012");
        when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                .thenReturn(List.of(batch));

        MessageBatch status = mockBatchStatus(MessageBatch.ProcessingStatus.ENDED);
        when(batchService.retrieve("msgbatch_012")).thenReturn(status);

        pollingService.pollPendingBatches();

        assertThat(batch.getLastPolledAt()).isNotNull();
        verify(resultProcessor).processResults(batch);
    }

    @Test
    @DisplayName("IN_PROGRESS batch has lastPolledAt set before save")
    void pollPendingBatches_inProgress_setsLastPolledAt() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch("msgbatch_013");
        when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                .thenReturn(List.of(batch));

        MessageBatch status = mockBatchStatus(MessageBatch.ProcessingStatus.IN_PROGRESS);
        MessageBatchRequestCounts counts = mock(MessageBatchRequestCounts.class);
        when(status.requestCounts()).thenReturn(counts);
        when(counts.processing()).thenReturn(5L);
        when(counts.succeeded()).thenReturn(0L);
        when(counts.errored()).thenReturn(0L);
        when(batchService.retrieve("msgbatch_013")).thenReturn(status);

        pollingService.pollPendingBatches();

        assertThat(batch.getLastPolledAt()).isNotNull();
        verify(batchRepository).save(batch);
    }

    @Test
    @DisplayName("API error sets lastPolledAt on the batch before saving")
    void pollPendingBatches_apiError_setsLastPolledAtBeforeSave() {
        stubBatchService();
        ForecastBatchEntity batch = buildBatch("msgbatch_014");
        when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                .thenReturn(List.of(batch));
        when(batchService.retrieve("msgbatch_014"))
                .thenThrow(new RuntimeException("timeout"));

        pollingService.pollPendingBatches();

        assertThat(batch.getLastPolledAt()).isNotNull();
        verify(batchRepository).save(batch);
    }

    @Test
    @DisplayName("EXPIRED batch has endedAt set before save")
    void pollPendingBatches_expired_setsEndedAt() {
        stubBatchService();
        ForecastBatchEntity batch = new ForecastBatchEntity("msgbatch_015", BatchType.FORECAST, 5,
                Instant.now().minusSeconds(3600));
        when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                .thenReturn(List.of(batch));

        MessageBatch status = mockBatchStatus(MessageBatch.ProcessingStatus.ENDED);
        when(batchService.retrieve("msgbatch_015")).thenReturn(status);

        pollingService.pollPendingBatches();

        ArgumentCaptor<ForecastBatchEntity> captor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BatchStatus.EXPIRED);
        assertThat(captor.getValue().getEndedAt()).isNotNull();
    }

    @Test
    @DisplayName("Multiple pending batches are all polled in one tick")
    void pollPendingBatches_multipleBatches_allPolled() {
        stubBatchService();
        ForecastBatchEntity ended = buildBatch("msgbatch_016");
        ForecastBatchEntity inProgress = buildBatch("msgbatch_017");
        when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                .thenReturn(List.of(ended, inProgress));

        MessageBatch endedStatus = mockBatchStatus(MessageBatch.ProcessingStatus.ENDED);
        when(batchService.retrieve("msgbatch_016")).thenReturn(endedStatus);

        MessageBatch inProgressStatus = mockBatchStatus(MessageBatch.ProcessingStatus.IN_PROGRESS);
        MessageBatchRequestCounts counts = mock(MessageBatchRequestCounts.class);
        when(inProgressStatus.requestCounts()).thenReturn(counts);
        when(counts.processing()).thenReturn(2L);
        when(counts.succeeded()).thenReturn(1L);
        when(counts.errored()).thenReturn(0L);
        when(batchService.retrieve("msgbatch_017")).thenReturn(inProgressStatus);

        pollingService.pollPendingBatches();

        verify(batchService).retrieve("msgbatch_016");
        verify(batchService).retrieve("msgbatch_017");
        verify(resultProcessor).processResults(ended);
        verify(resultProcessor, never()).processResults(inProgress);
        verify(batchRepository).save(inProgress);
    }

    @Test
    @DisplayName("Registered Runnable wires to pollPendingBatches")
    void registerJobTarget_runnableInvokesPollPendingBatches() {
        when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                .thenReturn(List.of());

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        pollingService.registerJobTarget();
        verify(dynamicSchedulerService).registerJobTarget(
                eq("batch_result_polling"), runnableCaptor.capture());

        runnableCaptor.getValue().run();

        verify(batchRepository).findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED);
    }

    private ForecastBatchEntity buildBatch(String batchId) {
        return new ForecastBatchEntity(batchId, BatchType.FORECAST, 3,
                Instant.now().plusSeconds(86400));
    }

    private MessageBatch mockBatchStatus(MessageBatch.ProcessingStatus processingStatus) {
        MessageBatch batch = mock(MessageBatch.class);
        when(batch.processingStatus()).thenReturn(processingStatus);
        return batch;
    }
}
