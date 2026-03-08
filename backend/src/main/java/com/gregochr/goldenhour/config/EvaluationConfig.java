package com.gregochr.goldenhour.config;

import tools.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.service.evaluation.AnthropicApiClient;
import com.gregochr.goldenhour.service.evaluation.ClaudeEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.NoOpEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.PromptBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Wires the evaluation strategy map and supporting infrastructure.
 *
 * <p>Each {@link EvaluationModel} maps to an {@link EvaluationStrategy}: the three Claude
 * models use {@link ClaudeEvaluationStrategy} with different model identifiers, and WILDLIFE
 * uses {@link NoOpEvaluationStrategy}. {@link com.gregochr.goldenhour.service.EvaluationService}
 * looks up the correct strategy by model at call time, optionally wrapping it with a
 * {@link com.gregochr.goldenhour.service.evaluation.MetricsLoggingDecorator}.
 */
@Configuration
public class EvaluationConfig {

    /**
     * Shared prompt builder used by all Claude-based strategies.
     *
     * @return a prompt builder instance
     */
    @Bean
    public PromptBuilder promptBuilder() {
        return new PromptBuilder();
    }

    /**
     * Maps each {@link EvaluationModel} to its evaluation strategy.
     *
     * @param anthropicApiClient resilient Anthropic API client
     * @param promptBuilder      shared prompt builder
     * @param objectMapper       Jackson mapper
     * @return immutable map from model to strategy
     */
    @Bean
    public Map<EvaluationModel, EvaluationStrategy> evaluationStrategies(
            AnthropicApiClient anthropicApiClient,
            PromptBuilder promptBuilder,
            ObjectMapper objectMapper) {
        return Map.of(
                EvaluationModel.HAIKU,
                new ClaudeEvaluationStrategy(anthropicApiClient, promptBuilder,
                        objectMapper, EvaluationModel.HAIKU),
                EvaluationModel.SONNET,
                new ClaudeEvaluationStrategy(anthropicApiClient, promptBuilder,
                        objectMapper, EvaluationModel.SONNET),
                EvaluationModel.OPUS,
                new ClaudeEvaluationStrategy(anthropicApiClient, promptBuilder,
                        objectMapper, EvaluationModel.OPUS),
                EvaluationModel.WILDLIFE,
                new NoOpEvaluationStrategy());
    }
}
