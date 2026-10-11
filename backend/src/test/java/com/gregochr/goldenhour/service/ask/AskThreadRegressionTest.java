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
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.evaluation.AnthropicApiClient;
import com.gregochr.goldenhour.util.GeoUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Assertions pending the owner's approval (2026-10-11); written by the autonomous build from the plan's
 * per-step expectations — the owner may tighten or loosen them; until approved, a failure here is a
 * signal, not a gate.
 *
 * <p>Thread regression for Ask PhotoCast ({@code docs/engineering/ask-thread-plan.md}): the owner's own
 * four-question session through the REAL {@link ClaudeAskEngine} (real system prompt, real tool schemas,
 * real tool loop, real validator), each answerable step fed to the next as the thread, exactly as the
 * client carries it:
 * <ol>
 *   <li>"Anything for sunrise with tide alignment this weekend?"</li>
 *   <li>"Anything closer to home?"</li>
 *   <li>"Neither of those take my fancy, I've been to both recently. What's the best sunset option?"</li>
 *   <li>"Can you rank those options you returned? I'll decide sunrise or sunset."</li>
 * </ol>
 *
 * <p>Like {@link AskPromptRegressionTest} (which this class neither edits nor shares assertions with) it
 * asserts <b>structure only</b>, never wording, and the validator already guarantees the cheap invariants
 * (a pick is a live, pick-eligible slot in scope). What a failure here catches is the prompt, the tool
 * schemas or the thread rules drifting so a follow-up stops resolving "closer", "those" or "neither"
 * against the earlier answers.
 *
 * <p><b>The setup.</b> The fixture is the Ask prompt-regression snapshot with every date shifted four days
 * (Friday 9 October is {@code now}), so a Saturday sunrise exists for "this weekend"; the asker has a home
 * point at 54.97, -2.10, a saved radius of 25 miles and a drive time to every fixture location (20-85
 * minutes). The fixture carries no coordinates, so this class supplies them (one place, Cheviot Edge, is
 * inside the radius), and distances are computed with {@link GeoUtils#distanceMiles}.
 *
 * <p>The per-step expectations were written down before any run (plan section 3, T2) and match what the plan
 * promises, with one loosening recorded from the real runs on the T5 head: step 2 asserts only what is invariant.
 * Across three real runs "Anything closer to home?" has produced three honest shapes, all naming the radius: (1)
 * new picks inside the radius; (2) no picks at all, because with the fixture's coordinates only inland Cheviot Edge
 * is inside 25 miles and the model carried step 1's tide constraint, so nothing tide-aligned was there; (3) step 1's
 * own picks re-offered (both outside the radius) beside a summary saying nothing closer qualifies, which the
 * validator accepts because the thread offered them. So step 2 requires status OK (personal) or CANT, an answer
 * that names the radius, and every pick either inside the radius or one of step 1's picks, never a new place
 * outside it. Step 3's exclusion set is built from every earlier step's picks (so places step 2 re-offers are
 * still turned down), and step 4 needs only two picks, since steps 1 to 3 may offer few.
 *
 * <p><b>First passing run, 2026-10-11, on 297dfa82</b> (the coordinator's third paid run), verbatim. Step 1 OK:
 * Bamburgh 5 stars and Whitby at sunrise (trace list_windows, get_hot_topics, rank_spots, submit_answer). Step 2 OK,
 * personal: "Nothing pick-eligible with high tide alignment is within 25 miles of home for tomorrow's sunrise.
 * Bamburgh at 70 minutes remains the best option." (one pick, Bamburgh: the re-offer shape). Step 3 OK: Cheviot Edge
 * (today sunset), Saltburn (tomorrow sunset) and Seahouses (today sunset), Bamburgh and Whitby excluded by the
 * turn-down rule. Step 4 OK in one turn, submit_answer only: Bamburgh sunrise, Cheviot Edge sunset and Whitby
 * sunrise, ranked.
 *
 * <p>This class is excluded from {@code mvn verify} (tagged "prompt-regression") and from PIT, and skips
 * when no API key is present. Run on demand, spending real money (a few pence a run), with:
 * <pre>
 *   cd backend &amp;&amp; ANTHROPIC_API_KEY=sk-ant-... ./mvnw test -Pprompt-regression \
 *       -Dtest=AskThreadRegressionTest
 * </pre>
 *
 * <p>There is no second, "thread reset" case here: the reset (a typed exchange answered against another
 * forecast run drops the whole thread, and the engine is handed an empty one) is server-side logic in
 * {@code AskService}, so it is pinned without a real call by {@code AskServiceTest} and
 * {@code AskTypedControllerTest}.
 */
@Tag("prompt-regression")
class AskThreadRegressionTest {

    private static final String FIXTURE = "/prompt-regression/ask/ask-fixture-snapshot.json";

    /** Every date in the fixture moves this far, so Friday 9 October is "now" and Saturday has windows. */
    private static final int SHIFT_DAYS = 4;

    private static final double HOME_LAT = 54.97;
    private static final double HOME_LON = -2.10;
    private static final int RADIUS_MILES = 25;
    private static final long ASKER_ID = 7L;

    /** A signed-in Pro asker with a saved home, a 25-mile radius and stored drive times. */
    private static final AskUserContext ASKER = new AskUserContext(ASKER_ID, UserRole.PRO_USER, true,
            new AskUserContext.Home(HOME_LAT, HOME_LON, RADIUS_MILES));

    /** Fixture location id to its invented coordinates; Cheviot Edge (2) is the one place inside the radius. */
    private static final Map<Long, AskLocationNames.Place> PLACES = Map.of(
            1L, new AskLocationNames.Place("Bamburgh", 55.609, -1.710),
            2L, new AskLocationNames.Place("Cheviot Edge", 55.200, -2.120),
            3L, new AskLocationNames.Place("Kielder Wood", 55.230, -2.580),
            4L, new AskLocationNames.Place("Grey Moor", 55.000, -2.050),
            5L, new AskLocationNames.Place("Seahouses", 55.580, -1.650),
            6L, new AskLocationNames.Place("Whitby", 54.486, -0.614),
            7L, new AskLocationNames.Place("Saltburn", 54.580, -0.970),
            20L, new AskLocationNames.Place("Lonely Tarn", 54.600, -3.100));

    /** Drive minutes from home to each fixture location, 20 to 85. */
    private static final Map<Long, Integer> DRIVE_MINUTES = Map.of(
            1L, 70, 2L, 35, 3L, 55, 4L, 20, 5L, 72, 6L, 85, 7L, 80, 20L, 60);

    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** The daily job run the mocked accounting hands out; nothing is written anywhere. */
    private static final long DAILY_RUN_ID = 1L;

    private static JsonNode fixture;
    private static AskSnapshot snapshot;
    private static ClaudeAskEngine engine;

    @BeforeAll
    static void setUp() throws IOException {
        String apiKey = System.getProperty("ANTHROPIC_API_KEY", System.getenv("ANTHROPIC_API_KEY"));
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "ANTHROPIC_API_KEY is not set: the Ask thread regression makes real API calls");

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        try (InputStream in = AskThreadRegressionTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(in).as("fixture %s on the test classpath", FIXTURE).isNotNull();
            fixture = mapper.readTree(shiftDates(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
        snapshot = buildSnapshot(fixture);

        AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        AskJobRunService jobRuns = mock(AskJobRunService.class);
        when(jobRuns.dailyRunId()).thenReturn(DAILY_RUN_ID);
        when(jobRuns.accountingAvailable()).thenReturn(true);
        DriveTimeResolver driveTimes = mock(DriveTimeResolver.class);
        when(driveTimes.getAllMinutes(ASKER_ID)).thenReturn(DRIVE_MINUTES);
        engine = new ClaudeAskEngine(new AnthropicApiClient(client), new AskProperties(), jobRuns,
                driveTimes, new AskAnswerValidator(), new AskPromptBuilder(), mapper, Clock.systemUTC(),
                () -> PLACES);
    }

    @AfterAll
    static void tearDown() {
        if (engine != null) {
            engine.shutdown();
        }
    }

    /**
     * The owner's session, one step feeding the next as the client's thread. Each step's expectation is
     * the plan's; a step that is not answerable is not an exchange (the client never appends it), so the
     * later steps carry on from the last answered one.
     */
    @Test
    void ownersFourQuestionSession() {
        List<ThreadExchange> thread = new ArrayList<>();
        Set<PickKey> earlierPicks = new HashSet<>();
        Set<Long> earlierLocations = new HashSet<>();

        // 1. A fresh question: coastal, tide-aligned sunrise picks on the weekend.
        AskRun first = ask("Anything for sunrise with tide alignment this weekend?", thread);
        AskAnswer one = requireOk(first);
        assertThat(one.picks()).as("step 1 picks").hasSizeGreaterThanOrEqualTo(2);
        assertThat(one.picks()).as("step 1: every pick is a sunrise window on a Saturday or Sunday")
                .allSatisfy(pick -> {
                    assertThat(pick.targetType()).isEqualTo(AskWindowKind.SUNRISE);
                    assertThat(pick.date().getDayOfWeek()).isIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
                });
        assertThat(one.picks()).as("step 1: every pick is coastal with the tide on its own wanted water")
                .allSatisfy(pick -> assertThat(isCoastalAndAligned(pick.locationId()))
                        .as("%s is coastal and tideAligned in the fixture", pick.locationName()).isTrue());
        assertLiveAndRanked(one);
        remember(one, "anything for sunrise with tide alignment this weekend?", thread, earlierPicks,
                earlierLocations);

        // 2. "Closer to home" resolves to the saved radius. Three honest shapes have been seen; the invariant is
        // that the answer names the radius and never offers a NEW place outside it.
        AskRun second = ask("Anything closer to home?", thread);
        AskOutcome secondOutcome = second.outcome();
        assertThat(secondOutcome.status()).as("step 2 status (engine reason: %s)", second.reason())
                .isIn(AskOutcome.Status.OK, AskOutcome.Status.CANT);
        AskAnswer two = secondOutcome.answer();
        if (secondOutcome.status() == AskOutcome.Status.OK) {
            assertThat(secondOutcome.personal()).as("step 2 is personal (nearHome or a drive limit)").isTrue();
        }
        String said = (two.summary() + " " + two.missing()).toLowerCase(Locale.ROOT);
        assertThat(said).as("step 2 names the radius").containsAnyOf("miles of home", RADIUS_MILES + " miles",
                "radius");
        assertThat(two.picks()).as("step 2: each pick is inside the radius or one step 1 already gave")
                .allSatisfy(pick -> assertThat(milesFromHome(pick.locationId()) <= RADIUS_MILES
                        || earlierPicks.contains(new PickKey(pick.locationId(), pick.windowId())))
                        .as("%s at %s is %.1f miles from home and not one of step 1's picks",
                                pick.locationName(), pick.windowId(), milesFromHome(pick.locationId()))
                        .isTrue());
        assertLiveAndRanked(two);
        if (secondOutcome.status() == AskOutcome.Status.OK) {
            // An answered step is an exchange even with no pick; a can't never is (the client skips it).
            remember(two, "anything closer to home?", thread, earlierPicks, earlierLocations);
        }

        // 3. A turn-down: the new answer is a sunset and at none of the places already given.
        AskRun third = ask("Neither of those take my fancy, I've been to both recently. "
                + "What's the best sunset option?", thread);
        AskAnswer three = requireOk(third);
        assertThat(three.picks()).as("step 3 picks").isNotEmpty();
        assertThat(three.picks()).as("step 3: no place from steps 1 or 2, at any window")
                .extracting(AskPick::locationId).doesNotContainAnyElementsOf(earlierLocations);
        assertThat(three.picks()).as("step 3: every pick is a sunset window")
                .allSatisfy(pick -> assertThat(pick.targetType()).isEqualTo(AskWindowKind.SUNSET));
        assertLiveAndRanked(three);
        remember(three, "neither of those take my fancy, i've been to both recently. what's the best sunset option?",
                thread, earlierPicks, earlierLocations);

        // 4. Rank the options given: only places and windows already returned, at least two, ranked 1..n.
        AskRun fourth = ask("Can you rank those options you returned? I'll decide sunrise or sunset.", thread);
        AskAnswer four = requireOk(fourth);
        assertThat(four.picks()).as("step 4 picks").hasSizeGreaterThanOrEqualTo(2);
        assertThat(four.picks()).as("step 4: every pick was offered by steps 1 to 3")
                .allSatisfy(pick -> assertThat(earlierPicks).contains(new PickKey(pick.locationId(),
                        pick.windowId())));
        assertThat(four.picks()).extracting(pick -> new PickKey(pick.locationId(), pick.windowId()))
                .as("step 4: distinct by place and window").doesNotHaveDuplicates();
        assertThat(four.picks()).extracting(AskPick::rank)
                .as("step 4 ranks").containsExactlyElementsOf(IntStream.rangeClosed(1, four.picks().size())
                        .boxed().toList());
        assertLiveAndRanked(four);
    }

    // -- helpers ----------------------------------------------------------------------------

    private static AskRun ask(String text, List<ThreadExchange> thread) {
        AskQuestion question = new AskQuestion(text, text.toLowerCase(Locale.ROOT), null, AskScope.ALL, "plan");
        AskRun run = engine.run(question, snapshot, ASKER, AskRunOptions.typed(new AskThread(thread)));
        System.out.println("[ask-regression] thread=" + thread.size() + " " + text + " -> "
                + run.outcome().status() + " in " + run.outcome().turns() + " turn(s), personal="
                + run.outcome().personal() + ": "
                + (run.outcome().answer() == null ? run.reason() : run.outcome().answer().summary()));
        System.out.println("[ask-regression] trace: " + run.trace().stream()
                .map(call -> call.tool() + (call.error() ? "(error)" : "")).collect(Collectors.joining(", ")));
        if (run.outcome().answer() != null) {
            run.outcome().answer().picks().forEach(pick -> System.out.println("[ask-regression]   pick "
                    + pick.rank() + " " + pick.locationName() + " " + pick.windowId()));
        }
        return run;
    }

    private static AskAnswer requireOk(AskRun run) {
        assertThat(run.outcome().status()).as("status (engine reason: %s)", run.reason())
                .isEqualTo(AskOutcome.Status.OK);
        AskAnswer answer = run.outcome().answer();
        assertThat(answer.answerable()).isTrue();
        assertThat(answer.summary()).isNotBlank();
        return answer;
    }

    /** Every pick is a live, pick-eligible slot; ranks run 1..n; the fresh rule's 3 or a ranking's 4 at most. */
    private static void assertLiveAndRanked(AskAnswer answer) {
        List<AskPick> picks = answer.picks();
        assertThat(picks).hasSizeLessThanOrEqualTo(AskAnswerValidator.MAX_RANKED_PICKS);
        assertThat(picks).extracting(AskPick::rank)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, picks.size()).boxed().toList());
        assertThat(picks).allSatisfy(pick -> assertThat(snapshot.candidate(pick.windowId(), pick.locationId()))
                .as("pick %s at %s is a pick-eligible slot", pick.locationName(), pick.windowId()).isPresent());
    }

    /** Appends an answered step to the thread exactly as the client would. */
    private static void remember(AskAnswer answer, String question, List<ThreadExchange> thread,
            Set<PickKey> earlierPicks, Set<Long> earlierLocations) {
        List<ThreadExchange.PickRef> picks = answer.picks().stream()
                .map(pick -> new ThreadExchange.PickRef(pick.locationId(), pick.windowId())).toList();
        List<ThreadExchange.EventRef> events = answer.events().stream()
                .map(event -> new ThreadExchange.EventRef(event.type(), event.date())).toList();
        thread.add(new ThreadExchange(question, answer.summary(), picks, events, snapshot.generatedAt(), false));
        answer.picks().forEach(pick -> {
            earlierPicks.add(new PickKey(pick.locationId(), pick.windowId()));
            earlierLocations.add(pick.locationId());
        });
    }

    private static double milesFromHome(long locationId) {
        AskLocationNames.Place place = PLACES.get(locationId);
        return GeoUtils.distanceMiles(HOME_LAT, HOME_LON, place.lat(), place.lon());
    }

    private static boolean isCoastalAndAligned(long locationId) {
        for (JsonNode region : fixture.get("regions")) {
            for (JsonNode slot : region.get("slots")) {
                if (slot.get("locationId").asLong() == locationId) {
                    return slot.path("coastal").asBoolean(false) && slot.path("tideAligned").asBoolean(false);
                }
            }
        }
        return false;
    }

    /** A pick's identity for the thread: the place at a window. */
    private record PickKey(long locationId, String windowId) {
    }

    /** Moves every ISO date in the fixture text (dates, window ids, "now") forward by {@link #SHIFT_DAYS}. */
    private static String shiftDates(String json) {
        Matcher matcher = ISO_DATE.matcher(json);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out,
                    LocalDate.parse(matcher.group()).plusDays(SHIFT_DAYS).toString());
        }
        matcher.appendTail(out);
        return out.toString();
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
