package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.gregochr.goldenhour.service.ask.AskFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the Ask records and {@link AskSnapshot}'s one eligibility rule. */
class AskReadModelTest {

    private static AskSnapshot.Region region(boolean eligible) {
        return new AskSnapshot.Region("Coast", null, null, eligible, List.of());
    }

    private static AskSnapshot.Slot slot(Long id, Integer rating, boolean canopy) {
        return new AskSnapshot.Slot(id, "Spot", rating, null, null, canopy, null, false, null);
    }

    @Test
    @DisplayName("pick eligibility: every clause is necessary, and none is waived by a high rating")
    void isPickEligible_everyClauseIsNecessary() {
        assertThat(AskSnapshot.isPickEligible(region(true), slot(1L, 3, false))).isTrue();
        assertThat(AskSnapshot.isPickEligible(region(true), slot(null, 5, false)))
                .as("no location id").isFalse();
        assertThat(AskSnapshot.isPickEligible(region(true), slot(1L, 5, true)))
                .as("a wood").isFalse();
        assertThat(AskSnapshot.isPickEligible(region(true), slot(1L, 2, false)))
                .as("under 3 stars").isFalse();
        assertThat(AskSnapshot.isPickEligible(region(true), slot(1L, null, false)))
                .as("unrated").isFalse();
        assertThat(AskSnapshot.isPickEligible(region(false), slot(1L, 5, false)))
                .as("a region the Plan tab would refuse a verdict, however high the rating")
                .isFalse();
    }

    @Test
    @DisplayName("pick eligibility is bounded above: 5 is in; 6, 491, 0 and negatives are not")
    void isPickEligible_ratingIsOnTheOneToFiveScale() {
        assertThat(AskSnapshot.isPickEligible(region(true), slot(1L, 5, false))).isTrue();
        assertThat(AskSnapshot.isPickEligible(region(true), slot(1L, 4, false))).isTrue();
        assertThat(AskSnapshot.isPickEligible(region(true), slot(1L, 6, false))).isFalse();
        assertThat(AskSnapshot.isPickEligible(region(true), slot(1L, 491, false))).isFalse();
        assertThat(AskSnapshot.isPickEligible(region(true), slot(1L, 0, false))).isFalse();
        assertThat(AskSnapshot.isPickEligible(region(true), slot(1L, -3, false))).isFalse();
    }

    @Test
    @DisplayName("a slot with no name to put on a card is not pick-eligible")
    void isPickEligible_needsAName() {
        AskSnapshot.Slot unnamed = new AskSnapshot.Slot(1L, null, 4, null, null, false, null, false, null);
        AskSnapshot.Slot blank = new AskSnapshot.Slot(1L, "  ", 4, null, null, false, null, false, null);

        assertThat(AskSnapshot.isPickEligible(region(true), unnamed)).isFalse();
        assertThat(AskSnapshot.isPickEligible(region(true), blank)).isFalse();
    }

