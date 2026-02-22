package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.client.AnthropicClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.config.AnthropicProperties;

/**
 * Evaluation strategy using Claude Haiku for lower cost and latency.
 *
 * <p>Requests a shorter 1-2 sentence explanation to match Haiku's concise output style.
 */
public class HaikuEvaluationStrategy extends AbstractEvaluationStrategy {

    /** Prompt suffix requesting a concise 1-2 sentence explanation. */
    static final String PROMPT_SUFFIX = "Rate 1-5 and explain in 1-2 sentences.";

    /**
     * Constructs a {@code HaikuEvaluationStrategy}.
     *
     * @param client       configured Anthropic client
     * @param properties   Anthropic configuration (model identifier)
     * @param objectMapper Jackson mapper for parsing Claude's JSON response
     */
    public HaikuEvaluationStrategy(AnthropicClient client, AnthropicProperties properties,
            ObjectMapper objectMapper) {
        super(client, properties, objectMapper);
    }

    @Override
    protected String getPromptSuffix() {
        return PROMPT_SUFFIX;
    }
}
