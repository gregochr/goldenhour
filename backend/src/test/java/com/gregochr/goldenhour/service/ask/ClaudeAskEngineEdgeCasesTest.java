package com.gregochr.goldenhour.service.ask;

import com.anthropic.core.RequestOptions;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.evaluation.AnthropicApiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.gregochr.goldenhour.service.ask.AskFixtures.TODAY;
import static com.gregochr.goldenhour.service.ask.AskMessages.submit;
import static com.gregochr.goldenhour.service.ask.AskMessages.tool;
import static com.gregochr.goldenhour.service.ask.AskMessages.toolTurn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ClaudeAskEngine} on the edges the main engine tests leave out: a deadline overshot rather than met, an
 * interrupted wait, a reply with no usage or no stop reason, a tool that throws, an unknown tool with an awkward
 * name, and the night the Map is showing.
 */
class ClaudeAskEngineEdgeCasesTest {

    private static final long RUN_ID = 77L;
    private static final AskUserContext USER = new AskUserContext(7L, UserRole.PRO_USER, true);
    private static final AskUserContext LITE = new AskUserContext(8L, UserRole.LITE_USER, false);

    private final AnthropicApiClient client = mock(AnthropicApiClient.class);
    private final AskJobRunService jobRuns = mock(AskJobRunService.class);
    private final DriveTimeResolver driveTimes = mock(DriveTimeResolver.class);
    private final ModelSelectionService modelSelection = mock(ModelSelectionService.class);
    private final AskProperties properties = new AskProperties();
    private final List<AskJobRunService.Turn> turns = new CopyOnWriteArrayList<>();
    private MutableClock clock;
    private ClaudeAskEngine engine;

    /** Matches the request: the haiku model and a first user message that is exactly the asked question. */
    private static MessageCreateParams params() {
        return argThat(p -> p != null && EvaluationModel.HAIKU.getModelId().equals(p.model().asString())
                && p.messages().getFirst().content().isString()
                && "Best spot tonight?".equals(p.messages().getFirst().content().asString()));
    }

    /** Matches the call options: the 20 s per-call timeout (the 30 s deadline has not started to run out). */
    private static RequestOptions options() {
        return argThat(o -> o != null && o.timeout().isPresent()
                && Duration.ofSeconds(20).equals(o.timeout().get().request()));
    }

