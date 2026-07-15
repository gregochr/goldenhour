package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Response DTO for a single scheduled job run.
 *
 * <p>Exposes only the fields the admin metrics UI needs, deliberately excluding the
 * {@code triggeredManually} flag and the lazy {@code apiCalls} child collection.
 *
 * @param id                     the database identifier
 * @param runType                the type of forecast run (what was requested)
 * @param evaluationModel        the Claude model used (null for WEATHER/TIDE)
 * @param startedAt              when the job started (UTC)
 * @param completedAt            when the job completed (UTC), or null if still running
 * @param durationMs             duration in milliseconds, or null if still running
 * @param locationsProcessed     total number of locations processed
 * @param succeeded              number of successful evaluations
 * @param failed                 number of failed evaluations
 * @param minTargetDate          earliest target date across evaluations in this run
 * @param maxTargetDate          latest target date across evaluations in this run
 * @param totalCostMicroDollars  total cost of all API calls in micro-dollars
 * @param exchangeRateGbpPerUsd  USD-to-GBP exchange rate at run start
 * @param activeStrategies       comma-separated enabled optimisation strategy names
 * @param appVersion             application version at the time of the run
 * @param notes                  free-text notes (e.g. Anthropic batch ID)
 * @param batchSummary           read-time batch enrichment, or null for non-batch runs
 */
public record JobRunDto(
        Long id,
        RunType runType,
        EvaluationModel evaluationModel,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        Long durationMs,
        Integer locationsProcessed,
        Integer succeeded,
        Integer failed,
        LocalDate minTargetDate,
        LocalDate maxTargetDate,
        Long totalCostMicroDollars,
        Double exchangeRateGbpPerUsd,
        String activeStrategies,
        String appVersion,
        String notes,
        BatchSummary batchSummary
) {

    /**
     * Builds a {@code JobRunDto} from a {@link JobRunEntity}, copying only the exposed fields.
     *
     * @param e the source entity
     * @return the populated DTO
     */
    public static JobRunDto from(JobRunEntity e) {
        return new JobRunDto(
                e.getId(),
                e.getRunType(),
                e.getEvaluationModel(),
                e.getStartedAt(),
                e.getCompletedAt(),
                e.getDurationMs(),
                e.getLocationsProcessed(),
                e.getSucceeded(),
                e.getFailed(),
                e.getMinTargetDate(),
                e.getMaxTargetDate(),
                e.getTotalCostMicroDollars(),
                e.getExchangeRateGbpPerUsd(),
                e.getActiveStrategies(),
                e.getAppVersion(),
                e.getNotes(),
                e.getBatchSummary()
        );
    }
}
