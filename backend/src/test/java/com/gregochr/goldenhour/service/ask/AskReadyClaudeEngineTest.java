package com.gregochr.goldenhour.service.ask;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.repository.JobRunRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.HotTopicSimulationService;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
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

import static com.gregochr.goldenhour.service.ask.AskMessages.submit;
import static com.gregochr.goldenhour.service.ask.AskMessages.tool;
import static com.gregochr.goldenhour.service.ask.AskMessages.toolTurn;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.day;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.oct;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The Ready path end to end with the REAL Claude engine and only the SDK client scripted: a model
 * that reaches for the asker's drive times in a conversation with no asker is refused with an error
 * result (never an exception, never a read of anyone's drive times), carries on, and the precompute
 * stores its answer, billed to the {@code ASK_READY} run as a Ready turn.
 */
class AskReadyClaudeEngineTest {

    private static final long JOB_RUN_ID = 900L;
    private static final String TONIGHT = "2026-10-09_sunset";

    private final AnthropicApiClient client = mock(AnthropicApiClient.class);
    private final AskJobRunService askJobRuns = mock(AskJobRunService.class);
    private final DriveTimeResolver driveTimes = mock(DriveTimeResolver.class);
    private final RegionRepository regions = mock(RegionRepository.class);
    private final AskReadyStore store = mock(AskReadyStore.class);
    private final JobRunService jobRunService = mock(JobRunService.class);
    private final AskSnapshotBuilder snapshotBuilder = mock(AskSnapshotBuilder.class);
    private final List<AskJobRunService.Turn> turns = new ArrayList<>();
    private final AskProperties properties = new AskProperties();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-09T12:00:00Z"));
    private ClaudeAskEngine engine;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        when(askJobRuns.accountingAvailable()).thenReturn(true);
        org.mockito.Mockito.doAnswer(inv -> turns.add(inv.getArgument(0))).when(askJobRuns).recordTurn(any());
        engine = new ClaudeAskEngine(client, properties, askJobRuns, driveTimes, regions,
                new AskAnswerValidator(), new AskPromptBuilder(), new ObjectMapper(), clock);
        AskSnapshot snapshot = ReadyFixtures.at(ReadyFixtures.FRIDAY_NOON,
                List.of(day(oct(9), false, true, null, ReadyFixtures.teesdale())),
                List.of(AskFixtures.topic("AURORA", "Aurora tonight", "Kp 6", oct(9), List.of()),
                        AskFixtures.topic("SNOW", "Snow on the Cheviot", "Fresh snow", oct(9), List.of())));
        when(snapshotBuilder.build()).thenReturn(Optional.of(snapshot));
        when(regions.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of());
        when(jobRunService.startRun(any(RunType.class), anyBoolean(), any(EvaluationModel.class)))
                .thenReturn(JobRunEntity.builder().id(JOB_RUN_ID).runType(RunType.ASK_READY).build());
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    @Test
    @DisplayName("a Ready conversation whose model asks for a drive limit gets a tool error, is not personal, "
            + "reads no drive times, and its answer is stored; every turn is billed to the ASK_READY run")
    void driveLimitIsRefusedInAReadyConversation() {
        when(client.createAskMessage(any(), any(), any())).thenReturn(
                // BEST_NEXT: a refused drive-limited call, then the real ranking, then the answer.
                toolTurn(tool("t1", "rank_spots", Map.of("maxDriveMinutes", 30))),
                toolTurn(tool("t2", "rank_spots", Map.of("limit", 3))),
                submit(Map.of("answerable", true, "summary", "Hamsterley looks best tonight.", "picks",
                        List.of(Map.of("locationId", 10, "windowId", TONIGHT, "why", "Clear at sunset.")))),
                // RARE_EVENTS and SNOW_TOPS.
                toolTurn(tool("t3", "get_hot_topics", Map.of())),
                submit(Map.of("answerable", true, "summary", "Aurora is forecast.", "events",
                        List.of(Map.of("type", "AURORA", "why", "Kp 6.")))),
                toolTurn(tool("t4", "get_hot_topics", Map.of())),
                submit(Map.of("answerable", true, "summary", "Snow on the Cheviot.", "events",
                        List.of(Map.of("type", "SNOW", "why", "Fresh snow.")))));
        AskReadyService service = new AskReadyService(properties, snapshotBuilder, engine, regions, store,
                jobRunService, mock(JobRunRepository.class), mock(HotTopicSimulationService.class),
                mock(AuroraStateCache.class), clock, Duration.ofMinutes(5));

        AskReadyService.Result result = service.precompute(42L);

        // Three asked; the weekend, next-few-days, coastal-high and sunrise-or-sunset questions are not
        // available on a forecast of one inland sunset.
        assertThat(result).isEqualTo(new AskReadyService.Result(3, 4, 0, null));
        List<MessageCreateParams> sent = sent();
        ToolResultBlockParam refusal = toolResultsIn(sent.get(1)).getFirst();
        assertThat(refusal.isError()).contains(true);
        assertThat(refusal.content().orElseThrow().string().orElseThrow()).contains("not available");
        verifyNoInteractions(driveTimes);

        ArgumentCaptor<AskAnswer> answer = ArgumentCaptor.forClass(AskAnswer.class);
        ArgumentCaptor<ReadyQuestion> question = ArgumentCaptor.forClass(ReadyQuestion.class);
        verify(store, org.mockito.Mockito.times(3)).upsert(any(), question.capture(), any(), answer.capture(),
                any(), any());
        assertThat(question.getAllValues()).containsExactly(ReadyQuestion.BEST_NEXT, ReadyQuestion.RARE_EVENTS,
                ReadyQuestion.SNOW_TOPS);
        assertThat(answer.getAllValues().getFirst().picks()).singleElement().satisfies(p -> {
            assertThat(p.locationName()).isEqualTo("Hamsterley");
            assertThat(p.windowId()).isEqualTo(TONIGHT);
            assertThat(p.ratingAtAnswer()).isEqualTo(4);
        });
        assertThat(turns).hasSize(7).allSatisfy(t -> {
            assertThat(t.ready()).isTrue();
            assertThat(t.runId()).isEqualTo(JOB_RUN_ID);
        });
        verify(askJobRuns, org.mockito.Mockito.never()).dailyRunId();
    }

    private List<MessageCreateParams> sent() {
        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(client, atLeastOnce()).createAskMessage(captor.capture(), any(), any());
        return captor.getAllValues();
    }

    private static List<ToolResultBlockParam> toolResultsIn(MessageCreateParams params) {
        List<ToolResultBlockParam> out = new ArrayList<>();
        params.messages().forEach(m -> {
            if (m.content().isBlockParams()) {
                m.content().asBlockParams().stream().filter(ContentBlockParam::isToolResult)
                        .map(ContentBlockParam::asToolResult).forEach(out::add);
            }
        });
        return out;
    }
}
