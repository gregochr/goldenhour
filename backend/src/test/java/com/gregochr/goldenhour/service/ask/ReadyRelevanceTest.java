package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static com.gregochr.goldenhour.service.ask.ReadyFixtures.at;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.both;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.day;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.northumberland;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.oct;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.teesdale;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-question relevance table: which event types and which picks each Ready question may
 * carry, from the one predicate ({@code admitsEvent}, {@code admitsPick}) that the store and the
 * serve both apply.
 */
class ReadyRelevanceTest {

    private static final AskScope ALL = AskScope.ALL;
    private static final DisplayVerdict WORTH_IT = DisplayVerdict.resolve(5, Verdict.GO);

    private static AskEvent event(String type, String safetyNote) {
        return new AskEvent(type, type + " label", oct(12), "Because.", safetyNote);
    }

    private static AskAnswer withEvents(AskEvent... events) {
        return new AskAnswer(true, "Summary.", List.of(), List.of(events), null);
    }

    private static AskPick pick(int rank, long id, String name, String region, String windowId, int rating) {
        return ReadyFixtures.pick(rank, id, name, region, windowId, rating, WORTH_IT);
    }

    // -- events -----------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        "RARE_EVENTS, AURORA, true",
        "RARE_EVENTS, ECLIPSE, true",
        "RARE_EVENTS, spring-tide, true",
        "RARE_EVENTS, SNOW_TOPS, true",
        "SNOW_TOPS, SNOW_TOPS, true",
        "SNOW_TOPS, SNOW_FRESH, true",
        "SNOW_TOPS, SNOW_MIST, true",
        "SNOW_TOPS, snow_fresh, true",
        "SNOW_TOPS, snow-tops, true",
        "SNOW_TOPS, 'SNOW-MIST ', true",
        "SNOW_TOPS, AURORA, false",
        "SNOW_TOPS, ECLIPSE, false",
        "SNOW_TOPS, SNOW, false",
        "SNOW_TOPS, supermoon, false",
        "BEST_WEEKEND, AURORA, false",
        "BEST_WEEKEND, SNOW_TOPS, false",
        "BEST_SOON, AURORA, false",
        "BEST_NEXT, AURORA, false",
        "COASTAL_HIGH, SPRING_TIDE, false",
        "AM_OR_PM, AURORA, false",
    })
    @DisplayName("which event types each question admits: RARE_EVENTS any, SNOW_TOPS only the three snow "
            + "topic types, every pick question none")
    void admitsEvent(ReadyQuestion question, String type, boolean admitted) {
        assertThat(ReadyRelevance.admitsEvent(question, type)).isEqualTo(admitted);
    }

    @Test
    @DisplayName("a missing type is admitted by RARE_EVENTS (it keeps anything) and by no other question")
    void nullType() {
        assertThat(ReadyRelevance.admitsEvent(ReadyQuestion.RARE_EVENTS, null)).isTrue();
        assertThat(ReadyRelevance.admitsEvent(ReadyQuestion.SNOW_TOPS, null)).isFalse();
        assertThat(ReadyRelevance.admitsEvent(ReadyQuestion.BEST_NEXT, null)).isFalse();
    }

    @Test
    @DisplayName("SNOW_TOPS keeps the snow event and loses an aurora beside it; RARE_EVENTS keeps both")
    void relevantPartOfAnEventsQuestion() {
        AskAnswer both = withEvents(event("AURORA", null), event("SNOW_FRESH", null));

        assertThat(ReadyRelevance.relevantPart(ReadyQuestion.SNOW_TOPS, both).events()).extracting(AskEvent::type)
                .containsExactly("SNOW_FRESH");
        assertThat(ReadyRelevance.relevantPart(ReadyQuestion.RARE_EVENTS, both).events()).extracting(AskEvent::type)
                .containsExactly("AURORA", "SNOW_FRESH");
        assertThat(ReadyRelevance.relevantPart(ReadyQuestion.SNOW_TOPS, withEvents(event("AURORA", null))).events())
                .isEmpty();
    }

    @Test
    @DisplayName("an events question carries no pick, and a pick question carries no event, once reduced; "
            + "a pick question's own picks are never dropped")
    void relevantPartDropsTheOtherKind() {
        AskPick pick = pick(1, 1L, "Bamburgh", "Northumberland", "2026-10-05_sunset", 5);
        AskAnswer mixed = new AskAnswer(true, "S.", List.of(pick), List.of(event("AURORA", null)), null);

        assertThat(ReadyRelevance.relevantPart(ReadyQuestion.RARE_EVENTS, mixed).picks()).isEmpty();
        assertThat(ReadyRelevance.relevantPart(ReadyQuestion.RARE_EVENTS, mixed).events()).hasSize(1);
        assertThat(ReadyRelevance.relevantPart(ReadyQuestion.BEST_NEXT, mixed).events()).isEmpty();
        assertThat(ReadyRelevance.relevantPart(ReadyQuestion.BEST_NEXT, mixed).picks()).containsExactly(pick);
    }

    @Test
    @DisplayName("dropping an event that carries a safety warning is refused: the answer cannot be stored "
            + "without the warning its card must show")
    void droppingAWarningIsRefused() {
        AskAnswer eclipse = withEvents(event("ECLIPSE", "Certified solar filter on the lens"),
                event("SNOW_TOPS", null));

        assertThat(ReadyRelevance.dropsWarning(ReadyQuestion.SNOW_TOPS, eclipse)).isTrue();
        assertThat(ReadyRelevance.dropsWarning(ReadyQuestion.RARE_EVENTS, eclipse)).isFalse();
        assertThat(ReadyRelevance.dropsWarning(ReadyQuestion.SNOW_TOPS, withEvents(event("AURORA", null)))).isFalse();
        assertThat(ReadyRelevance.dropsWarning(ReadyQuestion.BEST_NEXT, eclipse)).isTrue();
    }

    @Test
    @DisplayName("violation: an irrelevant event or a pick on an events question is refused, the reduced "
            + "answer passes")
    void violationOnEvents() {
        AskSnapshot snapshot = at(ReadyFixtures.MONDAY_NOON);
        ReadyQuestion.Offer offer = new ReadyQuestion.Offer("Is there snow on the tops?", List.of(), null);
        AskAnswer aurora = withEvents(event("AURORA", null));
        AskAnswer snow = withEvents(event("SNOW_TOPS", null));

        assertThat(ReadyRelevance.violation(ReadyQuestion.SNOW_TOPS, aurora, offer, snapshot, ALL))
                .hasValueSatisfying(v -> assertThat(v).contains("AURORA").contains("not relevant to SNOW_TOPS"));
        assertThat(ReadyRelevance.violation(ReadyQuestion.SNOW_TOPS, snow, offer, snapshot, ALL)).isEmpty();
        assertThat(ReadyRelevance.violation(ReadyQuestion.RARE_EVENTS, aurora, offer, snapshot, ALL)).isEmpty();
        AskPick pick = pick(1, 1L, "Bamburgh", "Northumberland", "2026-10-05_sunset", 5);
        assertThat(ReadyRelevance.violation(ReadyQuestion.RARE_EVENTS, new AskAnswer(true, "S.", List.of(pick),
                List.of(event("AURORA", null)), null), offer, snapshot, ALL))
                .hasValueSatisfying(v -> assertThat(v).contains("not relevant to RARE_EVENTS"));
    }

    // -- picks ------------------------------------------------------------------------------

    private static boolean admits(ReadyQuestion question, AskSnapshot snapshot, AskScope scope, AskPick pick) {
        ReadyQuestion.Offer offer = question.offer(snapshot, scope).orElseThrow();
        return ReadyRelevance.admitsPick(question, pick, offer, snapshot, scope);
    }

    @Test
    @DisplayName("BEST_WEEKEND admits a pick on a Saturday or Sunday window and none on a Friday one")
    void weekendPicks() {
        AskSnapshot snapshot = at(ReadyFixtures.THURSDAY_NOON, day(oct(9), true, true, null, northumberland()),
                both(oct(10), northumberland()), both(oct(11), northumberland()));

        assertThat(admits(ReadyQuestion.BEST_WEEKEND, snapshot, ALL,
                pick(1, 1L, "Bamburgh", "Northumberland", "2026-10-10_sunset", 5))).isTrue();
        assertThat(admits(ReadyQuestion.BEST_WEEKEND, snapshot, ALL,
                pick(1, 1L, "Bamburgh", "Northumberland", "2026-10-11_sunrise", 5))).isTrue();
        assertThat(admits(ReadyQuestion.BEST_WEEKEND, snapshot, ALL,
                pick(1, 1L, "Bamburgh", "Northumberland", "2026-10-09_sunset", 5))).isFalse();
    }

    @Test
    @DisplayName("BEST_SOON admits a pick on any window with a pick of its own and none on a window "
            + "outside the set, or with nothing eligible in scope")
    void soonPicks() {
        BriefingRegion weak = AskFixtures.region("Northumberland", true, AskFixtures.slot(2L, "Cheviot Edge", 2));
        AskSnapshot snapshot = at(ReadyFixtures.MONDAY_NOON, day(oct(5), false, true, null, northumberland()),
                day(oct(6), true, true, null, northumberland()), day(oct(7), true, false, null, weak));

        assertThat(admits(ReadyQuestion.BEST_SOON, snapshot, ALL,
                pick(1, 1L, "Bamburgh", "Northumberland", "2026-10-06_sunrise", 5))).isTrue();
        assertThat(admits(ReadyQuestion.BEST_SOON, snapshot, ALL,
                pick(1, 2L, "Cheviot Edge", "Northumberland", "2026-10-07_sunrise", 2))).isFalse();
        assertThat(admits(ReadyQuestion.BEST_SOON, snapshot, ALL,
                pick(1, 1L, "Bamburgh", "Northumberland", "2026-10-20_sunrise", 5))).isFalse();
    }

    @Test
    @DisplayName("BEST_NEXT admits a pick on the next window alone, not the one after it")
    void nextPicks() {
        AskSnapshot snapshot = at(ReadyFixtures.MONDAY_NOON, day(oct(5), false, true, null, northumberland()),
                day(oct(6), true, false, null, northumberland()));

        assertThat(admits(ReadyQuestion.BEST_NEXT, snapshot, ALL,
                pick(1, 1L, "Bamburgh", "Northumberland", "2026-10-05_sunset", 5))).isTrue();
        assertThat(admits(ReadyQuestion.BEST_NEXT, snapshot, ALL,
                pick(1, 1L, "Bamburgh", "Northumberland", "2026-10-06_sunrise", 5))).isFalse();
    }

    @Test
    @DisplayName("COASTAL_HIGH admits a coastal slot at high water on a window that has one, and refuses an "
            + "inland slot on the same window or a coastal one at low water")
    void coastalHighPicks() {
        BriefingRegion mixed = AskFixtures.region("Northumberland", true,
                AskFixtures.coastal(1L, "Bamburgh", 5, "HIGH", true),
                AskFixtures.coastal(3L, "Craster", 4, "LOW", true),
                AskFixtures.slot(2L, "Cheviot Edge", 4));
        AskSnapshot snapshot = at(ReadyFixtures.MONDAY_NOON, day(oct(5), false, true, null, mixed),
                day(oct(6), true, false, null, ReadyFixtures.coastAt("LOW", 5)));

        assertThat(admits(ReadyQuestion.COASTAL_HIGH, snapshot, ALL,
                pick(1, 1L, "Bamburgh", "Northumberland", "2026-10-05_sunset", 5))).isTrue();
        assertThat(admits(ReadyQuestion.COASTAL_HIGH, snapshot, ALL,
                pick(1, 2L, "Cheviot Edge", "Northumberland", "2026-10-05_sunset", 4))).isFalse();
        assertThat(admits(ReadyQuestion.COASTAL_HIGH, snapshot, ALL,
                pick(1, 3L, "Craster", "Northumberland", "2026-10-05_sunset", 4))).isFalse();
        assertThat(admits(ReadyQuestion.COASTAL_HIGH, snapshot, ALL,
                pick(1, 1L, "Bamburgh", "Northumberland", "2026-10-06_sunrise", 5))).isFalse();
    }

    @Test
    @DisplayName("AM_OR_PM admits a pick on either window of its date and none on another date's")
    void amOrPmPicks() {
        AskSnapshot snapshot = at(ReadyFixtures.MONDAY_NOON, day(oct(5), false, true, null, northumberland()),
                both(oct(6), northumberland()), both(oct(7), northumberland()));

        assertThat(admits(ReadyQuestion.AM_OR_PM, snapshot, ALL,
                pick(1, 1L, "Bamburgh", "Northumberland", "2026-10-06_sunrise", 5))).isTrue();
        assertThat(admits(ReadyQuestion.AM_OR_PM, snapshot, ALL,
                pick(1, 1L, "Bamburgh", "Northumberland", "2026-10-06_sunset", 5))).isTrue();
        assertThat(admits(ReadyQuestion.AM_OR_PM, snapshot, ALL,
                pick(1, 1L, "Bamburgh", "Northumberland", "2026-10-07_sunset", 5))).isFalse();
        assertThat(admits(ReadyQuestion.AM_OR_PM, snapshot, ALL,
                pick(1, 1L, "Bamburgh", "Northumberland", "2026-10-05_sunset", 5))).isFalse();
    }

    @Test
    @DisplayName("every pick question refuses a pick outside its scope or no longer pick-eligible, and an "
            + "events question refuses every pick")
    void scopeAndEligibilityApplyToEveryPickQuestion() {
        AskSnapshot snapshot = at(ReadyFixtures.MONDAY_NOON,
                day(oct(5), false, true, null, northumberland(), teesdale()));
        AskPick teesdalePick = pick(1, 10L, "Hamsterley", "Teesdale", "2026-10-05_sunset", 4);
        AskPick noSuchSlot = pick(1, 99L, "Nowhere", "Northumberland", "2026-10-05_sunset", 4);

        assertThat(admits(ReadyQuestion.BEST_NEXT, snapshot, ALL, teesdalePick)).isTrue();
        ReadyQuestion.Offer northOffer = ReadyQuestion.BEST_NEXT.offer(snapshot, TestScopes.of("Northumberland"))
                .orElseThrow();
        assertThat(ReadyRelevance.admitsPick(ReadyQuestion.BEST_NEXT, teesdalePick, northOffer, snapshot,
                TestScopes.of("Northumberland"))).isFalse();
        assertThat(admits(ReadyQuestion.BEST_NEXT, snapshot, ALL, noSuchSlot)).isFalse();
        ReadyQuestion.Offer none = new ReadyQuestion.Offer("Any rare events coming up?", List.of(), null);
        assertThat(ReadyRelevance.admitsPick(ReadyQuestion.RARE_EVENTS, teesdalePick, none, snapshot, ALL)).isFalse();
        assertThat(ReadyRelevance.admitsPick(ReadyQuestion.SNOW_TOPS, teesdalePick, none, snapshot, ALL)).isFalse();
    }
}
