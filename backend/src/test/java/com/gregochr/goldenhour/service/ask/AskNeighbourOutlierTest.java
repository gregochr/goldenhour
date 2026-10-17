package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.PlanWindowProjector;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.Raw;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.RawPick;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.Result;
import com.gregochr.goldenhour.service.ask.AskEvidence.Pair;
import com.gregochr.goldenhour.service.ask.AskEvidence.ThreadFact;
import com.gregochr.goldenhour.service.ask.ThreadExchange.PickRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.gregochr.goldenhour.service.ask.AskFixtures.GENERATED_AT;
import static com.gregochr.goldenhour.service.ask.AskFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Ask's side of the neighbour guard (neighbour-guard plan G2): a slot the guard marked as rated far
 * above the places beside it keeps its star but is never offered, validated, re-offered by a thread,
 * re-served from a Ready answer or counted in {@code list_windows}' best rating. Real snapshots built
 * through the real builder from a served tree that carries the mark.
 */
class AskNeighbourOutlierTest {

    private static final String SUNSET_TODAY = "2026-10-05_sunset";
    private static final String GONE_MISSING = "those places are no longer in the forecast";
    private static final String HELD_BACK_MISSING = "a trustworthy rating for those places";
    private static final AskUserContext PRO = new AskUserContext(7L, UserRole.PRO_USER, true);

    private final ObjectMapper mapper = new ObjectMapper();
    private final DriveTimeResolver driveTimes = mock(DriveTimeResolver.class);
    private final AskLocationNames names = mock(AskLocationNames.class);
    private final AskAnswerValidator validator = new AskAnswerValidator();

    /** Caw Gap 4 stars is the marked slot; Cawfields 1 and Steel Rigg 2 are its neighbours. */
    private static BriefingRegion wall(boolean eligible) {
        return AskFixtures.region("Wall", eligible,
                AskFixtures.outlier(AskFixtures.slot(1L, "Caw Gap", 4)),
                AskFixtures.slot(2L, "Cawfields", 1),
                AskFixtures.slot(3L, "Steel Rigg", 2),
                AskFixtures.slot(4L, "Housesteads", 3));
    }

