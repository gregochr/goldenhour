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
 * @param detail          human-readable reason for skip dispositions and for
 *                        {@link DispositionCategory#SUBMISSION_FAILED}, null for EVALUATED /
 *                        FORCE_EVALUATED — except the one rare case where a paired candidate's
 *                        (OPEN_FELL sky+bluebell) other bucket failed to submit while this one
 *                        succeeded, which records the partial loss even though the category
 *                        stays EVALUATED/FORCE_EVALUATED (see {@code
 *                        ScheduledBatchEvaluationService#applySubmissionFailures})
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
