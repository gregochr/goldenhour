package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.CoverageReason;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static com.gregochr.goldenhour.service.ask.ReadyFixtures.at;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.day;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.oct;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The figures an answer carries and the freshness test that withdraws it when they move
 * (ask-window-context plan §2.4): the whole {@link AskSnapshot.Spread} of every window the prose may
 * have quoted, built by the validator from the live snapshot, threaded through every rebuild of the
 * answer, compared by record equality on every serve.
 */
class AskCarriedWindowsTest {

    private static final String SAT_SUNRISE = "2026-10-10_sunrise";
    private static final String SAT_SUNSET = "2026-10-10_sunset";
    private static final String SUN_SUNRISE = "2026-10-11_sunrise";
    private static final String SUN_SUNSET = "2026-10-11_sunset";
    private static final List<String> WEEKEND = List.of(SAT_SUNRISE, SAT_SUNSET, SUN_SUNRISE, SUN_SUNSET);
    private static final LocalDateTime BUILT = LocalDateTime.of(2026, 10, 9, 5, 2, 11);
    private static final DisplayVerdict V4 = DisplayVerdict.resolve(4, Verdict.GO);

    private final AskAnswerValidator validator = new AskAnswerValidator();

    // -- fixtures ---------------------------------------------------------------------------

    /**
     * Friday noon: a Saturday of {@code saturday} and a Sunday of {@code sunday} (both windows each); the
     * Sunday sunset also holds the 4★ pick, "Sunday Four" (id 900).
     */
    private static AskSnapshot weekend(BriefingRegion saturday, BriefingRegion sunday) {
        return at(ReadyFixtures.FRIDAY_NOON,
                day(oct(10), true, true, null, saturday),
                day(oct(11), true, true, null, sunday));
    }

    private static AskSnapshot weekend(BriefingRegion saturday, BriefingRegion sunday, BriefingRegion saturdayToo) {
        return at(ReadyFixtures.FRIDAY_NOON,
                day(oct(10), true, true, null, saturday, saturdayToo),
                day(oct(11), true, true, null, sunday));
    }

    private static BriefingRegion region(int... bands) {
        return AskFixtures.bandedRegion("Northumberland", 1L, bands);
    }

    private static BriefingRegion sundayRegion() {
        return AskFixtures.region("Northumberland", true, AskFixtures.slot(900L, "Sunday Four", 4),
                AskFixtures.slot(901L, "Sunday Three", 3));
    }

    private static AskPick sundayPick() {
        return ReadyFixtures.pick(1, 900L, "Sunday Four", "Northumberland", SUN_SUNSET, 4, V4);
    }

    private static AskAnswerValidator.Raw rawSunday() {
        return new AskAnswerValidator.Raw(true, "Sunday evening is the one.",
                List.of(new AskAnswerValidator.RawPick(900L, SUN_SUNSET, "Clear.")), List.of(), null);
    }

    private static AskEvidence evidence(Set<String> listed, AskEvidence.Pair... pairs) {
        return new AskEvidence(Set.of(pairs), Set.of(), 1, AskEvidence.ThreadFact.NONE, listed);
    }

    private static ReadyQuestion.Offer weekendOffer(AskSnapshot snapshot) {
        return ReadyQuestion.BEST_WEEKEND.offer(snapshot, AskScope.ALL).orElseThrow();
    }

    private static AskReadyStore.Stored storedWith(AskAnswer answer, List<String> covered) {
        return new AskReadyStore.Stored("ALL", "BEST_WEEKEND", "Best spot this weekend?", WEEKEND, BUILT, answer,
                null, covered);
    }

    private AskAnswer carried(AskSnapshot snapshot, Set<String> listed) {
        AskAnswerValidator.Result result = validator.validate(rawSunday(), snapshot,
                evidence(listed, new AskEvidence.Pair(900L, SUN_SUNSET)), AskScope.ALL, null, null, false);
        assertThat(result.accepted()).isTrue();
        return result.answer();
    }

    private static List<String> windowIds(AskAnswer answer) {
        return answer.windows().stream().map(AskAnswer.WindowSpread::windowId).toList();
    }

