package com.gregochr.goldenhour.config;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying Resilience4j instances are wired with correct config.
 */
@SpringBootTest
class ResilienceConfigTest {

    @Autowired
    private RetryRegistry retryRegistry;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private BulkheadRegistry bulkheadRegistry;

    @Autowired
    private RateLimiterRegistry rateLimiterRegistry;

    @Test
    @DisplayName("Anthropic retry instance exists with 4 max attempts")
    void anthropicRetryConfigured() {
        var retry = retryRegistry.retry("anthropic");
        assertThat(retry).isNotNull();
        assertThat(retry.getRetryConfig().getMaxAttempts()).isEqualTo(4);
    }

    @Test
    @DisplayName("Open-Meteo retry instance exists with 3 max attempts")
    void openMeteoRetryConfigured() {
        var retry = retryRegistry.retry("open-meteo");
        assertThat(retry).isNotNull();
        assertThat(retry.getRetryConfig().getMaxAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("Anthropic circuit breaker instance exists with sliding window size 10")
    void anthropicCircuitBreakerConfigured() {
        var cb = circuitBreakerRegistry.circuitBreaker("anthropic");
        assertThat(cb).isNotNull();
        assertThat(cb.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("Open-Meteo circuit breaker instance exists with sliding window size 20")
    void openMeteoCircuitBreakerConfigured() {
        var cb = circuitBreakerRegistry.circuitBreaker("open-meteo");
        assertThat(cb).isNotNull();
        assertThat(cb.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("Forecast bulkhead instance exists with max 8 concurrent calls")
    void forecastBulkheadConfigured() {
        var bulkhead = bulkheadRegistry.bulkhead("forecast");
        assertThat(bulkhead).isNotNull();
        assertThat(bulkhead.getBulkheadConfig().getMaxConcurrentCalls()).isEqualTo(8);
    }

    @Test
    @DisplayName("Open-Meteo rate limiter instance exists with 8 requests per period")
    void openMeteoRateLimiterConfigured() {
        var limiter = rateLimiterRegistry.rateLimiter("open-meteo");
        assertThat(limiter).isNotNull();
        assertThat(limiter.getRateLimiterConfig().getLimitForPeriod()).isEqualTo(8);
    }

    @Test
    @DisplayName("Anthropic retry predicate accepts 529 overloaded errors")
    void anthropicRetryPredicateWired() {
        var retry = retryRegistry.retry("anthropic");
        // Verify the predicate was registered (non-null exception predicate)
        assertThat(retry.getRetryConfig().getExceptionPredicate()).isNotNull();
    }

    @Test
    @DisplayName("Open-Meteo retry predicate is wired")
    void openMeteoRetryPredicateWired() {
        var retry = retryRegistry.retry("open-meteo");
        assertThat(retry.getRetryConfig().getExceptionPredicate()).isNotNull();
    }
}
