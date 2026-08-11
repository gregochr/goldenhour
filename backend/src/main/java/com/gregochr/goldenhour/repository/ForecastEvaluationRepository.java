package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.CalibrationPair;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
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
     * Returns only the most recent evaluation run per slot for a set of locations within a
     * date range.
     *
     * <p>A "slot" is (location, target date, target type). For {@code HOURLY} wildlife rows —
     * which share a single target type across many hours of one day — the solar event time is
     * added to the key so each hour is kept distinct. For {@code SUNRISE}/{@code SUNSET} the
     * solar event time is deliberately excluded: it is derived from the location's editable
     * lat/lon, so a coordinate edit changes it, and including it would return a stale pre-edit
     * row alongside the latest one for the same slot.
     *
     * <p>Because every evaluation run inserts a new row (runs are never updated in place), the
     * table accumulates many rows per slot as forecasts are re-evaluated. The map view renders
     * only the latest run per slot, so this query performs that de-duplication at source — via a
     * single batched query instead of one query per location. The correlated {@code MAX} subquery
     * is supported by the composite index {@code idx_forecast_eval_latest_run} on
     * {@code (location_id, target_date, target_type, forecast_run_at)} (migration V128).
     *
     * <p>{@code JOIN FETCH e.location} initialises the eagerly-mapped location in the same query,
     * avoiding a secondary select per returned row.
     *
     * @param locationIds the location primary keys to query
     * @param from        the start of the date range (inclusive)
     * @param to          the end of the date range (inclusive)
     * @return the latest run per slot, ordered by location, target date, then target type
     */
    @Query("SELECT e FROM ForecastEvaluationEntity e JOIN FETCH e.location"
            + " WHERE e.location.id IN :locationIds"
            + " AND e.targetDate BETWEEN :from AND :to"
            + " AND e.forecastRunAt = ("
            + "   SELECT MAX(e2.forecastRunAt) FROM ForecastEvaluationEntity e2"
            + "   WHERE e2.location.id = e.location.id"
            + "   AND e2.targetDate = e.targetDate"
            + "   AND e2.targetType = e.targetType"
            + "   AND (e.targetType <> com.gregochr.goldenhour.entity.TargetType.HOURLY"
            + "     OR e2.solarEventTime = e.solarEventTime"
            + "     OR (e2.solarEventTime IS NULL AND e.solarEventTime IS NULL)))"
            + " ORDER BY e.location.id ASC, e.targetDate ASC, e.targetType ASC")
    List<ForecastEvaluationEntity> findLatestRunPerSlotByLocationIds(
            @Param("locationIds") Collection<Long> locationIds,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

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

    /**
     * Pairs every rated evaluation with the outcome a photographer recorded for the same slot.
     *
     * <p>Joins {@code forecast_evaluation} to {@code actual_outcome} on the natural key both share
     * — location, date and target type. One slot yields one row per {@code daysAhead}, which is
     * deliberate: it is what lets accuracy be scored per forecast horizon. Callers are expected to
     * keep only the latest {@code forecastRunAt} within each (slot, horizon) group, since a slot
     * can be re-evaluated by both the nightly batch and the intraday refresh.
     *
     * <p>Both ratings must be present; nothing else is filtered. In particular an outcome recorded
     * with {@code wentOut = false} still counts — a photographer can rate a sky they watched from
     * a window — so the flag is projected rather than applied.
     *
     * @param from start of the outcome window (inclusive)
     * @param to   end of the outcome window (inclusive)
     * @return forecast/outcome pairs, unordered
     */
    @Query("SELECT new com.gregochr.goldenhour.model.CalibrationPair("
            + " e.location.name, e.targetDate, e.targetType, e.daysAhead, e.forecastRunAt,"
            + " e.evaluationModel, e.rating, e.fierySkyPotential, e.goldenHourPotential,"
            + " o.actualRating, o.fierySkyActual, o.goldenHourActual, o.wentOut)"
            + " FROM ForecastEvaluationEntity e, ActualOutcomeEntity o"
            + " WHERE e.location.id = o.location.id"
            + " AND e.targetDate = o.outcomeDate"
            + " AND e.targetType = o.targetType"
            + " AND e.rating IS NOT NULL"
            + " AND o.actualRating IS NOT NULL"
            + " AND o.outcomeDate BETWEEN :from AND :to")
    List<CalibrationPair> findCalibrationPairs(
            @Param("from") LocalDate from, @Param("to") LocalDate to);
}
