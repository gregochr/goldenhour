package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.CachedEvaluationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for durable evaluation cache entries.
 */
@Repository
public interface CachedEvaluationRepository extends JpaRepository<CachedEvaluationEntity, Long> {

    /**
     * Find a cache entry by its composite key.
     *
     * @param cacheKey the key in "regionName|date|targetType" format
     * @return the cached entry if it exists
     */
    Optional<CachedEvaluationEntity> findByCacheKey(String cacheKey);

    /**
     * Find all entries for today and future dates (used for startup rehydration).
     *
     * @param date the earliest date to include (typically today)
     * @return all matching entries
     */
    List<CachedEvaluationEntity> findByEvaluationDateGreaterThanEqual(LocalDate date);

    /**
     * Delete entries older than the given date (housekeeping).
     *
     * @param date entries with evaluation_date before this are deleted
     */
    void deleteByEvaluationDateBefore(LocalDate date);
}
