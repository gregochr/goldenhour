package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;
import org.springframework.stereotype.Service;

/**
 * Evaluates sunrise/sunset colour potential by delegating to the active {@link EvaluationStrategy}.
 *
 * <p>The concrete strategy (Haiku or Sonnet) is selected at startup based on the active
 * Spring profile and injected automatically.
 */
@Service
public class EvaluationService {

    private final EvaluationStrategy strategy;

    /**
     * Constructs an {@code EvaluationService}.
     *
     * @param strategy the profile-selected evaluation strategy
     */
    public EvaluationService(EvaluationStrategy strategy) {
        this.strategy = strategy;
    }

    /**
     * Evaluates the colour potential for a solar event.
     *
     * @param data the atmospheric forecast data to evaluate
     * @return Claude's colour potential rating and plain-English explanation
     */
    public SunsetEvaluation evaluate(AtmosphericData data) {
        return strategy.evaluate(data);
    }
}
