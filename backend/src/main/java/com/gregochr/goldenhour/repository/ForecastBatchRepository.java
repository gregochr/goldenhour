package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link ForecastBatchEntity}.
 */
public interface ForecastBatchRepository extends JpaRepository<ForecastBatchEntity, Long> {

    /**
     * Returns all batches in a given status, ordered by submission time descending.
     *
     * @param status the batch status to filter by
     * @return matching batches, newest first
     */
    List<ForecastBatchEntity> findByStatusOrderBySubmittedAtDesc(BatchStatus status);

    /**
     * Returns the most recent N batches regardless of status, newest first.
     *
     * @return recent batch records
     */
    List<ForecastBatchEntity> findTop20ByOrderBySubmittedAtDesc();

    /**
     * Finds the batch linked to a given job run.
     *
     * @param jobRunId the job run ID
     * @return the batch entity if found
     */
    Optional<ForecastBatchEntity> findByJobRunId(Long jobRunId);

    /**
     * Finds the batch by its Anthropic-assigned batch ID.
     *
     * @param anthropicBatchId the {@code msgbatch_*} ID returned by the Batch API
     * @return the batch entity if found
     */
    Optional<ForecastBatchEntity> findByAnthropicBatchId(String anthropicBatchId);

    /**
     * Returns all batches tagged with the given pipeline run id.
     *
     * <p>Used by the nightly pipeline orchestrator to answer "is the batch
     * set for this cycle complete?" — the cycle is complete when no batch
     * in this list is still {@code SUBMITTED}.
     *
     * @param pipelineRunId the orchestrated cycle id
     * @return matching batches (any status)
     */
    List<ForecastBatchEntity> findByPipelineRunId(Long pipelineRunId);

    /**
     * Returns the precursor (non-retry) batches for a cycle.
     *
     * <p>The RETRY_FAILED phase selects its retry set from these batches' failed
     * requests only — never from a retry batch's own failures — which makes the
     * single-retry guarantee structural.
     *
     * @param pipelineRunId the orchestrated cycle id
     * @return matching {@code is_retry = false} batches (any status)
     */
    List<ForecastBatchEntity> findByPipelineRunIdAndRetryFalse(Long pipelineRunId);

    /**
     * Returns the retry batches already submitted for a cycle.
     *
     * <p>Used as the idempotency guard: a non-empty result means the RETRY_FAILED
     * phase has already submitted a retry for this cycle, so submission is skipped
     * (the existing retry batch is waited on instead). Bounds each cycle to one
     * retry.
     *
     * @param pipelineRunId the orchestrated cycle id
     * @return matching {@code is_retry = true} batches (any status)
     */
    List<ForecastBatchEntity> findByPipelineRunIdAndRetryTrue(Long pipelineRunId);
}
