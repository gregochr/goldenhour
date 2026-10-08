package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.service.ask.ReadyQuestion.Offer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.gregochr.goldenhour.service.ask.ReadyFixtures.at;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.both;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.day;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.northumberland;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.oct;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.teesdale;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Ready catalogue's availability predicates, each on both sides of its boundary, and the text
 * each question is asked under (fixed from the UK civil date).
 */
class ReadyQuestionTest {

    private static final AskScope ALL = AskScope.ALL;

    private static Optional<Offer> offer(ReadyQuestion q, AskSnapshot snapshot) {
        return q.offer(snapshot, ALL);
    }

    // -- BEST_WEEKEND / BEST_SOON -----------------------------------------------------------

    @Test
    @DisplayName("BEST_WEEKEND is not offered on a Monday, whose forecast stops before the weekend, but "
            + "BEST_SOON is")
    void mondayOffersNoWeekend() {
        AskSnapshot monday = at(ReadyFixtures.MONDAY_NOON, day(oct(5), false, true, null, northumberland()),
                both(oct(6), northumberland()), both(oct(7), northumberland()));

        assertThat(offer(ReadyQuestion.BEST_WEEKEND, monday)).isEmpty();
        assertThat(offer(ReadyQuestion.BEST_SOON, monday)).hasValueSatisfying(o -> {
            assertThat(o.text()).isEqualTo("Best spot in the next few days?");
            assertThat(o.windowIds()).hasSize(5).contains("2026-10-05_sunset", "2026-10-07_sunrise");
        });
    }

    @Test
    @DisplayName("BEST_WEEKEND is offered on a Friday, about the Saturday and Sunday windows only, and "
            + "BEST_SOON then stands down")
    void fridayOffersTheWeekend() {
        AskSnapshot friday = at(ReadyFixtures.FRIDAY_NOON, day(oct(9), false, true, null, northumberland()),
                both(oct(10), northumberland()), both(oct(11), northumberland()));

        assertThat(offer(ReadyQuestion.BEST_WEEKEND, friday)).hasValueSatisfying(o -> {
            assertThat(o.text()).isEqualTo("Best spot this weekend?");
            assertThat(o.windowIds()).containsExactly("2026-10-10_sunrise", "2026-10-10_sunset",
                    "2026-10-11_sunrise", "2026-10-11_sunset");
            assertThat(o.contextWindowId()).isNull();
        });
        assertThat(offer(ReadyQuestion.BEST_SOON, friday)).isEmpty();
    }

    @Test
    @DisplayName("a Saturday that is today with its sunrise passed still offers the weekend, about the "
            + "windows still ahead")
    void saturdayAfterSunriseStillOffersTheWeekend() {
        AskSnapshot saturday = at(ReadyFixtures.SATURDAY_NOON, both(oct(10), northumberland()),
                both(oct(11), northumberland()));

        assertThat(offer(ReadyQuestion.BEST_WEEKEND, saturday)).hasValueSatisfying(o ->
                assertThat(o.windowIds()).containsExactly("2026-10-10_sunset", "2026-10-11_sunrise",
                        "2026-10-11_sunset"));
    }

    @Test
    @DisplayName("a weekend window with nothing pick-eligible does not count: the weekend question is "
            + "off and the next-few-days one takes over")
    void weekendWithoutPicksFallsBackToSoon() {
        BriefingRegion weak = AskFixtures.region("Northumberland", true,
                AskFixtures.slot(2L, "Cheviot Edge", 2));
        AskSnapshot snapshot = at(ReadyFixtures.THURSDAY_NOON, day(oct(8), false, true, null, northumberland()),
                day(oct(9), true, true, null, northumberland()), both(oct(10), weak));

        assertThat(offer(ReadyQuestion.BEST_WEEKEND, snapshot)).isEmpty();
        assertThat(offer(ReadyQuestion.BEST_SOON, snapshot)).hasValueSatisfying(o ->
                assertThat(o.windowIds()).doesNotContain("2026-10-10_sunrise", "2026-10-10_sunset"));
    }

