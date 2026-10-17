package com.gregochr.goldenhour.config;

import com.anthropic.client.AnthropicClient;
import okhttp3.Protocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AppConfig} bean factory methods.
 *
 * <p>Verifies that each bean is constructed with the correct configuration,
 * killing PIT mutations on return values and void method calls.
 */
class AppConfigTest {

    private final AppConfig config = new AppConfig();

    @Test
    @DisplayName("restClient returns non-null RestClient")
    void restClient_returnsNonNull() {
        RestClient result = config.restClient();

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("outbound request factory carries both a connect and a read timeout")
    void timeoutRequestFactory_setsBothTimeouts() {
        // The shared RestClient was built by RestClient.create() until 2026-08-26 — no request
        // factory, so no read timeout, so a peer that accepted a connection and then went quiet
        // held the calling thread forever. restClient_returnsNonNull above passed throughout:
        // "non-null" is true of the untimed client too, which is why it never caught this.
        SimpleClientHttpRequestFactory factory = AppConfig.timeoutRequestFactory();

        assertThat(ReflectionTestUtils.getField(factory, "connectTimeout"))
                .isEqualTo((int) AppConfig.CONNECT_TIMEOUT.toMillis());
        assertThat(ReflectionTestUtils.getField(factory, "readTimeout"))
                .isEqualTo((int) AppConfig.READ_TIMEOUT.toMillis());
    }

    @Test
    @DisplayName("both timeouts are bounded, so a stalled peer cannot pin a thread indefinitely")
    void timeouts_areBoundedAndNonZero() {
        // Zero means "infinite" to SimpleClientHttpRequestFactory, so a non-zero assertion is the
        // one that matters; the upper bound keeps a future edit from re-creating the hang by
        // setting something like an hour.
        assertThat(AppConfig.CONNECT_TIMEOUT).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(30));
        assertThat(AppConfig.READ_TIMEOUT).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("forecastExecutor returns virtual-thread executor")
    void forecastExecutor_returnsNonNull() {
        Executor executor = config.forecastExecutor();

        assertThat(executor).isNotNull();
    }

    @Test
    @DisplayName("anthropicClient keeps a pool of 10 idle connections for 2 minutes")
    void anthropicClient_poolIsSizedForParallelRuns() {
        AnthropicClient client = config.anthropicClient(properties("test-key"));
        Object sdkTransport = sdkTransportOf(client);

        // The SDK transport records what it was asked for; the OkHttp pool is what enforces it.
        assertThat(ReflectionTestUtils.getField(sdkTransport, "maxIdleConnections")).isEqualTo(10);
        assertThat(ReflectionTestUtils.getField(sdkTransport, "keepAliveDuration"))
                .isEqualTo(Duration.ofMinutes(2));
        Object pool = ReflectionTestUtils.getField(okHttpOf(sdkTransport).connectionPool(), "delegate");
        assertThat(ReflectionTestUtils.getField(pool, "maxIdleConnections")).isEqualTo(10);
        assertThat(ReflectionTestUtils.getField(pool, "keepAliveDurationNs"))
                .isEqualTo(Duration.ofMinutes(2).toNanos());
    }

    @Test
    @DisplayName("anthropicClient does not force HTTP/1.1: the SDK's HTTP/2 default is back")
    void anthropicClient_doesNotForceHttp11() {
        // HTTP/1.1 was forced until 2026-10-06 against a Java 21 virtual-thread pinning deadlock;
        // Java 25 (JEP 491) ended it. If this fails, someone has put the protocol list back.
        okhttp3.OkHttpClient okHttp = okHttpOf(sdkTransportOf(config.anthropicClient(properties("test-key"))));

        assertThat(okHttp.protocols()).contains(Protocol.HTTP_2, Protocol.HTTP_1_1);
    }

    private static AnthropicProperties properties(String apiKey) {
        AnthropicProperties properties = new AnthropicProperties();
        properties.setApiKey(apiKey);
        return properties;
    }

    /** The SDK's own OkHttp-backed transport, reached through its client's internals. */
    private static Object sdkTransportOf(AnthropicClient client) {
        Object clientOptions = ReflectionTestUtils.getField(client, "clientOptions");
        Object closing = ReflectionTestUtils.getField(clientOptions, "originalHttpClient");
        return ReflectionTestUtils.getField(closing, "httpClient");
    }

    /** The OkHttp client underneath the SDK's transport. */
    private static okhttp3.OkHttpClient okHttpOf(Object sdkTransport) {
        return (okhttp3.OkHttpClient) ReflectionTestUtils.getField(sdkTransport, "okHttpClient");
    }

    @Test
    @DisplayName("openMeteoForecastApi returns non-null proxy")
    void openMeteoForecastApi_returnsNonNull() {
        assertThat(config.openMeteoForecastApi()).isNotNull();
    }

    @Test
    @DisplayName("openMeteoAirQualityApi returns non-null proxy")
    void openMeteoAirQualityApi_returnsNonNull() {
        assertThat(config.openMeteoAirQualityApi()).isNotNull();
    }
}
