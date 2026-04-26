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
}
