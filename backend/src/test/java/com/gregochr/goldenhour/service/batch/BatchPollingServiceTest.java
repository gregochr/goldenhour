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

    private BatchPollingService pollingService;

    @BeforeEach
    void setUp() {
        pollingService = new BatchPollingService(
                anthropicClient, batchRepository, resultProcessor, dynamicSchedulerService);
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