    @Test
    @DisplayName("BEST_SOON needs two windows to compare: one is not enough, two is")
    void bestSoonNeedsTwoWindows() {
        AskSnapshot one = at(ReadyFixtures.MONDAY_NOON, day(oct(5), false, true, null, northumberland()));
        AskSnapshot two = at(ReadyFixtures.MONDAY_NOON, day(oct(5), false, true, null, northumberland()),
                day(oct(6), true, false, null, northumberland()));

        assertThat(offer(ReadyQuestion.BEST_SOON, one)).isEmpty();
        assertThat(offer(ReadyQuestion.BEST_SOON, two)).isPresent();
    }

    @Test
    @DisplayName("a region scope narrows the pick-eligible test: a weekend only the other region can "
            + "answer is not this region's weekend")
    void scopeNarrowsAvailability() {
        BriefingRegion weakNorth = AskFixtures.region("Northumberland", true,
                AskFixtures.slot(2L, "Cheviot Edge", 2));
        AskSnapshot friday = at(ReadyFixtures.FRIDAY_NOON, day(oct(9), false, true, null, weakNorth, teesdale()),
                both(oct(10), weakNorth, teesdale()));

        assertThat(ReadyQuestion.BEST_WEEKEND.offer(friday, TestScopes.of("Teesdale"))).isPresent();
        assertThat(ReadyQuestion.BEST_WEEKEND.offer(friday, TestScopes.of("Northumberland"))).isEmpty();
        assertThat(ReadyQuestion.BEST_WEEKEND.offer(friday, TestScopes.of("teesdale"))).isPresent();
    }

    // -- BEST_NEXT --------------------------------------------------------------------------

    @Test
    @DisplayName("BEST_NEXT words the next window from the UK civil date: tonight, this morning, tomorrow "
            + "morning, tomorrow evening, and a weekday when it is further off")
    void bestNextDayWords() {
        assertThat(offerText(ReadyQuestion.BEST_NEXT, at(ReadyFixtures.MONDAY_NOON,
                day(oct(5), false, true, null, northumberland())))).isEqualTo("Best spot tonight?");
        assertThat(offerText(ReadyQuestion.BEST_NEXT, at(ReadyFixtures.MONDAY_BEFORE_DAWN,
                both(oct(5), northumberland())))).isEqualTo("Best spot this morning?");
        assertThat(offerText(ReadyQuestion.BEST_NEXT, at(LocalDateTime.of(2026, 10, 5, 19, 0),
                day(oct(6), true, true, null, northumberland())))).isEqualTo("Best spot tomorrow morning?");
        assertThat(offerText(ReadyQuestion.BEST_NEXT, at(LocalDateTime.of(2026, 10, 5, 19, 0),
                day(oct(6), false, true, null, northumberland())))).isEqualTo("Best spot tomorrow evening?");
        assertThat(offerText(ReadyQuestion.BEST_NEXT, at(ReadyFixtures.THURSDAY_NOON,
                day(oct(10), true, false, null, northumberland())))).isEqualTo("Best spot on Saturday morning?");
        assertThat(offerText(ReadyQuestion.BEST_NEXT, at(ReadyFixtures.THURSDAY_NOON,
                day(oct(11), false, true, null, northumberland())))).isEqualTo("Best spot on Sunday evening?");
    }

    @Test
    @DisplayName("the day word comes from the UK civil date, not the UTC one: at 23:30 UTC in BST the "
            + "next sunrise is this morning")
    void dayWordUsesTheUkDate() {
        AskSnapshot snapshot = at(LocalDateTime.of(2026, 10, 5, 23, 30),
                day(oct(6), true, true, null, northumberland()));

        assertThat(snapshot.today()).isEqualTo(oct(6));
        assertThat(offerText(ReadyQuestion.BEST_NEXT, snapshot)).isEqualTo("Best spot this morning?");
    }

