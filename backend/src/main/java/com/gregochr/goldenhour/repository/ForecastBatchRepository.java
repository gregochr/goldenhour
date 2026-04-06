package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

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
}
