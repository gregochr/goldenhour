package com.gregochr.goldenhour.integration;

import com.anthropic.client.AnthropicClient;
import com.gregochr.goldenhour.config.AppConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the production {@code AnthropicClient} bean with one whose base URL
 * points at a WireMock server URL injected at runtime via
 * {@code photocast.test.anthropic-base-url}.
 *
 * <p>The WireMock URL is set by {@code IntegrationTestBase}'s
 * {@code @DynamicPropertySource} hook against the dynamic port chosen by the
 * {@code WireMockExtension}. Production code remains untouched — the override
 * is scoped to the integration-test classpath via {@code @TestConfiguration}.
 *
 * <p>The client is built from {@link AppConfig#anthropicClientBuilder(String)}, the same
 * builder production uses, so the pool sizing and every other option are identical and only the
 * API key and the base URL differ. Until 2026-10-06 this class hand-assembled the client (to force
 * HTTP/1.1) and had to set the base URL on both the backend and {@code ClientOptions}, because
 * SDK 2.58.0 moved URL resolution out of the transport; the SDK's own builder does that itself.
 * {@code AnthropicClientWireMockRoutingTest} pins the routing without Docker.
 */
@TestConfiguration
public class WireMockAnthropicClientTestConfiguration {

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
        return AppConfig.anthropicClientBuilder("test-key-wiremock")
                .baseUrl(baseUrl)
                .build();
    }
}
