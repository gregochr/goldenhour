package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.client.AnthropicClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.config.AnthropicProperties;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.service.JobRunService;

/**
 * Evaluation strategy using Claude Opus for maximum accuracy.
 *
 * <p>Produces the same dual 0–100 scores ({@code fierySkyPotential} and
 * {@code goldenHourPotential}) as {@link SonnetEvaluationStrategy}, using the
 * same prompt and response format. Only the underlying model differs.
 */
public class OpusEvaluationStrategy extends SonnetEvaluationStrategy {

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