    // -- built by the validator, from the live snapshot -------------------------------------

    @Test
    @DisplayName("an answer carries the WHOLE spread of every window list_windows returned and of every pick's "
            + "window, joined from the live snapshot, in window order, and no window it never saw")
    void validator_carriesListedAndPickedWindows() {
        AskSnapshot snapshot = weekend(region(0, 5, 10, 1, 0), sundayRegion());

        AskAnswer answer = carried(snapshot, Set.of(SAT_SUNRISE));

        assertThat(windowIds(answer)).containsExactly(SAT_SUNRISE, SUN_SUNSET);
        assertThat(answer.windows()).allSatisfy(w ->
                assertThat(w.spread()).isEqualTo(snapshot.spread(w.windowId(), AskScope.ALL).orElseThrow()));
        assertThat(answer.windows().getFirst().spread().bands()).containsExactly(0, 5, 10, 1, 0);
    }

    @Test
    @DisplayName("with no list_windows call and no basis, only the pick's own window is carried")
    void validator_pickWindowAlone() {
        AskAnswer answer = carried(weekend(region(0, 5, 10, 1, 0), sundayRegion()), Set.of());

        assertThat(windowIds(answer)).containsExactly(SUN_SUNSET);
    }

    @Test
    @DisplayName("a Ready answer also carries every window the question COVERS, the all-blue Saturday with no "
            + "pick on it included, though nothing listed it")
    void validator_carriesTheCoveredWindows() {
        AskSnapshot snapshot = at(ReadyFixtures.FRIDAY_NOON,
                day(oct(10), true, true, null,
                        AskFixtures.blankedRegion("Northumberland", CoverageReason.STOOD_DOWN)),
                day(oct(11), true, true, null, sundayRegion()));
        ReadyQuestion.Offer offer = weekendOffer(snapshot);

        AskAnswerValidator.Result result = validator.validate(rawSunday(), snapshot,
                evidence(Set.of(), new AskEvidence.Pair(900L, SUN_SUNSET)), AskScope.ALL,
                ReadyQuestion.BEST_WEEKEND.anchor(offer), null, false,
                new AskAnswerValidator.ReadyBasis(ReadyQuestion.BEST_WEEKEND, offer));

        assertThat(result.accepted()).as(String.valueOf(result.reason())).isTrue();
        assertThat(windowIds(result.answer())).containsExactly(SAT_SUNRISE, SAT_SUNSET, SUN_SUNRISE, SUN_SUNSET);
        assertThat(result.answer().windows().getFirst().spread().regions().stoodDown()).isEqualTo(1);
    }

    @Test
    @DisplayName("an unanswerable reply carries no window figures")
    void validator_unanswerableCarriesNone() {
        AskSnapshot snapshot = weekend(region(0, 5, 10, 1, 0), sundayRegion());

        AskAnswerValidator.Result result = validator.validate(
                new AskAnswerValidator.Raw(false, "Cannot tell.", null, null, "data"), snapshot,
                evidence(Set.of(SAT_SUNRISE)), AskScope.ALL, null, null, false);

        assertThat(result.answer().windows()).isEmpty();
    }

    @Test
    @DisplayName("a night pick adds nothing to the carried set: a night window has no spread to carry")
    void validator_nightPickAddsNothing() {
        AskSnapshot solar = weekend(region(0, 5, 10, 1, 0), sundayRegion());
        AskSnapshot.NightSpot spot = AskNightFixtures.astro(77L, "Kielder", "Northumberland", 5, 2);
        AskSnapshot snapshot = AskNightFixtures.withNights(solar, null,
                AskNightFixtures.astroNight(AskNightFixtures.TONIGHT, spot));
        String astroId = AskNightFixtures.astroNight(AskNightFixtures.TONIGHT, spot).id();

        AskAnswerValidator.Result result = validator.validate(
                new AskAnswerValidator.Raw(true, "Kielder.",
                        List.of(new AskAnswerValidator.RawPick(77L, astroId, "Dark.")), List.of(), null),
                snapshot, evidence(Set.of(), new AskEvidence.Pair(77L, astroId)), AskScope.ALL, null, null, false);

        assertThat(result.accepted()).isTrue();
        assertThat(result.answer().picks()).hasSize(1);
        assertThat(result.answer().windows()).isEmpty();
    }

