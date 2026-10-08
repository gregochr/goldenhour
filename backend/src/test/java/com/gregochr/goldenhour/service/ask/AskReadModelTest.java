package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingRegion;
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
        AskToolResult error = AskToolResult.error("nope");

        assertThat(error.error()).isTrue();
        assertThat(error.content()).isEqualTo("nope");
        assertThat(error.payload()).isNull();
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
