package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.EvaluationDetail;
import com.gregochr.goldenhour.model.SunsetEvaluation;

/**
 * Strategy for evaluating sunrise/sunset colour potential via the Claude API.
 *
 * <p>Implementations differ in the model used, but share the same contract:
 * given atmospheric data, return an evaluation with scores and summary.
 *
 * <p>Cross-cutting concerns (timing, logging, metrics) are handled by
 * {@link MetricsLoggingDecorator}, not by strategy implementations.
 */
public interface EvaluationStrategy {

    /**
     * Evaluates the colour potential for a solar event.
     *
     * @param data the atmospheric forecast data to evaluate
     * @return Claude's colour potential rating and plain-English explanation
     */
    SunsetEvaluation evaluate(AtmosphericData data);

    /**
     * Evaluates the colour potential and returns full detail including the prompt
     * sent, raw response text, duration, and token usage.
     *
     * @param data the atmospheric forecast data to evaluate
     * @return full evaluation detail including prompt and raw response
     */
    EvaluationDetail evaluateWithDetails(AtmosphericData data);

    /**
     * Returns the evaluation model used by this strategy.
     *
     * @return the evaluation model (HAIKU, SONNET, OPUS, or WILDLIFE)
     */
    EvaluationModel getEvaluationModel();
}
