package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.ActualOutcomeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
