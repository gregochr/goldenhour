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
     * Customises the "anthropic-batch" retry instance with {@link BatchSubmitRetryPredicate}.
     *
     * <p>Deliberately a separate instance from "anthropic" above: batch creation is a handful of
     * calls per cycle (see {@code AnthropicBatchClient}), and must not share retry or circuit
     * breaker state with the per-location synchronous path — which is also why this instance has
     * no circuit breaker of its own.
     *
     * @return a customizer that filters retries to server errors and I/O failures on batch creation
     */
    @Bean
    public RetryConfigCustomizer anthropicBatchRetryCustomizer() {
        return RetryConfigCustomizer.of("anthropic-batch", builder ->
                builder.retryOnException(new BatchSubmitRetryPredicate()));
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
