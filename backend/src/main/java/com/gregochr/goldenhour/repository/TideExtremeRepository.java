package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Data repository for {@link TideExtremeEntity}.
 *
 * <p>Supports the weekly fetch-and-merge cycle: delete only the overlapping time window,
 * then bulk-save the fresh batch from WorldTides, preserving historical data.
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
     * Returns {@code true} if any tide extremes are stored for the given location.
     *
     * <p>Used at startup to decide whether a tide fetch is needed for a coastal location.
     *
     * @param locationId the location primary key
     * @return {@code true} if at least one tide extreme row exists for this location
     */
    boolean existsByLocationId(Long locationId);

    /**
     * Deletes all tide extremes for the given location.
     *
     * @param locationId the location primary key
     */
    @Modifying
    @Query("DELETE FROM TideExtremeEntity t WHERE t.locationId = :locationId")
    void deleteByLocationId(@Param("locationId") Long locationId);

    /**
     * Deletes tide extremes for a location within a time window.
     *
     * <p>Used before saving a fresh batch to clear only the overlapping range,
     * preserving historical data outside the fetch window.
     *
     * @param locationId the location primary key
     * @param from       window start (inclusive)
     * @param to         window end (inclusive)
     */
    @Modifying
    @Query("DELETE FROM TideExtremeEntity t WHERE t.locationId = :locationId "
            + "AND t.eventTime >= :from AND t.eventTime <= :to")
    void deleteByLocationIdAndEventTimeBetween(
            @Param("locationId") Long locationId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * Returns {@code true} if any tide extremes exist for the location within the given window.
     *
     * <p>Used by the backfill process to skip date ranges that already have data,
     * avoiding duplicate WorldTides API calls.
     *
     * @param locationId the location primary key
     * @param from       window start (inclusive)
     * @param to         window end (inclusive)
     * @return {@code true} if at least one tide extreme exists in the window
     */
    boolean existsByLocationIdAndEventTimeBetween(Long locationId, LocalDateTime from, LocalDateTime to);

    /**
     * Returns aggregate height statistics for a location and tide type.
     *
     * <p>Result is a single-element array: {@code [avgHeight, maxHeight, minHeight, count]}.
     *
     * @param locationId the location primary key
     * @param type       HIGH or LOW
     * @return Object array with avg, max, min BigDecimal heights and Long count
     */
    @Query("SELECT AVG(t.heightMetres), MAX(t.heightMetres), MIN(t.heightMetres), COUNT(t) "
            + "FROM TideExtremeEntity t WHERE t.locationId = :locationId AND t.type = :type")
    Object[] findHeightStatsByLocationIdAndType(
            @Param("locationId") Long locationId,
            @Param("type") TideExtremeType type);

    /**
     * Returns all heights for a location and tide type, ordered ascending.
     *
     * <p>Used for percentile calculations in Java (H2 lacks PERCENTILE_CONT).
     *
     * @param locationId the location primary key
     * @param type       HIGH or LOW
     * @return sorted list of heights
     */
    @Query("SELECT t.heightMetres FROM TideExtremeEntity t "
            + "WHERE t.locationId = :locationId AND t.type = :type ORDER BY t.heightMetres ASC")
    List<BigDecimal> findHeightsByLocationIdAndTypeOrderByHeightAsc(
            @Param("locationId") Long locationId,
            @Param("type") TideExtremeType type);
}
