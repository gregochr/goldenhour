package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.SlotAtmosphereEntity;
import com.gregochr.goldenhour.entity.TargetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for atmospheric readings (V115) — the readings half of the unified hot-topic read
 * surface.
 *
 * <p>⚠️ This table and these classes were named {@code survivor_atmosphere}/{@code
 * SurvivorAtmosphere*} until V159 (2026-09-30) — see {@code SlotAtmosphereWriter}'s class javadoc.
 * Since the "record conditions for every place" change (Phase 1, owner decision 2026-09-30) a row
 * exists for every candidate a batch cycle, a hand-started admin run, or the synchronous engine
 * fetched weather for, not only the ones that survived triage and Gate 4 — which is exactly why
 * "survivor" was renamed away.
 *
 * <p>Written through by {@code SlotAtmosphereWriter} at collection/submission/evaluation time:
 * {@link #findByLocationIdAndEvaluationDateAndEventType} is the upsert's lookup. Read by the
 * unified read model ({@code SlotSignalReader}) on behalf of the atmospheric hot-topic
 * detectors. Rows older than 180 days by {@code evaluation_date} are deleted nightly through
 * {@link #deleteByEvaluationDateBefore} (owner decision 2026-10-02).
 */
@Repository
public interface SlotAtmosphereRepository
        extends JpaRepository<SlotAtmosphereEntity, Long> {

    /**
     * Finds the single slot-atmosphere row for the unique key
     * {@code (location_id, evaluation_date, event_type)} — the upsert lookup.
     *
     * @param locationId     the location id
     * @param evaluationDate the forecast date
     * @param eventType      SUNRISE or SUNSET
     * @return the row, if one has been written
     */
    Optional<SlotAtmosphereEntity> findByLocationIdAndEvaluationDateAndEventType(
            Long locationId, LocalDate evaluationDate, TargetType eventType);

    /**
     * Returns the slot-atmosphere rows in a date range, eagerly fetching each row's location
     * and its region so the read model / detectors can group by region without a lazy-load.
     *
     * @param from inclusive start date
     * @param to   inclusive end date
     * @return matching rows (location + region fetched), in no guaranteed order
     */
    @Query("SELECT s FROM SlotAtmosphereEntity s "
            + "JOIN FETCH s.location l LEFT JOIN FETCH l.region "
            + "WHERE s.evaluationDate BETWEEN :from AND :to")
    List<SlotAtmosphereEntity> findInDateRange(
            @Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * Deletes every row whose {@code evaluation_date} — the slot's own date, not its write time —
     * is strictly before the cutoff, in one bulk statement the {@code idx_slot_atmosphere_date}
     * index serves. A row dated exactly at the cutoff is KEPT. Called by the nightly
     * {@code slot_atmosphere_cleanup} job ({@code SlotAtmosphereCleanupJob}), which supplies
     * {@code today - retention-days}; runs in its own transaction.
     *
     * @param cutoff the first date to keep — rows dated before it are deleted
     * @return the number of rows deleted
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM SlotAtmosphereEntity s WHERE s.evaluationDate < :cutoff")
    int deleteByEvaluationDateBefore(@Param("cutoff") LocalDate cutoff);
}
