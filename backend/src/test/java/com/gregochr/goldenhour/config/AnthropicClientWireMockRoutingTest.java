package com.gregochr.goldenhour.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.models.messages.MessageCreateParams;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.gregochr.goldenhour.integration.WireMockAnthropicClientTestConfiguration;
import com.gregochr.goldenhour.service.evaluation.AnthropicApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Pins that the WireMock-routed {@link AnthropicClient} (the production builder plus a base URL)
 * really does send its requests to WireMock — without Docker.
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
    private static final int HTTP_SERVER_ERROR = 500;

    @RegisterExtension
    static final WireMockExtension WIRE_MOCK = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private AnthropicClient client;

    @BeforeEach
    void buildClientAgainstWireMock() {
        WIRE_MOCK.resetAll();
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

    @Test
    @DisplayName("no client-wide call timeout: a request that sets none keeps the SDK's 10 minutes")
    void request_withoutOptions_keepsTheSdkDefaultTimeout() {
        // The old OkHttp client carried a "90 s call timeout" that the SDK overwrote on every
        // request, so it never applied. Setting one on the SDK builder WOULD apply, and would cut
        // off the streamed batch-results downloads, which pass no RequestOptions. The SDK states
        // the call timeout it applied in X-Stainless-Timeout (seconds).
        retrieveIgnoringSdkError("timeout-check");

        WIRE_MOCK.verify(getRequestedFor(
                urlPathEqualTo("/v1/messages/batches/timeout-check"))
                .withHeader("X-Stainless-Timeout", equalTo("600")));
    }

    @Test
    @DisplayName("the Ask door makes one HTTP attempt on a 500; the shared door still gets the SDK's retries")
    void askDoor_hasSdkRetriesOff() {
        // Ask's own Resilience4j retry counts attempts, so the SDK must not add hidden ones under
        // it. This is the real SDK client, not a mock: it proves withOptions(maxRetries(0)) still
        // takes effect on the current SDK. The shared door is the control: left alone, the SDK
        // makes 1 + 2 retries, so "1" above is not a missing stub.
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/messages"))
                .willReturn(aResponse().withStatus(HTTP_SERVER_ERROR)));
        AnthropicApiClient api = new AnthropicApiClient(client);
        MessageCreateParams params = MessageCreateParams.builder()
                .model("claude-haiku-4-5")
                .maxTokens(16)
                .addUserMessage("hello")
                .build();

        try {
            api.createAskMessage(params, RequestOptions.builder().timeout(Duration.ofSeconds(5)).build(), () -> true);
        } catch (RuntimeException expected) {
            // The 500 surfaces as an SDK exception; only the number of attempts matters here.
        }
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo("/v1/messages")));

        WIRE_MOCK.resetRequests();
        try {
            api.createMessage(params);
        } catch (RuntimeException expected) {
            // As above.
        }
        WIRE_MOCK.verify(3, postRequestedFor(urlPathEqualTo("/v1/messages")));
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
