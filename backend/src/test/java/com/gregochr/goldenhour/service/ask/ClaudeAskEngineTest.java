package com.gregochr.goldenhour.service.ask;

import com.anthropic.core.JsonValue;
import com.anthropic.core.RequestOptions;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.evaluation.AnthropicApiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.gregochr.goldenhour.service.ask.AskFixtures.TODAY;
import static com.gregochr.goldenhour.service.ask.AskMessages.submit;
import static com.gregochr.goldenhour.service.ask.AskMessages.tool;
import static com.gregochr.goldenhour.service.ask.AskMessages.toolTurn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ClaudeAskEngine} with the SDK mocked at {@link AnthropicApiClient}: the loop,
 * its failure modes, the deadline, the scope, the logging and the question's one route to Claude.
 */
class ClaudeAskEngineTest {

    private static final String WINDOW = "2026-10-05_sunset";
    private static final long RUN_ID = 77L;
    private static final long READY_RUN_ID = 900L;

    private static final AskUserContext USER = new AskUserContext(7L, UserRole.PRO_USER, true);
    private static final AskUserContext USER_NO_DRIVE = new AskUserContext(8L, UserRole.LITE_USER, false);

    private final AnthropicApiClient client = mock(AnthropicApiClient.class);
    private final AskJobRunService jobRuns = mock(AskJobRunService.class);
    private final DriveTimeResolver driveTimes = mock(DriveTimeResolver.class);
    private final RegionRepository regions = mock(RegionRepository.class);
    private final AskProperties properties = new AskProperties();
    private final List<Logged> logged = new CopyOnWriteArrayList<>();
    private MutableClock clock;
    private ClaudeAskEngine engine;