    // -- every rebuild site keeps them ------------------------------------------------------

    @Test
    @DisplayName("ReadyRelevance.relevantPart (what the precompute stores) keeps the carried windows")
    void relevantPartKeepsThem() {
        AskSnapshot snapshot = weekend(region(0, 5, 10, 1, 0), sundayRegion());
        AskAnswer answer = carried(snapshot, Set.of(SAT_SUNRISE));

        AskAnswer stored = ReadyRelevance.relevantPart(ReadyQuestion.BEST_WEEKEND, answer);

        assertThat(stored.windows()).isEqualTo(answer.windows()).isNotEmpty();
    }

    @Test
    @DisplayName("the freshness re-check's own rebuild (served and typed-cached answers) keeps them")
    void recheckKeepsThem() {
        AskSnapshot snapshot = weekend(region(0, 5, 10, 1, 0), sundayRegion());
        AskAnswer answer = carried(snapshot, Set.of(SAT_SUNRISE));

        AskReadyFreshness.Verdict verdict = AskReadyFreshness.recheck(answer, snapshot, AskScope.ALL, false);

        assertThat(verdict.fresh()).isTrue();
        assertThat(verdict.answer().windows()).isEqualTo(answer.windows()).isNotEmpty();
    }

    @Test
    @DisplayName("a full Ready check keeps them too, and AskAnswer.withWindows replaces only the windows")
    void fullCheckAndWither() {
        AskSnapshot snapshot = weekend(region(0, 5, 10, 1, 0), sundayRegion());
        AskAnswer answer = carried(snapshot, Set.of(SAT_SUNRISE));
        ReadyQuestion.Offer offer = weekendOffer(snapshot);

        AskReadyFreshness.Verdict verdict = AskReadyFreshness.check(ReadyQuestion.BEST_WEEKEND,
                storedWith(answer, offer.coveredWindowIds()), snapshot, AskScope.ALL);

        assertThat(verdict.fresh()).as(String.valueOf(verdict.reason())).isTrue();
        assertThat(verdict.answer().windows()).isEqualTo(answer.windows());
        AskAnswer bare = answer.withWindows(List.of());
        assertThat(bare.windows()).isEmpty();
        assertThat(bare.summary()).isEqualTo(answer.summary());
        assertThat(bare.picks()).isEqualTo(answer.picks());
    }

    // -- freshness: the figures move --------------------------------------------------------

    @Test
    @DisplayName("withheld when a carried window's spread moves without its rated count or its game-on count "
            + "changing (a 5★ becoming a 4★): the whole record is compared")
    void spreadMoved_aFiveBecomesAFour() {
        AskSnapshot before = weekend(region(0, 1, 2, 1, 1), sundayRegion());
        AskSnapshot after = weekend(region(0, 1, 2, 2, 0), sundayRegion());
        AskAnswer answer = carried(before, Set.of(SAT_SUNRISE));

        assertThat(before.spread(SAT_SUNRISE, AskScope.ALL).orElseThrow().rated())
                .isEqualTo(after.spread(SAT_SUNRISE, AskScope.ALL).orElseThrow().rated());
        assertThat(before.spread(SAT_SUNRISE, AskScope.ALL).orElseThrow().gameOn())
                .isEqualTo(after.spread(SAT_SUNRISE, AskScope.ALL).orElseThrow().gameOn());
        assertThat(AskReadyFreshness.recheck(answer, before, AskScope.ALL, false).fresh()).isTrue();
        AskReadyFreshness.Verdict verdict = AskReadyFreshness.recheck(answer, after, AskScope.ALL, false);
        assertThat(verdict.fresh()).isFalse();
        assertThat(verdict.reason()).contains(SAT_SUNRISE).contains("moved");
    }

