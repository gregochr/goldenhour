package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.TideExtremeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Data repository for {@link TideExtremeEntity}.
 *
 * <p>Supports the weekly fetch-and-replace cycle: delete stale rows for a location,
 * then bulk-save the fresh batch from WorldTides.
 */
public interface TideExtremeRepository extends JpaRepository<TideExtremeEntity, Long> {

    /**
     * Returns all tide extremes for a location within a time window, in chronological order.
     *
     * @param locationId the location primary key
     * @param from       window start (inclusive)
     * @param to         window end (inclusive)
     * @return chronologically ordered list of tide extremes
     */
    List<TideExtremeEntity> findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
            Long locationId, LocalDateTime from, LocalDateTime to);

    /**
     * Deletes all tide extremes for the given location.
     *
     * <p>Called before saving a fresh batch to avoid duplicates on the unique constraint.
     *
     * @param locationId the location primary key
     */
    @Modifying
    @Query("DELETE FROM TideExtremeEntity t WHERE t.locationId = :locationId")
    void deleteByLocationId(@Param("locationId") Long locationId);
}
