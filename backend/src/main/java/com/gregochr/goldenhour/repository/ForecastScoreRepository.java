package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.ForecastScoreEntity;
import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.TargetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Repository for normalised component-score rows (V108).
 *
 * <p>Pass 1 scaffolding: no production code writes or reads through this
 * yet. The find-by-unique-key accessor is the shape the Pass 2 dual-write
 * upsert needs (look up the component row, update score/summary/provenance
 * or insert a new row).
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
}
