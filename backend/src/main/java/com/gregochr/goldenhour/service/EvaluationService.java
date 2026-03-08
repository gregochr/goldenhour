package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.EvaluationDetail;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.MetricsLoggingDecorator;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Evaluates sunrise/sunset colour potential by delegating to the appropriate
 * {@link EvaluationStrategy}.
 *
 * <p>Strategies are looked up from an injected map keyed by {@link EvaluationModel}.
 * When a {@link JobRunEntity} is provided, the strategy is wrapped with a
 * {@link MetricsLoggingDecorator} that adds timing, logging, and metrics tracking —
 * keeping cross-cutting concerns out of the core strategies.
 */
@Service
public class EvaluationService {

    private final Map<EvaluationModel, EvaluationStrategy> strategies;
    private final JobRunService jobRunService;

    /**
     * Constructs an {@code EvaluationService}.
     *
     * @param strategies    map from evaluation model to its strategy
     * @param jobRunService the metrics service for decorator creation
     */
    public EvaluationService(Map<EvaluationModel, EvaluationStrategy> strategies,
            JobRunService jobRunService) {
        this.strategies = strategies;
        this.jobRunService = jobRunService;
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
     * Evaluates the colour potential for a solar event using the specified model,
     * with optional metrics tracking via the decorator pattern.
     *
     * @param data   the atmospheric forecast data to evaluate
     * @param model  which Claude model to use
     * @param jobRun the parent job run for metrics tracking, or {@code null}
     * @return Claude's colour potential evaluation and plain-English explanation
     */
    public SunsetEvaluation evaluate(AtmosphericData data, EvaluationModel model,
            JobRunEntity jobRun) {
        return decorateIfNeeded(getStrategy(model), jobRun).evaluate(data);
    }

    /**
     * Evaluates the colour potential and returns full detail including prompt and raw response.
     *
     * <p>Used by model comparison tests to capture exact inputs/outputs for side-by-side analysis.
     *
     * @param data   the atmospheric forecast data to evaluate
     * @param model  which Claude model to use
     * @param jobRun the parent job run for metrics tracking, or {@code null}
     * @return full evaluation detail including prompt and raw response
     */
    public EvaluationDetail evaluateWithDetails(AtmosphericData data, EvaluationModel model,
            JobRunEntity jobRun) {
        return decorateIfNeeded(getStrategy(model), jobRun).evaluateWithDetails(data);
    }

    /**
     * Returns the strategy for the given model.
     *
     * @throws IllegalArgumentException if no strategy is registered for the model
     */
    private EvaluationStrategy getStrategy(EvaluationModel model) {
        EvaluationStrategy strategy = strategies.get(model);
        if (strategy == null) {
            throw new IllegalArgumentException("No evaluation strategy for model: " + model);
        }
        return strategy;
    }

    /**
     * Wraps the strategy with a {@link MetricsLoggingDecorator} when a job run
     * is available for metrics tracking.
     */
    private EvaluationStrategy decorateIfNeeded(EvaluationStrategy strategy,
            JobRunEntity jobRun) {
        if (jobRun != null) {
            return new MetricsLoggingDecorator(strategy, jobRunService, jobRun);
        }
        return strategy;
    }
}
