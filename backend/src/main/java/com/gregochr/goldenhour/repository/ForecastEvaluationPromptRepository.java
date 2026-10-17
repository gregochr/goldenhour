package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.ForecastEvaluationPromptEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Repository for {@link ForecastEvaluationPromptEntity}: inserted per batch sky request by
 * {@code ForecastPromptStore}, pruned nightly by {@code ForecastPromptCleanupJob}.
 */
@Repository
public interface ForecastEvaluationPromptRepository
        extends JpaRepository<ForecastEvaluationPromptEntity, Long> {

    /**
     * Bulk-deletes every row stored strictly before the cutoff, in one statement.
     *
     * @param cutoff the first instant to keep
     * @return the number of rows deleted
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM ForecastEvaluationPromptEntity p WHERE p.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
