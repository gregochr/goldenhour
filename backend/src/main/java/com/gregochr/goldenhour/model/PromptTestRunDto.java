package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.PromptTestRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Response DTO for a single prompt regression test run.
 *
 * <p>Excludes {@code gitCommitDate} and the lazy {@code results} child collection.
 *
 * @param id                     the database identifier
 * @param startedAt              when the run started (UTC)
 * @param completedAt            when the run completed (UTC), or null if still running
 * @param durationMs             duration in milliseconds, or null if still running
 * @param targetDate             the date being evaluated
 * @param targetType             whether SUNRISE or SUNSET was evaluated
 * @param evaluationModel        the Claude model used
 * @param runType                the run type controlling the date range
 * @param locationsCount         number of locations included in the run
 * @param succeeded              number of successful evaluations
 * @param failed                 number of failed evaluations
 * @param totalCostMicroDollars  total cost of all evaluations in micro-dollars
 * @param exchangeRateGbpPerUsd  USD-to-GBP exchange rate at run start
 * @param parentRunId            parent run this replayed, or null for originals
 * @param gitCommitHash          git commit hash of the running build
 * @param gitDirty               whether the working tree had uncommitted changes
 * @param gitBranch              git branch of the running build
 */
public record PromptTestRunDto(
        Long id,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        Long durationMs,
        LocalDate targetDate,
        TargetType targetType,
        EvaluationModel evaluationModel,
        RunType runType,
        Integer locationsCount,
        Integer succeeded,
        Integer failed,
        Long totalCostMicroDollars,
        Double exchangeRateGbpPerUsd,
        Long parentRunId,
        String gitCommitHash,
        Boolean gitDirty,
        String gitBranch
) {

    /**
     * Builds a {@code PromptTestRunDto} from a {@link PromptTestRunEntity}, copying exposed fields.
     *
     * @param e the source entity
     * @return the populated DTO
     */
    public static PromptTestRunDto from(PromptTestRunEntity e) {
        return new PromptTestRunDto(
                e.getId(),
                e.getStartedAt(),
                e.getCompletedAt(),
                e.getDurationMs(),
                e.getTargetDate(),
                e.getTargetType(),
                e.getEvaluationModel(),
                e.getRunType(),
                e.getLocationsCount(),
                e.getSucceeded(),
                e.getFailed(),
                e.getTotalCostMicroDollars(),
                e.getExchangeRateGbpPerUsd(),
                e.getParentRunId(),
                e.getGitCommitHash(),
                e.getGitDirty(),
                e.getGitBranch()
        );
    }
}
