package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.service.evaluation.HaikuEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.SonnetEvaluationStrategy;
import org.springframework.stereotype.Service;

/**
 * Evaluates sunrise/sunset colour potential by delegating to the appropriate
 * {@link com.gregochr.goldenhour.service.evaluation.EvaluationStrategy}.
 *
 * <p>Both Haiku and Sonnet strategies are injected; the caller selects which to use
 * by passing an {@link EvaluationModel} to {@link #evaluate(AtmosphericData, EvaluationModel)}.
 */
@Service
public class EvaluationService {

    private final HaikuEvaluationStrategy haikuStrategy;
    private final SonnetEvaluationStrategy sonnetStrategy;

    /**
     * Constructs an {@code EvaluationService}.
     *
     * @param haikuStrategy  the Haiku evaluation strategy (1–5 rating)
     * @param sonnetStrategy the Sonnet evaluation strategy (dual 0–100 scores)
     */
    public EvaluationService(HaikuEvaluationStrategy haikuStrategy,
            SonnetEvaluationStrategy sonnetStrategy) {
        this.haikuStrategy = haikuStrategy;
        this.sonnetStrategy = sonnetStrategy;
    }

    /**
     * Evaluates the colour potential for a solar event using the specified model.
     *
     * @param data  the atmospheric forecast data to evaluate
     * @param model which Claude model to use — HAIKU returns a 1–5 rating,
     *              SONNET returns dual 0–100 scores
     * @return Claude's colour potential evaluation and plain-English explanation
     */
    public SunsetEvaluation evaluate(AtmosphericData data, EvaluationModel model) {
        return evaluate(data, model, null);
    }

    /**
     * Evaluates the colour potential for a solar event using the specified model.
     *
     * @param data   the atmospheric forecast data to evaluate
     * @param model  which Claude model to use — HAIKU returns a 1–5 rating,
     *               SONNET returns dual 0–100 scores
     * @param jobRun the parent job run for metrics tracking, or {@code null} if not from scheduled run
     * @return Claude's colour potential evaluation and plain-English explanation
     */
    public SunsetEvaluation evaluate(AtmosphericData data, EvaluationModel model, JobRunEntity jobRun) {
        return (model == EvaluationModel.HAIKU ? haikuStrategy : sonnetStrategy)
                .evaluate(data, jobRun);
    }
}
