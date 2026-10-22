package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.CoverageReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static com.gregochr.goldenhour.service.ask.ReadyFixtures.at;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.day;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.oct;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The honest "nothing worth going out for" Ready answer (ask-window-context plan §2.3): a solar pick
 * question whose covered windows hold no game-on candidate the question admits may be answered with no
 * pick, judged by ONE predicate ({@link ReadyRelevance#mayHaveNoPick}) at every site that decides —
 * the validator's anchor rule, {@link ReadyRelevance#violation} and {@link AskReadyFreshness#check} —
 * at store and at serve, in both directions: a stay-home weekend stores and serves it, a 4★ appearing
 * withholds it, and anchored behaviour is otherwise exactly what it was.
 */
class AskNoPickAnswerTest {

    private static final String SAT_SUNRISE = "2026-10-10_sunrise";
    private static final String SAT_SUNSET = "2026-10-10_sunset";
    private static final String SUN_SUNRISE = "2026-10-11_sunrise";
    private static final String SUN_SUNSET = "2026-10-11_sunset";
    private static final LocalDateTime BUILT = LocalDateTime.of(2026, 10, 9, 5, 2, 11);

    private final AskAnswerValidator validator = new AskAnswerValidator();

    // -- fixtures ---------------------------------------------------------------------------

    /** The 3★ the BEST BET names in a region of {@code stayHome} (ids 1-56 are 2★, 57+ are 3★). */
    private static BriefingWindow.Pick crownOnA3Star() {
        return AskFixtures.pick(BriefingWindow.PickKind.BEST, "Northumberland", "Northumberland 3-0", 57L);
    }

    private static BriefingRegion stayHome() {
        return AskFixtures.bandedRegion("Northumberland", 1L, 0, 56, 122, 0, 0);
    }

    private static BriefingRegion withTwoExceptions() {
        return AskFixtures.bandedRegion("Northumberland", 1L, 0, 56, 122, 2, 0);
    }

    /** Friday noon: Saturday and Sunday, both windows each, the given region on every one. */
    private static AskSnapshot weekendOf(BriefingRegion region, BriefingWindow.Pick saturdaySunsetPick) {
        return at(ReadyFixtures.FRIDAY_NOON,
                day(oct(10), true, true, saturdaySunsetPick, region),
                day(oct(11), true, true, null, region));
    }

    private static ReadyQuestion.Offer offerOf(ReadyQuestion question, AskSnapshot snapshot, AskScope scope) {
        return question.offer(snapshot, scope).orElseThrow();
    }

    private static AskAnswerValidator.Raw noPick() {
        return new AskAnswerValidator.Raw(true, "Not a weekend to go out for.", List.of(), List.of(), null);
    }

    private AskAnswerValidator.Result validate(ReadyQuestion question, AskSnapshot snapshot, AskScope scope,
            AskAnswerValidator.Raw raw) {
        ReadyQuestion.Offer offer = offerOf(question, snapshot, scope);
        return validator.validate(raw, snapshot, new AskEvidence(Set.of(), Set.of(), 1), scope,
                question.anchor(offer), null, false, new AskAnswerValidator.ReadyBasis(question, offer));
    }

    private static AskAnswer noPickAnswer() {
        return new AskAnswer(true, "Not a weekend to go out for.", List.of(), List.of(), null);
    }

    private static AskReadyStore.Stored stored(ReadyQuestion question, ReadyQuestion.Offer offer, AskAnswer answer) {
        return new AskReadyStore.Stored("ALL", question.name(), offer.text(), offer.windowIds(), BUILT, answer,
                null, offer.coveredWindowIds());
    }

    // -- the offer --------------------------------------------------------------------------

    @Test
    @DisplayName("on a stay-home weekend BEST_WEEKEND is still offered (a 3★ is a candidate), so the honest "
            + "answer has to be able to exist")
    void stayHomeWeekendIsStillOffered() {
        AskSnapshot snapshot = weekendOf(stayHome(), crownOnA3Star());

        ReadyQuestion.Offer offer = offerOf(ReadyQuestion.BEST_WEEKEND, snapshot, AskScope.ALL);

        assertThat(offer.windowIds()).containsExactly(SAT_SUNRISE, SAT_SUNSET, SUN_SUNRISE, SUN_SUNSET);
        assertThat(snapshot.hasGameOn(offer.coveredWindowIds(), AskScope.ALL, s -> true)).isFalse();
    }

    @Test
    @DisplayName("the covered set is temporal: an all-blue Saturday beside a game-on Sunday is covered but is "
            + "not among the candidate-bearing windowIds")
    void coveredWindowsAreNotTheCandidateBearingOnes() {
        AskSnapshot snapshot = at(ReadyFixtures.FRIDAY_NOON,
                day(oct(10), true, true, null, AskFixtures.blankedRegion("Northumberland", CoverageReason.STOOD_DOWN)),
                day(oct(11), true, true, null, withTwoExceptions()));

        ReadyQuestion.Offer weekend = offerOf(ReadyQuestion.BEST_WEEKEND, snapshot, AskScope.ALL);

        assertThat(weekend.windowIds()).containsExactly(SUN_SUNRISE, SUN_SUNSET);
        assertThat(weekend.coveredWindowIds()).containsExactly(SAT_SUNRISE, SAT_SUNSET, SUN_SUNRISE, SUN_SUNSET);
    }

    @Test
    @DisplayName("each question covers its own windows: the next window, both windows of the date, every solar "
            + "window of the set (including one with no candidate)")
    void coveredWindowsPerQuestion() {
        AskSnapshot weekdays = at(ReadyFixtures.MONDAY_NOON,
                day(oct(5), false, true, null, withTwoExceptions()),
                day(oct(6), true, true, null, withTwoExceptions()),
                day(oct(7), true, false, null,
                        AskFixtures.blankedRegion("Northumberland", CoverageReason.STOOD_DOWN)));

        assertThat(offerOf(ReadyQuestion.BEST_NEXT, weekdays, AskScope.ALL).coveredWindowIds())
                .containsExactly("2026-10-05_sunset");
        assertThat(offerOf(ReadyQuestion.AM_OR_PM, weekdays, AskScope.ALL).coveredWindowIds())
                .containsExactly("2026-10-06_sunrise", "2026-10-06_sunset");
        assertThat(offerOf(ReadyQuestion.BEST_SOON, weekdays, AskScope.ALL).windowIds())
                .as("only the windows holding a candidate").containsExactly("2026-10-05_sunset", "2026-10-06_sunrise",
                        "2026-10-06_sunset");
        assertThat(offerOf(ReadyQuestion.BEST_SOON, weekdays, AskScope.ALL).coveredWindowIds())
                .as("every solar window, the all-blue one too")
                .containsExactly("2026-10-05_sunset", "2026-10-06_sunrise", "2026-10-06_sunset",
                        "2026-10-07_sunrise");
    }

    // -- the validator ----------------------------------------------------------------------

    @Test
    @DisplayName("BEST_WEEKEND with no game-on place on the covered windows: a no-pick answer is accepted "
            + "though the forecast has a BEST BET to lead with")
    void validator_noPickStandsWhenNothingIsGameOn() {
        AskSnapshot snapshot = weekendOf(stayHome(), crownOnA3Star());

        AskAnswerValidator.Result result = validate(ReadyQuestion.BEST_WEEKEND, snapshot, AskScope.ALL, noPick());

        assertThat(result.accepted()).as(String.valueOf(result.reason())).isTrue();
        assertThat(result.answer().picks()).isEmpty();
    }

    @Test
    @DisplayName("with the very same BEST BET but a 4★ on the covered windows, the no-pick answer is discarded "
            + "again: the anchor is exactly what it was")
    void validator_noPickIsDiscardedOnceAGameOnPlaceExists() {
        AskSnapshot snapshot = weekendOf(withTwoExceptions(), crownOnA3Star());

        AskAnswerValidator.Result result = validate(ReadyQuestion.BEST_WEEKEND, snapshot, AskScope.ALL, noPick());

        assertThat(result.accepted()).isFalse();
        assertThat(result.reason()).contains("pick 1 is not on the BEST BET window");
    }

    @Test
    @DisplayName("a pick on a window other than the BEST BET's is still discarded when nothing is game on: "
            + "only the no-pick answer is admitted, a pick answer is anchored as before")
    void validator_pickAnswersStayAnchored() {
        AskSnapshot snapshot = weekendOf(stayHome(), crownOnA3Star());
        ReadyQuestion.Offer offer = offerOf(ReadyQuestion.BEST_WEEKEND, snapshot, AskScope.ALL);
        AskAnswerValidator.Raw onSunday = new AskAnswerValidator.Raw(true, "Sunday.",
                List.of(new AskAnswerValidator.RawPick(57L, SUN_SUNSET, "x")), List.of(), null);

        AskAnswerValidator.Result result = validator.validate(onSunday, snapshot,
                new AskEvidence(Set.of(new AskEvidence.Pair(57L, SUN_SUNSET)), Set.of(), 1), AskScope.ALL,
                ReadyQuestion.BEST_WEEKEND.anchor(offer), null, false,
                new AskAnswerValidator.ReadyBasis(ReadyQuestion.BEST_WEEKEND, offer));

        assertThat(result.accepted()).isFalse();
        assertThat(result.reason()).contains(SAT_SUNSET);
    }

    @Test
    @DisplayName("without the Ready basis (every typed question, every older caller) the anchor rule is "
            + "byte for byte what it was: a no-pick answer is discarded")
    void validator_noBasisKeepsTodaysBehaviour() {
        AskSnapshot snapshot = weekendOf(stayHome(), crownOnA3Star());
        ReadyQuestion.Offer offer = offerOf(ReadyQuestion.BEST_WEEKEND, snapshot, AskScope.ALL);

        AskAnswerValidator.Result result = validator.validate(noPick(), snapshot,
                new AskEvidence(Set.of(), Set.of(), 1), AskScope.ALL, ReadyQuestion.BEST_WEEKEND.anchor(offer),
                null, false);

        assertThat(result.accepted()).isFalse();
    }

    @Test
    @DisplayName("a region scope whose crown lies in ANOTHER region but which holds a 4★ is refused a no-pick "
            + "answer: the admission reads the set, never the anchor lookup's emptiness")
    void validator_regionScopeWithTheCrownElsewhereIsRefused() {
        BriefingRegion hills = AskFixtures.bandedRegion("Hills", 1_000L, 0, 0, 3, 1, 0);
        AskSnapshot snapshot = at(ReadyFixtures.FRIDAY_NOON,
                day(oct(10), true, true, crownOnA3Star(), stayHome(), hills),
                day(oct(11), true, true, null, stayHome(), hills));
        AskScope hillsOnly = TestScopes.of("Hills");

        AskAnswerValidator.Result result = validate(ReadyQuestion.BEST_WEEKEND, snapshot, hillsOnly, noPick());

        assertThat(ReadyQuestion.BEST_WEEKEND.anchor(offerOf(ReadyQuestion.BEST_WEEKEND, snapshot, hillsOnly)))
                .isNotNull();
        assertThat(AskAnswerValidator.anchoredWindow(snapshot, new AskAnswerValidator.BestAnchor(
                Set.of(SAT_SUNSET)), hillsOnly)).as("the crown is outside the scope, so no lead").isEmpty();
        assertThat(snapshot.hasGameOn(List.of(SAT_SUNSET), hillsOnly, s -> true)).isTrue();
        assertThat(result.accepted()).as("no lead, so the anchor rule passes it; relevance is what refuses it")
                .isTrue();
        assertThat(ReadyRelevance.violation(ReadyQuestion.BEST_WEEKEND, result.answer(),
                offerOf(ReadyQuestion.BEST_WEEKEND, snapshot, hillsOnly), snapshot, hillsOnly))
                .hasValue("a pick question with no pick");
    }

    // -- ReadyRelevance ---------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = ReadyQuestion.class, names = {"BEST_WEEKEND", "BEST_SOON", "BEST_NEXT", "COASTAL_HIGH",
        "AM_OR_PM"})
    @DisplayName("every SOLAR pick question may have no pick when nothing it covers is game on, and may not "
            + "once something is")
    void relevance_everySolarPickQuestion(ReadyQuestion question) {
        // BEST_WEEKEND needs a weekend in the set; BEST_SOON needs there to be none.
        boolean weekend = question == ReadyQuestion.BEST_WEEKEND;
        java.time.LocalDateTime now = weekend ? ReadyFixtures.FRIDAY_NOON : ReadyFixtures.MONDAY_NOON;
        int first = weekend ? 10 : 6;
        AskSnapshot quiet = at(now, day(oct(first), true, true, null, coastalThree()),
                day(oct(first + 1), true, true, null, coastalThree()));
        AskSnapshot lively = at(now, day(oct(first), true, true, null, coastalFour()),
                day(oct(first + 1), true, true, null, coastalFour()));

        ReadyQuestion.Offer quietOffer = offerOf(question, quiet, AskScope.ALL);
        ReadyQuestion.Offer livelyOffer = offerOf(question, lively, AskScope.ALL);

        assertThat(quietOffer.coveredWindowIds()).isNotEmpty();
        assertThat(ReadyRelevance.violation(question, noPickAnswer(), quietOffer, quiet, AskScope.ALL)).isEmpty();
        assertThat(ReadyRelevance.violation(question, noPickAnswer(), livelyOffer, lively, AskScope.ALL))
                .hasValue("a pick question with no pick");
    }

    /** A coastal 3★ at high water: a candidate, never game on. */
    private static BriefingRegion coastalThree() {
        return AskFixtures.region("Northumberland", true, AskFixtures.coastal(1L, "Bamburgh", 3, "HIGH", true));
    }

    /** A coastal 4★ at high water: game on. */
    private static BriefingRegion coastalFour() {
        return AskFixtures.region("Northumberland", true, AskFixtures.coastal(1L, "Bamburgh", 4, "HIGH", true));
    }

    @Test
    @DisplayName("COASTAL_HIGH: a 4★ INLAND place does not make a window game on for it, and a 4★ high-water "
            + "coastal one does; the question's own predicate is the test")
    void relevance_coastalHighUsesItsOwnSlotPredicate() {
        BriefingRegion inlandGameOn = AskFixtures.region("Northumberland", true,
                AskFixtures.coastal(1L, "Bamburgh", 3, "HIGH", true), AskFixtures.slot(2L, "Cheviot Edge", 4));
        BriefingRegion coastalGameOn = AskFixtures.region("Northumberland", true,
                AskFixtures.coastal(1L, "Bamburgh", 4, "HIGH", true), AskFixtures.slot(2L, "Cheviot Edge", 4));
        AskSnapshot inland = at(ReadyFixtures.FRIDAY_NOON, day(oct(10), true, true, null, inlandGameOn));
        AskSnapshot coast = at(ReadyFixtures.FRIDAY_NOON, day(oct(10), true, true, null, coastalGameOn));

        ReadyQuestion.Offer inlandOffer = offerOf(ReadyQuestion.COASTAL_HIGH, inland, AskScope.ALL);
        ReadyQuestion.Offer coastOffer = offerOf(ReadyQuestion.COASTAL_HIGH, coast, AskScope.ALL);

        assertThat(ReadyRelevance.mayHaveNoPick(ReadyQuestion.COASTAL_HIGH, inlandOffer, inland, AskScope.ALL))
                .as("the inland 4★ is not a high-water coastal place").isTrue();
        assertThat(ReadyRelevance.mayHaveNoPick(ReadyQuestion.BEST_SOON, inlandOffer, inland, AskScope.ALL))
                .as("while for a question that admits any slot it IS game on").isFalse();
        assertThat(ReadyRelevance.mayHaveNoPick(ReadyQuestion.COASTAL_HIGH, coastOffer, coast, AskScope.ALL))
                .isFalse();
    }

    @Test
    @DisplayName("a night-bound question and an events question never take the no-pick admission")
    void relevance_nightAndEventsAreUntouched() {
        AskSnapshot snapshot = weekendOf(stayHome(), null);
        ReadyQuestion.Offer none = new ReadyQuestion.Offer("Anything for the night sky tonight?",
                List.of("2026-10-09_astro"), null);

        assertThat(ReadyRelevance.mayHaveNoPick(ReadyQuestion.NIGHT_SKY, none, snapshot, AskScope.ALL)).isFalse();
        assertThat(ReadyRelevance.violation(ReadyQuestion.NIGHT_SKY, noPickAnswer(), none, snapshot, AskScope.ALL))
                .hasValue("a pick question with no pick");
        assertThat(ReadyRelevance.mayHaveNoPick(ReadyQuestion.RARE_EVENTS, none, snapshot, AskScope.ALL)).isFalse();
    }

    // -- freshness --------------------------------------------------------------------------

    @Test
    @DisplayName("a stored no-pick answer is fresh while the covered windows hold nothing game on, and "
            + "withheld the moment a 4★ appears (the BEST BET lead is back, or relevance refuses it)")
    void freshness_noPickAnswerIsWithheldWhenAGameOnPlaceAppears() {
        AskSnapshot quiet = weekendOf(stayHome(), crownOnA3Star());
        AskSnapshot lively = weekendOf(withTwoExceptions(), crownOnA3Star());
        ReadyQuestion.Offer offer = offerOf(ReadyQuestion.BEST_WEEKEND, quiet, AskScope.ALL);
        AskReadyStore.Stored stored = stored(ReadyQuestion.BEST_WEEKEND, offer, noPickAnswer());

        AskReadyFreshness.Verdict whileQuiet = AskReadyFreshness.check(ReadyQuestion.BEST_WEEKEND, stored, quiet,
                AskScope.ALL);
        AskReadyFreshness.Verdict onceLively = AskReadyFreshness.check(ReadyQuestion.BEST_WEEKEND, stored, lively,
                AskScope.ALL);

        assertThat(whileQuiet.fresh()).as(String.valueOf(whileQuiet.reason())).isTrue();
        assertThat(onceLively.fresh()).isFalse();
        assertThat(onceLively.reason()).contains("BEST BET");
    }

    @Test
    @DisplayName("with no BEST BET at all, a stored no-pick answer is withheld by relevance once a 4★ appears")
    void freshness_noLeadSoRelevanceWithholds() {
        AskSnapshot quiet = weekendOf(stayHome(), null);
        AskSnapshot lively = weekendOf(withTwoExceptions(), null);
        ReadyQuestion.Offer offer = offerOf(ReadyQuestion.BEST_WEEKEND, quiet, AskScope.ALL);
        AskReadyStore.Stored stored = stored(ReadyQuestion.BEST_WEEKEND, offer, noPickAnswer());

        assertThat(AskReadyFreshness.check(ReadyQuestion.BEST_WEEKEND, stored, quiet, AskScope.ALL).fresh()).isTrue();
        AskReadyFreshness.Verdict verdict = AskReadyFreshness.check(ReadyQuestion.BEST_WEEKEND, stored, lively,
                AskScope.ALL);
        assertThat(verdict.fresh()).isFalse();
        assertThat(verdict.reason()).isEqualTo("a pick question with no pick");
    }

    @Test
    @DisplayName("a stored answer WITH a pick is anchored exactly as before, whether or not anything is game on")
    void freshness_pickAnswersAreUnchanged() {
        AskSnapshot snapshot = weekendOf(stayHome(), crownOnA3Star());
        ReadyQuestion.Offer offer = offerOf(ReadyQuestion.BEST_WEEKEND, snapshot, AskScope.ALL);
        AskPick onSunday = ReadyFixtures.pick(1, 57L, "Northumberland 3-0", "Northumberland", SUN_SUNSET, 3,
                com.gregochr.goldenhour.model.DisplayVerdict.MAYBE);
        AskPick onTheCrown = ReadyFixtures.pick(1, 57L, "Northumberland 3-0", "Northumberland", SAT_SUNSET, 3,
                com.gregochr.goldenhour.model.DisplayVerdict.MAYBE);

        AskReadyFreshness.Verdict elsewhere = AskReadyFreshness.check(ReadyQuestion.BEST_WEEKEND,
                stored(ReadyQuestion.BEST_WEEKEND, offer, ReadyFixtures.answer(onSunday)), snapshot, AskScope.ALL);
        AskReadyFreshness.Verdict leading = AskReadyFreshness.check(ReadyQuestion.BEST_WEEKEND,
                stored(ReadyQuestion.BEST_WEEKEND, offer, ReadyFixtures.answer(onTheCrown)), snapshot, AskScope.ALL);

        assertThat(elsewhere.fresh()).isFalse();
        assertThat(elsewhere.reason()).contains(SAT_SUNSET);
        assertThat(leading.fresh()).isTrue();
    }

    @Test
    @DisplayName("COASTAL_HIGH covers every solar window of the set, and a stored no-pick COASTAL_HIGH answer "
            + "stays fresh beside an inland 4★ and is withheld once a high-water coastal 4★ appears")
    void coastalHighCoveredWindowsAndFreshness() {
        BriefingRegion inlandGameOn = AskFixtures.region("Northumberland", true,
                AskFixtures.coastal(1L, "Bamburgh", 3, "HIGH", true), AskFixtures.slot(2L, "Cheviot Edge", 4));
        BriefingRegion coastalGameOn = AskFixtures.region("Northumberland", true,
                AskFixtures.coastal(1L, "Bamburgh", 4, "HIGH", true), AskFixtures.slot(2L, "Cheviot Edge", 4));
        AskSnapshot inland = at(ReadyFixtures.FRIDAY_NOON, day(oct(10), true, true, null, inlandGameOn));
        AskSnapshot coast = at(ReadyFixtures.FRIDAY_NOON, day(oct(10), true, true, null, coastalGameOn));
        ReadyQuestion.Offer offer = offerOf(ReadyQuestion.COASTAL_HIGH, inland, AskScope.ALL);
        AskReadyStore.Stored stored = stored(ReadyQuestion.COASTAL_HIGH, offer, noPickAnswer());

        assertThat(offer.coveredWindowIds()).containsExactly(SAT_SUNRISE, SAT_SUNSET);
        assertThat(AskReadyFreshness.check(ReadyQuestion.COASTAL_HIGH, stored, inland, AskScope.ALL).fresh())
                .isTrue();
        AskReadyFreshness.Verdict verdict = AskReadyFreshness.check(ReadyQuestion.COASTAL_HIGH, stored, coast,
                AskScope.ALL);
        assertThat(verdict.fresh()).isFalse();
        assertThat(verdict.reason()).isEqualTo("a pick question with no pick");
    }
}