    @Test
    @DisplayName("withheld on a move inside the 1-3★ bands, which neither rated nor gameOn can see")
    void spreadMoved_insideTheLowBands() {
        AskSnapshot before = weekend(region(0, 3, 2, 1, 0), sundayRegion());
        AskSnapshot after = weekend(region(0, 2, 3, 1, 0), sundayRegion());
        AskAnswer answer = carried(before, Set.of(SAT_SUNRISE));

        assertThat(AskReadyFreshness.recheck(answer, after, AskScope.ALL, false).fresh()).isFalse();
    }

    @Test
    @DisplayName("withheld on a move of a pocket's mean alone: the bands, the game-on count and the number of "
            + "pockets unchanged (a 2★ and a 3★ swap places between two game-on regions)")
    void spreadMoved_aPocketMean() {
        AskSnapshot before = weekend(AskFixtures.region("Alpha", true, AskFixtures.slot(1L, "A4", 4),
                AskFixtures.slot(2L, "A2", 2)), sundayRegion(), AskFixtures.region("Beta", true,
                AskFixtures.slot(3L, "B4", 4), AskFixtures.slot(4L, "B3", 3)));
        AskSnapshot after = weekend(AskFixtures.region("Alpha", true, AskFixtures.slot(1L, "A4", 4),
                AskFixtures.slot(2L, "A3", 3)), sundayRegion(), AskFixtures.region("Beta", true,
                AskFixtures.slot(3L, "B4", 4), AskFixtures.slot(4L, "B2", 2)));
        AskSnapshot.Spread was = before.spread(SAT_SUNRISE, AskScope.ALL).orElseThrow();
        AskSnapshot.Spread now = after.spread(SAT_SUNRISE, AskScope.ALL).orElseThrow();
        AskAnswer answer = carried(before, Set.of(SAT_SUNRISE));

        assertThat(now.bands()).isEqualTo(was.bands());
        assertThat(now.gameOn()).isEqualTo(was.gameOn());
        assertThat(now.rated()).isEqualTo(was.rated());
        assertThat(now.pockets()).hasSameSizeAs(was.pockets()).isNotEqualTo(was.pockets());
        assertThat(AskReadyFreshness.recheck(answer, before, AskScope.ALL, false).fresh()).isTrue();
        assertThat(AskReadyFreshness.recheck(answer, after, AskScope.ALL, false).fresh()).isFalse();
    }

    private static AskSnapshot twoRegionWeekend(int[] coastBands, int[] hillsBands) {
        return at(ReadyFixtures.FRIDAY_NOON,
                day(oct(10), true, true, null, AskFixtures.bandedRegion("Coast", 1L, coastBands),
                        AskFixtures.bandedRegion("Hills", 500L, hillsBands)),
                day(oct(11), true, true, null,
                        AskFixtures.region("Coast", true, AskFixtures.slot(900L, "Sunday Four", 4))));
    }

    @Test
    @DisplayName("a region-scoped answer carries and rechecks its own region's spread: a move in another region "
            + "does not withhold it, a move in its own does")
    void scopedAnswerIsHeldToItsOwnRegion() {
        AskScope coast = TestScopes.of("Coast");
        AskSnapshot before = twoRegionWeekend(new int[] {0, 3, 2, 1, 0}, new int[] {0, 3, 2, 1, 0});
        AskSnapshot hillsMoved = twoRegionWeekend(new int[] {0, 3, 2, 1, 0}, new int[] {0, 1, 4, 1, 0});
        AskSnapshot coastMoved = twoRegionWeekend(new int[] {0, 1, 4, 1, 0}, new int[] {0, 3, 2, 1, 0});
        AskAnswer answer = validator.validate(rawSunday(), before,
                evidence(Set.of(SAT_SUNRISE), new AskEvidence.Pair(900L, SUN_SUNSET)), coast, null, null, false)
                .answer();

        assertThat(answer.windows().getFirst().spread().over()).isEqualTo("Coast");
        assertThat(answer.windows().getFirst().spread().rated()).isEqualTo(6);
        assertThat(AskReadyFreshness.recheck(answer, hillsMoved, coast, false).fresh()).isTrue();
        assertThat(AskReadyFreshness.recheck(answer, coastMoved, coast, false).fresh()).isFalse();
    }

