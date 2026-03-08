package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.EvaluationDetail;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TokenUsage;

/**
 * No-op evaluation strategy for wildlife/comfort-only locations.
 *
 * <p>Returns a null evaluation (no rating, no scores, no summary) without calling
 * the Claude API. Used when a location only needs weather comfort data.
 */
public class NoOpEvaluationStrategy implements EvaluationStrategy {

    /** The singleton null evaluation returned for every call. */
    private static final SunsetEvaluation NO_EVAL = new SunsetEvaluation(null, null, null, null);

    @Override
    public SunsetEvaluation evaluate(AtmosphericData data) {
        return NO_EVAL;
    }

    @Override
    public EvaluationDetail evaluateWithDetails(AtmosphericData data) {
        return new EvaluationDetail(NO_EVAL, null, null, 0L, TokenUsage.EMPTY);
    }

    @Override
    public EvaluationModel getEvaluationModel() {
        return EvaluationModel.WILDLIFE;
    }
}
