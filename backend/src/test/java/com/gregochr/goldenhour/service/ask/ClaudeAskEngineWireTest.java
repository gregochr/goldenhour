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
                new AskPromptBuilder(), new ObjectMapper(), Clock.systemUTC(), AskLocationNames.NONE);
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
        assertThat(tools).hasSize(7);
        assertThat(tools.get(1).get("name").asText()).isEqualTo("rank_spots");
        assertThat(tools.get(2).get("name").asText()).isEqualTo("look_up_place");
        assertThat(tools.get(2).get("input_schema").get("required").get(0).asText()).isEqualTo("name");
        assertThat(tools.get(1).get("input_schema").get("type").asText()).isEqualTo("object");
        assertThat(tools.get(1).get("input_schema").get("properties").get("limit").get("maximum").asInt())
                .isEqualTo(AskTools.MAX_SPOTS);
        assertThat(tools.get(1).get("input_schema").get("properties").has("maxDriveMinutes")).isTrue();
        assertThat(tools.get(6).get("name").asText()).isEqualTo("submit_answer");
        assertThat(tools.get(6).get("input_schema").get("required")).extracting(JsonNode::asText)
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
        assertThat(messages.get(2).get("content")).as("turn 2 of 4 is not the last: the tool result stands alone")
                .hasSize(1);
        assertThat(requests.get(0).getBodyAsString()).doesNotContain(ClaudeAskEngine.LAST_TURN_LINE);
        assertThat(requests.get(1).getBodyAsString()).doesNotContain(ClaudeAskEngine.LAST_TURN_LINE);

        ArgumentCaptor<AskJobRunService.Turn> turns = ArgumentCaptor.forClass(AskJobRunService.Turn.class);
        verify(jobRuns, times(2)).recordTurn(turns.capture());
        assertThat(turns.getAllValues()).extracting(AskJobRunService.Turn::usage)
                .containsOnly(new TokenUsage(1200, 60, 0, 0));
        WIRE_MOCK.verify(2, postRequestedFor(urlPathEqualTo(PATH))
                .withHeader("x-api-key", equalTo("test-key-wiremock")));
    }

    /** Stubs a model that explores with {@code list_windows} until it is told it is on its last turn. */
    private void stubExplorerThen(String lastTurnContent, String lastTurnStopReason) {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(PATH)).atPriority(2)
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(reply("tool_use", "[{\"type\":\"tool_use\",\"id\":\"toolu_x\","
                                + "\"name\":\"list_windows\",\"input\":{}}]"))));
        WIRE_MOCK.stubFor(post(urlPathEqualTo(PATH)).atPriority(1)
                .withRequestBody(containing("This is your last turn"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(reply(lastTurnStopReason, lastTurnContent))));
    }

    private AskRun runTyped() {
        return engine.run(new AskQuestion("Clear skies on the coast?", "clear skies on the coast", null,
                AskScope.ALL, "map"), snapshot(), new AskUserContext(7L, UserRole.PRO_USER, true),
                AskRunOptions.none());
    }

    @Test
    @DisplayName("the last of four turns forces submit_answer and its tool-result message ends with the last-turn "
            + "line; turns 1 to 3 stay tool_choice any and carry no such line")
    void lastTurnForcesSubmitAnswer() throws Exception {
        stubExplorerThen("[{\"type\":\"tool_use\",\"id\":\"toolu_s\",\"name\":\"submit_answer\","
                + "\"input\":{\"answerable\":true,\"summary\":\"Bamburgh is clear.\",\"picks\":[{"
                + "\"locationId\":1,\"windowId\":\"" + WINDOW + "\",\"why\":\"Clear.\"}]}}]", "tool_use");

        AskRun run = runTyped();

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.OK);
        assertThat(run.outcome().turns()).isEqualTo(4);
        List<LoggedRequest> requests = WIRE_MOCK.findAll(postRequestedFor(urlPathEqualTo(PATH)));
        assertThat(requests).hasSize(4);
        for (int i = 0; i < 3; i++) {
            JsonNode earlier = json.readTree(requests.get(i).getBodyAsString());
            assertThat(earlier.get("tool_choice").get("type").asText()).as("turn %d", i + 1).isEqualTo("any");
            assertThat(requests.get(i).getBodyAsString()).doesNotContain(ClaudeAskEngine.LAST_TURN_LINE);
        }
        JsonNode last = json.readTree(requests.get(3).getBodyAsString());
        assertThat(last.get("tool_choice").get("type").asText()).isEqualTo("tool");
        assertThat(last.get("tool_choice").get("name").asText()).isEqualTo("submit_answer");
        assertThat(last.get("tool_choice").has("disable_parallel_tool_use")).isFalse();
        assertThat(last.get("tools")).as("the tools stay on the request").hasSize(7);
        JsonNode messages = last.get("messages");
        assertThat(messages).hasSize(7);
        JsonNode lastUser = messages.get(6);
        assertThat(lastUser.get("role").asText()).isEqualTo("user");
        assertThat(lastUser.get("content")).hasSize(2);
        assertThat(lastUser.get("content").get(0).get("type").asText()).isEqualTo("tool_result");
        assertThat(lastUser.get("content").get(1).get("type").asText()).isEqualTo("text");
        assertThat(lastUser.get("content").get(1).get("text").asText()).isEqualTo(
                "This is your last turn: call submit_answer now with what you have, "
                        + "or with answerable:false and what is missing.");
        assertThat(messages.get(4).get("content")).as("an earlier tool-result turn is untouched").hasSize(1);
    }

    /** How many cache_control objects a request body holds anywhere. */
    private static int breakpoints(JsonNode node) {
        int count = node.has("cache_control") ? 1 : 0;
        for (JsonNode child : node) {
            count += breakpoints(child);
        }
        return count;
    }

    @Test
    @DisplayName("prompt caching: system block 1 (static) carries a breakpoint and block 2 (tail) none; each turn only "
            + "the latest tool-result message is marked, never more than 4 breakpoints, and the tools carry none")
    void breakpointsOnEveryTurn() throws Exception {
        stubExplorerThen("[{\"type\":\"tool_use\",\"id\":\"toolu_s\",\"name\":\"submit_answer\","
                + "\"input\":{\"answerable\":true,\"summary\":\"Bamburgh is clear.\",\"picks\":[{"
                + "\"locationId\":1,\"windowId\":\"" + WINDOW + "\",\"why\":\"Clear.\"}]}}]", "tool_use");

        assertThat(runTyped().outcome().turns()).isEqualTo(4);

        List<LoggedRequest> requests = WIRE_MOCK.findAll(postRequestedFor(urlPathEqualTo(PATH)));
        assertThat(requests).hasSize(4);
        for (int turn = 0; turn < 4; turn++) {
            JsonNode body = json.readTree(requests.get(turn).getBodyAsString());
            JsonNode system = body.get("system");
            assertThat(system).as("turn %d system blocks", turn + 1).hasSize(2);
            assertThat(system.get(0).get("cache_control").get("type").asText()).isEqualTo("ephemeral");
            assertThat(system.get(1).has("cache_control")).isFalse();
            assertThat(breakpoints(body.get("tools"))).as("tools carry none").isZero();
            JsonNode messages = body.get("messages");
            int marked = 0;
            for (int m = 0; m < messages.size(); m++) {
                int here = breakpoints(messages.get(m));
                marked += here;
                boolean latestToolResult = turn > 0 && m == messages.size() - 1;
                assertThat(here).as("turn %d message %d", turn + 1, m).isEqualTo(latestToolResult ? 1 : 0);
            }
            assertThat(marked).isEqualTo(turn == 0 ? 0 : 1);
            assertThat(breakpoints(body)).as("turn %d total", turn + 1).isLessThanOrEqualTo(4)
                    .isEqualTo(turn == 0 ? 1 : 2);
            if (turn > 0 && turn < 3) {
                JsonNode block = messages.get(messages.size() - 1).get("content").get(0);
                assertThat(block.get("type").asText()).isEqualTo("tool_result");
                assertThat(block.get("cache_control").get("type").asText()).isEqualTo("ephemeral");
            }
        }
    }

    @Test
    @DisplayName("prompt caching: two different questions send a byte-identical first system block and tools")
    void staticBlockIsSharedAcrossQuestions() throws Exception {
        stubExplorerThen("[{\"type\":\"tool_use\",\"id\":\"toolu_s\",\"name\":\"submit_answer\","
                + "\"input\":{\"answerable\":false,\"summary\":\"None.\",\"missing\":\"x\"}}]", "tool_use");

        engine.run(new AskQuestion("Clear skies on the coast?", "clear skies on the coast", null, AskScope.ALL,
                "map"), snapshot(), new AskUserContext(7L, UserRole.PRO_USER, true), AskRunOptions.none());
        engine.run(new AskQuestion("Anywhere dramatic in Northumberland?", "anywhere dramatic", null,
                TestScopes.of("Northumberland"), "map"), snapshot(),
                new AskUserContext(7L, UserRole.PRO_USER, true), AskRunOptions.none());

        List<LoggedRequest> requests = WIRE_MOCK.findAll(postRequestedFor(urlPathEqualTo(PATH)));
        JsonNode a = json.readTree(requests.get(0).getBodyAsString());
        JsonNode b = json.readTree(requests.get(requests.size() - 1).getBodyAsString());
        assertThat(b.get("system").get(0)).isEqualTo(a.get("system").get(0));
        assertThat(b.get("tools")).isEqualTo(a.get("tools"));
        assertThat(b.get("system").get(1)).isNotEqualTo(a.get("system").get(1));
    }

    @Test
    @DisplayName("a model that answers the forced last turn with answerable:false is an honest CANT, not a failure")
    void forcedLastTurnMayDeclineHonestly() {
        stubExplorerThen("[{\"type\":\"tool_use\",\"id\":\"toolu_s\",\"name\":\"submit_answer\","
                + "\"input\":{\"answerable\":false,\"summary\":\"No per-place cloud forecast here.\","
                + "\"missing\":\"cloud cover for one place\"}}]", "tool_use");

        AskRun run = runTyped();

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.CANT);
        assertThat(run.outcome().turns()).isEqualTo(4);
    }

    @Test
    @DisplayName("a model that still calls no submit_answer on the forced turn fails with the existing reason")
    void forcedLastTurnWithoutSubmitStillFails() {
        stubExplorerThen("[{\"type\":\"tool_use\",\"id\":\"toolu_y\",\"name\":\"list_windows\","
                + "\"input\":{}}]", "tool_use");

        AskRun run = runTyped();

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.reason()).contains("no submit_answer within 4 turns");
        WIRE_MOCK.verify(4, postRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("with max-turns 1 the first turn is the last: submit_answer is forced, no tool is consulted and "
            + "the request is the plain question")
    void singleTurnBudgetForcesSubmitAtOnce() throws Exception {
        properties.setMaxTurns(1);
        WIRE_MOCK.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(reply("tool_use", "[{\"type\":\"tool_use\",\"id\":\"toolu_s\","
                        + "\"name\":\"submit_answer\",\"input\":{\"answerable\":false,"
                        + "\"summary\":\"Not enough turns to look.\"}}]"))));

        AskRun run = runTyped();

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.CANT);
        List<LoggedRequest> requests = WIRE_MOCK.findAll(postRequestedFor(urlPathEqualTo(PATH)));
        assertThat(requests).hasSize(1);
        JsonNode only = json.readTree(requests.getFirst().getBodyAsString());
        assertThat(only.get("tool_choice").get("type").asText()).isEqualTo("tool");
        assertThat(only.get("tool_choice").get("name").asText()).isEqualTo("submit_answer");
        assertThat(only.get("messages")).hasSize(1);
        assertThat(requests.getFirst().getBodyAsString()).doesNotContain(ClaudeAskEngine.LAST_TURN_LINE);
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
