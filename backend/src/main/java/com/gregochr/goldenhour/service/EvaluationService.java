package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.EvaluationDetail;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.service.evaluation.HaikuEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.NoOpEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.OpusEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.SonnetEvaluationStrategy;
import org.springframework.stereotype.Service;

/**
 * Evaluates sunrise/sunset colour potential by delegating to the appropriate
 * {@link com.gregochr.goldenhour.service.evaluation.EvaluationStrategy}.
 *
 * <p>All strategies are injected; the caller selects which to use by passing an
 * {@link EvaluationModel} to {@link #evaluate(AtmosphericData, EvaluationModel)}.
 */
@Service
public class EvaluationService {

    private final HaikuEvaluationStrategy haikuStrategy;
    private final SonnetEvaluationStrategy sonnetStrategy;
    private final OpusEvaluationStrategy opusStrategy;
    private final NoOpEvaluationStrategy noOpStrategy;

    /**
     * Constructs an {@code EvaluationService}.
     *
     * @param haikuStrategy  the Haiku evaluation strategy
     * @param sonnetStrategy the Sonnet evaluation strategy
     * @param opusStrategy   the Opus evaluation strategy
     * @param noOpStrategy   the no-op strategy for wildlife locations
     */
    public EvaluationService(HaikuEvaluationStrategy haikuStrategy,
            SonnetEvaluationStrategy sonnetStrategy,
            OpusEvaluationStrategy opusStrategy,
            NoOpEvaluationStrategy noOpStrategy) {
        this.haikuStrategy = haikuStrategy;
        this.sonnetStrategy = sonnetStrategy;
        this.opusStrategy = opusStrategy;
        this.noOpStrategy = noOpStrategy;
    }

    /**
     * Evaluates the colour potential for a solar event using the specified model.
     *
     * @param data  the atmospheric forecast data to evaluate
     * @param model which Claude model to use
     * @return Claude's colour potential evaluation and plain-English explanation
     */
    public SunsetEvaluation evaluate(AtmosphericData data, EvaluationModel model) {
        return evaluate(data, model, null);
    }

    /**
     * Evaluates the colour potential for a solar event using the specified model.
     *
     * @param data   the atmospheric forecast data to evaluate
     * @param model  which Claude model to use
     * @param jobRun the parent job run for metrics tracking, or {@code null} if not from scheduled run
     * @return Claude's colour potential evaluation and plain-English explanation
     */
    public SunsetEvaluation evaluate(AtmosphericData data, EvaluationModel model, JobRunEntity jobRun) {
        return switch (model) {
            case HAIKU -> haikuStrategy.evaluate(data, jobRun);
            case OPUS -> opusStrategy.evaluate(data, jobRun);
            case WILDLIFE -> noOpStrategy.evaluate(data, jobRun);
            case SONNET -> sonnetStrategy.evaluate(data, jobRun);
        };
    }

    /**
     * Evaluates the colour potential and returns full detail including prompt and raw response.
     *
     * <p>Used by model comparison tests to capture exact inputs/outputs for side-by-side analysis.
     * Only supports Claude-based models (HAIKU, SONNET, OPUS); WILDLIFE is not supported.
     *
     * @param data   the atmospheric forecast data to evaluate
     * @param model  which Claude model to use (HAIKU, SONNET, or OPUS)
     * @param jobRun the parent job run for metrics tracking, or {@code null}
     * @return full evaluation detail including prompt and raw response
     * @throws IllegalArgumentException if model is WILDLIFE
     */
    public EvaluationDetail evaluateWithDetails(AtmosphericData data, EvaluationModel model,
            JobRunEntity jobRun) {
        return switch (model) {
            case HAIKU -> haikuStrategy.evaluateWithDetails(data, jobRun);
            case SONNET -> sonnetStrategy.evaluateWithDetails(data, jobRun);
            case OPUS -> opusStrategy.evaluateWithDetails(data, jobRun);
            case WILDLIFE -> throw new IllegalArgumentException(
                    "evaluateWithDetails not supported for WILDLIFE model");
        };
    }
}
