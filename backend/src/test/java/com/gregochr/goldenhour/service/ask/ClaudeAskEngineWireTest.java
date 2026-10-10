package com.gregochr.goldenhour.service.ask;

import com.anthropic.client.AnthropicClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.integration.WireMockAnthropicClientTestConfiguration;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.evaluation.AnthropicApiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.not;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ClaudeAskEngine} end to end over a real socket: the real SDK client builds the requests,
 * WireMock answers with the API's own JSON, and the engine reads it back. This is the one place the
 * SDK's request serialisation (tool schemas, tool choice, the assistant's own tool-use block
 * replayed on the next turn, the tool result) and its parsing of a real {@code tool_use} reply are
 * exercised, which a mocked {@code AnthropicApiClient} cannot do.
 */
class ClaudeAskEngineWireTest {

    private static final String PATH = "/v1/messages";
    private static final String WINDOW = "2026-10-05_sunset";

    @RegisterExtension
    static final WireMockExtension WIRE_MOCK = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final ObjectMapper json = new ObjectMapper();
    private final AskJobRunService jobRuns = mock(AskJobRunService.class);
    private final AskProperties properties = new AskProperties();
    private ClaudeAskEngine engine;

    @BeforeEach
    void setUp() {
        WIRE_MOCK.resetAll();
        AnthropicClient client = new WireMockAnthropicClientTestConfiguration()
                .wireMockAnthropicClient("http://localhost:" + WIRE_MOCK.getPort());
        engine = new ClaudeAskEngine(new AnthropicApiClient(client), properties, jobRuns,
                mock(DriveTimeResolver.class), new AskAnswerValidator(),
                new AskPromptBuilder(), new ObjectMapper(), Clock.systemUTC());
        when(jobRuns.dailyRunId()).thenReturn(5L);
        when(jobRuns.accountingAvailable()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    private static String reply(String stopReason, String contentJson) {
        return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-haiku-4-5-20251001\",\"content\":" + contentJson + ","
                + "\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":1200,\"output_tokens\":60}}";
    }

    private static AskSnapshot snapshot() {
        return AskFixtures.snapshotOf(AskFixtures.briefing(List.of(AskFixtures.sunsetDay(AskFixtures.TODAY, null,
                AskFixtures.region("Northumberland", true,
                        AskFixtures.coastal(1L, "Bamburgh", 5, "HIGH", true)))), List.of()));
    }

    @Test
    @DisplayName("a real tool-use conversation: the request carries the tools, tool_choice any and the question; "
            + "the second request replays the assistant's tool call and our result; the answer is read back")
    void toolConversationOverTheWire() throws Exception {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(PATH)).atPriority(2)
                .withRequestBody(not(containing("tool_result")))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(reply("tool_use", "[{\"type\":\"tool_use\",\"id\":\"toolu_1\","
                                + "\"name\":\"rank_spots\",\"input\":{\"limit\":3}}]"))));
        WIRE_MOCK.stubFor(post(urlPathEqualTo(PATH)).atPriority(1)
                .withRequestBody(containing("tool_result"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(reply("tool_use", "[{\"type\":\"tool_use\",\"id\":\"toolu_2\","
                                + "\"name\":\"submit_answer\",\"input\":{\"answerable\":true,"
                                + "\"summary\":\"Bamburgh is best tonight.\",\"picks\":[{\"locationId\":1,"
                                + "\"windowId\":\"" + WINDOW + "\",\"why\":\"Clear and high water.\"}]}}]"))));

        AskRun run = engine.run(new AskQuestion("Best spot tonight?", "best spot tonight", null, AskScope.ALL, "plan"),
                snapshot(), new AskUserContext(7L, UserRole.PRO_USER, true), AskRunOptions.none());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.OK);
        assertThat(run.outcome().turns()).isEqualTo(2);
        assertThat(run.outcome().answer().picks()).singleElement().satisfies(p -> {
            assertThat(p.locationName()).isEqualTo("Bamburgh");
            assertThat(p.why()).isEqualTo("Clear and high water.");
        });

