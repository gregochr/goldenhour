package com.gregochr.goldenhour.config;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.gregochr.goldenhour.service.evaluation.AnthropicApiClient;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.common.CompositeCustomizer;
import io.github.resilience4j.common.bulkhead.configuration.BulkheadConfigCustomizer;
import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import io.github.resilience4j.common.retry.configuration.RetryConfigCustomizer;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.spring6.bulkhead.configure.BulkheadConfigurationProperties;
import io.github.resilience4j.spring6.circuitbreaker.configure.CircuitBreakerConfigurationProperties;
import io.github.resilience4j.spring6.retry.configure.RetryConfigurationProperties;
import io.github.resilience4j.core.functions.Either;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The {@code ask} Resilience4j instances as the COMMITTED YAML declares them, with the real
 * customizers applied, in every profile file that declares resilience at all. No Spring context and
 * no copied numbers: the settings are bound onto Resilience4j's own properties classes, so a profile
 * that drifts (or one that forgets the instance and would silently run on Resilience4j's defaults,
 * three attempts that retry everything) fails here.
 *
 * <p>The files are the three committed profiles plus the test profile ({@code application.yml} on
 * the test classpath; the host's gitignored production file is never read).
 */
class AskResilienceYamlTest {

    private static final ResilienceConfig CUSTOMIZERS = new ResilienceConfig();

    private static List<PropertySource<?>> load(String yaml) throws IOException {
        return new YamlPropertySourceLoader().load(yaml, new ClassPathResource(yaml));
    }

    private static <T> T bind(String yaml, String prefix, Class<T> type) throws IOException {
        return new Binder(ConfigurationPropertySources.from(load(yaml)))
                .bind(prefix, Bindable.of(type)).orElseThrow(() -> new IllegalStateException(
                        "No " + prefix + " section in " + yaml));
    }

    private static AnthropicServiceException status(int code, String message) {
        AnthropicServiceException ex = mock(AnthropicServiceException.class);
        when(ex.statusCode()).thenReturn(code);
        when(ex.getMessage()).thenReturn(message);
        return ex;
    }

    private static RetryConfig retry(String yaml) throws IOException {
        RetryConfigurationProperties props = bind(yaml, "resilience4j.retry", RetryConfigurationProperties.class);
        return props.createRetryConfig("ask", new CompositeCustomizer<>(
                List.<RetryConfigCustomizer>of(CUSTOMIZERS.askRetryCustomizer())));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"application-local.yml", "application-example.yml", "application-prod.yml",
            "application.yml"})
    @DisplayName("retry: two attempts with a short wait, on server errors only, never the content-filter 400, "
            + "a rate limit, a client error or an I/O failure")
    void retryInstance(String yaml) throws Exception {
        RetryConfig config = retry(yaml);

        assertThat(config.getMaxAttempts()).isEqualTo(2);
        long expectedWaitMs = yaml.equals("application.yml") ? 10 : 500;
        assertThat(config.getIntervalBiFunction().apply(1, Either.left(new IllegalStateException())))
                .isEqualTo(expectedWaitMs);
        for (int retried : new int[] {500, 502, 503, 504, 529, 599}) {
            assertThat(config.getExceptionPredicate().test(status(retried, "x"))).as("HTTP %d", retried).isTrue();
        }
        for (int notRetried : new int[] {200, 400, 401, 403, 404, 408, 409, 413, 429, 499, 600}) {
            assertThat(config.getExceptionPredicate().test(status(notRetried, "x"))).as("HTTP %d", notRetried)
                    .isFalse();
        }
        assertThat(config.getExceptionPredicate().test(status(400, "output blocked by content filtering policy")))
                .as("the content-filter 400 the pipeline retries").isFalse();
        assertThat(config.getExceptionPredicate().test(new SocketTimeoutException("slow"))).isFalse();
        assertThat(config.getExceptionPredicate().test(new AnthropicIoException("io", new IOException("x"))))
                .isFalse();
        assertThat(config.getExceptionPredicate().test(new IllegalStateException("bug"))).isFalse();
        assertThat(config.getExceptionPredicate().test(new AnthropicApiClient.CallRefusedException()))
                .as("a gate refusal is never retried").isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"application-local.yml", "application-example.yml", "application-prod.yml",
            "application.yml"})
    @DisplayName("breaker: its own window of 10 (judged from 5 calls at 50%), open 30s, 2 probes, no health "
            + "indicator, and blind to a rejected key and to a full bulkhead")
    void breakerInstance(String yaml) throws Exception {
        CircuitBreakerConfigurationProperties props = bind(yaml, "resilience4j.circuitbreaker",
                CircuitBreakerConfigurationProperties.class);
        CircuitBreakerConfig config = props.createCircuitBreakerConfig("ask", props.getInstances().get("ask"),
                new CompositeCustomizer<>(List.<CircuitBreakerConfigCustomizer>of(
                        CUSTOMIZERS.askCircuitBreakerCustomizer())));

        assertThat(config.getSlidingWindowSize()).isEqualTo(10);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
        assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(30_000L);
        assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(2);
        assertThat(props.getInstances().get("ask").getRegisterHealthIndicator()).isFalse();
        assertThat(config.getIgnoreExceptionPredicate().test(status(401, "x"))).isTrue();
        assertThat(config.getIgnoreExceptionPredicate().test(status(403, "x"))).isTrue();
        assertThat(config.getIgnoreExceptionPredicate().test(
                BulkheadFullException.createBulkheadFullException(Bulkhead.ofDefaults("probe")))).isTrue();
        assertThat(config.getIgnoreExceptionPredicate().test(new AnthropicApiClient.CallRefusedException()))
                .as("a refused attempt made no request").isTrue();
        for (int counted : new int[] {400, 404, 429, 500, 529}) {
            assertThat(config.getIgnoreExceptionPredicate().test(status(counted, "x"))).as("HTTP %d", counted)
                    .isFalse();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"application-local.yml", "application-example.yml", "application-prod.yml",
            "application.yml"})
    @DisplayName("bulkhead: four concurrent conversations, a fifth waits two seconds")
    void bulkheadInstance(String yaml) throws Exception {
        BulkheadConfigurationProperties props = bind(yaml, "resilience4j.bulkhead",
                BulkheadConfigurationProperties.class);
        BulkheadConfig config = props.createBulkheadConfig(props.getInstances().get("ask"),
                new CompositeCustomizer<>(List.<BulkheadConfigCustomizer>of()), "ask");

        assertThat(config.getMaxConcurrentCalls()).isEqualTo(4);
        assertThat(config.getMaxWaitDuration()).isEqualTo(Duration.ofSeconds(2));
    }
}
