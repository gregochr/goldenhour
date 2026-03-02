package com.gregochr.goldenhour.service.evaluation;

import tools.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.config.AnthropicProperties;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.service.JobRunService;

/**
 * Evaluation strategy using Claude Opus for maximum accuracy.
 *
 * <p>Uses the shared system prompt and response format from
 * {@link AbstractEvaluationStrategy}. Only the underlying model differs.
 */
public class OpusEvaluationStrategy extends AbstractEvaluationStrategy {

    /**
     * Constructs an {@code OpusEvaluationStrategy}.
     *
     * @param anthropicApiClient resilient Anthropic API client with retry
     * @param properties         Anthropic configuration
     * @param objectMapper       Jackson mapper for parsing Claude's JSON response
     * @param jobRunService      optional service for metrics tracking
     */
    public OpusEvaluationStrategy(AnthropicApiClient anthropicApiClient,
            AnthropicProperties properties,
            ObjectMapper objectMapper, JobRunService jobRunService) {
        super(anthropicApiClient, properties, objectMapper, jobRunService);
    }

    @Override
    protected EvaluationModel getEvaluationModel() {
        return EvaluationModel.OPUS;
    }

    @Override
    protected String getModelName() {
        return "claude-opus-4-6";
    }
}