    /** Matches the accounting gate: it is open while the job-run service reports accounting available. */
    private static AnthropicApiClient.CallGate gate() {
        return argThat(g -> g != null && g.mayCall());
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-05T12:00:00Z"));
        when(modelSelection.getActiveModel(RunType.ASK)).thenReturn(EvaluationModel.HAIKU);
        engine = new ClaudeAskEngine(client, properties, modelSelection, jobRuns, driveTimes,
                new AskAnswerValidator(), new AskPromptBuilder(), new ObjectMapper(), clock, AskLocationNames.NONE);
        when(jobRuns.dailyRunId()).thenReturn(RUN_ID);
        when(jobRuns.accountingAvailable()).thenReturn(true);
        doAnswer(inv -> {
            turns.add(inv.getArgument(0));
            return null;
        }).when(jobRuns).recordTurn(isA(AskJobRunService.Turn.class));
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    private static AskSnapshot snapshot() {
        BriefingRegion coast = AskFixtures.region("Northumberland", true,
                AskFixtures.coastal(1L, "Bamburgh", 5, "HIGH", true), AskFixtures.slot(2L, "Cheviot Edge", 4));
        DailyBriefingResponse briefing = AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(TODAY, null, coast)), List.of());
        return AskFixtures.snapshotOf(briefing);
    }

    private static AskQuestion question(String windowId) {
        return new AskQuestion("Best spot tonight?", "best spot tonight", windowId, AskScope.ALL, "plan");
    }

    private AskRun run(AskUserContext user) {
        return engine.run(question(null), snapshot(), user, AskRunOptions.none());
    }

    private List<MessageCreateParams> sentParams() {
        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(client, atLeastOnce()).createAskMessage(captor.capture(), options(), gate());
        return captor.getAllValues();
    }

    // -- time and threads -------------------------------------------------------------------

    @Test
    @DisplayName("a deadline that is overshot, not just met, stops the loop before the next turn")
    void deadlineOvershot() {
        when(client.createAskMessage(params(), options(), gate())).thenAnswer(inv -> {
            clock.advance(Duration.ofSeconds(45));
            return toolTurn(tool("t", "list_windows", Map.of()));
        });

        AskRun run = run(USER);

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.outcome().turns()).isEqualTo(1);
        assertThat(run.reason()).contains("deadline passed before turn 2");
        verify(client, times(1)).createAskMessage(params(), options(), gate());
    }

    @Test
    @DisplayName("an interrupted wait is FAILED as 'interrupted', logged as a failed turn, and keeps the "
            + "thread's interrupt flag for its owner")
    void interruptedWait() {
        // The call is slow, so the wait is still pending when the (already interrupted) caller reaches it.
        when(client.createAskMessage(params(), options(), gate())).thenAnswer(inv -> {
            Thread.sleep(5_000);
            return submit(Map.of("answerable", false, "summary", "Can't tell."));
        });
        Thread.currentThread().interrupt();
        AskRun run;
        boolean flagKept;
        try {
            run = run(USER);
        } finally {
            flagKept = Thread.interrupted();
        }

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.reason()).isEqualTo("interrupted");
        assertThat(flagKept).isTrue();
        assertThat(turns).singleElement().satisfies(t -> {
            assertThat(t.succeeded()).isFalse();
            assertThat(t.error()).isEqualTo("interrupted");
            assertThat(t.usage()).isNull();
        });
        verify(jobRuns).recordQuestion(RUN_ID, false);
    }

    // -- the reply --------------------------------------------------------------------------

    @Test
    @DisplayName("a reply that carries no usage is still read, and its turn is logged with no token figures")
    void replyWithoutUsage() {
        Message real = submit(Map.of("answerable", false, "summary", "Can't tell."));
        Message noUsage = spy(real);
        doReturn(null).when(noUsage).usage();
        when(client.createAskMessage(params(), options(), gate())).thenReturn(noUsage);

        AskRun run = run(USER);

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.CANT);
        assertThat(turns).singleElement().satisfies(t -> {
            assertThat(t.succeeded()).isTrue();
            assertThat(t.usage()).isNull();
        });
    }

    @Test
    @DisplayName("a reply with no stop reason at all is FAILED, naming 'none'")
    void replyWithoutAStopReason() {
        Message real = submit(Map.of("answerable", false, "summary", "Can't tell."));
        Message noStop = spy(real);
        doReturn(Optional.empty()).when(noStop).stopReason();
        when(client.createAskMessage(params(), options(), gate())).thenReturn(noStop);

        AskRun run = run(USER);

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.reason()).isEqualTo("the turn ended without a tool call (stop_reason=none)");
        assertThat(turns).singleElement().satisfies(t -> {
            assertThat(t.succeeded()).isFalse();
            assertThat(t.error()).contains("stop_reason=none");
        });
    }

    @Test
    @DisplayName("a call that throws with no message is FAILED naming only its type")
    void callThrowsWithoutAMessage() {
        when(client.createAskMessage(params(), options(), gate())).thenThrow(new IllegalStateException());

        AskRun run = run(USER);

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.reason()).isEqualTo("the model call failed: IllegalStateException");
        assertThat(turns).singleElement().satisfies(t -> assertThat(t.error()).isEqualTo("IllegalStateException"));
    }

    // -- the tools --------------------------------------------------------------------------

    @Test
    @DisplayName("a tool that throws is fed back as an error result and the conversation goes on")
    void toolThrows() {
        when(driveTimes.getAllMinutes(7L)).thenThrow(new IllegalStateException("drive times down"));
        when(client.createAskMessage(params(), options(), gate())).thenReturn(
                toolTurn(tool("boom", "rank_spots", Map.of("maxDriveMinutes", 60))),
                submit(Map.of("answerable", false, "summary", "Can't tell.")));

        AskRun run = run(USER);

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.CANT);
        List<ToolResultBlockParam> results = new ArrayList<>();
        sentParams().get(1).messages().forEach(m -> {
            if (m.content().isContentBlockParams()) {
                m.content().asContentBlockParams().stream().filter(b -> b.isToolResult())
                        .map(b -> b.asToolResult()).forEach(results::add);
            }
        });
        assertThat(results).singleElement().satisfies(r -> {
            assertThat(r.isError()).contains(true);
            assertThat(r.content().orElseThrow().string().orElseThrow()).isEqualTo("The tool could not be run.");
        });
        assertThat(run.trace()).extracting(AskTools.ToolCall::tool).containsExactly("rank_spots", "submit_answer");
        assertThat(run.trace().getFirst().error()).isTrue();
    }

    @Test
    @DisplayName("an unknown tool name is cut at 40 characters without splitting a surrogate pair")
    void unknownToolNameIsCutOnACharacterBoundary() {
        String name = "t".repeat(39) + "😀" + "tail";
        when(client.createAskMessage(params(), options(), gate())).thenReturn(
                toolTurn(tool("odd", name, Map.of())),
                submit(Map.of("answerable", false, "summary", "Can't tell.")));

        AskRun run = run(USER);

        assertThat(run.trace().getFirst().tool()).isEqualTo("t".repeat(39) + "…");
        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.CANT);
    }

    // -- the night the Map is on ------------------------------------------------------------

    private AskSnapshot withNights() {
        return AskNightFixtures.withNights(snapshot(), null,
                AskNightFixtures.astroNight(AskNightFixtures.TONIGHT,
                        AskNightFixtures.astro(1L, "Bamburgh", "Northumberland", 5, 3)),
                AskNightFixtures.auroraNight(AskNightFixtures.TONIGHT,
                        AskNightFixtures.aurora(1L, "Bamburgh", "Northumberland", 5, 4, 3)));
    }

    private String systemPromptOfFirstRequest() {
        return sentParams().getFirst().system().orElseThrow().toString();
    }

    @Test
    @DisplayName("an astro night the Map is showing is named in the prompt, whoever the reader is")
    void astroContextForALiteReader() {
        when(client.createAskMessage(params(), options(), gate())).thenReturn(
                submit(Map.of("answerable", false, "summary", "Can't tell.")));

        engine.run(question("2026-10-05_astro"), withNights(), LITE, AskRunOptions.none());
        assertThat(systemPromptOfFirstRequest()).contains("astro night (windowId 2026-10-05_astro)");
    }

    @Test
    @DisplayName("an aurora night the reader may see is named in the prompt")
    void auroraContextForAProReader() {
        when(client.createAskMessage(params(), options(), gate())).thenReturn(
                submit(Map.of("answerable", false, "summary", "Can't tell.")));

        engine.run(question("2026-10-05_aurora"), withNights(), USER, AskRunOptions.none());

        assertThat(systemPromptOfFirstRequest()).contains("aurora night (windowId 2026-10-05_aurora)");
    }

    @Test
    @DisplayName("an aurora night is no context at all to a reader who may not see aurora")
    void auroraContextHiddenFromALiteReader() {
        when(client.createAskMessage(params(), options(), gate())).thenReturn(
                submit(Map.of("answerable", false, "summary", "Can't tell.")));

        engine.run(question("2026-10-05_aurora"), withNights(), LITE, AskRunOptions.none());

        assertThat(systemPromptOfFirstRequest()).doesNotContain("aurora night (windowId")
                .doesNotContain("2026-10-05_aurora");
    }

    @Test
    @DisplayName("the usage figure the engine logs is the reply's own")
    void usageIsTheRepliesOwn() {
        when(client.createAskMessage(params(), options(), gate())).thenReturn(
                submit(Map.of("answerable", false, "summary", "Can't tell.")));

        run(USER);

        assertThat(turns).singleElement().satisfies(t -> {
            TokenUsage usage = t.usage();
            assertThat(usage.inputTokens()).isEqualTo(1_000);
            assertThat(usage.outputTokens()).isEqualTo(100);
        });
    }
}
