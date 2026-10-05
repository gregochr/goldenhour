package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.EclipseHotTopicStrategy;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.BestAnchor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.gregochr.goldenhour.service.ask.AskFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StubAskEngine}: it answers from the real tools, its answers are held to the
 * real validator, and it honours scope, the context window and the Ready {@code BEST_*} anchor.
 */
class StubAskEngineTest {

    private static final String TODAY_SUNSET = "2026-10-05_sunset";
    private static final String TOMORROW_SUNRISE = "2026-10-06_sunrise";
    private static final AskUserContext USER = new AskUserContext(7L, UserRole.PRO_USER, true);

    private final RegionRepository regions = mock(RegionRepository.class);
    private final DriveTimeResolver driveTimes = mock(DriveTimeResolver.class);
    private final AskAnswerValidator validator = new AskAnswerValidator();
    private final StubAskEngine engine =
            new StubAskEngine(validator, driveTimes, regions, new ObjectMapper());

    // -- fixtures ---------------------------------------------------------------------------

    private static BriefingRegion coast() {
        return AskFixtures.region("Coast", true,
                AskFixtures.coastal(1L, "Whitby", 5, "HIGH", true),
                AskFixtures.coastal(2L, "Saltburn", 4, "LOW", false),
                AskFixtures.slot(3L, "Inland Edge", 4),
                AskFixtures.slot(4L, "Poor Moor", 2),
                AskFixtures.wood(5L, "Hollin Wood", 5));
    }

    private static BriefingRegion hills() {
        return AskFixtures.region("Hills", true, AskFixtures.slot(10L, "Hill Top", 3),
                AskFixtures.slot(11L, "High Crag", 4));
    }

    /** Today's sunset window then tomorrow's sunrise, both holding both regions. */
    private static AskSnapshot snapshot(BriefingWindow.Pick sunsetPick) {
        BriefingDay today = AskFixtures.sunsetDay(TODAY, sunsetPick, coast(), hills());
        BriefingWindow sunrise = AskFixtures.window(TODAY.plusDays(1).atTime(6, 0),
                DisplayVerdict.WORTH_IT, 4, null);
        BriefingDay tomorrow = AskFixtures.day(TODAY.plusDays(1),
                AskFixtures.summary(TargetType.SUNRISE, sunrise,
                        AskFixtures.region("Coast", true, AskFixtures.slot(1L, "Whitby", 3),
                                AskFixtures.slot(3L, "Inland Edge", 5)),
                        hills()));
        return AskFixtures.snapshotOf(AskFixtures.briefing(List.of(today, tomorrow), List.of(
                AskFixtures.topic("AURORA", "Aurora tonight", "Kp 6 forecast", TODAY, List.of("Coast")),
                AskFixtures.topic("SNOW", "Snow on the tops", "Fresh snow above 600 m", TODAY.plusDays(1),
                        List.of("Hills")))));
    }

    private static AskQuestion question(String text) {
        return new AskQuestion(text, text.toLowerCase(), null, List.of(), "plan");
    }

    private AskRun run(String text) {
        return engine.run(question(text), snapshot(null), USER, AskRunOptions.none());
    }

    private static List<String> names(AskRun run) {
        return run.outcome().answer().picks().stream().map(AskPick::locationName).toList();
    }

    // -- where and when ---------------------------------------------------------------------

