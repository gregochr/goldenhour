package com.gregochr.goldenhour.service.batch;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.batches.MessageBatch;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.JobRunService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Polls the Anthropic Batch API every 60 seconds for submitted batches that have completed.
 *
 * <p>On each tick:
 * <ol>
 *   <li>Loads all {@code SUBMITTED} batches from the database.</li>
 *   <li>Calls {@code retrieve(batchId)} on each to check {@code processingStatus}.</li>
 *   <li>If the status is {@code ENDED}, delegates to {@link BatchResultProcessor} to
 *       fetch results and write them to the appropriate cache.</li>
 *   <li>If the batch has expired, marks it {@code EXPIRED} and skips result fetching.</li>
 * </ol>
 *
 * <p>Registered with {@link DynamicSchedulerService} as {@code batch_result_polling}.
 */
@Service
public class BatchPollingService {

    private static final Logger LOG = LoggerFactory.getLogger(BatchPollingService.class);

    private final AnthropicClient anthropicClient;
    private final ForecastBatchRepository batchRepository;
    private final BatchResultProcessor resultProcessor;
    private final DynamicSchedulerService dynamicSchedulerService;
    private final JobRunService jobRunService;

    /**
     * Constructs the batch polling service.
     *
     * @param anthropicClient         raw SDK client for checking batch status
     * @param batchRepository         repository for reading and updating batch records
     * @param resultProcessor         processes results for completed batches
     * @param dynamicSchedulerService scheduler to register the polling job target
     * @param jobRunService           service for updating job run progress records
     */
    public BatchPollingService(AnthropicClient anthropicClient,
            ForecastBatchRepository batchRepository,
            BatchResultProcessor resultProcessor,
            DynamicSchedulerService dynamicSchedulerService,
            JobRunService jobRunService) {
        this.anthropicClient = anthropicClient;
        this.batchRepository = batchRepository;
        this.resultProcessor = resultProcessor;
        this.dynamicSchedulerService = dynamicSchedulerService;
        this.jobRunService = jobRunService;
    }

    /**
     * Registers the polling job target with the dynamic scheduler.
     */
    @PostConstruct
    public void registerJobTarget() {
        dynamicSchedulerService.registerJobTarget(
                "batch_result_polling", this::pollPendingBatches);
        LOG.info("Batch poller registered — 60s polling interval");
    }

    /**
     * Checks all pending batches and processes any that have completed.
     *
     * <p>Called by the dynamic scheduler every 60 seconds (see {@code scheduler_job_config}
     * seed row for {@code batch_result_polling}).
     */
    public void pollPendingBatches() {
        List<ForecastBatchEntity> pending =
                batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED);

        if (pending.isEmpty()) {
            LOG.debug("Batch polling: no pending batches");
            return;
        }

        LOG.info("Batch polling: checking {} pending batch(es)", pending.size());

        for (ForecastBatchEntity batch : pending) {
            pollBatch(batch);
        }
    }

    private void pollBatch(ForecastBatchEntity batch) {
        String batchId = batch.getAnthropicBatchId();
        try {
            MessageBatch status = anthropicClient.messages().batches().retrieve(batchId);
            batch.setLastPolledAt(Instant.now());

            MessageBatch.ProcessingStatus processingStatus = status.processingStatus();

            if (processingStatus.equals(MessageBatch.ProcessingStatus.IN_PROGRESS)
                    || processingStatus.equals(MessageBatch.ProcessingStatus.CANCELING)) {
                LOG.debug("Batch {}: still {} ({} processed so far)",
                        batchId, processingStatus,
                        status.requestCounts().processing()
                                + status.requestCounts().succeeded()
                                + status.requestCounts().errored());
                if (batch.getJobRunId() != null) {
                    jobRunService.updateBatchRunProgress(
                            batch.getJobRunId(),
                            (int) status.requestCounts().succeeded(),
                            (int) status.requestCounts().errored());
                }
                batchRepository.save(batch);
                return;
            }

            if (processingStatus.equals(MessageBatch.ProcessingStatus.ENDED)) {
                LOG.info("Batch {}: ENDED — fetching results (type={})",
                        batchId, batch.getBatchType());

                // Check expiry before attempting result download
                if (batch.getExpiresAt() != null && Instant.now().isAfter(batch.getExpiresAt())) {
                    LOG.warn("Batch {}: marked as ENDED but expiresAt is in the past, skipping",
                            batchId);
                    batch.setStatus(BatchStatus.EXPIRED);
                    batch.setEndedAt(Instant.now());
                    batchRepository.save(batch);
                    if (batch.getJobRunId() != null) {
                        jobRunService.completeBatchRun(batch.getJobRunId(), 0, batch.getRequestCount());
                    }
                    return;
                }

                resultProcessor.processResults(batch);
            } else {
                LOG.warn("Batch {}: unexpected processing status '{}', will retry next poll",
                        batchId, processingStatus);
                batchRepository.save(batch);
            }

        } catch (Exception e) {
            LOG.warn("Batch polling: failed to check status for {}: {}", batchId, e.getMessage());
            batch.setLastPolledAt(Instant.now());
            batchRepository.save(batch);
        }
    }
}
