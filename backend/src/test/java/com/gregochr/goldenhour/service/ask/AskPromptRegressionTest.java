package com.gregochr.goldenhour.service.ask;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.evaluation.AnthropicApiClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Prompt regression for Ask PhotoCast: three questions through the REAL {@link ClaudeAskEngine}
 * (real system prompt, real tool schemas, real tool loop, real validator) against a small fixed
 * forecast, with the model on the other end of a real API call.
 *
 * <p>These assert <b>structure only</b>, never wording. The engine's validator already guarantees
 * the cheap invariants (a pick is a pair a tool returned and is still pick-eligible, an event is a
 * type a tool returned, summaries are word-capped); what a regression here catches is the system
 * prompt or the tool schemas drifting so the model stops using the tools well: no picks, picks it
 * was never offered, the BEST BET ignored, a rare-events answer with no events, or an
 * unanswerable question it answers anyway. A failed run is itself a failure, with the engine's
 * reason in the message.
 *
 * <p>The fixture is {@code prompt-regression/ask/ask-fixture-snapshot.json}: Monday 5 October 2026,
 * three windows (tonight's sunset, tomorrow's sunrise with a BEST BET on Bamburgh, tomorrow's
 * sunset), two eligible regions with coastal spots at both tide states and a wood, one region the
 * Plan tab's sample gate refuses (it holds a 5-star slot that must never be offered), and three hot
 * topics, one of them a solar eclipse carrying its lens-filter safety note.
 *
 * <p>This class is excluded from {@code mvn verify} (tagged "prompt-regression") and from PIT, and
 * skips when no API key is present. Run on demand, spending real money (a few pence), with:
 * <pre>
 *   cd backend && ANTHROPIC_API_KEY=sk-ant-... ./mvnw test -Pprompt-regression \
 *       -Dtest=AskPromptRegressionTest
 * </pre>
 *
 * <p>The assertions in this class are the owner's: they encode what a good answer must still be,
 * and may be changed only by the owner.
 */
@Tag("prompt-regression")
class AskPromptRegressionTest {

    private static final String FIXTURE = "/prompt-regression/ask/ask-fixture-snapshot.json";

    /** A signed-in Pro asker with no stored drive times: the conversation is user-bound, not personal. */
    private static final AskUserContext ASKER = new AskUserContext(7L, UserRole.PRO_USER, false);

    /** The daily job run the mocked accounting hands out; nothing is written anywhere. */
    private static final long DAILY_RUN_ID = 1L;

    private static JsonNode fixture;
    private static AskSnapshot snapshot;
    private static ClaudeAskEngine engine;

    @BeforeAll
    static void setUp() throws IOException {
        String apiKey = System.getProperty("ANTHROPIC_API_KEY", System.getenv("ANTHROPIC_API_KEY"));
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "ANTHROPIC_API_KEY is not set: the Ask prompt regression makes real API calls");

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        try (InputStream in = AskPromptRegressionTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(in).as("fixture %s on the test classpath", FIXTURE).isNotNull();
            fixture = mapper.readTree(in);
        }
        snapshot = buildSnapshot(fixture);

        AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        AskJobRunService jobRuns = mock(AskJobRunService.class);
        when(jobRuns.dailyRunId()).thenReturn(DAILY_RUN_ID);
        when(jobRuns.accountingAvailable()).thenReturn(true);
        engine = new ClaudeAskEngine(new AnthropicApiClient(client), new AskProperties(), jobRuns,
                mock(DriveTimeResolver.class), mock(RegionRepository.class), new AskAnswerValidator(),
                new AskPromptBuilder(), mapper, Clock.systemUTC());
    }

    @AfterAll
    static void tearDown() {
        if (engine != null) {
            engine.shutdown();
        }
    }

    /**
     * "Best spot tomorrow morning?" — a where question. The answer must be 2 or 3 picks at distinct
     * locations, each a slot the tools offer (so never the wood, never a slot rated under 3, never
     * the region the sample gate refuses), the first on the BEST BET window the fixture names.
     */
    @Test
    void whereQuestion_picksOfferedSpotsAndLeadsWithBestBet() {
        AskRun run = ask("Best spot tomorrow morning?");

        assertThat(run.outcome().status())
                .as("status (engine reason: %s)", run.reason())
                .isEqualTo(AskOutcome.Status.OK);
        AskAnswer answer = run.outcome().answer();
        assertThat(answer.answerable()).isTrue();

        assertThat(run.trace()).extracting(AskTools.ToolCall::tool).contains("rank_spots");

        List<AskPick> picks = answer.picks();
        assertThat(picks).hasSizeBetween(2, 3);
        assertThat(picks).extracting(AskPick::locationId).doesNotHaveDuplicates();
        assertThat(picks).extracting(AskPick::rank)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, picks.size()).boxed().toList());
        assertThat(picks).allSatisfy(pick -> assertThat(snapshot.candidate(pick.windowId(), pick.locationId()))
                .as("pick %s at %s is a pick-eligible slot the tools offered", pick.locationName(),
                        pick.windowId())
                .isPresent());

        assertThat(picks).extracting(AskPick::regionName).doesNotContain(ineligibleRegionName());
        assertThat(picks).extracting(AskPick::locationName).doesNotContain(woodName());

        assertThat(picks.get(0).windowId()).as("pick 1 is on the BEST BET window")
                .isEqualTo(bestBetWindowId());

        assertThat(answer.summary()).isNotBlank();
        assertThat(answer.summary().trim().split("\\s+").length)
                .as("summary words")
                .isLessThanOrEqualTo(AskAnswerValidator.SUMMARY_WORDS);
    }

    /**
     * "Any rare events coming up?" — an events question. The answer must carry at least one event,
     * every event a type a tool returned, and the solar eclipse must carry the fixture's exact
     * safety note: the note is joined by the server from the served topic and is never the model's,
     * so it must survive whatever the model writes.
     */
    @Test
    void rareEventsQuestion_returnsToolEventsAndTheEclipseSafetyNote() {
        AskRun run = ask("Any rare events coming up?");

        assertThat(run.outcome().status())
                .as("status (engine reason: %s)", run.reason())
                .isEqualTo(AskOutcome.Status.OK);
        AskAnswer answer = run.outcome().answer();
        assertThat(answer.answerable()).isTrue();

        assertThat(run.trace()).extracting(AskTools.ToolCall::tool)
                .containsAnyOf("get_hot_topics", "get_coming_up");

        List<AskEvent> events = answer.events();
        assertThat(events).isNotEmpty();
        Set<String> offeredTypes = snapshot.hotTopics().stream().map(AskSnapshot.Topic::type)
                .collect(Collectors.toSet());
        assertThat(events).extracting(AskEvent::type).isSubsetOf(offeredTypes);

        AskEvent eclipse = events.stream().filter(e -> "ECLIPSE".equals(e.type())).findFirst()
                .orElseThrow(() -> new AssertionError("the fixture's solar eclipse is not among the events"));
        assertThat(eclipse.safetyNote()).isEqualTo(eclipseSafetyNote());
        assertThat(events).filteredOn(e -> !"ECLIPSE".equals(e.type()))
                .allSatisfy(e -> assertThat(e.safetyNote()).isNull());

        assertThat(answer.summary()).isNotBlank();
        assertThat(answer.summary().trim().split("\\s+").length)
                .as("summary words")
                .isLessThanOrEqualTo(AskAnswerValidator.SUMMARY_WORDS);
    }

    /**
     * "What's the pollen count?" — something no tool can answer. (The production pre-filter stops
     * crowd, parking and opening-times questions before they reach Claude, so this is the engine's
     * own can't-answer path: the model must say so, name what is missing, and offer nothing.)
     */
    @Test
    void unanswerableQuestion_isCantWithMissingAndNoCards() {
        AskRun run = ask("What's the pollen count?");

        assertThat(run.outcome().status())
                .as("status (engine reason: %s)", run.reason())
                .isEqualTo(AskOutcome.Status.CANT);
        AskAnswer answer = run.outcome().answer();
        assertThat(answer.answerable()).isFalse();
        assertThat(answer.missing()).isNotBlank();
        assertThat(answer.picks()).isEmpty();
        assertThat(answer.events()).isEmpty();
        assertThat(answer.summary()).isNotBlank();
    }

    // -- helpers ----------------------------------------------------------------------------

    private static AskRun ask(String text) {
        AskQuestion question = new AskQuestion(text, text.toLowerCase(Locale.ROOT), null, List.of(), "plan");
        AskRun run = engine.run(question, snapshot, ASKER, AskRunOptions.none());
        System.out.println("[ask-regression] " + text + " -> " + run.outcome().status()
                + " in " + run.outcome().turns() + " turn(s): "
                + (run.outcome().answer() == null ? run.reason() : run.outcome().answer().summary()));
        System.out.println("[ask-regression] trace: " + run.trace().stream()
                .map(call -> call.tool() + (call.error() ? "(error)" : "")).collect(Collectors.joining(", ")));
        return run;
    }

    private static String bestBetWindowId() {
        for (JsonNode window : fixture.get("windows")) {
            if (window.has("bestBet")) {
                return windowId(window);
            }
        }
        throw new IllegalStateException("the fixture names no BEST BET window");
    }

    private static String ineligibleRegionName() {
        for (JsonNode region : fixture.get("regions")) {
            if (!region.get("eligible").asBoolean()) {
                return region.get("name").asText();
            }
        }
        throw new IllegalStateException("the fixture has no ineligible region");
    }

    private static String woodName() {
        for (JsonNode region : fixture.get("regions")) {
            for (JsonNode slot : region.get("slots")) {
                if (slot.path("canopy").asBoolean(false)) {
                    return slot.get("name").asText();
                }
            }
        }
        throw new IllegalStateException("the fixture has no wood");
    }

    private static String eclipseSafetyNote() {
        for (JsonNode topic : fixture.get("topics")) {
            if ("ECLIPSE".equals(topic.get("type").asText())) {
                return topic.get("safetyNote").asText();
            }
        }
        throw new IllegalStateException("the fixture has no eclipse topic");
    }

    private static String windowId(JsonNode window) {
        return AskWindowId.format(LocalDate.parse(window.get("date").asText()),
                TargetType.valueOf(window.get("type").asText()));
    }

    /** Builds the snapshot through the real builder from the fixture's compact description. */
    private static AskSnapshot buildSnapshot(JsonNode root) {
        LocalDateTime now = LocalDateTime.parse(root.get("now").asText());
        Map<LocalDate, List<BriefingEventSummary>> byDate = new LinkedHashMap<>();
        for (JsonNode window : root.get("windows")) {
            String id = windowId(window);
            List<BriefingRegion> regions = new ArrayList<>();
            for (JsonNode region : root.get("regions")) {
                List<BriefingSlot> slots = new ArrayList<>();
                for (JsonNode slot : region.get("slots")) {
                    if (slot.get("ratings").has(id)) {
                        slots.add(slot(slot, slot.get("ratings").get(id).asInt()));
                    }
                }
                if (!slots.isEmpty()) {
                    regions.add(AskFixtures.region(region.get("name").asText(),
                            region.get("eligible").asBoolean(), slots.toArray(BriefingSlot[]::new)));
                }
            }
            LocalDate date = LocalDate.parse(window.get("date").asText());
            BriefingWindow servedWindow = AskFixtures.window(
                    date.atTime(LocalTime.parse(window.get("time").asText())), DisplayVerdict.WORTH_IT,
                    window.get("best").asInt(), bestBet(window));
            byDate.computeIfAbsent(date, d -> new ArrayList<>()).add(AskFixtures.summary(
                    TargetType.valueOf(window.get("type").asText()), servedWindow,
                    regions.toArray(BriefingRegion[]::new)));
        }
        List<BriefingDay> days = new ArrayList<>();
        byDate.forEach((date, summaries) ->
                days.add(AskFixtures.day(date, summaries.toArray(BriefingEventSummary[]::new))));

        List<HotTopic> topics = new ArrayList<>();
        for (JsonNode topic : root.get("topics")) {
            List<String> regions = new ArrayList<>();
            topic.get("regions").forEach(r -> regions.add(r.asText()));
            HotTopic served = AskFixtures.topic(topic.get("type").asText(), topic.get("label").asText(),
                    topic.get("detail").asText(), LocalDate.parse(topic.get("date").asText()), regions);
            topics.add(topic.has("safetyNote") ? served.withSafety(topic.get("safetyNote").asText()) : served);
        }
        DailyBriefingResponse briefing = AskFixtures.briefing(days, topics);
        return AskFixtures.snapshotAt(now, briefing, List.of());
    }

    private static BriefingSlot slot(JsonNode slot, int rating) {
        long id = slot.get("locationId").asLong();
        String name = slot.get("name").asText();
        BriefingSlot built;
        if (slot.path("canopy").asBoolean(false)) {
            built = AskFixtures.wood(id, name, rating);
        } else if (slot.path("coastal").asBoolean(false)) {
            built = AskFixtures.coastal(id, name, rating, slot.get("tideState").asText(),
                    slot.get("tideAligned").asBoolean());
        } else {
            built = AskFixtures.slot(id, name, rating);
        }
        return AskFixtures.withHeadline(built, slot.get("headline").asText());
    }

    private static BriefingWindow.Pick bestBet(JsonNode window) {
        if (!window.has("bestBet")) {
            return null;
        }
        JsonNode pick = window.get("bestBet");
        return AskFixtures.pick(BriefingWindow.PickKind.BEST, pick.get("region").asText(),
                pick.get("location").asText(), pick.get("locationId").asLong());
    }
}