    @Test
    @DisplayName("a where question gets the top three spots at different locations, best first, joined to "
            + "served facts, and a trace ending in submit_answer")
    void topThreeDistinctSpots() {
        AskRun run = run("Where is the best spot?");

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.OK);
        assertThat(run.reason()).isNull();
        assertThat(run.outcome().turns()).isEqualTo(1);
        assertThat(run.outcome().personal()).isFalse();
        List<AskPick> picks = run.outcome().answer().picks();
        // Whitby 5 (sunset), then the 5 at Inland Edge tomorrow, then Saltburn / High Crag at 4.
        assertThat(picks).extracting(AskPick::locationName).containsExactly("Whitby", "Inland Edge", "High Crag");
        assertThat(picks).extracting(AskPick::locationId).doesNotHaveDuplicates();
        assertThat(picks).extracting(AskPick::rank).containsExactly(1, 2, 3);
        assertThat(picks.getFirst().ratingAtAnswer()).isEqualTo(5);
        assertThat(picks.getFirst().regionName()).isEqualTo("Coast");
        assertThat(picks.getFirst().why()).contains("Rated 5").contains("today sunset").contains("tide suits");
        assertThat(run.outcome().answer().summary()).startsWith("Whitby at today sunset looks strongest at 5★")
                .contains("followed by Inland Edge and High Crag");
        assertThat(run.trace()).extracting(AskTools.ToolCall::tool)
                .containsExactly("rank_spots", "submit_answer");
    }

    @Test
    @DisplayName("the stub never offers a wood, a slot under 3 stars, or a region the sample gate refuses")
    void neverOffersWhatTheToolsRefuse() {
        BriefingRegion refused = AskFixtures.region("Thin", false, AskFixtures.slot(40L, "Forced A", 4),
                AskFixtures.slot(41L, "Forced B", 4));
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(TODAY, null, coast(), refused)), List.of()));

        AskRun run = engine.run(question("best place"), snapshot, USER, AskRunOptions.none());

        assertThat(names(run)).doesNotContain("Hollin Wood", "Poor Moor", "Forced A", "Forced B");
    }

    @Test
    @DisplayName("scope: a question about one region is answered from that region only")
    void scopeNarrowsThePicks() {
        when(regions.findAllById(Set.of(2L))).thenReturn(List.of(region(2L, "Hills")));
        AskQuestion scoped = new AskQuestion("best spot", "best spot", null, List.of(2L), "map");

        AskRun run = engine.run(scoped, snapshot(null), USER, AskRunOptions.none());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.OK);
        assertThat(run.outcome().answer().picks()).extracting(AskPick::regionName).containsOnly("Hills");
        assertThat(names(run)).containsExactly("High Crag", "Hill Top");
    }

    @Test
    @DisplayName("an unknown region id fails the run before any tool call: scope is a boundary, not a hint")
    void unknownRegionFails() {
        when(regions.findAllById(Set.of(99L))).thenReturn(List.of());
        AskQuestion scoped = new AskQuestion("best spot", "best spot", null, List.of(99L), "map");

        AskRun run = engine.run(scoped, snapshot(null), USER, AskRunOptions.none());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.reason()).contains("region id");
        assertThat(run.trace()).isEmpty();
    }

    @Test
    @DisplayName("a context window restricts the ranking to that window; one not in the set is ignored")
    void contextWindow() {
        AskQuestion inWindow = new AskQuestion("best spot", "best spot", TOMORROW_SUNRISE, List.of(), "map");
        AskQuestion unknown = new AskQuestion("best spot", "best spot", "2031-01-01_sunrise", List.of(), "map");

        AskRun only = engine.run(inWindow, snapshot(null), USER, AskRunOptions.none());
        AskRun ignored = engine.run(unknown, snapshot(null), USER, AskRunOptions.none());

        assertThat(only.outcome().answer().picks()).extracting(AskPick::windowId).containsOnly(TOMORROW_SUNRISE);
        assertThat(ignored.outcome().answer().picks()).extracting(AskPick::windowId)
                .contains(TODAY_SUNSET, TOMORROW_SUNRISE);
    }

    @Test
    @DisplayName("coast and tide words narrow to coastal spots; high tide to HIGH water, low tide to LOW")
    void tideWords() {
        AskRun coastal = run("Best coastal spot?");
        AskRun high = run("Best coastal spot at high tide?");
        AskRun low = run("Where at low tide?");
        AskRun highWater = run("Where is best at high water?");
        AskRun lowWater = run("Where is best at low water?");

        assertThat(names(highWater)).containsExactly("Whitby");
        assertThat(names(lowWater)).containsExactly("Saltburn");
        assertThat(names(coastal)).containsExactlyInAnyOrder("Whitby", "Saltburn");
        assertThat(high.outcome().answer().picks()).extracting(AskPick::locationName).containsExactly("Whitby");
        assertThat(names(low)).containsExactly("Saltburn");
    }

    @Test
    @DisplayName("a tide-misaligned spot says what the water is at the event and never claims the tide suits it")
    void misalignedTideIsNotClaimed() {
        AskRun low = run("Where at low tide?");

        assertThat(low.outcome().answer().picks().getFirst().why())
                .contains("The tide is low at the event.").doesNotContain("suits");
    }

    @Test
    @DisplayName("nothing eligible is an honest answer with no picks, not an error and not a made-up pick")
    void nothingEligible() {
        BriefingRegion poor = AskFixtures.region("Poor", true, AskFixtures.slot(1L, "Grey", 2));
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(TODAY, null, poor)), List.of()));

        AskRun run = engine.run(question("best spot"), snapshot, USER, AskRunOptions.none());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.OK);
        assertThat(run.outcome().answer().picks()).isEmpty();
        assertThat(run.outcome().answer().summary()).contains("3★");
    }

    @Test
    @DisplayName("an empty forecast (no windows at all) is the same honest answer")
    void noWindows() {
        AskSnapshot empty = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of()));

        AskRun run = engine.run(question("best spot"), empty, USER, AskRunOptions.none());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.OK);
        assertThat(run.outcome().answer().picks()).isEmpty();
    }

    @Test
    @DisplayName("a lone pick has a summary of its own and no 'followed by'")
    void singlePickSummary() {
        BriefingRegion one = AskFixtures.region("One", true, AskFixtures.slot(1L, "Only Spot", 4));
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(TODAY, null, one)), List.of()));

        AskRun run = engine.run(question("best"), snapshot, USER, AskRunOptions.none());

        assertThat(run.outcome().answer().summary()).isEqualTo("Only Spot at today sunset looks strongest at 4★.");
    }

    @Test
    @DisplayName("two picks are 'followed by' the second, with no 'and'")
    void twoPickSummary() {
        BriefingRegion two = AskFixtures.region("Two", true, AskFixtures.slot(1L, "First", 5),
                AskFixtures.slot(2L, "Second", 4));
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(TODAY, null, two)), List.of()));

        AskRun run = engine.run(question("best"), snapshot, USER, AskRunOptions.none());

        assertThat(run.outcome().answer().summary()).endsWith("followed by Second.");
    }

    @Test
    @DisplayName("a window two days out is named by its weekday")
    void weekdayWords() {
        BriefingRegion one = AskFixtures.region("One", true, AskFixtures.slot(1L, "Only Spot", 4));
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(TODAY.plusDays(2), null, one)), List.of()));

        AskRun run = engine.run(question("best"), snapshot, USER, AskRunOptions.none());

        // 2026-10-07 is a Wednesday.
        assertThat(run.outcome().answer().picks().getFirst().why()).contains("Wednesday sunset");
    }

    // -- the BEST anchor ---------------------------------------------------------------------

    @Test
    @DisplayName("BEST_*: pick 1 is on the BEST BET window, led by the BEST BET location, even when another "
            + "window holds a higher rating")
    void bestAnchorLeads() {
        // Today's sunset BEST BET is Saltburn (4); tomorrow's sunrise holds a 5 at Inland Edge.
        BriefingWindow.Pick best = AskFixtures.pick(BriefingWindow.PickKind.BEST, "Coast", "Saltburn", 2L);
        AskRunOptions anchored = AskRunOptions.ready(900L, new BestAnchor(Set.of(TODAY_SUNSET)));

        AskRun run = engine.run(question("Best spot tonight?"), snapshot(best), AskUserContext.userLess(), anchored);

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.OK);
        List<AskPick> picks = run.outcome().answer().picks();
        assertThat(picks.getFirst().windowId()).isEqualTo(TODAY_SUNSET);
        assertThat(picks.getFirst().locationName()).isEqualTo("Saltburn");
        assertThat(picks).extracting(AskPick::locationId).doesNotHaveDuplicates();
        assertThat(run.trace()).extracting(AskTools.ToolCall::tool)
                .containsExactly("rank_spots", "rank_spots", "submit_answer");
    }

    @Test
    @DisplayName("BEST_*: an anchor whose windows carry no BEST BET leaves the ordinary ranking alone")
    void anchorWithoutBestBet() {
        AskRunOptions anchored = AskRunOptions.ready(900L, new BestAnchor(Set.of(TODAY_SUNSET)));

        AskRun run = engine.run(question("Best spot tonight?"), snapshot(null), AskUserContext.userLess(), anchored);

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.OK);
        assertThat(names(run).getFirst()).isEqualTo("Whitby");
        assertThat(run.trace()).extracting(AskTools.ToolCall::tool).containsExactly("rank_spots", "submit_answer");
    }

    @Test
    @DisplayName("a Ready conversation is billed to nothing by the stub, but needs its run id, as the Claude "
            + "engine does; a typed one must not carry one")
    void optionsContract() {
        AskQuestion q = question("best spot");

        assertThatThrownBy(() -> engine.run(q, snapshot(null), AskUserContext.userLess(), AskRunOptions.none()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ASK_READY");
        assertThatThrownBy(() -> engine.run(q, snapshot(null), USER, AskRunOptions.ready(900L, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("daily ASK run");
        assertThat(engine.run(q, snapshot(null), AskUserContext.userLess(), AskRunOptions.ready(900L, null))
                .outcome().status()).isEqualTo(AskOutcome.Status.OK);
        assertThat(engine.run(q, snapshot(null), USER, null).outcome().status()).isEqualTo(AskOutcome.Status.OK);
    }

    @Test
    @DisplayName("a blank question is a FAILED run with no tool call")
    void blankQuestion() {
        AskRun run = run("   ");

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.reason()).contains("empty");
        assertThat(run.trace()).isEmpty();
        verifyNoInteractions(driveTimes);
    }

    // -- events -----------------------------------------------------------------------------

    @Test
    @DisplayName("a rare-events question is answered from the topics and the Coming up feed, with served labels")
    void eventsFromBothTools() {
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of(
                        AskFixtures.topic("AURORA", "Aurora tonight", "Kp 6 forecast", TODAY, List.of()))),
                List.of(AskSnapshotBuilderTest.almanacEntry("meteor", "Orionid meteor shower",
                        TODAY.plusDays(16), TODAY.plusDays(17), "Peaks overnight")));

        AskRun run = engine.run(question("Any rare events coming up?"), snapshot, USER, AskRunOptions.none());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.OK);
        assertThat(run.outcome().answer().picks()).isEmpty();
        assertThat(run.outcome().answer().events()).extracting(AskEvent::type)
                .containsExactly("AURORA", "METEOR");
        assertThat(run.outcome().answer().events()).extracting(AskEvent::label)
                .containsExactly("Aurora tonight", "Orionid meteor shower");
        assertThat(run.outcome().answer().summary()).isEqualTo(
                "Coming up in the forecast: Aurora tonight, Orionid meteor shower.");
        assertThat(run.trace()).extracting(AskTools.ToolCall::tool)
                .containsExactly("get_hot_topics", "get_coming_up", "submit_answer");
    }

    @Test
    @DisplayName("a topical keyword keeps only matching events; the served solar-eclipse safety note rides along")
    void topicalFilterAndSafetyNote() {
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of(
                        AskFixtures.topic("AURORA", "Aurora tonight", "Kp 6 forecast", TODAY, List.of()))),
                List.of(AskSnapshotBuilderTest.almanacEntry("eclipse", "Partial solar eclipse",
                        TODAY.plusDays(40), TODAY.plusDays(40), "A partial eclipse")));

        AskRun run = engine.run(question("Is there an eclipse soon?"), snapshot, USER, AskRunOptions.none());

        assertThat(run.outcome().answer().events()).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo("ECLIPSE");
            assertThat(e.safetyNote()).isEqualTo(EclipseHotTopicStrategy.SAFETY_NOTE);
        });
    }

    @Test
    @DisplayName("a topical keyword with no matching event says so, and offers no event")
    void topicalNoMatch() {
        AskRun run = run("Any aurora?");
        AskRun none = engine.run(question("Any meteor showers?"), snapshot(null), USER, AskRunOptions.none());

        assertThat(run.outcome().answer().events()).extracting(AskEvent::type).containsExactly("AURORA");
        assertThat(none.outcome().answer().events()).isEmpty();
        assertThat(none.outcome().answer().summary())
                .isEqualTo("Nothing about meteor is showing in the forecast right now.");
    }

    @Test
    @DisplayName("snow is matched on the topic's label and detail as well as its type")
    void snowByLabel() {
        AskRun run = run("Is there snow on the tops?");

        assertThat(run.outcome().answer().events()).extracting(AskEvent::type).containsExactly("SNOW");
    }

    @Test
    @DisplayName("an events question with no events at all is an honest, generic empty answer")
    void noEventsAtAll() {
        AskSnapshot empty = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of()));

        AskRun run = engine.run(question("Any rare events?"), empty, USER, AskRunOptions.none());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.OK);
        assertThat(run.outcome().answer().summary()).isEqualTo("No rare events are showing in the forecast right now.");
    }

    @Test
    @DisplayName("an event's reason is the first sentence of its served detail, or the whole detail when it has "
            + "no full stop to cut at")
    void eventReasonIsTheFirstServedSentence() {
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of(
                AskFixtures.topic("AURORA", "Aurora tonight", "Kp 6 forecast. Look north after dark.", TODAY,
                        List.of()),
                AskFixtures.topic("SNOW", "Snow", "  Fresh snow above 600 m  ", TODAY, List.of()),
                AskFixtures.topic("DUST", "Dust", "   ", TODAY, List.of()))));

        AskRun run = engine.run(question("rare events"), snapshot, USER, AskRunOptions.none());

        assertThat(run.outcome().answer().events()).extracting(AskEvent::why).containsExactly(
                "Kp 6 forecast.", "Fresh snow above 600 m", "Listed in the forecast.");
    }

    @Test
    @DisplayName("at most three events, none twice, whichever tool supplied them")
    void eventsCapped() {
        List<com.gregochr.goldenhour.model.HotTopic> topics = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            topics.add(AskFixtures.topic("TYPE" + i, "Topic " + i, null, TODAY, List.of()));
        }
        topics.add(AskFixtures.topic("TYPE0", "Topic 0 again", "dup", TODAY, List.of()));
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), topics));

        AskRun run = engine.run(question("rare events"), snapshot, USER, AskRunOptions.none());

        assertThat(run.outcome().answer().events()).extracting(AskEvent::type)
                .containsExactly("TYPE0", "TYPE1", "TYPE2");
        assertThat(run.outcome().answer().events().getFirst().why()).isEqualTo("Listed in the forecast.");
    }

    // -- failure paths ----------------------------------------------------------------------

    @Test
    @DisplayName("a rank_spots error stops the stub with a reason and the trace so far")
    void rankSpotsErrorFails() {
        String huge = "x".repeat(AskTools.RESULT_CHAR_CAP);
        BriefingSlot.TideInfo tide = new BriefingSlot.TideInfo("HIGH", true, null, null, false, false,
                null, null, null, null, null, null, null, null, null, null, null, huge, null);
        BriefingSlot slot = new BriefingSlot(1L, "Whitby", AskFixtures.NOW.plusHours(6), Verdict.GO, null, tide,
                List.of(), null, 5, 5, null, null, null, DisplayVerdict.WORTH_IT, null, false, null);
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(TODAY, null, AskFixtures.region("Coast", true, slot))), List.of()));

        AskRun run = engine.run(question("best spot"), snapshot, USER, AskRunOptions.none());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.reason()).startsWith("rank_spots returned an error");
        assertThat(run.trace()).extracting(AskTools.ToolCall::error).containsExactly(true);
    }

    @Test
    @DisplayName("both event tools failing stops the stub; one failing is survivable")
    void eventToolErrors() {
        String huge = "y".repeat(AskTools.RESULT_CHAR_CAP);
        List<ComingUpEntry> hugeEntries = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            hugeEntries.add(AskSnapshotBuilderTest.almanacEntry("meteor", huge + i, TODAY.plusDays(i + 1),
                    TODAY.plusDays(i + 1), "d"));
        }
        com.gregochr.goldenhour.model.HotTopic bigWarning = new com.gregochr.goldenhour.model.HotTopic(
                "ECLIPSE", "Eclipse", "d", TODAY, 1, null, List.of(), "desc", null, null, null, null, null,
                null, null, null, null, null, null, huge);
        DailyBriefingResponse briefing = AskFixtures.briefing(List.of(), List.of(bigWarning));

        AskRun both = engine.run(question("rare events"), AskFixtures.snapshotOf(briefing, hugeEntries), USER,
                AskRunOptions.none());
        AskRun one = engine.run(question("rare events"), AskFixtures.snapshotOf(briefing), USER, AskRunOptions.none());

        assertThat(both.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(both.reason()).startsWith("both event tools returned an error");
        assertThat(one.outcome().status()).isEqualTo(AskOutcome.Status.OK);
        assertThat(one.outcome().answer().events()).isEmpty();
    }

    @Test
    @DisplayName("an answer the validator discards is a FAILED run with the reason: the stub is not trusted either")
    void discardedAnswerFails() {
        AskAnswerValidator rejecting = mock(AskAnswerValidator.class);
        when(rejecting.validate(any(), any(), any(), any(), any()))
                .thenReturn(new AskAnswerValidator.Result(null, "no summary"));
        StubAskEngine strict = new StubAskEngine(rejecting, driveTimes, regions, new ObjectMapper());

        AskRun run = strict.run(question("best spot"), snapshot(null), USER, AskRunOptions.none());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.reason()).isEqualTo("the answer was discarded: no summary");
        assertThat(run.trace()).extracting(AskTools.ToolCall::tool).containsExactly("rank_spots", "submit_answer");
    }

    @Test
    @DisplayName("a validated unanswerable reply is a CANT outcome (the stub's validator path, not its script)")
    void cantStatusIsReportedFromTheValidator() {
        AskAnswerValidator cant = mock(AskAnswerValidator.class);
        when(cant.validate(any(), any(), any(), any(), any())).thenReturn(new AskAnswerValidator.Result(
                new AskAnswer(false, "Cannot tell.", List.of(), List.of(), "parking"), null));
        StubAskEngine stub = new StubAskEngine(cant, driveTimes, regions, new ObjectMapper());

        AskRun run = stub.run(question("best spot"), snapshot(null), USER, AskRunOptions.none());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.CANT);
    }

    private static RegionEntity region(long id, String name) {
        return RegionEntity.builder().id(id).name(name).enabled(true).build();
    }

    @SuppressWarnings("unused")
    private static LocalDate unused() {
        return TODAY;
    }
}
