package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link ForecastEvaluationEntity} persistence operations.
 */
@Repository
public interface ForecastEvaluationRepository extends JpaRepository<ForecastEvaluationEntity, Long> {

    /**
     * Returns all evaluations for a location across a range of target dates, ordered by
     * date and target type. Used to build the T through T+7 forecast timeline.
     *
     * @param locationId the location primary key
     * @param from       the start of the date range (inclusive)
     * @param to         the end of the date range (inclusive)
     * @return evaluations ordered by target date ascending then target type ascending
     */
    List<ForecastEvaluationEntity> findByLocationIdAndTargetDateBetweenOrderByTargetDateAscTargetTypeAsc(
            Long locationId, LocalDate from, LocalDate to);

    /**
     * Returns evaluations for a location, date range, and evaluation model, ordered by date
     * and target type. Used by {@code GET /api/forecast} to return role-appropriate rows.
     *
     * @param locationId      the location primary key
     * @param from            the start of the date range (inclusive)
     * @param to              the end of the date range (inclusive)
     * @param evaluationModel which model's rows to return (HAIKU or SONNET)
     * @return evaluations ordered by target date ascending then target type ascending
     */
    @Query("SELECT e FROM ForecastEvaluationEntity e WHERE e.location.id = :locationId"
            + " AND e.targetDate BETWEEN :from AND :to AND e.evaluationModel = :model"
            + " ORDER BY e.targetDate ASC, e.targetType ASC")
    List<ForecastEvaluationEntity> findByLocationAndDateRangeAndModel(
            @Param("locationId") Long locationId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("model") EvaluationModel evaluationModel);

    /**
     * Returns all evaluation runs for a specific location, date, and target type, ordered
     * chronologically by when the forecast was run. Used to plot forecast convergence over time.
     *
     * @param locationId the location primary key
     * @param targetDate the date being forecast
     * @param targetType SUNRISE or SUNSET
     * @return evaluations ordered by forecast_run_at ascending
     */
    List<ForecastEvaluationEntity> findByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtAsc(
            Long locationId, LocalDate targetDate, TargetType targetType);

    /**
     * Returns the most recent evaluation for a given location, date, and target type.
     * Used to check prior ratings before deciding whether an Opus optimisation run is worthwhile.
     *
     * @param locationId the location primary key
     * @param targetDate the date being forecast
     * @param targetType SUNRISE or SUNSET
     * @return the most recent evaluation, if any
     */
    Optional<ForecastEvaluationEntity> findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
            Long locationId, LocalDate targetDate, TargetType targetType);
}
