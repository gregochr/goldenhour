package com.gregochr.goldenhour.integration;

import com.anthropic.backends.AnthropicBackend;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientImpl;
import com.anthropic.core.ClientOptions;
import okhttp3.ConnectionPool;
import okhttp3.Protocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Replaces the production {@code AnthropicClient} bean with one whose backend
 * points at a WireMock server URL injected at runtime via
 * {@code photocast.test.anthropic-base-url}.
 *
 * <p>The WireMock URL is set by {@code IntegrationTestBase}'s
 * {@code @DynamicPropertySource} hook against the dynamic port chosen by the
 * {@code WireMockExtension}. Production code remains untouched — the override
 * is scoped to the integration-test classpath via {@code @TestConfiguration}.
 *
 * <p>The OkHttp client mirrors {@code AppConfig.createOkHttpClient()} (HTTP/1.1,
 * connection pool, 90 s call timeout) so request behaviour is otherwise
 * identical to production.
 */
@TestConfiguration
public class WireMockAnthropicClientTestConfiguration {

    private static final int CONNECTION_POOL_IDLE = 10;
    private static final int CONNECTION_POOL_KEEP_ALIVE_MINUTES = 2;
    private static final int CALL_TIMEOUT_SECONDS = 90;

    /**
     * Constructs an {@link AnthropicClient} routed to WireMock for integration tests.
     *
     * @param baseUrl the WireMock server's base URL, supplied via
     *                {@code photocast.test.anthropic-base-url}
     * @return a WireMock-routed Anthropic client, marked {@code @Primary} so it
     *         supersedes the production bean for tests that import this config
     */
    @Bean
    @Primary
    public AnthropicClient wireMockAnthropicClient(
            @Value("${photocast.test.anthropic-base-url}") String baseUrl) {
        okhttp3.OkHttpClient okHttp = new okhttp3.OkHttpClient.Builder()
                .protocols(List.of(Protocol.HTTP_1_1))
                .connectionPool(new ConnectionPool(
                        CONNECTION_POOL_IDLE,
                        CONNECTION_POOL_KEEP_ALIVE_MINUTES,
                        TimeUnit.MINUTES))
                .callTimeout(Duration.ofSeconds(CALL_TIMEOUT_SECONDS))
                .build();

        AnthropicBackend backend = AnthropicBackend.builder()
                .apiKey("test-key-wiremock")
                .baseUrl(baseUrl)
                .build();

        com.anthropic.client.okhttp.OkHttpClient httpClient =
                new com.anthropic.client.okhttp.OkHttpClient(okHttp, backend);

        ClientOptions clientOptions = ClientOptions.builder()
                .httpClient(httpClient)
                .build();

        return new AnthropicClientImpl(clientOptions);
    }
}
