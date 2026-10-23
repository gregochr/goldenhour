package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.MarineHighWaterEntity;
import com.gregochr.goldenhour.model.BigSeasCandidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for {@link MarineHighWaterEntity} - the per-high-water sea state big seas reads.
 */
public interface MarineHighWaterRepository extends JpaRepository<MarineHighWaterEntity, Long> {

    /**
     * Every sampled high water for a current, enabled big-seas structure, in one query.
     *
     * <p>Rows for a location whose structure or bearing has since been un-set, or that is disabled,
     * are not returned. Whether a row is a big sea is decided by the caller from the numbers.
     *
     * @param since earliest {@code evaluated_at} accepted (older samples are stale)
     * @param from  earliest high water (inclusive)
     * @param to    latest high water (exclusive)
     * @return the candidates, unordered
     */
    @Query("SELECT new com.gregochr.goldenhour.model.BigSeasCandidate(l.id, l.name, l.coastalStructure, "
            + "l.structureLabel, l.structureFacesDeg, l.lat, l.lon, m.hwTime, m.windowStart, m.windowEnd, "
            + "m.waveHeightMetres, m.wavePeriodSeconds, m.waveDirectionDegrees) "
            + "FROM MarineHighWaterEntity m JOIN m.location l "
            + "WHERE l.coastalStructure IS NOT NULL AND l.structureFacesDeg IS NOT NULL AND l.enabled = true "
            + "AND m.evaluatedAt >= :since AND m.hwTime >= :from AND m.hwTime < :to")
    List<BigSeasCandidate> findBigSeasCandidates(
            @Param("since") Instant since,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

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
