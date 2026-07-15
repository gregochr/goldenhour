package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ModelTestResultEntity;

/**
 * Response DTO for a single location/model result within a model comparison test run.
 *
 * <p>Excludes internal identifiers, the stored prompt and raw response, the full
 * denormalised atmospheric block, cache token counts, and creation timestamp.
 *
 * @param regionName           denormalised region name
 * @param locationName         denormalised location name
 * @param evaluationModel      which Claude model was used
 * @param rating               star rating (1-5), or null if the evaluation failed
 * @param fierySkyPotential    Fiery Sky Potential (0-100), or null if failed
 * @param goldenHourPotential  Golden Hour Potential (0-100), or null if failed
 * @param summary              Claude's plain-English summary, or null if failed
 * @param durationMs           how long the Claude call took in milliseconds
 * @param succeeded            whether the evaluation succeeded
 * @param errorMessage         error message if the evaluation failed, else null
 * @param inputTokens          standard input tokens consumed
 * @param outputTokens         output tokens generated
 * @param costMicroDollars     cost of this evaluation in micro-dollars
 * @param atmosphericDataJson  serialised atmospheric data JSON for replay
 */
public record ModelTestResultDto(
        String regionName,
        String locationName,
        EvaluationModel evaluationModel,
        Integer rating,
        Integer fierySkyPotential,
        Integer goldenHourPotential,
        String summary,
        Long durationMs,
        Boolean succeeded,
        String errorMessage,
        Long inputTokens,
        Long outputTokens,
        Long costMicroDollars,
        String atmosphericDataJson
) {

    /**
     * Builds a {@code ModelTestResultDto} from a {@link ModelTestResultEntity}, copying exposed fields.
     *
     * @param e the source entity
     * @return the populated DTO
     */
    public static ModelTestResultDto from(ModelTestResultEntity e) {
        return new ModelTestResultDto(
                e.getRegionName(),
                e.getLocationName(),
                e.getEvaluationModel(),
                e.getRating(),
                e.getFierySkyPotential(),
                e.getGoldenHourPotential(),
                e.getSummary(),
                e.getDurationMs(),
                e.getSucceeded(),
                e.getErrorMessage(),
                e.getInputTokens(),
                e.getOutputTokens(),
                e.getCostMicroDollars(),
                e.getAtmosphericDataJson()
        );
    }
}
