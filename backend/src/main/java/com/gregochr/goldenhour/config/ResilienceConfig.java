package com.gregochr.goldenhour.config;

import io.github.resilience4j.common.retry.configuration.RetryConfigCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers custom retry predicates for Resilience4j retry instances.
 *
 * <p>Each customizer enriches the YAML-configured retry instance with a
 * domain-specific exception predicate so that only retryable errors trigger retries.
 */
@Configuration
public class ResilienceConfig {

    /**
     * Customises the "anthropic" retry instance with {@link ClaudeRetryPredicate}.
     *
     * @return a customizer that filters retries to Anthropic-specific transient errors
     */
    @Bean
    public RetryConfigCustomizer anthropicRetryCustomizer() {
        return RetryConfigCustomizer.of("anthropic", builder ->
                builder.retryOnException(new ClaudeRetryPredicate()));
    }

    /**
     * Customises the "open-meteo" retry instance with {@link TransientHttpErrorPredicate}.
     *
     * @return a customizer that filters retries to HTTP 5xx and 429 errors
     */
    @Bean
    public RetryConfigCustomizer openMeteoRetryCustomizer() {
        return RetryConfigCustomizer.of("open-meteo", builder ->
                builder.retryOnException(new TransientHttpErrorPredicate()));
    }
}
