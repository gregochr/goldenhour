package com.gregochr.goldenhour.config;

import com.anthropic.client.AnthropicClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.HaikuEvaluationStrategy;
import com.gregochr.goldenhour.service.evaluation.SonnetEvaluationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Wires the correct {@link EvaluationStrategy} based on the active Spring profile.
 *
 * <p>The {@code lite} profile selects Haiku for lower cost; all other profiles
 * (including the default) select Sonnet for higher accuracy.
 */
@Configuration
public class EvaluationConfig {

    /**
     * Haiku-based evaluation strategy, activated when the {@code lite} profile is active.
     *
     * @param client       Anthropic API client
     * @param properties   Anthropic configuration
     * @param objectMapper Jackson mapper
     * @return a Haiku evaluation strategy
     */
    @Bean
    @Profile("lite")
    public EvaluationStrategy liteStrategy(AnthropicClient client,
            AnthropicProperties properties, ObjectMapper objectMapper) {
        return new HaikuEvaluationStrategy(client, properties, objectMapper);
    }

    /**
     * Sonnet-based evaluation strategy, activated for all profiles except {@code lite}.
     *
     * @param client       Anthropic API client
     * @param properties   Anthropic configuration
     * @param objectMapper Jackson mapper
     * @return a Sonnet evaluation strategy
     */
    @Bean
    @Profile("!lite")
    public EvaluationStrategy proStrategy(AnthropicClient client,
            AnthropicProperties properties, ObjectMapper objectMapper) {
        return new SonnetEvaluationStrategy(client, properties, objectMapper);
    }
}
