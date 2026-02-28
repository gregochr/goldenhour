package com.gregochr.goldenhour.config;

import com.anthropic.client.AnthropicClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.evaluation.HaikuEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.NoOpEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.OpusEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.SonnetEvaluationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes both evaluation strategy beans so they can be injected by name.
 *
 * <p>All three strategies (Haiku, Sonnet, Opus) are always available regardless of the active
 * profile. {@link com.gregochr.goldenhour.service.EvaluationService} selects the correct
 * strategy at call time based on the {@link com.gregochr.goldenhour.entity.EvaluationModel} argument.
 */
@Configuration
public class EvaluationConfig {

    /**
     * Haiku-based evaluation strategy — lower cost, 1–5 rating output.
     *
     * @param client       Anthropic API client
     * @param properties   Anthropic configuration
     * @param objectMapper Jackson mapper
     * @param jobRunService job run metrics service
     * @return a Haiku evaluation strategy
     */
    @Bean
    public HaikuEvaluationStrategy haikuEvaluationStrategy(AnthropicClient client,
            AnthropicProperties properties, ObjectMapper objectMapper, JobRunService jobRunService) {
        return new HaikuEvaluationStrategy(client, properties, objectMapper, jobRunService);
    }

    /**
     * Sonnet-based evaluation strategy — higher accuracy, dual 0–100 score output.
     *
     * @param client       Anthropic API client
     * @param properties   Anthropic configuration
     * @param objectMapper Jackson mapper
     * @param jobRunService job run metrics service
     * @return a Sonnet evaluation strategy
     */
    @Bean
    public SonnetEvaluationStrategy sonnetEvaluationStrategy(AnthropicClient client,
            AnthropicProperties properties, ObjectMapper objectMapper, JobRunService jobRunService) {
        return new SonnetEvaluationStrategy(client, properties, objectMapper, jobRunService);
    }

    /**
     * Opus-based evaluation strategy — highest accuracy, dual 0–100 score output.
     *
     * @param client       Anthropic API client
     * @param properties   Anthropic configuration
     * @param objectMapper Jackson mapper
     * @param jobRunService job run metrics service
     * @return an Opus evaluation strategy
     */
    @Bean
    public OpusEvaluationStrategy opusEvaluationStrategy(AnthropicClient client,
            AnthropicProperties properties, ObjectMapper objectMapper, JobRunService jobRunService) {
        return new OpusEvaluationStrategy(client, properties, objectMapper, jobRunService);
    }

    /**
     * No-op evaluation strategy — returns null evaluation without calling Claude.
     * Used for wildlife/comfort-only locations.
     *
     * @return a no-op evaluation strategy
     */
    @Bean
    public NoOpEvaluationStrategy noOpEvaluationStrategy() {
        return new NoOpEvaluationStrategy();
    }
}
