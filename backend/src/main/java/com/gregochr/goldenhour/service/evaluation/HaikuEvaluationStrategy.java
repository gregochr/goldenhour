package com.gregochr.goldenhour.service.evaluation;

import tools.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.config.AnthropicProperties;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.service.JobRunService;

/**
 * Evaluation strategy using Claude Haiku for lower cost and latency.
 *
 * <p>Uses the shared system prompt and response format from
 * {@link AbstractEvaluationStrategy}. Only the underlying model differs.
 */
public class HaikuEvaluationStrategy extends AbstractEvaluationStrategy {

    /**
     * Constructs a {@code HaikuEvaluationStrategy}.
     *
     * @param anthropicApiClient resilient Anthropic API client with retry
     * @param properties         Anthropic configuration (model identifier)
     * @param objectMapper       Jackson mapper for parsing Claude's JSON response
     * @param jobRunService      optional service for metrics tracking
     */
    public HaikuEvaluationStrategy(AnthropicApiClient anthropicApiClient,
            AnthropicProperties properties,
            ObjectMapper objectMapper, JobRunService jobRunService) {
        super(anthropicApiClient, properties, objectMapper, jobRunService);
    }

    @Override
    protected EvaluationModel getEvaluationModel() {
        return EvaluationModel.HAIKU;
    }

    @Override
    protected String getModelName() {
        return "claude-haiku-4-5";
    }
}