    @Test
    @DisplayName("the covered set is compared in both directions: a stored window the live offer no longer covers "
            + "withholds the answer too")
    void coveredSet_aWindowWasDropped() {
        AskSnapshot snapshot = weekend(region(0, 3, 2, 1, 0), sundayRegion());
        AskAnswer answer = carried(snapshot, Set.of());
        List<String> builtOver = List.of(SAT_SUNRISE, SAT_SUNSET, SUN_SUNRISE, SUN_SUNSET, "2026-10-12_sunrise");

        AskReadyFreshness.Verdict verdict = AskReadyFreshness.check(ReadyQuestion.BEST_WEEKEND,
                storedWith(answer, builtOver), snapshot, AskScope.ALL);

        assertThat(verdict.fresh()).isFalse();
        assertThat(verdict.reason()).contains("covers have changed");
    }

    @Test
    @DisplayName("an UNPICKED window that was carried withholds the answer when it moves, though the picked "
            + "window is unchanged")
    void spreadMoved_anUnpickedWindow() {
        AskSnapshot before = weekend(region(0, 3, 2, 1, 0), sundayRegion());
        AskSnapshot after = weekend(region(0, 3, 2, 0, 1), sundayRegion());
        AskAnswer answer = carried(before, Set.of(SAT_SUNRISE, SAT_SUNSET));

        assertThat(windowIds(answer)).contains(SAT_SUNRISE, SAT_SUNSET);
        assertThat(AskReadyFreshness.recheck(answer, after, AskScope.ALL, false).fresh()).isFalse();
    }

    @Test
    @DisplayName("a carried window that has passed or left the set is stale, not skipped: Saturday's prose "
            + "cannot outlive its figures behind a Sunday pick that survives")
    void spreadMoved_aVanishedWindow() {
        AskSnapshot before = weekend(region(0, 3, 2, 1, 0), sundayRegion());
        AskSnapshot saturdayNoon = at(ReadyFixtures.SATURDAY_NOON, day(oct(11), true, true, null, sundayRegion()));
        AskAnswer answer = carried(before, Set.of(SAT_SUNRISE));

        AskReadyFreshness.Verdict verdict = AskReadyFreshness.recheck(answer, saturdayNoon, AskScope.ALL, false);

        assertThat(verdict.fresh()).isFalse();
        assertThat(verdict.reason()).contains(SAT_SUNRISE).contains("no longer in the forecast");
    }

    @Test
    @DisplayName("an answer carrying no figures (a row written before they existed) is unknown, not stale")
    void legacyRowIsServed() {
        AskSnapshot snapshot = weekend(region(0, 3, 2, 1, 0), sundayRegion());
        AskAnswer legacy = carried(snapshot, Set.of(SAT_SUNRISE)).withWindows(List.of());

        assertThat(AskReadyFreshness.recheck(legacy, snapshot, AskScope.ALL, false).fresh()).isTrue();
        assertThat(AskReadyFreshness.check(ReadyQuestion.BEST_WEEKEND, storedWith(legacy, null), snapshot,
                AskScope.ALL).fresh()).isTrue();
    }

    @Test
    @DisplayName("a typed-cache hit is held to the same test: the cache calls the very same recheck")
    void typedCacheUsesTheSameTest() {
        AskSnapshot before = weekend(region(0, 3, 2, 1, 0), sundayRegion());
        AskSnapshot after = weekend(region(0, 3, 1, 2, 0), sundayRegion());
        AskAnswer answer = carried(before, Set.of(SAT_SUNRISE));

        assertThat(AskReadyFreshness.recheck(answer, after, AskScope.ALL, true).fresh()).isFalse();
    }

    // -- freshness: the covered set ---------------------------------------------------------

