package com.gregochr.goldenhour.config;

import com.anthropic.client.AnthropicClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.service.evaluation.HaikuEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.SonnetEvaluationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes both evaluation strategy beans so they can be injected by name.
 *
 * <p>Both Haiku and Sonnet strategies are always available regardless of the active profile.
 * {@link com.gregochr.goldenhour.service.EvaluationService} selects the correct strategy
 * at call time based on the {@link com.gregochr.goldenhour.entity.EvaluationModel} argument.
 */
@Configuration
public class EvaluationConfig {

    /**
     * Haiku-based evaluation strategy — lower cost, 1–5 rating output.
     *
     * @param client       Anthropic API client
     * @param properties   Anthropic configuration
     * @param objectMapper Jackson mapper
     * @return a Haiku evaluation strategy
     */
    @Bean
    public HaikuEvaluationStrategy haikuEvaluationStrategy(AnthropicClient client,
            AnthropicProperties properties, ObjectMapper objectMapper) {
        return new HaikuEvaluationStrategy(client, properties, objectMapper);
    }

    /**
     * Sonnet-based evaluation strategy — higher accuracy, dual 0–100 score output.
     *
     * @param client       Anthropic API client
     * @param properties   Anthropic configuration
     * @param objectMapper Jackson mapper
     * @return a Sonnet evaluation strategy
     */
    @Bean
    public SonnetEvaluationStrategy sonnetEvaluationStrategy(AnthropicClient client,
            AnthropicProperties properties, ObjectMapper objectMapper) {
        return new SonnetEvaluationStrategy(client, properties, objectMapper);
    }
}
