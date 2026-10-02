package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.errors.AnthropicServiceException;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.exception.WeatherDataFetchException;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.EvaluationDetail;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.service.EvaluationFailure;
import com.gregochr.goldenhour.service.JobRunService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorator that adds timing, logging, and metrics tracking to any {@link EvaluationStrategy}.
 *
 * <p>Separates cross-cutting concerns from the core evaluation logic:
 * <ul>
 *   <li>Entry/exit logging with location, target type, date, and scores</li>
 *   <li>Duration measurement</li>
 *   <li>API call metrics recording via {@link JobRunService}</li>
 *   <li>Error logging with the real HTTP status (null when the failure had none) and the
 *       {@link EvaluationFailure} error type</li>
 *   <li>Content filter diagnostic logging</li>
 * </ul>
 *
 * <p>Created per-evaluation by {@link com.gregochr.goldenhour.service.EvaluationService}
 * when a {@link JobRunEntity} is available for metrics tracking.
 */
public class MetricsLoggingDecorator implements EvaluationStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(MetricsLoggingDecorator.class);

    private final EvaluationStrategy delegate;
    private final JobRunService jobRunService;
    private final JobRunEntity jobRun;

    /**
     * Wraps an evaluation strategy with timing, logging, and metrics.
     *
     * @param delegate      the core evaluation strategy to decorate
     * @param jobRunService the metrics service for recording API calls
     * @param jobRun        the parent job run for metrics tracking
     */
    public MetricsLoggingDecorator(EvaluationStrategy delegate,
            JobRunService jobRunService, JobRunEntity jobRun) {
        this.delegate = delegate;
        this.jobRunService = jobRunService;
        this.jobRun = jobRun;
    }

    @Override
    public SunsetEvaluation evaluate(AtmosphericData data) {
        return evaluateWithDetails(data).evaluation();
    }

    @Override
    public EvaluationDetail evaluateWithDetails(AtmosphericData data) {
        String modelId = getEvaluationModel().getModelId();
        LOG.info("Anthropic ({}) <- {} {} {}", modelId,
                data.locationName(), data.targetType(), data.solarEventTime().toLocalDate());

        try {
            EvaluationDetail detail = delegate.evaluateWithDetails(data);

            LOG.info("Anthropic -> {} {}: rating={}/5 fiery={}/100 golden={}/100 ({}ms, {}tok)",
                    data.locationName(), data.targetType(),
                    detail.evaluation().rating(), detail.evaluation().fierySkyPotential(),
                    detail.evaluation().goldenHourPotential(), detail.durationMs(),
                    detail.tokenUsage().totalTokens());

            logApiCall(detail.durationMs(), 200, null, null, true, detail.tokenUsage(), data);
            return detail;
        } catch (WeatherDataFetchException e) {
            LOG.error("Skipping Anthropic evaluation — weather data unavailable: {}",
                    e.getMessage());
            throw e;
        } catch (Exception e) {
            handleFailure(e, data);
            throw e;
        }
    }

    @Override
    public EvaluationModel getEvaluationModel() {
        return delegate.getEvaluationModel();
    }

    /**
     * Logs a failed evaluation with appropriate status code extraction and diagnostics.
     */
    private void handleFailure(Exception e, AtmosphericData data) {
        String errorMessage = e.getMessage();
        // The HTTP status Anthropic answered with, or null when the failure had none (an I/O failure,
        // an open breaker, anything that is not an Anthropic service error): never a placeholder 500.
        Integer statusCode = EvaluationFailure.httpStatusOf(e);

        if (e instanceof AnthropicServiceException serviceEx) {
            if (serviceEx.statusCode() == 400 && errorMessage != null
                    && errorMessage.contains("content filtering")) {
                LOG.warn("Anthropic content filter — final failure. "
                        + "Location: {}, Target: {}, Date: {}, Model: {}",
                        data.locationName(), data.targetType(),
                        data.solarEventTime().toLocalDate(),
                        getEvaluationModel().getModelId());
            }
        }

        LOG.error("Anthropic evaluation failed: {}", e.getMessage(), e);
        logApiCall(0, statusCode, EvaluationFailure.errorTypeOf(e), errorMessage, false,
                TokenUsage.EMPTY, data);
    }

    /**
     * Records an API call to the metrics store.
     */
    private void logApiCall(long durationMs, Integer statusCode, String errorType, String errorMessage,
            boolean succeeded, TokenUsage tokenUsage, AtmosphericData data) {
        if (jobRun != null && jobRunService != null) {
            jobRunService.logAnthropicApiCall(jobRun.getId(),
                    durationMs, statusCode, errorMessage, succeeded, errorMessage,
                    getEvaluationModel(), tokenUsage, false,
                    data.solarEventTime().toLocalDate(), data.targetType(), errorType);
        }
    }
}
