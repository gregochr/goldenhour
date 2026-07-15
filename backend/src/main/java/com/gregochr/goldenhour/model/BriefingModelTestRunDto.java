package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.BriefingModelTestRunEntity;

import java.time.LocalDateTime;

/**
 * Response DTO for a single briefing model comparison test run.
 *
 * <p>Excludes {@code completedAt} and the lazy {@code results} child collection.
 *
 * @param id                     the database identifier
 * @param startedAt              when the test run started (UTC)
 * @param briefingGeneratedAt    when the briefing providing input data was generated (UTC)
 * @param succeeded              number of successful model evaluations
 * @param failed                 number of failed model evaluations
 * @param durationMs             total duration of the run in milliseconds
 * @param totalCostMicroDollars  total cost of all evaluations in micro-dollars
 * @param exchangeRateGbpPerUsd  USD-to-GBP exchange rate at run start
 * @param rollupJson             the rollup JSON sent to all three models
 */
public record BriefingModelTestRunDto(
        Long id,
        LocalDateTime startedAt,
        LocalDateTime briefingGeneratedAt,
        Integer succeeded,
        Integer failed,
        Long durationMs,
        Long totalCostMicroDollars,
        Double exchangeRateGbpPerUsd,
        String rollupJson
) {

    /**
     * Builds a {@code BriefingModelTestRunDto} from a {@link BriefingModelTestRunEntity}.
     *
     * @param e the source entity
     * @return the populated DTO
     */
    public static BriefingModelTestRunDto from(BriefingModelTestRunEntity e) {
        return new BriefingModelTestRunDto(
                e.getId(),
                e.getStartedAt(),
                e.getBriefingGeneratedAt(),
                e.getSucceeded(),
                e.getFailed(),
                e.getDurationMs(),
                e.getTotalCostMicroDollars(),
                e.getExchangeRateGbpPerUsd(),
                e.getRollupJson()
        );
    }
}
