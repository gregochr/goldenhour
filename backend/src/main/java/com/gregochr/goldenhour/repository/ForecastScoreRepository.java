package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.ForecastScoreEntity;
import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.TargetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for normalised component-score rows (V108).
 *
 * <p>Written through by the Pass 2 dual-write ({@code ForecastScoreWriter}):
 * the find-by-unique-key accessor is the upsert's lookup (find the component
 * row, update score/summary/provenance, or insert a new row). Nothing reads
 * the table yet — the read migration is Pass 4.
 */
@Repository
public interface ForecastScoreRepository extends JpaRepository<ForecastScoreEntity, Long> {

    /**
     * Finds the single component row for the unique key
     * {@code (forecast_type_id, location_id, evaluation_date, event_type)}.
     * Prefer {@link #findComponent} which takes the enum.
     *
     * @param forecastTypeId the score product's lookup id
     * @param locationId     the location id
     * @param evaluationDate the forecast date
     * @param eventType      SUNRISE or SUNSET
     * @return the component row, if one has been written
     */
    Optional<ForecastScoreEntity> findByForecastTypeIdAndLocationIdAndEvaluationDateAndEventType(
            Long forecastTypeId,
            Long locationId,
            LocalDate evaluationDate,
            TargetType eventType);

    /**
     * Enum-typed accessor for the component unique key — the lookup shape
     * the Pass 2 dual-write upsert needs.
     *
     * @param forecastType   the score product
     * @param locationId     the location id
     * @param evaluationDate the forecast date
     * @param eventType      SUNRISE or SUNSET
     * @return the component row, if one has been written
     */
    default Optional<ForecastScoreEntity> findComponent(
            ForecastType forecastType,
            Long locationId,
            LocalDate evaluationDate,
            TargetType eventType) {
        return findByForecastTypeIdAndLocationIdAndEvaluationDateAndEventType(
                forecastType.getId(), locationId, evaluationDate, eventType);
    }

    /**
     * Finds the component rows of a given type for a set of locations within a date range,
     * eagerly fetching each row's location and its region so callers can group by region without
     * a lazy-load. Used by the bluebell hot topic to read the Claude {@code BLUEBELL} rows (1–5)
     * straight from the nightly pipeline's dual-write, replacing the legacy
     * {@code forecast_evaluation.bluebell_score} read.
     *
     * @param forecastTypeId the score product's lookup id (e.g. {@code ForecastType.BLUEBELL.getId()})
     * @param locationIds    the candidate location ids
     * @param from           inclusive start date
     * @param to             inclusive end date
     * @return matching component rows (location + region fetched), in no guaranteed order
     */
    @Query("SELECT s FROM ForecastScoreEntity s "
            + "JOIN FETCH s.location l LEFT JOIN FETCH l.region "
            + "WHERE s.forecastTypeId = :forecastTypeId "
            + "AND l.id IN :locationIds "
            + "AND s.evaluationDate BETWEEN :from AND :to")
    List<ForecastScoreEntity> findComponentsForLocations(
            @Param("forecastTypeId") Long forecastTypeId,
            @Param("locationIds") Collection<Long> locationIds,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /**
     * Finds every component row of a given type within a date range, eagerly fetching each row's
     * location and region so callers can group by region without a lazy-load. The location-set
     * filter of {@link #findComponentsForLocations} is omitted because some types are written only
     * for a self-selecting subset — e.g. {@code INVERSION} rows exist only for inversion-eligible
     * locations whose evaluation carried a score — so the type id alone already restricts the rows.
     * Used by the inversion hot topic to read the Claude {@code INVERSION} score (0–10) straight
     * from the nightly pipeline's dual-write, replacing the legacy
     * {@code forecast_evaluation.inversion_potential} read.
     *
     * @param forecastTypeId the score product's lookup id (e.g. {@code ForecastType.INVERSION.getId()})
     * @param from           inclusive start date
     * @param to             inclusive end date
     * @return matching component rows (location + region fetched), in no guaranteed order
     */
    @Query("SELECT s FROM ForecastScoreEntity s "
            + "JOIN FETCH s.location l LEFT JOIN FETCH l.region "
            + "WHERE s.forecastTypeId = :forecastTypeId "
            + "AND s.evaluationDate BETWEEN :from AND :to")
    List<ForecastScoreEntity> findComponentsByType(
            @Param("forecastTypeId") Long forecastTypeId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
