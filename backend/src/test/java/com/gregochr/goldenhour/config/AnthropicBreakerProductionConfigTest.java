package com.gregochr.goldenhour.config;

import com.anthropic.errors.AnthropicServiceException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The {@code anthropic} breaker as built from the committed profile YAML plus the real customizer (see
 * {@link ProductionAnthropicBreaker}), with no Spring context: its settings, and how it behaves half-open.
 */
class AnthropicBreakerProductionConfigTest {

    private static AnthropicServiceException serviceError(int status) {
        AnthropicServiceException ex = mock(AnthropicServiceException.class);
        when(ex.statusCode()).thenReturn(status);
        return ex;
    }

    private static void probeFails(CircuitBreaker breaker, int status) {
        AnthropicServiceException failure = serviceError(status);
        assertThat(breaker.tryAcquirePermission()).as("a probe is permitted").isTrue();
        breaker.onError(0, TimeUnit.NANOSECONDS, failure);
    }

    private static void probeSucceeds(CircuitBreaker breaker) {
        assertThat(breaker.tryAcquirePermission()).as("a probe is permitted").isTrue();
        breaker.onSuccess(0, TimeUnit.NANOSECONDS);
    }

    private static CircuitBreaker halfOpenBreaker() {
        CircuitBreaker breaker = ProductionAnthropicBreaker.newBreaker();
        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        return breaker;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"application-prod.yml", "application-local.yml", "application-example.yml"})
    @DisplayName("every committed profile gives the anthropic breaker the settings the tests rely on")
    void committedProfiles_agreeOnTheAnthropicBreakerSettings(String profile) {
        CircuitBreakerConfig config = ProductionAnthropicBreaker.config(profile);

        assertThat(config.getSlidingWindowSize()).isEqualTo(10);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
        assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(60_000L);
        assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
        assertThat(config.getIgnoreExceptionPredicate().test(serviceError(401))).as("401 ignored").isTrue();
        assertThat(config.getIgnoreExceptionPredicate().test(serviceError(500))).as("500 counted").isFalse();
    }

    @Test
    @DisplayName("half-open: rejected-key probes return their permit, so the breaker is neither closed nor "
            + "reopened by them and never gets stuck refusing calls (10 probes against 3 permitted)")
    void halfOpen_rejectedKeyProbes_doNotStickAndDoNotDecide() {
        CircuitBreaker breaker = halfOpenBreaker();

        for (int i = 0; i < 10; i++) {
            probeFails(breaker, 401);
        }
        for (int i = 0; i < 5; i++) {
            probeFails(breaker, 403);
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.tryAcquirePermission()).as("a further call is still permitted").isTrue();
        assertThat(breaker.getMetrics().getNumberOfNotPermittedCalls()).isZero();
    }

    @Test
    @DisplayName("half-open: rejected-key probes in between do not stop a successful probe sequence closing it")
    void halfOpen_successfulProbes_closeItDespiteRejectedKeyProbes() {
        CircuitBreaker breaker = halfOpenBreaker();

        probeSucceeds(breaker);
        probeFails(breaker, 401);
        probeFails(breaker, 401);
        probeSucceeds(breaker);
        assertThat(breaker.getState()).as("two of three counted probes so far")
                .isEqualTo(CircuitBreaker.State.HALF_OPEN);
        probeFails(breaker, 403);
        probeSucceeds(breaker);

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("half-open: a real failure on a probe still reopens it (the contrast to a rejected key)")
    void halfOpen_realFailures_stillReopenIt() {
        CircuitBreaker breaker = halfOpenBreaker();

        probeFails(breaker, 500);
        probeFails(breaker, 500);
        probeFails(breaker, 500);

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
