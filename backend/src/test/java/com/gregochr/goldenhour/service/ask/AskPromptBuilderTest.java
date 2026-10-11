package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.ask.ThreadExchange.EventRef;
import com.gregochr.goldenhour.service.ask.ThreadExchange.PickRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for {@link AskPromptBuilder}. */
class AskPromptBuilderTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);

    private final AskPromptBuilder builder = new AskPromptBuilder();

    @Test
    @DisplayName("states today's UK date with the day name, and the rules")
    void statesTodayAndTheRules() {
        String prompt = builder.systemPrompt(MONDAY, TestScopes.of(), null, true);

        assertThat(prompt).contains("Today is Monday 5 October 2026 in the UK.")
                .contains("every region")
                .contains("submit_answer")
                .contains("under 22 words")
                .contains("tideAligned is true")
                .contains("bestBet")
                .contains("Never give advice about access, parking, walking")
                .contains("No web addresses");
    }

    @Test
    @DisplayName("names the scope's regions, sorted, when the question has one")
    void namesTheScope() {
        String prompt = builder.systemPrompt(MONDAY, TestScopes.of("Teesdale", "Northumberland"), null,
                true);

        assertThat(prompt).contains("these regions only: Northumberland, Teesdale.")
                .doesNotContain("every region");
    }

    @Test
    @DisplayName("states the window the reader is looking at, with its id")
    void statesTheContextWindow() {
        AskSnapshot.Window window = new AskSnapshot.Window("2026-10-10_sunrise", LocalDate.of(2026, 10, 10),
                TargetType.SUNRISE, LocalDateTime.of(2026, 10, 10, 6, 0), null, null, null, List.of());

        String prompt = builder.systemPrompt(MONDAY, TestScopes.of(), window, true);

        assertThat(prompt).contains("looking at Saturday 10 October sunrise (windowId 2026-10-10_sunrise)");
    }

    @Test
    @DisplayName("a user-less conversation is told never to mention home; a typed one is told about "
            + "drive times, and never both")
    void userLessAndTypedDiffer() {
        String shared = builder.systemPrompt(MONDAY, TestScopes.of(), null, false);
        String typed = builder.systemPrompt(MONDAY, TestScopes.of(), null, true);

        assertThat(shared).contains("never mention home, distance or drive times")
                .doesNotContain("maxDriveMinutes");
        assertThat(typed).contains("maxDriveMinutes").doesNotContain("never mention home");
    }

    @Test
    @DisplayName("the model may never ask the reader anything, and 'close to home' is nearHome, a number of "
            + "minutes is maxDriveMinutes, 'closer' narrows the earlier drive (the 60-minute rule is gone)")
    void neverAsksAndReadsCloseToHomeAsNearHome() {
        String typed = builder.systemPrompt(MONDAY, TestScopes.of(), null, true).replaceAll("\\s+", " ");

        assertThat(typed).contains("never ask the reader a question")
                .contains("'Close to home', 'near', 'nearby' or 'local' with no number: pass rank_spots' "
                        + "nearHome true")
                .contains("A number of minutes: pass it as maxDriveMinutes")
                .contains("'Closer' in a follow-up: pass a maxDriveMinutes smaller than the earlier picks' "
                        + "drive, or nearHome true if it was not used")
                .contains("within N miles of home")
                .contains("never say within reach")
                .doesNotContain("pass 60").doesNotContain("an hour's drive");
    }

    @Test
    @DisplayName("a user-less prompt says nothing of nearHome")
    void userLessPromptDoesNotMentionNearHome() {
        assertThat(builder.systemPrompt(MONDAY, TestScopes.of(), null, false)).doesNotContain("nearHome");
    }

    @Test
    @DisplayName("an events question must consult BOTH events tools, and 'no events' is allowed only after both "
            + "returned nothing")
    void eventsQuestionsRequireBothTools() {
        String prompt = builder.systemPrompt(MONDAY, TestScopes.of(), null, true)
                .replaceAll("\\s+", " ");

        assertThat(prompt).contains("call BOTH get_hot_topics")
                .contains("and get_coming_up")
                .contains("Say there are no events only after both returned nothing");
    }

    // -- the thread ---------------------------------------------------------------------------

    @Test
    @DisplayName("a fresh question's system prompt is byte for byte what it was before threads, whichever "
            + "overload builds it")
    void freshSystemPromptIsUnchanged() {
        for (boolean hasUser : new boolean[] {true, false}) {
            String legacy = builder.systemPrompt(MONDAY, TestScopes.of(), null, hasUser);

            assertThat(builder.systemPrompt(MONDAY, TestScopes.of(), null, hasUser, false))
                    .isEqualTo(legacy);
            assertThat(legacy).doesNotContain("Earlier picks").doesNotContain("emoji");
        }
    }

    @Test
    @DisplayName("a follow-up's system prompt gains the plan's rule word for word, the emoji line and the "
            + "gone-places line, after everything else")
    void threadSystemPromptGainsTheRules() {
        String fresh = builder.systemPrompt(MONDAY, TestScopes.of(), null, true);

        String threaded = builder.systemPrompt(MONDAY, TestScopes.of(), null, true, true);

        assertThat(threaded).startsWith(fresh).endsWith(AskPromptBuilder.THREAD_RULES);
        assertThat(threaded.replaceAll("\\s+", " ")).contains("Earlier picks are real candidates: you may "
                + "rank, compare, or exclude them. Resolve 'closer', 'earlier', 'the other one', 'those' "
                + "against them. Call `rank_spots` again when the reader asks for new places; you do not "
                + "need to for a question only about the earlier picks.")
                .contains("Never use emoji or symbols other than ★ in the summary.")
                .contains("no longer in the forecast, or outside the area, cannot be a pick");
    }

    @Test
    @DisplayName("a fresh question's user message is the question and nothing else")
    void freshUserMessageIsTheQuestion() {
        assertThat(builder.userMessage("best spot this weekend", AskThreadContext.NONE))
                .isEqualTo("best spot this weekend");
        assertThat(builder.userMessage("best spot this weekend", null)).isEqualTo("best spot this weekend");
    }

    @Test
    @DisplayName("golden: a follow-up's user message is the pinned text - earlier exchanges re-joined live, "
            + "a place outside the area, a place that has gone, an event no longer flagged")
    void threadUserMessageIsTheGolden() throws Exception {
        assertThat(builder.userMessage("what about sunrise instead?", goldenThread()))
                .isEqualTo(golden("user-message-thread.txt"));
    }

    private static AskThreadContext turnedDownThread(boolean turnedDown) {
        BriefingRegion coast = AskFixtures.region("Coast", true, AskFixtures.coastal(1L, "Whitby", 4, "HIGH", true));
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(MONDAY, null, coast)), List.of()));
        AskTools tools = new AskTools(snapshot, new AskUserContext(7L, UserRole.PRO_USER, false),
                TestScopes.of(), mock(DriveTimeResolver.class), new ObjectMapper());
        ThreadExchange earlier = new ThreadExchange("best sunset?", "Whitby.",
                List.of(new PickRef(1L, "2026-10-05_sunset")), List.of(), AskFixtures.GENERATED_AT, false);
        return AskThreadContext.of(new AskThread(List.of(earlier)), snapshot, TestScopes.of(), tools,
                AskLocationNames.NONE, turnedDown);
    }

    @Test
    @DisplayName("turned down: the earlier pick is marked, and the message says turned-down places must not be "
            + "picked again, before the question")
    void turnedDownUserMessage() {
        String message = builder.userMessage("neither of those, best sunset?", turnedDownThread(true));

        assertThat(message).contains("1. Whitby (Coast) · locationId 1")
                .contains(" · the reader has turned this place down\n")
                .contains("Turned-down places must not be picked again")
                .endsWith("Now the reader asks: \"neither of those, best sunset?\"");
        assertThat(message.indexOf("Turned-down places")).isLessThan(message.indexOf("Now the reader asks"));
    }

    @Test
    @DisplayName("not turned down: neither the mark nor the rule line appears")
    void notTurnedDownUserMessage() {
        String message = builder.userMessage("best sunset?", turnedDownThread(false));

        assertThat(message).doesNotContain("turned this place down").doesNotContain("Turned-down");
    }

    private static AskThreadContext goldenThread() {
        LocalDate wednesday = MONDAY.plusDays(2);
        BriefingRegion coast = AskFixtures.region("Coast", true,
                AskFixtures.coastal(1L, "Whitby", 4, "HIGH", true),
                AskFixtures.coastal(2L, "Saltburn", 4, "LOW", false));
        BriefingRegion hills = AskFixtures.region("Hills", true, AskFixtures.slot(3L, "Kielder", 5));
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(MONDAY, null, coast, hills)),
                List.of(AskFixtures.topic("KING_TIDE", "King tide", "Big water", wednesday,
                        List.of("Coast")))));
        DriveTimeResolver driveTimes = mock(DriveTimeResolver.class);
        when(driveTimes.getAllMinutes(7L)).thenReturn(Map.of(1L, 55, 2L, 80, 3L, 40));
        AskScope scope = TestScopes.of("Coast");
        AskTools tools = new AskTools(snapshot, new AskUserContext(7L, UserRole.PRO_USER, true), scope,
                driveTimes, new ObjectMapper());
        ThreadExchange first = new ThreadExchange("anything for sunset with the tide right?",
                "Whitby and Saltburn both work, Whitby has the water.",
                List.of(new PickRef(1L, "2026-10-05_sunset"), new PickRef(2L, "2026-10-05_sunset")),
                List.of(new EventRef("KING_TIDE", wednesday)), AskFixtures.GENERATED_AT, false);
        ThreadExchange second = new ThreadExchange("Thanks. Anything closer to home?",
                "Kielder is 40 minutes away and rated 5★.",
                List.of(new PickRef(3L, "2026-10-05_sunset"), new PickRef(41L, "2026-10-05_sunrise")),
                List.of(new EventRef("AURORA", wednesday)), AskFixtures.GENERATED_AT, false);
        return AskThreadContext.of(new AskThread(List.of(first, second)), snapshot, scope, tools,
                () -> Map.of(41L, new AskLocationNames.Place("Dunstanburgh", 55.5, -1.6)));
    }

    private static String golden(String name) throws Exception {
        try (var in = AskPromptBuilderTest.class.getResourceAsStream("/ask/" + name)) {
            return new String(java.util.Objects.requireNonNull(in, name).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
