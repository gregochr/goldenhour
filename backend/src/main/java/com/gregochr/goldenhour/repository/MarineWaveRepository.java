package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.MarineWaveEntity;
import com.gregochr.goldenhour.entity.TargetType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link MarineWaveEntity} — the shared sea-state carrier read by the coastal
 * hot-topic pills and UPSERTed by the briefing marine fetch.
 */
public interface MarineWaveRepository extends JpaRepository<MarineWaveEntity, Long> {

    /**
     * Finds the sea-state sample for a location, date and event — the UPSERT and per-pill lookup key.
     *
     * @param locationId     the location primary key
     * @param evaluationDate the calendar date
     * @param eventType      SUNRISE or SUNSET
     * @return the matching sample, or empty
     */
    Optional<MarineWaveEntity> findByLocation_IdAndEvaluationDateAndEventType(
            Long locationId, LocalDate evaluationDate, TargetType eventType);

    /**
     * Returns every sea-state sample whose date is within the inclusive range — the coastal
     * detector scan shape.
     *
     * @param fromDate start of the window (inclusive)
     * @param toDate   end of the window (inclusive)
     * @return all matching samples, possibly empty
     */
    List<MarineWaveEntity> findByEvaluationDateBetween(LocalDate fromDate, LocalDate toDate);
}
