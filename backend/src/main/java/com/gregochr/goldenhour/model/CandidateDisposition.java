package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;

/**
 * In-memory record of one candidate's outcome during forecast task collection,
 * emitted by {@code ForecastTaskCollector} as the collector iterates briefing
 * slots and triages survivors.
 *
 * <p>Not a JPA entity. The corresponding
 * {@code ForecastRunDispositionEntity} is built and persisted once the cycle's
 * first job run has been created (after the first non-empty bucket has been
 * submitted to the Anthropic Batch API). At that point a list of these is
 * handed to {@code ForecastDispositionService.persist(jobRunId, list)}.
 *
 * @param locationId      resolved location id, or {@code null} for past-date,
 *                        cached, and unknown-location dispositions
 * @param locationName    location name from the briefing slot (always populated)
 * @param evaluationDate  date of the briefing slot
 * @param eventType       SUNRISE or SUNSET
 * @param daysAhead       days from today to {@code evaluationDate} —
 *                        negative for past-date dispositions, may be null
 * @param category        disposition outcome — see {@link DispositionCategory}
 * @param detail          human-readable reason for skip dispositions, null for EVALUATED
 */
public record CandidateDisposition(
        Long locationId,
        String locationName,
        LocalDate evaluationDate,
        TargetType eventType,
        Integer daysAhead,
        DispositionCategory category,
        String detail) {
}
