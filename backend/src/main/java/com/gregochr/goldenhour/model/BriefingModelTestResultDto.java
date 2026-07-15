package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.BriefingModelTestResultEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;

/**
 * Response DTO for a single model's result within a briefing model comparison test run.
 *
 * <p>Excludes internal identifiers, the raw response, cache token counts,
 * error message, and creation timestamp.
 *
 * @param evaluationModel   which Claude model was used
 * @param picksJson         JSON array of the parsed picks returned by this model
 * @param costMicroDollars  cost of this evaluation in micro-dollars
 * @param durationMs        how long the Claude call took in milliseconds
 * @param inputTokens       standard input tokens consumed
 * @param outputTokens      output tokens generated
 * @param picksReturned     number of picks returned by the model (before validation)
 * @param picksValid        number of picks that passed validation
 * @param succeeded         whether the evaluation succeeded
 * @param thinkingText      extended thinking chain text, or null when not present
 */
public record BriefingModelTestResultDto(
        EvaluationModel evaluationModel,
        String picksJson,
        Long costMicroDollars,
        Long durationMs,
        Long inputTokens,
        Long outputTokens,
        Integer picksReturned,
        Integer picksValid,
        Boolean succeeded,
        String thinkingText
) {

    /**
     * Builds a {@code BriefingModelTestResultDto} from a {@link BriefingModelTestResultEntity}.
     *
     * @param e the source entity
     * @return the populated DTO
     */
    public static BriefingModelTestResultDto from(BriefingModelTestResultEntity e) {
        return new BriefingModelTestResultDto(
                e.getEvaluationModel(),
                e.getPicksJson(),
                e.getCostMicroDollars(),
                e.getDurationMs(),
                e.getInputTokens(),
                e.getOutputTokens(),
                e.getPicksReturned(),
                e.getPicksValid(),
                e.getSucceeded(),
                e.getThinkingText()
        );
    }
}