    @Test
    @DisplayName("BEST_NEXT is about the next window alone, and names it as the context window")
    void bestNextIsAboutOneWindow() {
        AskSnapshot snapshot = at(ReadyFixtures.MONDAY_NOON, day(oct(5), false, true, null, northumberland()),
                day(oct(6), true, false, null, northumberland()));

        assertThat(offer(ReadyQuestion.BEST_NEXT, snapshot)).hasValueSatisfying(o -> {
            assertThat(o.windowIds()).containsExactly("2026-10-05_sunset");
            assertThat(o.contextWindowId()).isEqualTo("2026-10-05_sunset");
        });
    }

    @Test
    @DisplayName("BEST_NEXT is off when the next window has nothing pick-eligible, even if a later one does")
    void bestNextNeedsThePickInTheNextWindow() {
        BriefingRegion weak = AskFixtures.region("Northumberland", true, AskFixtures.slot(2L, "Cheviot Edge", 2));
        AskSnapshot snapshot = at(ReadyFixtures.MONDAY_NOON, day(oct(5), false, true, null, weak),
                day(oct(6), true, false, null, northumberland()));

        assertThat(offer(ReadyQuestion.BEST_NEXT, snapshot)).isEmpty();
    }

    @Test
    @DisplayName("BEST_NEXT is off with no window at all")
    void bestNextNeedsAWindow() {
        assertThat(offer(ReadyQuestion.BEST_NEXT, at(ReadyFixtures.MONDAY_NOON))).isEmpty();
    }

    // -- COASTAL_HIGH -----------------------------------------------------------------------

    @Test
    @DisplayName("COASTAL_HIGH is offered only when a pick-eligible coastal slot has HIGH water, and is "
            + "about the windows that have one")
    void coastalHighBoundaries() {
        AskSnapshot high = at(ReadyFixtures.MONDAY_NOON,
                day(oct(5), false, true, null, ReadyFixtures.coastAt("HIGH", 5)),
                day(oct(6), true, false, null, ReadyFixtures.coastAt("LOW", 5)));
        assertThat(offer(ReadyQuestion.COASTAL_HIGH, high)).hasValueSatisfying(o -> {
            assertThat(o.text()).isEqualTo("Best coastal spot at high tide?");
            assertThat(o.windowIds()).containsExactly("2026-10-05_sunset");
        });

        assertThat(offer(ReadyQuestion.COASTAL_HIGH, at(ReadyFixtures.MONDAY_NOON,
                day(oct(5), false, true, null, ReadyFixtures.coastAt("MID", 5))))).isEmpty();
        assertThat(offer(ReadyQuestion.COASTAL_HIGH, at(ReadyFixtures.MONDAY_NOON,
                day(oct(5), false, true, null, ReadyFixtures.coastAt("HIGH", 2))))).isEmpty();
        assertThat(offer(ReadyQuestion.COASTAL_HIGH, at(ReadyFixtures.MONDAY_NOON,
                day(oct(5), false, true, null, teesdale())))).isEmpty();
    }

    @Test
    @DisplayName("a high-water wood is not a coastal pick, and a region the Plan refuses cannot offer one")
    void coastalHighIgnoresWoodsAndIneligibleRegions() {
        BriefingRegion wood = AskFixtures.region("Northumberland", true,
                AskFixtures.slot(2L, "Cheviot Edge", 4));
        BriefingRegion refused = AskFixtures.region("Dales", false,
                AskFixtures.coastal(30L, "Hand-run Cove", 5, "HIGH", true));

        assertThat(offer(ReadyQuestion.COASTAL_HIGH, at(ReadyFixtures.MONDAY_NOON,
                day(oct(5), false, true, null, wood, refused)))).isEmpty();
    }

    // -- AM_OR_PM ---------------------------------------------------------------------------

