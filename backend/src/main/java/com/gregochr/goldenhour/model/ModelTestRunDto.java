package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.ModelTestRunEntity;
import com.gregochr.goldenhour.entity.RerunType;
import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Response DTO for a single model comparison test run.
 *
 * <p>Excludes {@code completedAt} and the lazy {@code results} child collection.
 *
 * @param id                     the database identifier
 * @param startedAt              when the test run started (UTC)
 * @param targetDate             the date being evaluated
 * @param targetType             whether SUNRISE or SUNSET was tested
 * @param regionsCount           number of regions included in the test
 * @param succeeded              number of successful model evaluations
 * @param failed                 number of failed model evaluations
 * @param durationMs             total duration of the run in milliseconds
 * @param totalCostMicroDollars  total cost of all evaluations in micro-dollars
 * @param exchangeRateGbpPerUsd  USD-to-GBP exchange rate at run start
 * @param parentRunId            parent run this was re-run from, or null for originals
 * @param rerunType              the type of re-run, or null for original runs
 */
public record ModelTestRunDto(
        Long id,
        LocalDateTime startedAt,
        LocalDate targetDate,
        TargetType targetType,
        Integer regionsCount,
        Integer succeeded,
        Integer failed,
        Long durationMs,
        Long totalCostMicroDollars,
        Double exchangeRateGbpPerUsd,
        Long parentRunId,
        RerunType rerunType
) {

    /**
     * Builds a {@code ModelTestRunDto} from a {@link ModelTestRunEntity}, copying exposed fields.
     *
     * @param e the source entity
     * @return the populated DTO
     */
    public static ModelTestRunDto from(ModelTestRunEntity e) {
        return new ModelTestRunDto(
                e.getId(),
                e.getStartedAt(),
                e.getTargetDate(),
                e.getTargetType(),
                e.getRegionsCount(),
                e.getSucceeded(),
                e.getFailed(),
                e.getDurationMs(),
                e.getTotalCostMicroDollars(),
                e.getExchangeRateGbpPerUsd(),
                e.getParentRunId(),
                e.getRerunType()
        );
    }
}
