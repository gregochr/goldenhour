package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.SunsetEvaluation;

/**
 * Strategy for evaluating sunrise/sunset colour potential via the Claude API.
 *
 * <p>Implementations differ in the model used and prompt style, but share the
 * same contract: given atmospheric data, return a 1-5 rating with summary.
 */
public interface EvaluationStrategy {

    /**
     * Evaluates the colour potential for a solar event.
     *
     * @param data the atmospheric forecast data to evaluate
     * @return Claude's colour potential rating and plain-English explanation
     */
    SunsetEvaluation evaluate(AtmosphericData data);
}
