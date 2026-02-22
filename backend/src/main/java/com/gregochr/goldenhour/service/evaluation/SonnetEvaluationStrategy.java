package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.client.AnthropicClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.config.AnthropicProperties;

/**
 * Evaluation strategy using Claude Sonnet for higher accuracy.
 *
 * <p>Requests a more detailed 2-3 sentence explanation to leverage Sonnet's richer reasoning.
 */
public class SonnetEvaluationStrategy extends AbstractEvaluationStrategy {

    /** Prompt suffix requesting a detailed 2-3 sentence explanation. */
    static final String PROMPT_SUFFIX = "Rate 1-5 and explain in 2-3 sentences.";

    /**
     * Constructs a {@code SonnetEvaluationStrategy}.
     *
     * @param client       configured Anthropic client
     * @param properties   Anthropic configuration (model identifier)
     * @param objectMapper Jackson mapper for parsing Claude's JSON response
     */
    public SonnetEvaluationStrategy(AnthropicClient client, AnthropicProperties properties,
            ObjectMapper objectMapper) {
        super(client, properties, objectMapper);
    }

    @Override
    protected String getPromptSuffix() {
        return PROMPT_SUFFIX;
    }
}
