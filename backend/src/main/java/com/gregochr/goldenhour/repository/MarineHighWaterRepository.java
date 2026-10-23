package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.MarineHighWaterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * Repository for {@link MarineHighWaterEntity} - the per-high-water sea state big seas reads.
 */
public interface MarineHighWaterRepository extends JpaRepository<MarineHighWaterEntity, Long> {

    /**
     * Deletes a location's rows whose high water falls in the window.
     *
     * <p>A bulk query on purpose, mirroring {@code TideExtremeRepository.deleteByLocationIdAndEventTimeBetween}:
     * a derived {@code deleteBy...} loads and removes rows one by one, and Hibernate flushes inserts
     * before deletes, so re-inserting an unchanged {@code hw_time} would hit the UNIQUE key.
     *
     * @param locationId the location primary key
     * @param from       window start (inclusive)
     * @param to         window end (inclusive)
     */
    @Modifying
    @Query("DELETE FROM MarineHighWaterEntity m WHERE m.location.id = :locationId "
            + "AND m.hwTime >= :from AND m.hwTime <= :to")
    void deleteByLocationIdAndHwTimeBetween(
            @Param("locationId") Long locationId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
}