    /** One turn the engine handed to {@link AskJobRunService#recordTurn}. */
    private record Logged(long runId, boolean ready, Integer status, boolean succeeded, String error,
            EvaluationModel model, TokenUsage usage) {
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-05T12:00:00Z"));
        engineWith(clock);
        when(jobRuns.dailyRunId()).thenReturn(RUN_ID);
        when(jobRuns.accountingAvailable()).thenReturn(true);
        doAnswer(inv -> {
            AskJobRunService.Turn t = inv.getArgument(0);
            logged.add(new Logged(t.runId(), t.ready(), t.status(), t.succeeded(), t.error(), t.model(),
                    t.usage()));
            return null;
        }).when(jobRuns).recordTurn(any());
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    private void engineWith(Clock useClock) {
        engine = new ClaudeAskEngine(client, properties, jobRuns, driveTimes, regions,
                new AskAnswerValidator(), new AskPromptBuilder(), new ObjectMapper(), useClock);
    }

    // -- fixtures ---------------------------------------------------------------------------

    private static BriefingRegion northumberland() {
        return AskFixtures.region("Northumberland", true,
                AskFixtures.coastal(1L, "Bamburgh", 5, "HIGH", true),
                AskFixtures.slot(2L, "Cheviot Edge", 4),
                AskFixtures.wood(3L, "Kielder Wood", 5),
                AskFixtures.slot(4L, "Grey Moor", 2));
    }

    private static BriefingRegion teesdale() {
        return AskFixtures.region("Teesdale", true, AskFixtures.slot(10L, "Hamsterley", 4));
    }

    private static AskSnapshot snapshot(BriefingWindow.Pick pick) {
        DailyBriefingResponse briefing = AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(TODAY, pick, northumberland(), teesdale())),
                List.of(AskFixtures.topic("AURORA", "Aurora tonight", "Kp 6 forecast", TODAY,
                        List.of("Northumberland"))));
        return AskFixtures.snapshotOf(briefing);
    }

    private static AskQuestion question(String text) {
        return new AskQuestion(text, text.toLowerCase(), null, List.of(), "plan");
    }

    private static AskQuestion question() {
        return question("Best spot tonight?");
    }

    private AskRun run(AskUserContext user, Message... replies) {
        when(client.createAskMessage(any(), any(), any())).thenReturn(replies[0],
                Arrays.copyOfRange(replies, 1, replies.length));
        return engine.run(question(), snapshot(null), user, AskRunOptions.none());
    }

    private static Map<String, Object> pick(long locationId, String windowId) {
        return Map.of("locationId", locationId, "windowId", windowId, "why", "Clear sky and the right tide.");
    }

    private static Map<String, Object> answer(Object... picks) {
        return Map.of("answerable", true, "summary", "Bamburgh looks best tonight.", "picks", List.of(picks));
    }

    private List<MessageCreateParams> sentParams() {
        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(client, atLeastOnce()).createAskMessage(captor.capture(), any(), any());
        return captor.getAllValues();
    }

    private List<ToolResultBlockParam> toolResultsIn(MessageCreateParams params) {
        List<ToolResultBlockParam> out = new ArrayList<>();
        params.messages().forEach(m -> {
            if (m.content().isBlockParams()) {
                m.content().asBlockParams().stream().filter(ContentBlockParam::isToolResult)
                        .map(ContentBlockParam::asToolResult).forEach(out::add);
            }
        });
        return out;
    }

    // -- the loop ---------------------------------------------------------------------------

    @Test
    @DisplayName("one turn: a submit_answer that says the forecast cannot answer is a CANT outcome, with one "
            + "logged call and the question counted")
    void oneTurnCant() {
        AskRun run = run(USER, submit(Map.of("answerable", false, "summary", "I can't tell from the forecast.",
                "missing", "parking information")));

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.CANT);
        assertThat(run.outcome().turns()).isEqualTo(1);
        assertThat(run.outcome().answer().answerable()).isFalse();
        assertThat(run.outcome().answer().missing()).isEqualTo("parking information");
        assertThat(run.outcome().personal()).isFalse();
        assertThat(run.reason()).isNull();
        verify(client, times(1)).createAskMessage(any(), any(), any());
        verify(jobRuns).recordQuestion(RUN_ID, true);
    }

    @Test
    @DisplayName("two turns: a rank_spots call, then submit_answer; the validator joins the served card facts "
            + "and drops the wood the model named")
    void twoTurnsWithPicks() {
        AskRun run = run(USER,
                toolTurn(tool("t1", "rank_spots", Map.of())),
                submit(answer(pick(1, WINDOW), pick(3, WINDOW))));

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.OK);
        assertThat(run.outcome().turns()).isEqualTo(2);
        assertThat(run.outcome().answer().picks()).singleElement().satisfies(p -> {
            assertThat(p.locationName()).isEqualTo("Bamburgh");
            assertThat(p.regionName()).isEqualTo("Northumberland");
            assertThat(p.ratingAtAnswer()).isEqualTo(5);
            assertThat(p.windowId()).isEqualTo(WINDOW);
            assertThat(p.rank()).isEqualTo(1);
        });
        assertThat(run.trace()).extracting(AskTools.ToolCall::tool).containsExactly("rank_spots", "submit_answer");
    }

    @Test
    @DisplayName("three turns: list_windows, then rank_spots, then submit_answer; each request carries the whole "
            + "conversation so far (the assistant's tool call, then our tool result)")
    void threeTurnsCarryTheConversation() {
        AskRun run = run(USER,
                toolTurn(tool("t1", "list_windows", Map.of())),
                toolTurn(tool("t2", "rank_spots", Map.of("limit", 3))),
                submit(answer(pick(2, WINDOW))));

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.OK);
        assertThat(run.outcome().turns()).isEqualTo(3);
        List<MessageCreateParams> sent = sentParams();
        assertThat(sent).hasSize(3);
        assertThat(sent.get(0).messages()).hasSize(1);
        assertThat(sent.get(1).messages()).hasSize(3);
        assertThat(sent.get(2).messages()).hasSize(5);
        assertThat(toolResultsIn(sent.get(2))).extracting(ToolResultBlockParam::toolUseId)
                .containsExactly("t1", "t2");
        assertThat(toolResultsIn(sent.get(1)).getFirst().content().orElseThrow().string().orElseThrow())
                .contains(WINDOW);
    }

    @Test
    @DisplayName("several tool calls in one turn each get a result, in order, in the one reply")
    void parallelToolCalls() {
        AskRun run = run(USER,
                toolTurn(tool("a", "list_windows", Map.of()), tool("b", "rank_spots", Map.of()),
                        tool("c", "get_hot_topics", Map.of())),
                submit(answer(pick(1, WINDOW))));

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.OK);
        assertThat(toolResultsIn(sentParams().get(1))).extracting(ToolResultBlockParam::toolUseId)
                .containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("an event named after get_hot_topics is kept, with the served label and no safety note invented")
    void eventsJoinServedFacts() {
        AskRun run = run(USER,
                toolTurn(tool("t1", "get_hot_topics", Map.of())),
                submit(Map.of("answerable", true, "summary", "Aurora is forecast tonight.",
                        "events", List.of(Map.of("type", "aurora", "why", "Kp 6.")))));

        assertThat(run.outcome().answer().events()).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo("AURORA");
            assertThat(e.label()).isEqualTo("Aurora tonight");
            assertThat(e.safetyNote()).isNull();
        });
    }

    @Test
    @DisplayName("submit_answer on the last allowed turn succeeds; one tool-only turn more is a FAILED outcome "
            + "(max-turns 4: at the limit, and over it)")
    void turnLimitBoundary() {
        // Three tool turns, then submit on turn 4 = the limit.
        AskRun atLimit = run(USER,
                toolTurn(tool("1", "list_windows", Map.of())),
                toolTurn(tool("2", "list_windows", Map.of())),
                toolTurn(tool("3", "rank_spots", Map.of())),
                submit(answer(pick(1, WINDOW))));
        assertThat(atLimit.outcome().status()).isEqualTo(AskOutcome.Status.OK);
        assertThat(atLimit.outcome().turns()).isEqualTo(4);

        // Four tool-only turns: turn 4 ends without submit_answer.
        reset(client);
        AskRun over = run(USER,
                toolTurn(tool("1", "list_windows", Map.of())),
                toolTurn(tool("2", "list_windows", Map.of())),
                toolTurn(tool("3", "list_windows", Map.of())),
                toolTurn(tool("4", "list_windows", Map.of())),
                submit(answer(pick(1, WINDOW))));
        assertThat(over.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(over.outcome().turns()).isEqualTo(4);
        assertThat(over.outcome().answer()).isNull();
        assertThat(over.reason()).contains("no submit_answer within 4 turns");
        verify(client, times(4)).createAskMessage(any(), any(), any());
    }

    @Test
    @DisplayName("the turn limit is the property: max-turns 1 allows a single tool-only turn")
    void turnLimitFollowsTheProperty() {
        properties.setMaxTurns(1);

        AskRun run = run(USER, toolTurn(tool("1", "list_windows", Map.of())));

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        verify(client, times(1)).createAskMessage(any(), any(), any());
    }

    @Test
    @DisplayName("a submit_answer sitting beside other calls ends the loop there: tools after it never run, "
            + "tools before it have already been counted as evidence")
    void submitEndsTheLoopInBlockOrder() {
        AskRun run = run(USER, toolTurn(
                tool("a", "rank_spots", Map.of()),
                tool("s", "submit_answer", answer(pick(1, WINDOW))),
                tool("b", "get_hot_topics", Map.of())));

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.OK);
        assertThat(run.trace()).extracting(AskTools.ToolCall::tool).containsExactly("rank_spots", "submit_answer");
    }

    // -- failure modes ----------------------------------------------------------------------

    @Test
    @DisplayName("answer() is run() with no options, reduced to the outcome")
    void answerIsRunWithNoOptions() {
        when(client.createAskMessage(any(), any(), any())).thenReturn(
                submit(Map.of("answerable", false, "summary", "Can't tell.")));

        AskOutcome outcome = engine.answer(question(), snapshot(null), USER);

        assertThat(outcome.status()).isEqualTo(AskOutcome.Status.CANT);
        assertThat(outcome.turns()).isEqualTo(1);
    }

    @Test
    @DisplayName("null options read as none: a typed question with no anchor")
    void nullOptionsAreNone() {
        when(client.createAskMessage(any(), any(), any())).thenReturn(
                submit(Map.of("answerable", false, "summary", "Can't tell.")));

        AskRun run = engine.run(question(), snapshot(null), USER, null);

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.CANT);
    }

    @Test
    @DisplayName("a refusal is FAILED, logged as a failed turn with its tokens")
    void refusalFails() {
        AskRun run = run(USER, AskMessages.message(StopReason.REFUSAL));

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.reason()).contains("refused");
        assertThat(logged).singleElement().satisfies(l -> {
            assertThat(l.succeeded()).isFalse();
            assertThat(l.usage()).isEqualTo(new TokenUsage(1_000, 100, 0, 0));
            assertThat(l.error()).contains("refused");
        });
        verify(jobRuns).recordQuestion(RUN_ID, false);
    }

    @Test
    @DisplayName("max_tokens is FAILED even when the cut-off turn carries a tool call that would parse")
    void maxTokensFails() {
        AskRun run = run(USER, AskMessages.message(StopReason.MAX_TOKENS,
                tool("s", "submit_answer", Map.of("answerable", false, "summary", "Cut"))));

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.reason()).contains("max_tokens");
        assertThat(run.trace()).isEmpty();
    }

    @Test
    @DisplayName("a context-window stop is FAILED")
    void contextWindowFails() {
        AskRun run = run(USER, AskMessages.message(StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED));

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.reason()).contains("context window");
    }

    @Test
    @DisplayName("a turn of plain text that ends normally is FAILED: the reply is the tool call, never prose")
    void plainTextFails() {
        AskRun run = run(USER, AskMessages.text("Bamburgh, definitely."));

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.reason()).contains("without a tool call");
        assertThat(logged).singleElement().satisfies(l -> assertThat(l.succeeded()).isFalse());
    }

    @Test
    @DisplayName("a tool_use stop with no tool call in it is FAILED")
    void toolUseStopWithNoToolCallFails() {
        AskRun run = run(USER, AskMessages.message(StopReason.TOOL_USE));

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.reason()).contains("no tool call");
    }

    @Test
    @DisplayName("a call that throws is FAILED, never an exception; its status code is logged")
    void modelCallFailure() {
        AnthropicServiceException serverError = mock(AnthropicServiceException.class);
        when(serverError.statusCode()).thenReturn(529);
        when(serverError.getMessage()).thenReturn("overloaded");
        when(client.createAskMessage(any(), any(), any())).thenThrow(serverError);

        AskRun run = engine.run(question(), snapshot(null), USER, AskRunOptions.none());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.outcome().turns()).isEqualTo(1);
        assertThat(logged).singleElement().satisfies(l -> {
            assertThat(l.status()).isEqualTo(529);
            assertThat(l.succeeded()).isFalse();
            assertThat(l.usage()).isNull();
        });
        verify(jobRuns).recordQuestion(RUN_ID, false);
    }

    @Test
    @DisplayName("an unexpected runtime error inside the loop (here the validator throwing) is FAILED too, with "
            + "the question counted failed: nothing escapes the engine")
    void unexpectedErrorIsFailed() {
        AskAnswerValidator broken = mock(AskAnswerValidator.class);
        when(broken.validate(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("boom"));
        engine = new ClaudeAskEngine(client, properties, jobRuns, driveTimes, regions, broken,
                new AskPromptBuilder(), new ObjectMapper(), clock);
        when(client.createAskMessage(any(), any(), any())).thenReturn(
                submit(Map.of("answerable", false, "summary", "Can't tell.")));

        AskRun run = engine.run(question(), snapshot(null), USER, AskRunOptions.none());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.reason()).contains("unexpected error").contains("boom");
        verify(jobRuns).recordQuestion(RUN_ID, false);
    }

    @Test
    @DisplayName("fail closed on accounting: while a paid call's cost is unrecorded, a new conversation makes no "
            + "model call, opens no job run and fails with the distinct accounting reason")
    void unrecordedCostStopsANewConversation() {
        when(jobRuns.accountingAvailable()).thenReturn(false);

        AskRun run = engine.run(question(), snapshot(null), USER, AskRunOptions.none());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.outcome().turns()).isZero();
        assertThat(run.accountingUnavailable()).isTrue();
        assertThat(run.reason()).isEqualTo(AskRun.ACCOUNTING_UNAVAILABLE);
        verifyNoInteractions(client);
        verify(jobRuns, never()).dailyRunId();
    }

    @Test
    @DisplayName("the same latch stops a Ready conversation")
    void unrecordedCostStopsAReadyConversation() {
        when(jobRuns.accountingAvailable()).thenReturn(false);

        AskRun run = engine.run(question(), snapshot(null), AskUserContext.userLess(),
                AskRunOptions.ready(READY_RUN_ID, null));

        assertThat(run.accountingUnavailable()).isTrue();
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("the conversation in which a write failed stops before its next turn: turn 1 is paid for, "
            + "turn 2 is never started, and the question is counted failed")
    void aFailureMidConversationStopsTheConversation() {
        // Open for the pre-check and turn 1; latched by the time turn 2 would start.
        when(jobRuns.accountingAvailable()).thenReturn(true, false);
        when(client.createAskMessage(any(), any(), any())).thenReturn(
                toolTurn(tool("t", "rank_spots", Map.of())), submit(answer(pick(1, WINDOW))));

        AskRun run = engine.run(question(), snapshot(null), USER, AskRunOptions.none());

        assertThat(run.accountingUnavailable()).isTrue();
        assertThat(run.outcome().turns()).isEqualTo(1);
        verify(client, times(1)).createAskMessage(any(), any(), any());
        verify(jobRuns).recordQuestion(RUN_ID, false);
    }

    @Test
    @DisplayName("a turn that carried submit_answer is still returned even though its own write was the one "
            + "that failed: it is paid for, and nothing further is started")
    void theAnswerTurnIsStillReturned() {
        // Open for the pre-check; the write of the only turn fails (so the latch is set afterwards), but
        // the loop never asks again because the answer ends it.
        when(jobRuns.accountingAvailable()).thenReturn(true, false);

        AskRun run = run(USER, submit(Map.of("answerable", false, "summary", "Can't tell.")));

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.CANT);
        verify(jobRuns, times(1)).accountingAvailable();
    }

    @Test
    @DisplayName("a flush that succeeds mid-conversation lets it carry on")
    void aSuccessfulFlushLetsTheConversationContinue() {
        when(jobRuns.accountingAvailable()).thenReturn(true, true);
        when(client.createAskMessage(any(), any(), any())).thenReturn(
                toolTurn(tool("t", "rank_spots", Map.of())), submit(answer(pick(1, WINDOW))));

        AskRun run = engine.run(question(), snapshot(null), USER, AskRunOptions.none());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.OK);
        assertThat(run.outcome().turns()).isEqualTo(2);
    }

    @Test
    @DisplayName("fail closed: if the day's job run cannot be opened, no model call is made at all")
    void noJobRunNoSpend() {
        when(jobRuns.dailyRunId()).thenThrow(new IllegalStateException("db down"));

        AskRun run = engine.run(question(), snapshot(null), USER, AskRunOptions.none());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.outcome().turns()).isZero();
        assertThat(run.reason()).contains("job run could not be opened");
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   \t"})
    @DisplayName("a blank question is FAILED before any spend")
    void blankQuestion(String text) {
        AskRun run = engine.run(new AskQuestion(text, text, null, List.of(), "plan"), snapshot(null), USER,
                AskRunOptions.none());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        verifyNoInteractions(client);
        verify(jobRuns, never()).dailyRunId();
    }

    // -- the model's output is untrusted ----------------------------------------------------

    @Test
    @DisplayName("a tool error is fed back to the model as an isError result, not thrown: unknown window id, "
            + "unknown tool, a wrongly typed argument and a non-object argument")
    void toolErrorsAreFedBack() {
        AskRun run = run(USER,
                toolTurn(tool("bad-window", "rank_spots", Map.of("windowIds", List.of("2026-10-05_midnight"))),
                        tool("bad-tool", "delete_everything", Map.of()),
                        tool("bad-type", "rank_spots", Map.of("limit", "five")),
                        AskMessages.toolWithRawInput("bad-shape", "get_hot_topics", JsonValue.from("oops")),
                        tool("good", "rank_spots", Map.of())),
                submit(answer(pick(1, WINDOW))));

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.OK);
        List<ToolResultBlockParam> results = toolResultsIn(sentParams().get(1));
        assertThat(results).extracting(ToolResultBlockParam::toolUseId)
                .containsExactly("bad-window", "bad-tool", "bad-type", "bad-shape", "good");
        assertThat(results).extracting(r -> r.isError().orElse(false))
                .containsExactly(true, true, true, true, false);
        assertThat(results.get(0).content().orElseThrow().string().orElseThrow()).contains("Unknown window id");
        assertThat(results.get(1).content().orElseThrow().string().orElseThrow()).contains("Unknown tool");
        assertThat(results.get(2).content().orElseThrow().string().orElseThrow()).contains("'limit'");
        assertThat(run.trace()).extracting(AskTools.ToolCall::error)
                .containsExactly(true, true, true, true, false, false);
    }

    @Test
    @DisplayName("an unknown tool name is bounded in the error and the trace, so a model cannot flood either")
    void unknownToolNameIsClipped() {
        String longName = "x".repeat(500);

        AskRun run = run(USER, toolTurn(tool("t", longName, Map.of())),
                submit(Map.of("answerable", false, "summary", "Can't tell.")));

        assertThat(run.trace().getFirst().tool()).hasSizeLessThan(60);
        String message = toolResultsIn(sentParams().get(1)).getFirst().content().orElseThrow().string()
                .orElseThrow();
        assertThat(message).hasSizeLessThan(300);
    }

    @Test
    @DisplayName("an unknown tool alone is not a tool call: an answerable reply with no evidence is discarded")
    void unknownToolIsNotEvidence() {
        AskRun run = run(USER, toolTurn(tool("t", "made_up", Map.of())), submit(answer(pick(1, WINDOW))));

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.reason()).contains("discarded");
    }

    @Test
    @DisplayName("a pick the tools never returned is dropped, and an answerable reply left with nothing is FAILED")
    void unreturnedPickIsDiscarded() {
        AskRun run = run(USER, submit(answer(pick(1, WINDOW))));

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.reason()).contains("no pick, no event and no tool call");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"submit\"", "42", "null", "{}", "{\"summary\": \"x\"}",
            "{\"answerable\": \"yes\", \"summary\": \"x\"}",
            "{\"answerable\": true, \"summary\": \"x\", \"picks\": [{\"locationId\": \"1\", \"windowId\": \"w\"}]}"})
    @DisplayName("a malformed submit_answer is FAILED with its reason, never an exception")
    void malformedSubmitIsFailed(String json) throws Exception {
        JsonValue input = JsonValue.fromJsonNode(new ObjectMapper().readTree(json));
        when(client.createAskMessage(any(), any(), any())).thenReturn(toolTurn(
                AskMessages.toolWithRawInput("s", "submit_answer", input)));

        AskRun run = engine.run(question(), snapshot(null), USER, AskRunOptions.none());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.reason()).contains("submit_answer was malformed");
        assertThat(run.trace()).singleElement().satisfies(t -> assertThat(t.error()).isTrue());
    }

    @Test
    @DisplayName("extra fields on submit_answer, including a 'try' list, are ignored: there is no field for them")
    void extraFieldsIgnored() {
        Map<String, Object> reply = new LinkedHashMap<>(answer(pick(1, WINDOW)));
        reply.put("try", List.of("Best tomorrow?"));
        reply.put("safetyNote", "Wear nothing");

        AskRun run = run(USER, toolTurn(tool("t", "rank_spots", Map.of())), submit(reply));

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.OK);
        assertThat(run.outcome().answer().events()).isEmpty();
    }

    @Test
    @DisplayName("a Ready BEST question whose answer does not lead with the BEST BET window is discarded "
            + "(the anchor is the caller's, passed straight to the validator)")
    void bestAnchorIsEnforced() {
        BriefingWindow.Pick best = AskFixtures.pick(BriefingWindow.PickKind.BEST, "Northumberland", "Bamburgh", 1L);
        when(client.createAskMessage(any(), any(), any())).thenReturn(
                submit(Map.of("answerable", false, "summary", "Nothing is worth it.")));

        AskRun run = engine.run(question(), snapshot(best), AskUserContext.userLess(),
                AskRunOptions.ready(READY_RUN_ID, new AskAnswerValidator.BestAnchor(Set.of(WINDOW))));

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.reason()).contains("BEST BET");
    }

    // -- the question's route to Claude -----------------------------------------------------

    @Test
    @DisplayName("the question reaches Claude only as the user message: once per request, never in the system "
            + "prompt, a tool result or the logged request body")
    void questionOnlyInTheUserMessage() {
        String secret = "zebra-crossing-9921 where do i stand";
        when(client.createAskMessage(any(), any(), any())).thenReturn(
                toolTurn(tool("t1", "rank_spots", Map.of()), tool("t2", "list_windows", Map.of())),
                submit(answer(pick(1, WINDOW))));

        engine.run(question(secret), snapshot(null), USER, AskRunOptions.none());

        for (MessageCreateParams params : sentParams()) {
            assertThat(params.messages().getFirst().content().asString()).isEqualTo(secret);
            assertThat(params.system().orElseThrow().asTextBlockParams().getFirst().text()).doesNotContain(secret);
            assertThat(countOccurrences(params.toString(), secret)).as("occurrences in the request").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("each request carries the model, the 600-token ceiling, every tool, and auto tool choice")
    void requestShape() {
        run(USER, submit(Map.of("answerable", false, "summary", "Can't tell.")));

        MessageCreateParams params = sentParams().getFirst();
        assertThat(params.model().asString()).isEqualTo(EvaluationModel.HAIKU.getModelId());
        assertThat(params.maxTokens()).isEqualTo(600);
        assertThat(params.tools().orElseThrow()).hasSize(5);
        assertThat(params.toolChoice().orElseThrow().isAuto()).isTrue();
        assertThat(params.outputConfig()).as("Haiku and Sonnet 4.6 take no effort setting").isEmpty();
    }

    @Test
    @DisplayName("the configured model is used: Sonnet 4.6 when asked for")
    void configuredModel() {
        properties.setModel(EvaluationModel.SONNET);

        run(USER, submit(Map.of("answerable", false, "summary", "Can't tell.")));

        assertThat(sentParams().getFirst().model().asString()).isEqualTo(EvaluationModel.SONNET.getModelId());
        assertThat(logged.getFirst().model()).isEqualTo(EvaluationModel.SONNET);
    }

    @Test
    @DisplayName("max-tokens follows the property")
    void maxTokensFollowsTheProperty() {
        properties.setMaxTokens(900);

        run(USER, submit(Map.of("answerable", false, "summary", "Can't tell.")));

        assertThat(sentParams().getFirst().maxTokens()).isEqualTo(900);
    }

    // -- personal, drive, user-less ---------------------------------------------------------

    @Test
    @DisplayName("personal is set only by maxDriveMinutes, and the drive times are the asker's own")
    void personalOnlyByDriveLimit() {
        when(driveTimes.getAllMinutes(7L)).thenReturn(Map.of(1L, 30, 2L, 120));

        AskRun plain = run(USER, toolTurn(tool("t", "rank_spots", Map.of())),
                submit(answer(pick(1, WINDOW))));
        reset(client);
        AskRun limited = run(USER, toolTurn(tool("t", "rank_spots", Map.of("maxDriveMinutes", 60))),
                submit(answer(pick(1, WINDOW))));

        assertThat(plain.outcome().personal()).isFalse();
        assertThat(limited.outcome().personal()).isTrue();
        assertThat(limited.outcome().status()).isEqualTo(AskOutcome.Status.OK);
        verify(driveTimes, times(1)).getAllMinutes(7L);
    }

    @Test
    @DisplayName("a user with no drive times who uses maxDriveMinutes still marks the answer personal "
            + "(it depends on who asked), and gets the explanatory empty result")
    void driveLimitWithoutDriveTimes() {
        AskRun run = run(USER_NO_DRIVE, toolTurn(tool("t", "rank_spots", Map.of("maxDriveMinutes", 30))),
                submit(Map.of("answerable", false, "summary", "Can't say within that drive.")));

        assertThat(run.outcome().personal()).isTrue();
        assertThat(toolResultsIn(sentParams().get(1)).getFirst().content().orElseThrow().string().orElseThrow())
                .contains("No drive times are stored");
        verifyNoInteractions(driveTimes);
    }

    @Test
    @DisplayName("a typed conversation is told about maxDriveMinutes; a Ready one is not, and is refused it "
            + "if the model tries anyway")
    void readyConversationHasNoHome() {
        when(client.createAskMessage(any(), any(), any())).thenReturn(
                toolTurn(tool("t", "rank_spots", Map.of("maxDriveMinutes", 30))),
                submit(Map.of("answerable", false, "summary", "Can't say.")));

        AskRun ready = engine.run(question(), snapshot(null), AskUserContext.userLess(),
                AskRunOptions.ready(READY_RUN_ID, null));

        List<MessageCreateParams> sent = sentParams();
        assertThat(sent.getFirst().toString()).doesNotContain("maxDriveMinutes");
        assertThat(sent.getFirst().system().orElseThrow().asTextBlockParams().getFirst().text())
                .contains("never mention home");
        ToolResultBlockParam refusal = toolResultsIn(sent.get(1)).getFirst();
        assertThat(refusal.isError()).contains(true);
        assertThat(refusal.content().orElseThrow().string().orElseThrow()).contains("not available");
        assertThat(ready.outcome().personal()).isFalse();
        verifyNoInteractions(driveTimes);
    }

    // -- cost and logging -------------------------------------------------------------------

    @Test
    @DisplayName("every model turn is logged with its tokens, the day's run total is incremented by each call's "
            + "cost, and the question is counted once")
    void everyTurnIsLoggedAndCosted() {
        run(USER, toolTurn(tool("t1", "list_windows", Map.of())),
                toolTurn(tool("t2", "rank_spots", Map.of())),
                submit(answer(pick(1, WINDOW))));

        assertThat(logged).hasSize(3).allSatisfy(l -> {
            assertThat(l.runId()).isEqualTo(RUN_ID);
            assertThat(l.ready()).isFalse();
            assertThat(l.status()).isEqualTo(200);
            assertThat(l.succeeded()).isTrue();
            assertThat(l.model()).isEqualTo(EvaluationModel.HAIKU);
            assertThat(l.usage()).isEqualTo(new TokenUsage(1_000, 100, 0, 0));
        });
        verify(jobRuns, times(3)).recordTurn(any());
        verify(jobRuns, times(1)).recordQuestion(anyLong(), anyBoolean());
        // Once to open the conversation, then once per turn: the run is resolved for each turn.
        verify(jobRuns, times(4)).dailyRunId();
    }

    @Test
    @DisplayName("a Ready conversation is billed to the ASK_READY run it was given, tagged ask-ready, and never "
            + "touches the daily ASK run, its cost increments or its question count")
    void readyIsBilledToItsOwnRun() {
        when(client.createAskMessage(any(), any(), any())).thenReturn(
                toolTurn(tool("t", "rank_spots", Map.of())), submit(answer(pick(1, WINDOW))));

        AskRun run = engine.run(question(), snapshot(null), AskUserContext.userLess(),
                AskRunOptions.ready(READY_RUN_ID, null));

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.OK);
        assertThat(logged).hasSize(2).allSatisfy(l -> {
            assertThat(l.runId()).isEqualTo(READY_RUN_ID);
            assertThat(l.ready()).isTrue();
        });
        verify(jobRuns, never()).dailyRunId();
        verify(jobRuns, never()).recordQuestion(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("the options must match the conversation: a Ready conversation needs its run, a typed one must "
            + "not carry one")
    void optionsMustMatchTheConversation() {
        AskSnapshot snapshot = snapshot(null);

        assertThatThrownBy(() -> engine.run(question(), snapshot, AskUserContext.userLess(), AskRunOptions.none()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ASK_READY");
        assertThatThrownBy(() -> engine.run(question(), snapshot, USER, AskRunOptions.ready(1L, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("daily ASK run");
        verifyNoInteractions(client);
    }

    // -- scope ------------------------------------------------------------------------------

    @Test
    @DisplayName("the question's region ids become the scope: the tools return only that region, and the system "
            + "prompt names it")
    void scopeIsResolvedAndApplied() {
        when(regions.findAllById(Set.of(5L))).thenReturn(List.of(RegionEntity.builder().id(5L)
                .name("Teesdale").build()));
        when(client.createAskMessage(any(), any(), any())).thenReturn(
                toolTurn(tool("t", "rank_spots", Map.of())),
                submit(answer(pick(10, WINDOW), pick(1, WINDOW))));

        AskRun run = engine.run(new AskQuestion("Best spot?", "best spot", null, List.of(5L), "plan"),
                snapshot(null), USER, AskRunOptions.none());

        assertThat(run.outcome().answer().picks()).extracting(AskPick::locationName).containsExactly("Hamsterley");
        String result = toolResultsIn(sentParams().get(1)).getFirst().content().orElseThrow().string().orElseThrow();
        assertThat(result).contains("Hamsterley").doesNotContain("Bamburgh");
        assertThat(sentParams().getFirst().system().orElseThrow().asTextBlockParams().getFirst().text())
                .contains("these regions only: Teesdale.");
    }

    @Test
    @DisplayName("a tool asked for a region outside the scope is told so, as an error result")
    void outOfScopeRegionIsAToolError() {
        when(regions.findAllById(Set.of(5L))).thenReturn(List.of(RegionEntity.builder().id(5L)
                .name("Teesdale").build()));
        when(client.createAskMessage(any(), any(), any())).thenReturn(
                toolTurn(tool("t", "rank_spots", Map.of("regionNames", List.of("Northumberland")))),
                submit(Map.of("answerable", false, "summary", "Can't say.")));

        engine.run(new AskQuestion("Best spot?", "best spot", null, List.of(5L), "plan"), snapshot(null), USER,
                AskRunOptions.none());

        ToolResultBlockParam result = toolResultsIn(sentParams().get(1)).getFirst();
        assertThat(result.isError()).contains(true);
        assertThat(result.content().orElseThrow().string().orElseThrow()).contains("outside the scope");
    }

    @Test
    @DisplayName("a region id that does not resolve fails the run before any spend: an empty scope would widen it "
            + "to every region")
    void unknownRegionIdFails() {
        when(regions.findAllById(Set.of(5L, 6L))).thenReturn(List.of(RegionEntity.builder().id(5L)
                .name("Teesdale").build()));

        AskRun run = engine.run(new AskQuestion("Best spot?", "best spot", null, List.of(5L, 6L), "plan"),
                snapshot(null), USER, AskRunOptions.none());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.reason()).contains("region id");
        verifyNoInteractions(client, jobRuns);
    }

    @Test
    @DisplayName("an empty region list is every region, with no repository lookup")
    void emptyScopeIsEverything() {
        AskRun run = run(USER, toolTurn(tool("t", "rank_spots", Map.of())),
                submit(answer(pick(10, WINDOW), pick(1, WINDOW))));

        assertThat(run.outcome().answer().picks()).extracting(AskPick::locationName)
                .containsExactly("Hamsterley", "Bamburgh");
        verifyNoInteractions(regions);
    }

    @Test
    @DisplayName("the context window is stated to the model only when it is in the snapshot")
    void contextWindowOnlyWhenKnown() {
        when(client.createAskMessage(any(), any(), any())).thenReturn(
                submit(Map.of("answerable", false, "summary", "Can't say.")));

        engine.run(new AskQuestion("And then?", "and then", WINDOW, List.of(), "map"), snapshot(null), USER,
                AskRunOptions.none());
        engine.run(new AskQuestion("And then?", "and then", "2026-12-25_sunrise", List.of(), "map"),
                snapshot(null), USER, AskRunOptions.none());

        List<MessageCreateParams> sent = sentParams();
        assertThat(sent.get(0).system().orElseThrow().asTextBlockParams().getFirst().text())
                .contains("windowId " + WINDOW);
        assertThat(sent.get(1).system().orElseThrow().asTextBlockParams().getFirst().text())
                .doesNotContain("looking at").doesNotContain("2026-12-25");
    }

    // -- time -------------------------------------------------------------------------------

    @Test
    @DisplayName("each call's timeout is the shorter of the per-call timeout and the time left to the deadline: "
            + "20s at the start, 15s once 15 of the 30 seconds are gone, and the loop stops when none are left")
    void callTimeoutIsClampedToTheTimeLeft() {
        AtomicInteger calls = new AtomicInteger();
        List<Duration> timeouts = new CopyOnWriteArrayList<>();
        when(client.createAskMessage(any(), any(), any())).thenAnswer(inv -> {
            RequestOptions options = inv.getArgument(1);
            timeouts.add(options.getTimeout().request());
            clock.advance(Duration.ofSeconds(15));
            return toolTurn(tool("t" + calls.incrementAndGet(), "list_windows", Map.of()));
        });

        AskRun run = engine.run(question(), snapshot(null), USER, AskRunOptions.none());

        assertThat(timeouts).containsExactly(Duration.ofSeconds(20), Duration.ofSeconds(15));
        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.outcome().turns()).isEqualTo(2);
        assertThat(run.reason()).contains("deadline passed before turn 3");
        verify(client, times(2)).createAskMessage(any(), any(), any());
    }

    @Test
    @DisplayName("deadline boundary: half a second left still makes a call (with a 500ms timeout); none left does not")
    void deadlineBoundary() {
        when(client.createAskMessage(any(), any(), any())).thenAnswer(inv -> {
            clock.advance(Duration.ofMillis(29_500));
            return toolTurn(tool("t", "list_windows", Map.of()));
        }).thenReturn(submit(Map.of("answerable", false, "summary", "Can't say.")));

        AskRun oneLeft = engine.run(question(), snapshot(null), USER, AskRunOptions.none());
        assertThat(oneLeft.outcome().turns()).isEqualTo(2);

        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(client, times(2)).createAskMessage(any(), options.capture(), any());
        assertThat(options.getAllValues().get(1).getTimeout().request()).isEqualTo(Duration.ofMillis(500));

        reset(client);
        when(client.createAskMessage(any(), any(), any())).thenAnswer(inv -> {
            clock.advance(Duration.ofSeconds(30));
            return toolTurn(tool("t", "list_windows", Map.of()));
        });
        AskRun noneLeft = engine.run(question(), snapshot(null), USER, AskRunOptions.none());
        assertThat(noneLeft.outcome().turns()).isEqualTo(1);
        assertThat(noneLeft.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
    }

    @Test
    @DisplayName("a slow call cannot outlast the deadline: the engine stops waiting at it even though the call "
            + "itself would take five seconds, and logs the turn as failed")
    void slowCallCannotExceedTheDeadline() {
        properties.setCallTimeoutSeconds(1);
        properties.setDeadlineSeconds(1);
        engineWith(Clock.systemUTC());
        when(client.createAskMessage(any(), any(), any())).thenAnswer(inv -> {
            Thread.sleep(5_000);
            return submit(Map.of("answerable", false, "summary", "Too late."));
        });

        long started = System.nanoTime();
        AskRun run = engine.run(question(), snapshot(null), USER, AskRunOptions.none());
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.reason()).contains("deadline");
        assertThat(elapsedMs).as("elapsed").isLessThan(3_000);
        assertThat(logged).singleElement().satisfies(l -> assertThat(l.succeeded()).isFalse());
        verify(jobRuns).recordQuestion(RUN_ID, false);
    }

    @Test
    @DisplayName("UK midnight mid-conversation: each typed turn is booked to the daily run as of that turn, so "
            + "turn 2 goes to the new day's run (the day the spend figure counts it in), while the question "
            + "is counted on the day it started")
    void eachTurnIsBookedToTheDailyRunAsOfThatTurn() {
        // 1st call opens the conversation, 2nd is turn 1, 3rd is turn 2 (after midnight).
        when(jobRuns.dailyRunId()).thenReturn(77L, 77L, 78L);

        AskRun run = run(USER, toolTurn(tool("t", "rank_spots", Map.of())), submit(answer(pick(1, WINDOW))));

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.OK);
        assertThat(logged).extracting(Logged::runId).containsExactly(77L, 78L);
        verify(jobRuns).recordQuestion(77L, true);
    }

    @Test
    @DisplayName("if the daily run cannot be resolved for a later turn, that turn is not made: nothing is spent "
            + "that cannot be booked")
    void aRunLookupFailureMidConversationStopsTheConversation() {
        when(jobRuns.dailyRunId()).thenReturn(RUN_ID, RUN_ID).thenThrow(new IllegalStateException("db down"));
        when(client.createAskMessage(any(), any(), any())).thenReturn(
                toolTurn(tool("t", "rank_spots", Map.of())), submit(answer(pick(1, WINDOW))));

        AskRun run = engine.run(question(), snapshot(null), USER, AskRunOptions.none());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.reason()).contains("job run could not be opened");
        assertThat(run.outcome().turns()).isEqualTo(1);
        verify(client, times(1)).createAskMessage(any(), any(), any());
    }

    @Test
    @DisplayName("the gate the engine hands the client is the accounting latch, asked per attempt: a refusal "
            + "from it ends the run with the accounting reason and no turn is logged for the refused call")
    void aGateRefusalEndsTheRunWithoutLoggingATurn() {
        AtomicBoolean open = new AtomicBoolean(true);
        when(jobRuns.accountingAvailable()).thenAnswer(inv -> open.get());
        when(client.createAskMessage(any(), any(), any())).thenAnswer(inv -> {
            AnthropicApiClient.CallGate gate = inv.getArgument(2);
            open.set(false);
            if (!gate.mayCall()) {
                throw new AnthropicApiClient.CallRefusedException();
            }
            return submit(Map.of("answerable", false, "summary", "Can't tell."));
        });

        AskRun run = engine.run(question(), snapshot(null), USER, AskRunOptions.none());

        assertThat(run.accountingUnavailable()).isTrue();
        assertThat(run.outcome().turns()).isZero();
        assertThat(logged).isEmpty();
        verify(jobRuns).recordQuestion(RUN_ID, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"end_turn", "stop_sequence", "pause_turn"})
    @DisplayName("any non-tool stop reason is FAILED")
    void otherStopReasonsFail(String stopReason) {
        AskRun run = run(USER, AskMessages.message(StopReason.of(stopReason)));

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