    @Test
    @DisplayName("a malformed rating never reaches rank_spots and cannot be validated as a pick")
    void malformedRating_isNeverOfferedOrValidated() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "Bad", 491),
                AskFixtures.slot(2L, "Six", 6), AskFixtures.slot(3L, "Good", 5));
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(TODAY, null, region)), List.of()));
        AskTools tools = new AskTools(snapshot, AskUserContext.userLess(), TestScopes.of(), null,
                new com.fasterxml.jackson.databind.ObjectMapper());

        List<AskTools.SpotInfo> found = ((AskTools.RankSpotsResult) tools.rankSpots(null).payload())
                .spots();
        AskAnswerValidator.Result result = new AskAnswerValidator().validate(
                new AskAnswerValidator.Raw(true, "Bad.", List.of(
                        new AskAnswerValidator.RawPick(1L, "2026-10-05_sunset", "x"),
                        new AskAnswerValidator.RawPick(2L, "2026-10-05_sunset", "y")), null, null),
                snapshot, new AskEvidence(Set.of(new AskEvidence.Pair(1L, "2026-10-05_sunset"),
                        new AskEvidence.Pair(2L, "2026-10-05_sunset")), Set.of(), 1), null, null, null);

        assertThat(found).extracting(AskTools.SpotInfo::name).containsExactly("Good");
        assertThat(snapshot.candidate("2026-10-05_sunset", 1L)).isEmpty();
        assertThat(snapshot.candidate("2026-10-05_sunset", 2L)).isEmpty();
        assertThat(result.answer().picks()).isEmpty();
    }

    @Test
    @DisplayName("candidate() finds a location's eligible slot at a window and nothing else")
    void candidate_lookup() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "A", 4),
                AskFixtures.slot(2L, "B", 2), AskFixtures.wood(3L, "Wood", 5));
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(TODAY, null, region)), List.of()));

        assertThat(snapshot.candidate("2026-10-05_sunset", 1L)).isPresent();
        assertThat(snapshot.candidate("2026-10-05_sunset", 2L)).as("under 3 stars").isEmpty();
        assertThat(snapshot.candidate("2026-10-05_sunset", 3L)).as("a wood").isEmpty();
        assertThat(snapshot.candidate("2026-10-05_sunset", 99L)).as("unknown").isEmpty();
        assertThat(snapshot.candidate("2026-10-06_sunset", 1L)).as("no such window").isEmpty();
        assertThat(snapshot.candidates()).hasSize(1);
        assertThat(snapshot.window("2026-10-05_sunset")).isPresent();
        assertThat(snapshot.window("nope")).isEmpty();
    }

    @Test
    @DisplayName("a coastal slot is one with a served tide state")
    void slot_coastal() {
        assertThat(new AskSnapshot.Slot(1L, "A", 4, null, null, false, "HIGH", true, null).coastal())
                .isTrue();
        assertThat(slot(1L, 4, false).coastal()).isFalse();
    }

    @Test
    @DisplayName("snapshot records read null collections as empty and copy what they are given")
    void snapshot_collectionsAreNormalised() {
        AskSnapshot snapshot = new AskSnapshot(null, null, TODAY, null, null, null);
        assertThat(snapshot.windows()).isEmpty();
        assertThat(snapshot.hotTopics()).isEmpty();
        assertThat(snapshot.comingUp()).isEmpty();
        assertThat(new AskSnapshot.Window("id", TODAY, null, null, null, null, null, null).regions())
                .isEmpty();
        assertThat(new AskSnapshot.Region("R", null, null, true, null).slots()).isEmpty();
        assertThat(new AskSnapshot.Topic("T", "t", null, TODAY, null).regions()).isEmpty();

        List<AskSnapshot.Topic> source = new ArrayList<>();
        AskSnapshot copied = new AskSnapshot(null, null, TODAY, List.of(), source, List.of());
        source.add(new AskSnapshot.Topic("T", "t", null, TODAY, List.of()));
        assertThat(copied.hotTopics()).isEmpty();
    }

    @Test
    @DisplayName("answer, question and evidence records read null collections as empty")
    void contractRecords_collectionsAreNormalised() {
        assertThat(new AskAnswer(true, "s", null, null, null).picks()).isEmpty();
        assertThat(new AskAnswer(true, "s", null, null, null).events()).isEmpty();
        assertThat(new AskQuestion("q", "q", null, null, "map").regionIds()).isEmpty();
        assertThat(new AskQuestion("q", "q", null, null, "map").scope()).isSameAs(AskScope.ALL);
        assertThat(new AskQuestion("q", "q", null, AskScope.of(List.of(3L), Set.of("Coast")), "map").regionIds())
                .containsExactly(3L);
        AskEvidence evidence = new AskEvidence(null, null, 0);
        assertThat(evidence.pairs()).isEmpty();
        assertThat(evidence.events()).isEmpty();
        assertThat(evidence.anyToolCalled()).isFalse();
        assertThat(new AskEvidence(Set.of(), Set.of(), 2).anyToolCalled()).isTrue();
    }

    @Test
    @DisplayName("a user-less context has no user; a user context does")
    void userContext() {
        assertThat(AskUserContext.userLess().hasUser()).isFalse();
        assertThat(AskUserContext.userLess().userId()).isNull();
        assertThat(new AskUserContext(5L, UserRole.LITE_USER, false).hasUser()).isTrue();
    }

    @Test
    @DisplayName("a tool error result carries the message and no payload")
    void toolResult_error() {
        AskToolResult<AskTools.RankSpotsResult> error = AskToolResult.error("nope");

        assertThat(error.error()).isTrue();
        assertThat(error.content()).isEqualTo("nope");
        assertThat(error.payload()).isNull();
    }

    // -- the timeline: the one definition of what get_coming_up can return -------------------

    private static AskSnapshot almanacSnapshot(int... dayOffsets) {
        List<ComingUpEntry> entries = new ArrayList<>();
        for (int offset : dayOffsets) {
            entries.add(AskSnapshotBuilderTest.almanacEntry("E" + offset, "Entry " + offset,
                    TODAY.plusDays(offset), TODAY.plusDays(offset), "d"));
        }
        return AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of()), entries);
    }

    private static List<String> timelineTypes(AskSnapshot snapshot, AskScope scope, int days) {
        return snapshot.timeline(scope, days).stream().map(AskSnapshot.ComingUp::type).toList();
    }

    @Test
    @DisplayName("the timeline's horizon is 90 days, the one constant the tool schema and the freshness check read")
    void timeline_horizonConstant() {
        assertThat(AskSnapshot.MAX_COMING_UP_DAYS).isEqualTo(90);
    }

    @Test
    @DisplayName("timeline: N days is N civil dates from today; day N-1 is in, day N is out")
    void timeline_rangeIsNCivilDates() {
        AskSnapshot snapshot = almanacSnapshot(0, 6, 7, 8);

        assertThat(timelineTypes(snapshot, TestScopes.of(), 7)).containsExactly("E0", "E6");
        assertThat(timelineTypes(snapshot, TestScopes.of(), 8)).containsExactly("E0", "E6", "E7");
        assertThat(timelineTypes(snapshot, TestScopes.of(), 1)).as("one day is today only")
                .containsExactly("E0");
    }

    @Test
    @DisplayName("timeline: entries equal on date and title still sort the same way every time")
    void timeline_sortIsTotal() {
        ComingUpEntry b = AskSnapshotBuilderTest.almanacEntry("B", "Same", TODAY.plusDays(2),
                TODAY.plusDays(2), "d");
        ComingUpEntry a = AskSnapshotBuilderTest.almanacEntry("A", "Same", TODAY.plusDays(2),
                TODAY.plusDays(2), "d");

        List<String> forward = timelineTypes(AskFixtures.snapshotOf(
                AskFixtures.briefing(List.of(), List.of()), List.of(b, a)), TestScopes.of(), 30);
        List<String> reverse = timelineTypes(AskFixtures.snapshotOf(
                AskFixtures.briefing(List.of(), List.of()), List.of(a, b)), TestScopes.of(), 30);

        assertThat(forward).containsExactly("A", "B");
        assertThat(reverse).isEqualTo(forward);
    }

    @Test
    @DisplayName("timeline: a live topic the almanac already lists (same type, date inside the span; the "
            + "almanac's lower-case hyphenated type is the hot topic's upper-case underscored one) "
            + "appears once, not twice")
    void timeline_doesNotRepeatWhatTheAlmanacLists() {
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of(
                AskFixtures.topic("ECLIPSE", "Partial solar eclipse", "d", TODAY.plusDays(4), List.of()),
                AskFixtures.topic("LUNAR_ECLIPSE", "Lunar eclipse", "d", TODAY.plusDays(5), List.of()),
                AskFixtures.topic("ECLIPSE", "Another eclipse", "d", TODAY.plusDays(20), List.of()))),
                List.of(AskSnapshotBuilderTest.almanacEntry("eclipse", "Partial solar eclipse",
                                TODAY.plusDays(4), TODAY.plusDays(4), "d"),
                        AskSnapshotBuilderTest.almanacEntry("lunar-eclipse", "Total lunar eclipse",
                                TODAY.plusDays(4), TODAY.plusDays(6), "d")));

        assertThat(snapshot.timeline(TestScopes.of(), 90)).extracting(AskSnapshot.ComingUp::title)
                .containsExactly("Partial solar eclipse", "Total lunar eclipse", "Another eclipse");
    }

    @Test
    @DisplayName("timeline keeps a NIGHT topic dated yesterday — the aurora alert for the night still "
            + "running before dawn, whose morning half is today's sunrise — on its own date, the one "
            + "get_hot_topics and the freshness check know it by")
    void timeline_runningNightTopicDatedYesterdayKeepsItsDate() {
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of(
                AskFixtures.topic("AURORA", "Aurora possible", "Kp 5 forecast until dawn",
                        TODAY.minusDays(1), List.of()).withEvent("NIGHT", "18:30"),
                AskFixtures.topic("DUST", "Saharan dust", "d", TODAY.minusDays(1), List.of())
                        .withEvent("SUNSET", "18:00"))));

        List<AskSnapshot.ComingUp> timeline = snapshot.timeline(TestScopes.of(), 90);

        assertThat(timeline).extracting(AskSnapshot.ComingUp::type).containsExactly("AURORA");
        assertThat(timeline.getFirst().startDate()).isEqualTo(TODAY.minusDays(1));
        assertThat(timeline.getFirst().endDate()).isEqualTo(TODAY.minusDays(1));
    }

    @Test
    @DisplayName("timeline leaves out a live topic dated before today, beyond the horizon, undated or "
            + "naming only regions outside the question's scope")
    void timeline_liveTopicsAreBoundedByHorizonAndScope() {
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of(
                AskFixtures.topic("PAST", "Past", "d", TODAY.minusDays(1), List.of()),
                AskFixtures.topic("TODAY", "Today", "d", TODAY, List.of()),
                AskFixtures.topic("EDGE", "Edge", "d", TODAY.plusDays(6), List.of()),
                AskFixtures.topic("BEYOND", "Beyond", "d", TODAY.plusDays(7), List.of()),
                AskFixtures.topic("UNDATED", "Undated", "d", null, List.of()),
                AskFixtures.topic("ELSEWHERE", "Elsewhere", "d", TODAY, List.of("Cornwall")),
                AskFixtures.topic("HERE", "Here", "d", TODAY, List.of("Coast")))));

        assertThat(timelineTypes(snapshot, TestScopes.of(), 7)).as("unscoped: every region")
                .containsExactly("ELSEWHERE", "HERE", "TODAY", "EDGE");
        assertThat(timelineTypes(snapshot, TestScopes.of("Coast"), 7))
                .containsExactly("HERE", "TODAY", "EDGE");
    }

    @Test
    @DisplayName("an outcome carries its status, answer, personal flag and turn count")
    void outcome() {
        AskAnswer answer = new AskAnswer(false, "s", null, null, "m");
        AskOutcome outcome = new AskOutcome(AskOutcome.Status.CANT, answer, true, 2);

        assertThat(outcome.status()).isEqualTo(AskOutcome.Status.CANT);
        assertThat(outcome.answer()).isSameAs(answer);
        assertThat(outcome.personal()).isTrue();
        assertThat(outcome.turns()).isEqualTo(2);
        assertThat(AskOutcome.Status.values()).containsExactly(AskOutcome.Status.OK,
                AskOutcome.Status.CANT, AskOutcome.Status.FAILED);
    }
}
