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

    /**
     * Counts coastal locations with tide alignment for a given date, grouped by target type.
     *
     * <p>Used by king/spring tide hot topic strategies to populate pill subtitles
     * with sunrise/sunset alignment counts.
     *
     * @param date the target date to query
     * @return rows of [TargetType, count] for coastal locations where tideAligned is true
     */
    @Query("SELECT e.targetType, COUNT(DISTINCT e.location.id)"
            + " FROM ForecastEvaluationEntity e"
            + " JOIN e.location l JOIN l.tideType tt"
            + " WHERE e.targetDate = :date AND e.tideAligned = true"
            + " GROUP BY e.targetType")
    List<Object[]> countTideAlignedByTargetType(@Param("date") LocalDate date);

    /**
     * Returns distinct (target date, region name) pairs for evaluations in the window whose
     * cloud inversion potential matches the given classification. The {@code inversion_potential}
     * column is only populated for elevated / overlooks-water locations, so matching rows are
     * already restricted to inversion-eligible locations. Used by the inversion hot topic.
     *
     * @param from      start of the window (inclusive)
     * @param to        end of the window (inclusive)
     * @param potential the inversion potential classification to match (e.g. "STRONG")
     * @return rows of [LocalDate targetDate, String regionName] ordered by date ascending
     */
    @Query("SELECT DISTINCT e.targetDate, r.name"
            + " FROM ForecastEvaluationEntity e"
            + " JOIN e.location l LEFT JOIN l.region r"
            + " WHERE e.targetDate BETWEEN :from AND :to AND e.inversionPotential = :potential"
            + " ORDER BY e.targetDate ASC")
    List<Object[]> findInversionDaysByPotential(@Param("from") LocalDate from,
            @Param("to") LocalDate to, @Param("potential") String potential);

    /**
     * Returns distinct (target date, region name) pairs for evaluations in the window whose
     * storm surge risk matches the given level. The {@code surge_risk_level} column is only
     * populated for coastal locations, so matching rows are already restricted to coastal
     * locations. Used by the storm surge hot topic.
     *
     * @param from      start of the window (inclusive)
     * @param to        end of the window (inclusive)
     * @param riskLevel the surge risk level to match (e.g. "HIGH")
     * @return rows of [LocalDate targetDate, String regionName] ordered by date ascending
     */
    @Query("SELECT DISTINCT e.targetDate, r.name"
            + " FROM ForecastEvaluationEntity e"
            + " JOIN e.location l LEFT JOIN l.region r"
            + " WHERE e.targetDate BETWEEN :from AND :to AND e.surgeRiskLevel = :riskLevel"
            + " ORDER BY e.targetDate ASC")
    List<Object[]> findSurgeDaysByRiskLevel(@Param("from") LocalDate from,
            @Param("to") LocalDate to, @Param("riskLevel") String riskLevel);

    /**
     * Returns distinct (target date, region name) pairs for evaluations in the window whose
     * aerosol readings indicate dust-enhanced skies, mirroring the Saharan dust badge proxy:
     * elevated AOD or surface dust, with PM2.5 low enough to rule out smoke/haze.
     *
     * @param from        start of the window (inclusive)
     * @param to          end of the window (inclusive)
     * @param aodThreshold     aerosol optical depth above which dust is elevated
     * @param dustThreshold    surface dust (µg/m³) above which dust is elevated
     * @param pm25Threshold    PM2.5 (µg/m³) below which smoke/haze is ruled out
     * @return rows of [LocalDate targetDate, String regionName] ordered by date ascending
     */
    @Query("SELECT DISTINCT e.targetDate, r.name"
            + " FROM ForecastEvaluationEntity e"
            + " JOIN e.location l LEFT JOIN l.region r"
            + " WHERE e.targetDate BETWEEN :from AND :to"
            + " AND ((e.aerosolOpticalDepth IS NOT NULL AND e.aerosolOpticalDepth > :aodThreshold)"
            + "   OR (e.dust IS NOT NULL AND e.dust > :dustThreshold))"
            + " AND (e.pm25 IS NULL OR e.pm25 < :pm25Threshold)"
            + " ORDER BY e.targetDate ASC")
    List<Object[]> findDustDays(@Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("aodThreshold") java.math.BigDecimal aodThreshold,
            @Param("dustThreshold") java.math.BigDecimal dustThreshold,
            @Param("pm25Threshold") java.math.BigDecimal pm25Threshold);
}