    @Test
    @DisplayName("AM_OR_PM names the next date with both windows ahead: tomorrow on a Monday noon, today "
            + "before dawn")
    void amOrPmDayWords() {
        AskSnapshot noon = at(ReadyFixtures.MONDAY_NOON, day(oct(5), false, true, null, northumberland()),
                both(oct(6), northumberland()));
        assertThat(offer(ReadyQuestion.AM_OR_PM, noon)).hasValueSatisfying(o -> {
            assertThat(o.text()).isEqualTo("Sunrise or sunset tomorrow?");
            assertThat(o.windowIds()).containsExactly("2026-10-06_sunrise", "2026-10-06_sunset");
        });

        AskSnapshot dawn = at(ReadyFixtures.MONDAY_BEFORE_DAWN, both(oct(5), northumberland()));
        assertThat(offerText(ReadyQuestion.AM_OR_PM, dawn)).isEqualTo("Sunrise or sunset today?");
    }

    @Test
    @DisplayName("AM_OR_PM names a weekday when the date is further off, and skips a date with only one "
            + "window or one without a pick on both sides")
    void amOrPmSkipsIncompleteDates() {
        BriefingRegion weak = AskFixtures.region("Northumberland", true, AskFixtures.slot(2L, "Cheviot Edge", 2));
        AskSnapshot snapshot = at(ReadyFixtures.THURSDAY_NOON, day(oct(8), false, true, null, northumberland()),
                day(oct(9), true, true, null, weak, ReadyFixtures.ineligible()),
                both(oct(10), northumberland()));

        assertThat(offerText(ReadyQuestion.AM_OR_PM, snapshot)).isEqualTo("Sunrise or sunset on Saturday?");
    }

    @Test
    @DisplayName("AM_OR_PM is off when no date has both windows ahead")
    void amOrPmNeedsBothWindows() {
        AskSnapshot snapshot = at(ReadyFixtures.MONDAY_NOON, day(oct(5), false, true, null, northumberland()),
                day(oct(6), true, false, null, northumberland()));

        assertThat(offer(ReadyQuestion.AM_OR_PM, snapshot)).isEmpty();
    }

    // -- the two that are always asked ------------------------------------------------------

    @Test
    @DisplayName("RARE_EVENTS and SNOW_TOPS are always asked, about no window, even with no forecast")
    void alwaysOffered() {
        AskSnapshot empty = at(ReadyFixtures.MONDAY_NOON);

        assertThat(offer(ReadyQuestion.RARE_EVENTS, empty)).hasValueSatisfying(o -> {
            assertThat(o.text()).isEqualTo("Any rare events coming up?");
            assertThat(o.windowIds()).isEmpty();
        });
        assertThat(offer(ReadyQuestion.SNOW_TOPS, empty)).hasValueSatisfying(o ->
                assertThat(o.text()).isEqualTo("Is there snow on the tops?"));
    }

    // -- the catalogue's own shape ----------------------------------------------------------

    @Test
    @DisplayName("each question's tabs, and which ones are answered with picks and led by the BEST BET")
    void catalogueShape() {
        assertThat(ReadyQuestion.BEST_WEEKEND.tabs()).containsExactly("plan", "map");
        assertThat(ReadyQuestion.BEST_SOON.tabs()).containsExactly("plan", "map");
        assertThat(ReadyQuestion.BEST_NEXT.tabs()).containsExactly("plan", "map");
        assertThat(ReadyQuestion.COASTAL_HIGH.tabs()).containsExactly("map");
        assertThat(ReadyQuestion.AM_OR_PM.tabs()).containsExactly("plan");
        assertThat(ReadyQuestion.RARE_EVENTS.tabs()).containsExactly("coming-up", "map");
        assertThat(ReadyQuestion.SNOW_TOPS.tabs()).containsExactly("coming-up");
        assertThat(List.of(ReadyQuestion.values()).stream().filter(ReadyQuestion::anchored))
                .containsExactly(ReadyQuestion.BEST_WEEKEND, ReadyQuestion.BEST_SOON, ReadyQuestion.BEST_NEXT);
        assertThat(List.of(ReadyQuestion.values()).stream().filter(q -> !q.picks()))
                .containsExactly(ReadyQuestion.RARE_EVENTS, ReadyQuestion.SNOW_TOPS);
    }

