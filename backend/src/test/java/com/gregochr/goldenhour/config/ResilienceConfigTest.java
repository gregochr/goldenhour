package com.gregochr.goldenhour.config;

import com.anthropic.errors.AnthropicServiceException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    @DisplayName("Anthropic circuit breaker is configured as the run-level tests assume: opens at a 50% failure "
            + "rate once 5 calls are in a window of 10, stays open 60s, lets 3 probes through")
    void anthropicCircuitBreakerSettings() {
        CircuitBreakerConfig config = circuitBreakerRegistry.circuitBreaker("anthropic").getCircuitBreakerConfig();

        assertThat(config.getSlidingWindowSize()).isEqualTo(10);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
        assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(60_000L);
        assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
    }

    /** A fresh breaker built from the application's real "anthropic" configuration (YAML plus customizer). */
    private CircuitBreaker freshAnthropicBreaker() {
        return CircuitBreaker.of("anthropic-probe",
                circuitBreakerRegistry.circuitBreaker("anthropic").getCircuitBreakerConfig());
    }

    private static AnthropicServiceException serviceError(int status) {
        AnthropicServiceException ex = mock(AnthropicServiceException.class);
        when(ex.statusCode()).thenReturn(status);
        return ex;
    }

    /** Runs {@code calls} failing calls through the breaker, each answering {@code status}. */
    private static void failCalls(CircuitBreaker breaker, int status, int calls) {
        AnthropicServiceException failure = serviceError(status);
        for (int i = 0; i < calls && breaker.tryAcquirePermission(); i++) {
            breaker.onError(0, TimeUnit.NANOSECONDS, failure);
        }
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {401, 403})
    @DisplayName("a rejected key never opens the Anthropic breaker, however many calls answer it (3 x the window)")
    void rejectedKey_neverOpensTheAnthropicBreaker(int status) {
        CircuitBreaker breaker = freshAnthropicBreaker();

        failCalls(breaker, status, 30);

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.tryAcquirePermission()).as("the next call is still permitted").isTrue();
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(breaker.getMetrics().getNumberOfNotPermittedCalls()).isZero();
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {400, 404, 429, 500, 529})
    @DisplayName("any other status still opens it: the same number of failures that left 401 closed")
    void otherFailures_stillOpenTheAnthropicBreaker(int status) {
        CircuitBreaker breaker = freshAnthropicBreaker();

        failCalls(breaker, status, 30);

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.tryAcquirePermission()).as("the next call is refused").isFalse();
    }

    @Test
    @DisplayName("rejected-key calls interleaved with real failures do not dilute or count toward the failure rate")
    void rejectedKeyCalls_areNeitherFailureNorSuccess() {
        CircuitBreaker breaker = freshAnthropicBreaker();

        // Four real failures: one short of the five calls the breaker needs before it judges the rate.
        failCalls(breaker, 500, 4);
        failCalls(breaker, 401, 20);

        // The twenty rejected calls are neither failures (the fifth call would have opened it) nor
        // successes (which would pad the window and hold the rate under 50% later).
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(4);
        assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isZero();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // One more real failure is the fifth call and the rate is 100%: it opens.
        failCalls(breaker, 500, 1);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
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
    @DisplayName("Anthropic-batch retry instance exists with 4 max attempts, separate from "
            + "\"anthropic\"")
    void anthropicBatchRetryConfigured() {
        var retry = retryRegistry.retry("anthropic-batch");
        assertThat(retry).isNotNull();
        assertThat(retry.getRetryConfig().getMaxAttempts()).isEqualTo(4);
        assertThat(retry.getRetryConfig().getExceptionPredicate()).isNotNull();
    }

    @Test
    @DisplayName("Anthropic-batch has no circuit breaker of its own — it must not share tripped "
            + "state with the per-location synchronous \"anthropic\" breaker")
    void anthropicBatchHasNoDedicatedCircuitBreaker() {
        assertThat(circuitBreakerRegistry.getAllCircuitBreakers())
                .extracting(cb -> cb.getName())
                .doesNotContain("anthropic-batch");
    }

    @Test
    @DisplayName("Open-Meteo retry predicate is wired")
    void openMeteoRetryPredicateWired() {
        var retry = retryRegistry.retry("open-meteo");
        assertThat(retry.getRetryConfig().getExceptionPredicate()).isNotNull();
    }
}
