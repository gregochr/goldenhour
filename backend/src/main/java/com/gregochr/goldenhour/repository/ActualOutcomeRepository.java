package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.ActualOutcomeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Repository for {@link ActualOutcomeEntity} persistence operations.
 */
@Repository
public interface ActualOutcomeRepository extends JpaRepository<ActualOutcomeEntity, Long> {

    /**
     * Returns all recorded outcomes for a location within a date range. Used by the
     * {@code GET /api/outcome} endpoint to populate outcome history in the UI.
     *
     * @param locationLat the latitude of the location
     * @param locationLon the longitude of the location
     * @param from        the start of the date range (inclusive)
     * @param to          the end of the date range (inclusive)
     * @return outcomes ordered by outcome date ascending
     */
    List<ActualOutcomeEntity> findByLocationLatAndLocationLonAndOutcomeDateBetweenOrderByOutcomeDateAsc(
            BigDecimal locationLat, BigDecimal locationLon, LocalDate from, LocalDate to);

    /**
     * Returns all recorded outcomes across every location within a date range. Used by the
     * {@code GET /api/outcome/all} endpoint so the map view can load every location's outcomes
     * in a single request instead of one request per location.
     *
     * <p>The {@code JOIN FETCH} eagerly loads each outcome's location in the same query,
     * avoiding a per-row select when the {@code locationName} is serialised.
     *
     * @param from the start of the date range (inclusive)
     * @param to   the end of the date range (inclusive)
     * @return outcomes ordered by outcome date ascending
     */
    @Query("SELECT o FROM ActualOutcomeEntity o JOIN FETCH o.location"
            + " WHERE o.outcomeDate BETWEEN :from AND :to"
            + " ORDER BY o.outcomeDate ASC")
    List<ActualOutcomeEntity> findAllByOutcomeDateBetween(
            @Param("from") LocalDate from, @Param("to") LocalDate to);
}
