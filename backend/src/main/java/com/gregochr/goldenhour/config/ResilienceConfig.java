package com.gregochr.goldenhour.config;

import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import io.github.resilience4j.common.retry.configuration.RetryConfigCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers custom exception predicates for Resilience4j retry and circuit breaker instances.
 *
 * <p>Each customizer enriches the YAML-configured instance with a domain-specific exception
 * predicate: retry instances so that only retryable errors trigger retries, the {@code anthropic}
 * circuit breaker so that a rejected API key does not count toward opening it.
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
     * Customises the "anthropic" circuit breaker with {@link ClaudeBreakerIgnorePredicate}, so that
     * HTTP 401 and 403 (a rejected API key) are ignored: not a failure, not a success. A rejected key
     * is not an outage, and counting it opened the breaker for a minute after the first wave of calls.
     *
     * <p>Done in code rather than YAML so no per-host {@code application.yml} needs editing: the
     * window, threshold and wait come from YAML, this predicate is added on top. Like the retry
     * customizers it applies to an instance declared under
     * {@code resilience4j.circuitbreaker.instances} (the {@code local}, {@code prod} and example
     * files all declare it).
     *
     * @return a customizer that makes the breaker ignore a rejected key
     */
    @Bean
    public CircuitBreakerConfigCustomizer anthropicCircuitBreakerCustomizer() {
        return CircuitBreakerConfigCustomizer.of("anthropic", builder ->
                builder.ignoreException(new ClaudeBreakerIgnorePredicate()));
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
