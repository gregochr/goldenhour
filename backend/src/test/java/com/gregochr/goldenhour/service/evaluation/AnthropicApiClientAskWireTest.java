package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageCreateParams;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.gregochr.goldenhour.integration.WireMockAnthropicClientTestConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AnthropicApiClient#createAskMessage} against a real SDK client over a real socket (WireMock,
 * no Docker): the two properties the Ask time budget stands on, which a mocked client cannot show.
 * The Resilience4j annotations are not woven here (no Spring), so what is counted is HTTP attempts
 * and elapsed time made by the SDK alone.
 */
class AnthropicApiClientAskWireTest {

    private static final int HTTP_SERVER_ERROR = 500;

    @RegisterExtension
    static final WireMockExtension WIRE_MOCK = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private AnthropicApiClient api;

    /**
     * The first request in a JVM spends a second or two loading the HTTP and JSON classes, and that
     * time counts against the call's own timeout (measured: 2.1 s for a 400 ms timeout, then 403 ms
     * every time after). A timing assertion made cold would measure class loading, so one throwaway
     * call is made first.
     */
    @BeforeAll
    static void warmUp() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/messages")).willReturn(aResponse().withStatus(200)
                .withBody("{}")));
        AnthropicClient client = new WireMockAnthropicClientTestConfiguration()
                .wireMockAnthropicClient("http://localhost:" + WIRE_MOCK.getPort());
        new AnthropicApiClient(client).createAskMessage(params(), RequestOptions.none());
    }

    @BeforeEach
    void setUp() {
        WIRE_MOCK.resetAll();
        AnthropicClient client = new WireMockAnthropicClientTestConfiguration()
                .wireMockAnthropicClient("http://localhost:" + WIRE_MOCK.getPort());
        api = new AnthropicApiClient(client);
    }

    private static MessageCreateParams params() {
        return MessageCreateParams.builder().model("claude-haiku-4-5-20251001").maxTokens(50)
                .addUserMessage("hello").build();
    }

    @Test
    @DisplayName("the Ask door makes ONE HTTP attempt per call: the SDK's own retries are off, so the ask retry "
            + "instance counts every attempt and a retry cannot multiply a turn's time")
    void askDoorMakesOneAttempt() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/messages"))
                .willReturn(aResponse().withStatus(HTTP_SERVER_ERROR).withBody("{\"type\":\"error\"}")));

        assertThatThrownBy(() -> api.createAskMessage(params(), RequestOptions.none()))
                .isInstanceOf(AnthropicServiceException.class);

        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo("/v1/messages")));
    }

    @Test
    @DisplayName("control: the shared client's own door still gets the SDK's default retries (three attempts), "
            + "so the single attempt above is the derived client's doing and nothing else changed")
    void sharedDoorKeepsTheSdkRetries() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/messages"))
                .willReturn(aResponse().withStatus(HTTP_SERVER_ERROR).withBody("{\"type\":\"error\"}")));

        assertThatThrownBy(() -> api.createMessage(params())).isInstanceOf(AnthropicServiceException.class);

        WIRE_MOCK.verify(3, postRequestedFor(urlPathEqualTo("/v1/messages")));
    }

    @Test
    @DisplayName("the per-call timeout is real: a response that would take three seconds is abandoned at the "
            + "400 ms the caller asked for, and is not retried by the SDK")
    void perCallTimeoutIsEnforced() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/messages"))
                .willReturn(aResponse().withFixedDelay(3_000).withStatus(200).withBody("{}")));
        RequestOptions options = RequestOptions.builder().timeout(Duration.ofMillis(400)).build();

        long started = System.nanoTime();
        assertThatThrownBy(() -> api.createAskMessage(params(), options))
                .isInstanceOf(AnthropicIoException.class);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(elapsedMs).as("elapsed").isLessThan(2_000);
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo("/v1/messages")));
    }

    @Test
    @DisplayName("the per-call timeout is the whole call, not an idle gap: a body trickled out over three "
            + "seconds is cut off at the timeout too")
    void timeoutBoundsTheWholeCall() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/messages"))
                .willReturn(aResponse().withStatus(200).withBody("{\"id\": \"msg\"}")
                        .withChunkedDribbleDelay(10, 3_000)));
        RequestOptions options = RequestOptions.builder().timeout(Duration.ofMillis(500)).build();

        long started = System.nanoTime();
        assertThatThrownBy(() -> api.createAskMessage(params(), options)).isInstanceOf(RuntimeException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(2_500);
    }
}