        List<LoggedRequest> requests = WIRE_MOCK.findAll(postRequestedFor(urlPathEqualTo(PATH)));
        assertThat(requests).hasSize(2);
        JsonNode first = json.readTree(requests.get(0).getBodyAsString());
        assertThat(first.get("model").asText()).isEqualTo(EvaluationModel.HAIKU.getModelId());
        assertThat(first.get("max_tokens").asInt()).isEqualTo(600);
        assertThat(first.get("tool_choice").get("type").asText()).isEqualTo("any");
        assertThat(first.get("system").get(0).get("text").asText()).contains("submit_answer");
        assertThat(first.get("messages")).hasSize(1);
        assertThat(first.get("messages").get(0).get("role").asText()).isEqualTo("user");
        assertThat(first.get("messages").get(0).get("content").asText()).isEqualTo("Best spot tonight?");
        JsonNode tools = first.get("tools");
        assertThat(tools).hasSize(5);
        assertThat(tools.get(1).get("name").asText()).isEqualTo("rank_spots");
        assertThat(tools.get(1).get("input_schema").get("type").asText()).isEqualTo("object");
        assertThat(tools.get(1).get("input_schema").get("properties").get("limit").get("maximum").asInt())
                .isEqualTo(AskTools.MAX_SPOTS);
        assertThat(tools.get(1).get("input_schema").get("properties").has("maxDriveMinutes")).isTrue();
        assertThat(tools.get(4).get("name").asText()).isEqualTo("submit_answer");
        assertThat(tools.get(4).get("input_schema").get("required")).extracting(JsonNode::asText)
                .containsExactly("answerable", "summary");

        JsonNode second = json.readTree(requests.get(1).getBodyAsString());
        assertThat(second.get("tool_choice").get("type").asText())
                .as("a later turn must be a tool call too").isEqualTo("any");
        assertThat(first.get("tool_choice").has("disable_parallel_tool_use"))
                .as("parallel tool use stays on").isFalse();
        JsonNode messages = second.get("messages");
        assertThat(messages).hasSize(3);
        assertThat(messages.get(1).get("role").asText()).isEqualTo("assistant");
        JsonNode toolUse = messages.get(1).get("content").get(0);
        assertThat(toolUse.get("type").asText()).isEqualTo("tool_use");
        assertThat(toolUse.get("id").asText()).isEqualTo("toolu_1");
        assertThat(toolUse.get("name").asText()).isEqualTo("rank_spots");
        assertThat(toolUse.get("input").get("limit").asInt()).isEqualTo(3);
        JsonNode toolResult = messages.get(2).get("content").get(0);
        assertThat(messages.get(2).get("role").asText()).isEqualTo("user");
        assertThat(toolResult.get("type").asText()).isEqualTo("tool_result");
        assertThat(toolResult.get("tool_use_id").asText()).isEqualTo("toolu_1");
        assertThat(toolResult.get("content").asText()).contains("Bamburgh").contains(WINDOW);

        ArgumentCaptor<AskJobRunService.Turn> turns = ArgumentCaptor.forClass(AskJobRunService.Turn.class);
        verify(jobRuns, times(2)).recordTurn(turns.capture());
        assertThat(turns.getAllValues()).extracting(AskJobRunService.Turn::usage)
                .containsOnly(new TokenUsage(1200, 60, 0, 0));
        WIRE_MOCK.verify(2, postRequestedFor(urlPathEqualTo(PATH))
                .withHeader("x-api-key", equalTo("test-key-wiremock")));
    }

    @Test
    @DisplayName("a user-less (Ready) request is never offered maxDriveMinutes, on the wire")
    void readyRequestHasNoDriveLimit() throws Exception {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(reply("tool_use", "[{\"type\":\"tool_use\",\"id\":\"toolu_1\","
                        + "\"name\":\"submit_answer\",\"input\":{\"answerable\":false,"
                        + "\"summary\":\"Can't tell.\"}}]"))));

        AskRun run = engine.run(new AskQuestion("Best spot tonight?", "best spot tonight", null, AskScope.ALL, "plan"),
                snapshot(), AskUserContext.userLess(), AskRunOptions.ready(9L, null));

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.CANT);
        String body = WIRE_MOCK.findAll(postRequestedFor(urlPathEqualTo(PATH))).getFirst().getBodyAsString();
        assertThat(body).doesNotContain("maxDriveMinutes");
    }

    @Test
    @DisplayName("a refusal over the wire is a FAILED outcome whose tokens are still logged")
    void refusalOverTheWire() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(reply("refusal", "[]"))));

        AskRun run = engine.run(new AskQuestion("Best spot tonight?", "best spot tonight", null, AskScope.ALL, "plan"),
                snapshot(), new AskUserContext(7L, UserRole.PRO_USER, true), AskRunOptions.none());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.reason()).contains("refused");
    }

    @Test
    @DisplayName("a response slower than the call timeout fails the question inside the deadline: one request, "
            + "no SDK retry, well under the five seconds the server would take")
    void slowServerFailsInsideTheDeadline() {
        properties.setCallTimeoutSeconds(1);
        properties.setDeadlineSeconds(2);
        WIRE_MOCK.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withFixedDelay(5_000).withStatus(200)
                .withBody(reply("end_turn", "[]"))));

        long started = System.nanoTime();
        AskRun run = engine.run(new AskQuestion("Best spot tonight?", "best spot tonight", null, AskScope.ALL, "plan"),
                snapshot(), new AskUserContext(7L, UserRole.PRO_USER, true), AskRunOptions.none());
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(elapsedMs).isLessThan(3_500);
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo(PATH)));
    }
}
