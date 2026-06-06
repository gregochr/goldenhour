package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.CachedEvaluationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
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
     * Returns the newest {@code evaluated_at} across all cache rows, or {@code null} when
     * the table is empty. Used by the cache-health heartbeat to detect a backwards jump
     * (the 2026-06-06 signature: rows written then disappearing).
     *
     * @return the maximum {@code evaluated_at}, or {@code null} if there are no rows
     */
    @Query("SELECT MAX(c.evaluatedAt) FROM CachedEvaluationEntity c")
    Instant findMaxEvaluatedAt();

    /**
     * Counts distinct cache keys. Under the unique constraint on {@code cache_key} this
     * equals the row count; logging it alongside the row count in the heartbeat doubles as
     * a cheap integrity cross-check (a divergence would indicate duplicate-key corruption).
     *
     * @return the number of distinct cache keys
     */
    @Query("SELECT COUNT(DISTINCT c.cacheKey) FROM CachedEvaluationEntity c")
    long countDistinctCacheKeys();
}
