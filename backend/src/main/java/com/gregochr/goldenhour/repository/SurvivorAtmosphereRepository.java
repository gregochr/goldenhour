package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.SurvivorAtmosphereEntity;
import com.gregochr.goldenhour.entity.TargetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for atmospheric readings (V115) — the readings half of the unified hot-topic read
 * surface.
 *
 * <p>⚠️ "Survivor" in the table and class names is historical — see {@code SurvivorAtmosphereWriter}'s
 * class javadoc. Since the "record conditions for every place" change (Phase 1, owner decision
 * 2026-09-30) a row exists for every candidate a batch cycle, a hand-started admin run, or the
 * synchronous engine fetched weather for, not only the ones that survived triage and Gate 4.
 *
 * <p>Written through by {@code SurvivorAtmosphereWriter} at collection/submission/evaluation time:
 * {@link #findByLocationIdAndEvaluationDateAndEventType} is the upsert's lookup. Read by the
 * unified read model ({@code SurvivorSignalReader}) on behalf of the atmospheric hot-topic
 * detectors.
 */
@Repository
public interface SurvivorAtmosphereRepository
        extends JpaRepository<SurvivorAtmosphereEntity, Long> {

    /**
     * Finds the single survivor-atmosphere row for the unique key
     * {@code (location_id, evaluation_date, event_type)} — the upsert lookup.
     *
     * @param locationId     the location id
     * @param evaluationDate the forecast date
     * @param eventType      SUNRISE or SUNSET
     * @return the row, if one has been written
     */
    Optional<SurvivorAtmosphereEntity> findByLocationIdAndEvaluationDateAndEventType(
            Long locationId, LocalDate evaluationDate, TargetType eventType);

    /**
     * Returns the survivor-atmosphere rows in a date range, eagerly fetching each row's location
     * and its region so the read model / detectors can group by region without a lazy-load.
     *
     * @param from inclusive start date
     * @param to   inclusive end date
     * @return matching rows (location + region fetched), in no guaranteed order
     */
    @Query("SELECT s FROM SurvivorAtmosphereEntity s "
            + "JOIN FETCH s.location l LEFT JOIN FETCH l.region "
            + "WHERE s.evaluationDate BETWEEN :from AND :to")
    List<SurvivorAtmosphereEntity> findInDateRange(
            @Param("from") LocalDate from, @Param("to") LocalDate to);
}