    private static AskSnapshot snapshotOf(BriefingRegion... regions) {
        return AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(TODAY, null, regions)), List.of()));
    }

    private AskTools tools(AskSnapshot snapshot) {
        return new AskTools(snapshot, AskUserContext.userLess(), TestScopes.of(), driveTimes, mapper);
    }

    private AskThreadContext context(AskThread thread, AskSnapshot snapshot) {
        AskTools tools = new AskTools(snapshot, PRO, AskScope.ALL, driveTimes, mapper);
        return AskThreadContext.of(thread, snapshot, AskScope.ALL, tools, names, false, false);
    }

    private static AskThread thread(long... locationIds) {
        List<PickRef> refs = Arrays.stream(locationIds)
                .mapToObj(id -> new PickRef(id, SUNSET_TODAY)).toList();
        return new AskThread(List.of(new ThreadExchange("anything for sunset?", "Caw Gap looks best.",
                refs, List.of(), GENERATED_AT, false)));
    }

    private static Pair pair(long id) {
        return new Pair(id, SUNSET_TODAY);
    }

    private static Raw answer(long... ids) {
        List<RawPick> picks = Arrays.stream(ids)
                .mapToObj(id -> new RawPick(id, SUNSET_TODAY, "why")).toList();
        return new Raw(true, "Best spots.", picks, List.of(), null);
    }

    private Result validate(Raw raw, AskSnapshot snapshot, AskEvidence evidence) {
        return validator.validate(raw, snapshot, evidence, AskScope.ALL, null, null, false);
    }

    // -- rank_spots and the validator ---------------------------------------------------------

    @Test
    @DisplayName("rank_spots omits a neighbour outlier but still offers its unmarked neighbours at or above "
            + "the floor")
    void rankSpots_omitsTheOutlier() {
        List<AskTools.SpotInfo> spots = tools(snapshotOf(wall(true)))
                .rankSpots(new AskTools.RankSpotsArgs(null, null, null, null, null, 8)).payload().spots();

        assertThat(spots).extracting(AskTools.SpotInfo::name).containsExactly("Housesteads");
    }

    @Test
    @DisplayName("the validator drops a submitted pick on an outlier even when a tool had offered it; an "
            + "unmarked pick beside it survives")
    void validator_dropsASubmittedOutlier() {
        AskEvidence evidence = new AskEvidence(Set.of(pair(1L), pair(4L)), Set.of(), 1);

        Result result = validate(answer(1L, 4L), snapshotOf(wall(true)), evidence);

        assertThat(result.accepted()).isTrue();
        assertThat(result.answer().picks()).extracting(AskPick::locationName).containsExactly("Housesteads");
    }

    // -- the thread -------------------------------------------------------------------------

    @Test
    @DisplayName("an earlier pick that has become an outlier re-joins with the held-back line - not the "
            + "rated-down line, its rating has not fallen - and is not offered")
    void thread_heldBackLine() {
        AskThreadContext context = context(thread(1L), snapshotOf(wall(true)));

        assertThat(context.exchanges().getFirst().pickLines())
                .containsExactly("1. Caw Gap · held back: rated well above the places beside it");
        assertThat(context.fact().picks()).isEmpty();
        assertThat(context.fact().droppedPicks()).containsExactly(pair(1L));
        assertThat(context.fact().heldBack()).containsExactly(pair(1L));
    }

    @Test
    @DisplayName("a pick dropped for any other reason keeps its own wording and is not held back: rated "
            + "down, or marked but under the floor")
    void thread_otherReasonsAreNotHeldBack() {
        BriefingRegion region = AskFixtures.region("Wall", true,
                AskFixtures.slot(2L, "Cawfields", 1),
                AskFixtures.outlier(AskFixtures.slot(5L, "Low Mark", 2)));
        AskThreadContext context = context(thread(2L, 5L, 99L), snapshotOf(region));

        assertThat(context.exchanges().getFirst().pickLines()).containsExactly(
                "1. Cawfields · no longer rated well enough to pick",
                "2. Low Mark · no longer rated well enough to pick",
                "3. a place no longer listed · no longer in the forecast");
        assertThat(context.fact().heldBack()).isEmpty();
        assertThat(context.fact().droppedPicks()).containsExactlyInAnyOrder(pair(2L), pair(5L), pair(99L));
    }

    @Test
    @DisplayName("the held-back wording is not said of a pick outside the follow-up's scope")
    void thread_outsideScopeWinsOverHeldBack() {
        AskSnapshot snapshot = snapshotOf(wall(true));
        AskScope elsewhere = TestScopes.of("Elsewhere");
        AskTools tools = new AskTools(snapshot, PRO, elsewhere, driveTimes, mapper);
        AskThreadContext context = AskThreadContext.of(thread(1L), snapshot, elsewhere, tools, names, false,
                false);

        assertThat(context.exchanges().getFirst().pickLines())
                .containsExactly("1. Caw Gap · outside the area you are now asking about");
        assertThat(context.fact().heldBack()).isEmpty();
    }

    @Test
    @DisplayName("the thread rules permit one explanation of a held-back place and forbid calling it low, "
            + "poor or unreliable or quoting its star - and a fresh question's prompt never carries them")
    void promptRule_namesHeldBack() {
        String rules = AskPromptBuilder.THREAD_RULES.replaceAll("\\s+", " ");
        AskPromptBuilder builder = new AskPromptBuilder();

        assertThat(rules)
                .contains("say only that a held-back place is not being suggested because its rating is "
                        + "out of line with the places beside it")
                .contains("never call it low, poor or unreliable, and do not quote its star");
        assertThat(builder.systemPrompt(TODAY, AskScope.ALL, null, true, false))
                .doesNotContain("held back").doesNotContain("held-back");
        assertThat(builder.systemPrompt(TODAY, AskScope.ALL, null, true, true))
                .endsWith(AskPromptBuilder.THREAD_RULES);
    }

    @Test
    @DisplayName("a marked pick whose window has passed reads as gone, not held back")
    void thread_markedButWindowGone_readsAsGone() {
        AskThread passed = new AskThread(List.of(new ThreadExchange("anything?", "Caw Gap.",
                List.of(new PickRef(1L, "2026-10-04_sunset")), List.of(), GENERATED_AT, false)));

        AskThreadContext context = context(passed, snapshotOf(wall(true)));

        assertThat(context.exchanges().getFirst().pickLines())
                .containsExactly("1. a place no longer listed · no longer in the forecast");
        assertThat(context.fact().heldBack()).isEmpty();
        assertThat(context.fact().droppedPicks()).containsExactly(new Pair(1L, "2026-10-04_sunset"));
    }

    // -- the rank_spots note ----------------------------------------------------------------

    private AskToolResult<AskTools.RankSpotsResult> rank(AskTools tools, String... regionNames) {
        return tools.rankSpots(new AskTools.RankSpotsArgs(null, regionNames.length == 0 ? null
                : List.of(regionNames), null, null, null, 8));
    }

    @Test
    @DisplayName("rank_spots says how many places the guard withheld - never which: no name, id or star")
    void rankSpots_notesTheCountNotTheNames() {
        AskToolResult<AskTools.RankSpotsResult> result = rank(tools(snapshotOf(wall(true))));

        assertThat(result.payload().note()).isEqualTo(
                "1 place in these windows is rated well above the places beside it and is not offered.");
        assertThat(result.content()).doesNotContain("Caw Gap").doesNotContain("\"locationId\":1,");
    }

    @Test
    @DisplayName("the count is of distinct places, pluralised, and absent when the guard withheld nothing")
    void rankSpots_noteCountAndAbsence() {
        BriefingRegion two = AskFixtures.region("Wall", true,
                AskFixtures.outlier(AskFixtures.slot(1L, "Caw Gap", 4)),
                AskFixtures.outlier(AskFixtures.slot(5L, "Peel Crags", 4)),
                AskFixtures.slot(4L, "Housesteads", 3));
        BriefingRegion none = AskFixtures.region("Wall", true, AskFixtures.slot(4L, "Housesteads", 3));

        assertThat(rank(tools(snapshotOf(two))).payload().note()).isEqualTo(
                "2 places in these windows are rated well above the places beside them and are not offered.");
        assertThat(rank(tools(snapshotOf(none))).payload().note()).isNull();
    }

    @Test
    @DisplayName("the note is added to the empty-list note, and only for places in the scope and regions "
            + "searched")
    void rankSpots_noteRespectsScopeAndRegions() {
        BriefingRegion onlyMarked = AskFixtures.region("Wall", true,
                AskFixtures.outlier(AskFixtures.slot(1L, "Caw Gap", 4)));
        BriefingRegion hills = AskFixtures.region("Hills", true, AskFixtures.slot(9L, "Hill Top", 3));
        AskSnapshot snapshot = snapshotOf(onlyMarked, hills);

        assertThat(rank(tools(snapshotOf(onlyMarked))).payload().note()).isEqualTo(
                "Nothing rated 3★ or better in scope. 1 place in these windows is rated well above the "
                        + "places beside it and is not offered.");
        assertThat(rank(new AskTools(snapshot, AskUserContext.userLess(), TestScopes.of("Hills"), driveTimes,
                mapper)).payload().note()).as("the marked place is outside the scope").isNull();
        assertThat(rank(tools(snapshot), "Hills").payload().note())
                .as("the marked place is outside the regions asked for").isNull();
        assertThat(rank(tools(snapshot), "Wall").payload().note()).isNotNull();
    }

    // -- the stale-thread can't-answer ------------------------------------------------------

    private Result staleResult(ThreadFact fact, long... submitted) {
        AskEvidence evidence = new AskEvidence(Set.of(), Set.of(), 0).withThread(fact);
        return validate(answer(submitted), snapshotOf(wall(true)), evidence);
    }

    @Test
    @DisplayName("every submitted pick held back: the can't-answer says the places rate above their "
            + "neighbours, in words that are true of places still in the forecast")
    void stale_allHeldBack() {
        Result result = staleResult(new ThreadFact(Set.of(), Set.of(pair(1L), pair(5L)), Set.of(),
                Set.of(), Set.of(pair(1L), pair(5L))), 1L, 5L);

        assertThat(result.accepted()).isTrue();
        assertThat(result.answer().answerable()).isFalse();
        assertThat(result.answer().missing()).isEqualTo(HELD_BACK_MISSING);
        assertThat(result.answer().summary()).isEqualTo("The places from your earlier answers are rated well "
                + "above the places beside them, so PhotoCast is not suggesting them.");
    }

    @Test
    @DisplayName("mixed reasons: the neutral can't-answer; none held back: today's text, unchanged")
    void stale_mixedAndNoneHeldBack() {
        ThreadFact mixed = new ThreadFact(Set.of(), Set.of(pair(1L), pair(7L)), Set.of(), Set.of(),
                Set.of(pair(1L)));
        ThreadFact none = new ThreadFact(Set.of(), Set.of(pair(1L), pair(7L)), Set.of());

        Result mixedResult = staleResult(mixed, 1L, 7L);
        Result noneResult = staleResult(none, 1L, 7L);

        assertThat(mixedResult.answer().missing()).isEqualTo("those places as suggestions");
        assertThat(mixedResult.answer().summary())
                .isEqualTo("The places from your earlier answers can no longer be suggested.");
        assertThat(noneResult.answer().missing()).isEqualTo(GONE_MISSING);
        assertThat(noneResult.answer().summary())
                .isEqualTo("The places from your earlier answers are no longer in the forecast.");
    }

    @Test
    @DisplayName("the reason is judged over the places SUBMITTED, not every dropped one: a held-back place "
            + "the model did not submit does not make the others' text 'held back'")
    void stale_judgedOverSubmitted() {
        ThreadFact fact = new ThreadFact(Set.of(), Set.of(pair(1L), pair(7L)), Set.of(), Set.of(),
                Set.of(pair(1L)));

        assertThat(staleResult(fact, 7L).answer().missing()).isEqualTo(GONE_MISSING);
        assertThat(staleResult(fact, 1L).answer().missing()).isEqualTo(HELD_BACK_MISSING);
    }

    @Test
    @DisplayName("with nothing submitted and no earlier pick left, the reason is judged over every dropped "
            + "pick")
    void stale_nothingSubmitted() {
        ThreadFact fact = new ThreadFact(Set.of(), Set.of(pair(1L)), Set.of(), Set.of(), Set.of(pair(1L)));

        assertThat(staleResult(fact).answer().missing()).isEqualTo(HELD_BACK_MISSING);
    }

    @Test
    @DisplayName("all three missing phrases fit the word cap the model's own does")
    void stale_missingPhrasesFitTheCap() {
        for (String missing : List.of(AskAnswerValidator.HELD_BACK_MISSING,
                AskAnswerValidator.MIXED_STALE_MISSING, AskAnswerValidator.STALE_THREAD_MISSING)) {
            assertThat(missing.split(" ")).hasSizeLessThanOrEqualTo(AskAnswerValidator.MISSING_WORDS);
        }
    }

    @Test
    @DisplayName("end to end: a thread whose only pick became an outlier, with the model resubmitting it, is "
            + "the held-back can't-answer")
    void stale_endToEndFromTheThreadContext() {
        AskSnapshot snapshot = snapshotOf(wall(true));
        AskEvidence evidence = new AskEvidence(Set.of(), Set.of(), 0)
                .withThread(context(thread(1L), snapshot).fact());

        Result result = validate(answer(1L), snapshot, evidence);

        assertThat(result.answer().answerable()).isFalse();
        assertThat(result.answer().missing()).isEqualTo(HELD_BACK_MISSING);
    }

    // -- the Ready answer -------------------------------------------------------------------

    private static AskSnapshot live(BriefingRegion saturday) {
        return ReadyFixtures.at(ReadyFixtures.FRIDAY_NOON,
                ReadyFixtures.day(ReadyFixtures.oct(9), false, true, null, ReadyFixtures.northumberland()),
                ReadyFixtures.day(ReadyFixtures.oct(10), true, true,
                        AskFixtures.pick(BriefingWindow.PickKind.BEST, "Northumberland", "Bamburgh", 1L),
                        saturday),
                ReadyFixtures.both(ReadyFixtures.oct(11), ReadyFixtures.northumberland()));
    }

    @Test
    @DisplayName("a Ready answer whose pick has become a neighbour outlier is withheld whole; unmarked it "
            + "is fresh")
    void ready_pickBecameAnOutlier_isWithheld() {
        AskPick pick = ReadyFixtures.pick(1, 1L, "Bamburgh", "Northumberland", "2026-10-10_sunset", 5,
                DisplayVerdict.resolve(5, Verdict.GO));
        AskReadyStore.Stored stored = new AskReadyStore.Stored("ALL", "BEST_WEEKEND", "Best spot this weekend?",
                List.of("2026-10-10_sunrise", "2026-10-10_sunset", "2026-10-11_sunrise", "2026-10-11_sunset"),
                LocalDateTime.of(2026, 10, 9, 5, 2, 11), ReadyFixtures.answer(pick));
        BriefingRegion marked = AskFixtures.region("Northumberland", true,
                AskFixtures.outlier(AskFixtures.coastal(1L, "Bamburgh", 5, "HIGH", true)),
                AskFixtures.slot(2L, "Cheviot Edge", 4));
        BriefingRegion clean = AskFixtures.region("Northumberland", true,
                AskFixtures.coastal(1L, "Bamburgh", 5, "HIGH", true),
                AskFixtures.slot(2L, "Cheviot Edge", 4));

        AskReadyFreshness.Verdict withheld = AskReadyFreshness.check(ReadyQuestion.BEST_WEEKEND, stored,
                live(marked), TestScopes.of());
        AskReadyFreshness.Verdict fresh = AskReadyFreshness.check(ReadyQuestion.BEST_WEEKEND, stored,
                live(clean), TestScopes.of());

        assertThat(fresh.fresh()).as("control: the same answer against an unmarked forecast").isTrue();
        assertThat(withheld.fresh()).isFalse();
        assertThat(withheld.answer()).isNull();
        assertThat(withheld.reason()).contains("no longer pick-eligible");
    }

    // -- list_windows' best rating ----------------------------------------------------------

    private AskTools.WindowInfo windowInfo(int served, BriefingRegion... regions) {
        BriefingWindow window = AskFixtures.window(TODAY.atTime(18, 0), DisplayVerdict.WORTH_IT, served, null);
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(AskFixtures.day(TODAY,
                AskFixtures.summary(TargetType.SUNSET, window, regions))), List.of()));
        return ((AskTools.ListWindowsResult) tools(snapshot).listWindows().payload()).windows().getFirst();
    }

    @Test
    @DisplayName("bestRating drops only the outlier: the served 4 stars (Caw Gap) gives way to the best of "
            + "the rest")
    void windowBest_changesWhenTheBestWasAnOutlier() {
        assertThat(windowInfo(4, wall(true)).bestRating()).isEqualTo(3);
    }

    @Test
    @DisplayName("bestRating keeps a verdict-ineligible region's star: a pick-eligibility derivation would "
            + "have reported nothing")
    void windowBest_keepsAVerdictIneligibleRegionsStar() {
        BriefingRegion thin = AskFixtures.region("Thin", false, AskFixtures.slot(8L, "Lonely", 3));
        BriefingRegion marked = AskFixtures.region("Wall", true,
                AskFixtures.outlier(AskFixtures.slot(1L, "Caw Gap", 4)), AskFixtures.slot(2L, "Cawfields", 1));

        assertThat(windowInfo(4, marked, thin).bestRating()).isEqualTo(3);
    }

    @Test
    @DisplayName("bestRating keeps a sub-3 best: with the outlier gone the window's best is 2 stars, as the "
            + "Plan projector's population says, not nothing")
    void windowBest_keepsASubFloorBest() {
        BriefingRegion marked = AskFixtures.region("Wall", true,
                AskFixtures.outlier(AskFixtures.slot(1L, "Caw Gap", 4)),
                AskFixtures.slot(2L, "Cawfields", 1), AskFixtures.slot(3L, "Steel Rigg", 2));

        assertThat(windowInfo(4, marked).bestRating()).isEqualTo(2);
    }

    @Test
    @DisplayName("bestRating is the served figure when no slot is marked, including an all-canopy window")
    void windowBest_unchangedWithoutAnOutlier() {
        BriefingRegion woods = AskFixtures.region("Woods", true, AskFixtures.wood(6L, "Kielder Wood", 5),
                AskFixtures.wood(7L, "Allen Banks", 4));
        BriefingRegion plain = AskFixtures.region("Plain", true, AskFixtures.slot(2L, "Cawfields", 3));

        assertThat(windowInfo(5, woods).bestRating()).as("all-canopy window").isEqualTo(5);
        assertThat(windowInfo(4, plain).bestRating()).as("served figure, not re-derived").isEqualTo(4);
    }

    @Test
    @DisplayName("bestRating does not change when the best was not the outlier: a higher rating elsewhere "
            + "stands")
    void windowBest_unchangedWhenTheBestWasElsewhere() {
        BriefingRegion elsewhere = AskFixtures.region("Coast", true, AskFixtures.slot(9L, "Whitby", 5));

        assertThat(windowInfo(5, wall(true), elsewhere).bestRating()).isEqualTo(5);
    }

    @Test
    @DisplayName("the projector helper counts canopy only in an all-canopy window and never an unusable "
            + "rating, so Ask's population is the Plan projector's own")
    void projectorHelper_population() {
        BriefingSlot woodMarked = AskFixtures.outlier(AskFixtures.wood(6L, "Kielder Wood", 5));
        BriefingSlot wood = AskFixtures.wood(7L, "Allen Banks", 4);
        BriefingEventSummary allCanopy = AskFixtures.summaryWithoutWindow(TargetType.SUNSET,
                AskFixtures.region("Woods", true, woodMarked, wood));
        BriefingEventSummary mixed = AskFixtures.summaryWithoutWindow(TargetType.SUNSET,
                AskFixtures.region("Mixed", true, AskFixtures.wood(6L, "Kielder Wood", 5),
                        AskFixtures.slot(2L, "Cawfields", 3), AskFixtures.slot(3L, "Bad", 491)));

        assertThat(PlanWindowProjector.bestRatingWithoutOutliers(allCanopy))
                .as("all-canopy: the wood stands in, the marked one excluded").isEqualTo(4);
        assertThat(PlanWindowProjector.bestRatingWithoutOutliers(mixed))
                .as("a wood never beats a sky slot; 491 is no rating").isEqualTo(3);
    }

    @Test
    @DisplayName("the snapshot slot carries the served mark; an unmarked slot does not")
    void builder_readsTheServedMark() {
        AskSnapshot snapshot = snapshotOf(wall(true));

        assertThat(snapshot.slotAt(SUNSET_TODAY, 1L).orElseThrow().slot().neighbourOutlier()).isTrue();
        assertThat(snapshot.slotAt(SUNSET_TODAY, 2L).orElseThrow().slot().neighbourOutlier()).isFalse();
    }
}
