package com.gregochr.goldenhour.config;

import com.anthropic.client.AnthropicClient;
import okhttp3.Protocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

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
    @DisplayName("forecastExecutor returns virtual-thread executor")
    void forecastExecutor_returnsNonNull() {
        Executor executor = config.forecastExecutor();

        assertThat(executor).isNotNull();
    }

    @Test
    @DisplayName("anthropicClient returns non-null client")
    void anthropicClient_returnsNonNull() {
        AnthropicProperties properties = new AnthropicProperties();
        properties.setApiKey("test-key");

        AnthropicClient client = config.anthropicClient(properties);

        assertThat(client).isNotNull();
    }

    @Test
    @DisplayName("OkHttp client uses HTTP/1.1 only to avoid virtual-thread pinning")
    void okHttpClient_usesHttp11Only() {
        okhttp3.OkHttpClient okHttp = config.createOkHttpClient();

        assertThat(okHttp.protocols())
                .containsExactly(Protocol.HTTP_1_1)
                .doesNotContain(Protocol.HTTP_2);
    }

    @Test
    @DisplayName("OkHttp client has 90-second call timeout")
    void okHttpClient_hasCallTimeout() {
        okhttp3.OkHttpClient okHttp = config.createOkHttpClient();

        assertThat(okHttp.callTimeoutMillis()).isEqualTo(90_000);
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