    @Test
    @DisplayName("only a BEST_* question hands the engine an anchor, over the offer's own windows")
    void anchorOnlyForBestQuestions() {
        AskSnapshot friday = at(ReadyFixtures.FRIDAY_NOON, both(oct(10), northumberland()));
        Offer weekend = offer(ReadyQuestion.BEST_WEEKEND, friday).orElseThrow();

        assertThat(ReadyQuestion.BEST_WEEKEND.anchor(weekend).windowIds())
                .containsExactlyInAnyOrder("2026-10-10_sunrise", "2026-10-10_sunset");
        assertThat(ReadyQuestion.COASTAL_HIGH.anchor(weekend)).isNull();
        assertThat(ReadyQuestion.RARE_EVENTS.anchor(weekend)).isNull();
    }

    @Test
    @DisplayName("an answer is kept only when it has what the question wants and stays inside its windows")
    void violations() {
        AskSnapshot friday = at(ReadyFixtures.FRIDAY_NOON, day(oct(9), false, true, null, northumberland()),
                both(oct(10), northumberland()));
        Offer weekend = offer(ReadyQuestion.BEST_WEEKEND, friday).orElseThrow();
        AskPick onSaturday = ReadyFixtures.pick(1, 1L, "Bamburgh", "Northumberland", "2026-10-10_sunset", 5,
                AskFixtures.slot(1L, "x", 5).displayVerdict());
        AskPick onFriday = ReadyFixtures.pick(2, 2L, "Cheviot Edge", "Northumberland", "2026-10-09_sunset", 4,
                AskFixtures.slot(2L, "x", 4).displayVerdict());

        assertThat(ReadyRelevance.violation(ReadyQuestion.BEST_WEEKEND, ReadyFixtures.answer(onSaturday),
                weekend, friday, ALL)).isEmpty();
        assertThat(ReadyRelevance.violation(ReadyQuestion.BEST_WEEKEND,
                ReadyFixtures.answer(onSaturday, onFriday), weekend, friday, ALL))
                .hasValueSatisfying(v -> assertThat(v).contains("is not relevant to BEST_WEEKEND"));
        assertThat(ReadyRelevance.violation(ReadyQuestion.BEST_WEEKEND,
                new AskAnswer(true, "Nothing.", List.of(), List.of(), null), weekend, friday, ALL))
                .hasValueSatisfying(v -> assertThat(v).contains("no pick"));
        assertThat(ReadyRelevance.violation(ReadyQuestion.RARE_EVENTS, ReadyFixtures.answer(onSaturday),
                new Offer("Any rare events coming up?", List.of(), null), friday, ALL))
                .hasValueSatisfying(v -> assertThat(v).contains("no event"));
    }

    @Test
    @DisplayName("a coastal-high answer must be on coastal slots that are at high water")
    void coastalHighViolation() {
        AskSnapshot snapshot = at(ReadyFixtures.MONDAY_NOON,
                day(oct(5), false, true, null, ReadyFixtures.coastAt("HIGH", 5)));
        Offer offer = offer(ReadyQuestion.COASTAL_HIGH, snapshot).orElseThrow();
        AskPick coastal = ReadyFixtures.pick(1, 1L, "Bamburgh", "Northumberland", "2026-10-05_sunset", 5,
                AskFixtures.slot(1L, "x", 5).displayVerdict());
        AskPick inland = ReadyFixtures.pick(1, 2L, "Cheviot Edge", "Northumberland", "2026-10-05_sunset", 4,
                AskFixtures.slot(2L, "x", 4).displayVerdict());

        assertThat(ReadyRelevance.violation(ReadyQuestion.COASTAL_HIGH, ReadyFixtures.answer(coastal), offer,
                snapshot, ALL)).isEmpty();
        assertThat(ReadyRelevance.violation(ReadyQuestion.COASTAL_HIGH, ReadyFixtures.answer(inland), offer,
                snapshot, ALL))
                .hasValueSatisfying(v -> assertThat(v).contains("is not relevant to COASTAL_HIGH"));
    }

    private static String offerText(ReadyQuestion q, AskSnapshot snapshot) {
        return offer(q, snapshot).orElseThrow().text();
    }
}
