package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.client.AnthropicClient;
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
     * @param client          configured Anthropic client
     * @param properties      Anthropic configuration
     * @param objectMapper    Jackson mapper for parsing Claude's JSON response
     * @param jobRunService   optional service for metrics tracking
     */
    public OpusEvaluationStrategy(AnthropicClient client, AnthropicProperties properties,
            ObjectMapper objectMapper, JobRunService jobRunService) {
        super(client, properties, objectMapper, jobRunService);
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