    @Test
    @DisplayName("a live covered window the answer never considered (a travel day unmarked, a rendered set that "
            + "has filled in) withholds it: the stored covered set must equal the live one")
    void coveredSet_aWindowWasAdded() {
        AskSnapshot snapshot = weekend(region(0, 3, 2, 1, 0), sundayRegion());
        AskAnswer answer = carried(snapshot, Set.of());
        List<String> builtOver = List.of(SAT_SUNRISE, SAT_SUNSET, SUN_SUNSET);

        AskReadyFreshness.Verdict verdict = AskReadyFreshness.check(ReadyQuestion.BEST_WEEKEND,
                storedWith(answer, builtOver), snapshot, AskScope.ALL);

        assertThat(verdict.fresh()).isFalse();
        assertThat(verdict.reason()).contains("covers have changed");
    }

    @Test
    @DisplayName("the stored covered set equal to the live one is fresh, in any order")
    void coveredSet_equalIsFresh() {
        AskSnapshot snapshot = weekend(region(0, 3, 2, 1, 0), sundayRegion());
        AskAnswer answer = carried(snapshot, Set.of());

        assertThat(AskReadyFreshness.check(ReadyQuestion.BEST_WEEKEND,
                storedWith(answer, List.of(SUN_SUNSET, SUN_SUNRISE, SAT_SUNSET, SAT_SUNRISE)), snapshot,
                AskScope.ALL).fresh()).isTrue();
    }

    // -- the store and the wire -------------------------------------------------------------

    private static final ObjectMapper PLAIN = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("an answer with its windows round-trips through JSON, and a property the reader does not know "
            + "is ignored rather than thrown (the rollback tolerance)")
    void answerJson_roundTripAndTolerance() throws Exception {
        AskSnapshot snapshot = weekend(region(0, 3, 2, 1, 0), sundayRegion());
        AskAnswer answer = carried(snapshot, Set.of(SAT_SUNRISE));

        String json = PLAIN.writeValueAsString(answer);
        AskAnswer back = PLAIN.readValue(json, AskAnswer.class);
        AskAnswer tolerant = PLAIN.readValue(json.replaceFirst("\\{", "{\"aFutureField\":{\"x\":1},"), AskAnswer.class);

        assertThat(back).isEqualTo(answer);
        assertThat(tolerant).isEqualTo(answer);
        assertThat(PLAIN.readValue(json.replaceFirst(",\"windows\":\\[.*\\]\\}$", "}"), AskAnswer.class).windows())
                .as("a row written before the field existed").isEmpty();
    }

    @Test
    @DisplayName("on the wire an answer's windows are omitted when empty, and present beside the summary otherwise")
    void wire_windowsAreOmittedWhenEmpty() throws Exception {
        AskSnapshot snapshot = weekend(region(0, 3, 2, 1, 0), sundayRegion());
        AskAnswer answer = carried(snapshot, Set.of(SAT_SUNRISE));
        AskReadyResponse.Answer with = new AskReadyResponse.Answer(true, "ready", answer.summary(), List.of(),
                List.of(), null, List.of(), answer.windows());
        AskReadyResponse.Answer without = new AskReadyResponse.Answer(true, "ready", answer.summary(), List.of(),
                List.of(), null, List.of());
        AskResponse own = new AskResponse(true, AskResponse.KIND_OWN, "s", List.of(), List.of(), null, List.of(), 3,
                30, true, BUILT, "06:02", null, null, null, answer.windows());
        AskResponse plain = new AskResponse(true, AskResponse.KIND_OWN, "s", List.of(), List.of(), null, List.of(),
                3, 30, true, BUILT, "06:02");

        assertThat(PLAIN.writeValueAsString(with)).contains("\"windows\":[{\"windowId\":\"" + SAT_SUNRISE + "\"",
                "\"spread\":{\"over\":\"every region\"");
        assertThat(PLAIN.writeValueAsString(without)).doesNotContain("windows");
        assertThat(PLAIN.writeValueAsString(own)).contains("\"windows\":[{");
        assertThat(PLAIN.writeValueAsString(plain)).doesNotContain("windows");
        assertThat(own.withThreadReset("forecast updated").windows()).isEqualTo(answer.windows());
        assertThat(own.withNightVersion("n1;x").windows()).isEqualTo(answer.windows());
    }
}
