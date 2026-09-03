package com.gregochr.goldenhour.config;

import com.anthropic.client.AnthropicClient;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.gregochr.goldenhour.integration.WireMockAnthropicClientTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Pins that the hand-assembled WireMock-routed {@link AnthropicClient} really
 * does send its requests to WireMock — without Docker.
 *
 * <p>{@code IntegrationTestBaseSmokeTest} makes the same check, but it extends
 * {@code IntegrationTestBase} and so needs a Postgres Testcontainer, which
 * means it runs only in CI: the local gate excludes {@code **&#47;integration/**}.
 * This class lives outside that path on purpose. The routing broke silently on
 * the SDK 2.57.0 → 2.59.0 bump (2.58.0 moved base-URL resolution from the
 * transport into {@code ClientOptions}), and nothing runnable on this machine
 * would have said so.
 *
 * <p>If this test fails, every {@code IntegrationTestBase} subclass is sending
 * its "stubbed" Anthropic traffic to {@code api.anthropic.com}.
 */
class AnthropicClientWireMockRoutingTest {

    private static final int HTTP_NOT_FOUND = 404;

    @RegisterExtension
    static final WireMockExtension WIRE_MOCK = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private AnthropicClient client;

    @BeforeEach
    void buildClientAgainstWireMock() {
        client = new WireMockAnthropicClientTestConfiguration()
                .wireMockAnthropicClient("http://localhost:" + WIRE_MOCK.getPort());
        WIRE_MOCK.stubFor(get(urlPathMatching("/v1/messages/batches/.*"))
                .willReturn(aResponse().withStatus(HTTP_NOT_FOUND)));
    }

    @Test
    @DisplayName("a request through the test client reaches WireMock, not the production host")
    void request_isSentToWireMock() {
        retrieveIgnoringSdkError("routing-check");

        WIRE_MOCK.verify(getRequestedFor(
                urlPathEqualTo("/v1/messages/batches/routing-check")));
    }

    @Test
    @DisplayName("the request still carries the backend's API key once ClientOptions owns the URL")
    void request_carriesBackendApiKey() {
        retrieveIgnoringSdkError("credential-check");

        WIRE_MOCK.verify(getRequestedFor(
                urlPathEqualTo("/v1/messages/batches/credential-check"))
                .withHeader("x-api-key", equalTo("test-key-wiremock")));
    }

    /**
     * The stubbed 404 surfaces as an SDK exception; only the routing matters here.
     */
    private void retrieveIgnoringSdkError(String batchId) {
        try {
            client.messages().batches().retrieve(batchId);
        } catch (RuntimeException expected) {
            // The 404 is the stub's answer. The assertion is on where the request went.
        }
    }
}
