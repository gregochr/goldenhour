package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Repository for {@link ForecastEvaluationEntity} persistence operations.
 */
@Repository
public interface ForecastEvaluationRepository extends JpaRepository<ForecastEvaluationEntity, Long> {

    /**
     * Returns all evaluations for a location across a range of target dates, ordered by
     * date and target type. Used to build the T through T+7 forecast timeline.
     *
     * @param locationName the configured location name
     * @param from         the start of the date range (inclusive)
     * @param to           the end of the date range (inclusive)
     * @return evaluations ordered by target date ascending then target type ascending
     */
    List<ForecastEvaluationEntity> findByLocationNameAndTargetDateBetweenOrderByTargetDateAscTargetTypeAsc(
            String locationName, LocalDate from, LocalDate to);

    /**
     * Returns all evaluation runs for a specific location, date, and target type, ordered
     * chronologically by when the forecast was run. Used to plot forecast convergence over time.
     *
     * @param locationName the configured location name
     * @param targetDate   the date being forecast
     * @param targetType   SUNRISE or SUNSET
     * @return evaluations ordered by forecast_run_at ascending
     */
    List<ForecastEvaluationEntity> findByLocationNameAndTargetDateAndTargetTypeOrderByForecastRunAtAsc(
            String locationName, LocalDate targetDate, TargetType targetType);
}
