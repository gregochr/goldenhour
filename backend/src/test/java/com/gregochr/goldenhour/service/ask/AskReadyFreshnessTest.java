package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static com.gregochr.goldenhour.service.ask.ReadyFixtures.at;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.both;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.day;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.northumberland;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.oct;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The serve-time freshness check, one test per rule, each against a snapshot built through the real
 * builder. A rule that fires withholds the WHOLE question: {@code Verdict.answer()} is null, never a
 * partial answer.
 */
class AskReadyFreshnessTest {

    private static final LocalDateTime BUILT = LocalDateTime.of(2026, 10, 9, 5, 2, 11);
    private static final String SAT_SUNRISE = "2026-10-10_sunrise";
    private static final String SAT_SUNSET = "2026-10-10_sunset";
    private static final String SUN_SUNSET = "2026-10-11_sunset";
    private static final List<String> WEEKEND = List.of(SAT_SUNRISE, SAT_SUNSET, "2026-10-11_sunrise",
            SUN_SUNSET);
    private static final DisplayVerdict V5 = DisplayVerdict.resolve(5, Verdict.GO);
    private static final DisplayVerdict V4 = DisplayVerdict.resolve(4, Verdict.GO);

    private static BriefingWindow.Pick bestAt(String region, String location, long id) {
        return AskFixtures.pick(BriefingWindow.PickKind.BEST, region, location, id);
    }

    /** Friday noon: Saturday sunset carries the BEST BET at Bamburgh. */
    private static AskSnapshot friday(BriefingRegion saturday) {
        return at(ReadyFixtures.FRIDAY_NOON, day(oct(9), false, true, null, northumberland()),
                day(oct(10), true, true, bestAt("Northumberland", "Bamburgh", 1L), saturday),
                both(oct(11), northumberland()));
    }

    private static AskSnapshot friday() {
        return friday(northumberland());
    }

    private static AskPick bamburghSaturday() {
        return ReadyFixtures.pick(1, 1L, "Bamburgh", "Northumberland", SAT_SUNSET, 5, V5);
    }

    private static AskReadyStore.Stored weekend(AskAnswer answer) {
        return new AskReadyStore.Stored("ALL", "BEST_WEEKEND", "Best spot this weekend?", WEEKEND, BUILT,
                answer);
    }

    private static AskReadyFreshness.Verdict check(AskReadyStore.Stored stored, AskSnapshot live) {
        return AskReadyFreshness.check(ReadyQuestion.valueOf(stored.questionId()), stored, live, Set.of());
    }

    private static AskSnapshot withEvents(AskSnapshot base, List<AskSnapshot.Topic> topics,
            List<AskSnapshot.ComingUp> comingUp) {
        return new AskSnapshot(base.generatedAt(), base.runLabel(), base.today(), base.windows(), topics,
                comingUp);
    }

    private static AskReadyStore.Stored events(String question, String text, AskEvent... events) {
        return new AskReadyStore.Stored("ALL", question, text, List.of(), BUILT,
                new AskAnswer(true, "Something is coming.", List.of(), List.of(events), null));
    }

    // -- fresh, and re-decorated -------------------------------------------------------------

    @Test
    @DisplayName("an answer that still agrees with live data is fresh and is re-decorated from the live "
            + "snapshot: a renamed spot shows its live name, the stored prose is kept")
    void freshAndReDecorated() {
        BriefingRegion renamed = AskFixtures.region("Northumberland", true,
                AskFixtures.coastal(1L, "Bamburgh Castle", 5, "HIGH", true));

        AskReadyFreshness.Verdict verdict = check(weekend(ReadyFixtures.answer(bamburghSaturday())),
                friday(renamed));

        assertThat(verdict.fresh()).isTrue();
        assertThat(verdict.answer().picks()).singleElement().satisfies(p -> {
            assertThat(p.locationName()).isEqualTo("Bamburgh Castle");
            assertThat(p.regionName()).isEqualTo("Northumberland");
            assertThat(p.date()).isEqualTo(oct(10));
            assertThat(p.windowId()).isEqualTo(SAT_SUNSET);
            assertThat(p.why()).isEqualTo("Clear sky.");
            assertThat(p.ratingAtAnswer()).isEqualTo(5);
        });
    }

    // -- windows ----------------------------------------------------------------------------

    @Test
    @DisplayName("withheld when every window the question names has passed")
    void allWindowsPassed() {
        AskSnapshot nextWeek = at(LocalDateTime.of(2026, 10, 12, 12, 0), both(oct(13), northumberland()));

        AskReadyFreshness.Verdict verdict = check(weekend(ReadyFixtures.answer(bamburghSaturday())), nextWeek);

        assertThat(verdict.fresh()).isFalse();
        assertThat(verdict.answer()).isNull();
        assertThat(verdict.reason()).contains("every window");
    }

    @Test
    @DisplayName("withheld when any pick's window has passed, though other windows of the question are live")
    void aPicksWindowPassed() {
        AskPick onSaturdayMorning = ReadyFixtures.pick(2, 2L, "Cheviot Edge", "Northumberland", SAT_SUNRISE, 4,
                V4);
        AskReadyStore.Stored stored = weekend(ReadyFixtures.answer(bamburghSaturday(), onSaturdayMorning));
        AskSnapshot saturdayNoon = at(ReadyFixtures.SATURDAY_NOON,
                day(oct(10), true, true, bestAt("Northumberland", "Bamburgh", 1L), northumberland()),
                both(oct(11), northumberland()));

        AskReadyFreshness.Verdict verdict = check(stored, saturdayNoon);

        assertThat(verdict.fresh()).isFalse();
        assertThat(verdict.reason()).contains("pick 2's window has passed");
    }

    // -- ratings and verdicts ---------------------------------------------------------------

    @Test
    @DisplayName("withheld when a pick's live rating differs from the stored one: the whole question, "
            + "even though the second pick is unchanged")
    void ratingChanged() {
        AskPick other = ReadyFixtures.pick(2, 2L, "Cheviot Edge", "Northumberland", SAT_SUNSET, 4, V4);
        BriefingRegion down = AskFixtures.region("Northumberland", true,
                AskFixtures.coastal(1L, "Bamburgh", 4, "HIGH", true), AskFixtures.slot(2L, "Cheviot Edge", 4));

        AskReadyFreshness.Verdict verdict = check(weekend(ReadyFixtures.answer(bamburghSaturday(), other)),
                friday(down));

        assertThat(verdict.fresh()).isFalse();
        assertThat(verdict.answer()).isNull();
        assertThat(verdict.reason()).contains("pick 1's rating changed");
    }

    @Test
    @DisplayName("withheld when only a pick's live verdict differs: the rating is unchanged")
    void verdictChanged() {
        BriefingRegion reworded = AskFixtures.region("Northumberland", true,
                ReadyFixtures.withVerdict(AskFixtures.coastal(1L, "Bamburgh", 5, "HIGH", true),
                        DisplayVerdict.MAYBE));

        AskReadyFreshness.Verdict verdict = check(weekend(ReadyFixtures.answer(bamburghSaturday())),
                friday(reworded));

        assertThat(verdict.fresh()).isFalse();
        assertThat(verdict.reason()).contains("pick 1's verdict changed");
    }

    @Test
    @DisplayName("withheld when a pick's slot is no longer pick-eligible: its region has lost the verdict "
            + "sample gate, or the slot has gone")
    void noLongerPickEligible() {
        BriefingRegion refused = AskFixtures.region("Northumberland", false,
                AskFixtures.coastal(1L, "Bamburgh", 5, "HIGH", true));
        BriefingRegion gone = AskFixtures.region("Northumberland", true, AskFixtures.slot(2L, "Cheviot Edge", 4));
        AskReadyStore.Stored stored = weekend(ReadyFixtures.answer(bamburghSaturday()));

        assertThat(check(stored, friday(refused)).reason()).contains("no longer pick-eligible");
        assertThat(check(stored, friday(gone)).reason()).contains("no longer pick-eligible");
    }

    // -- events -----------------------------------------------------------------------------

    private static AskEvent aurora() {
        return new AskEvent("AURORA", "Aurora tonight", oct(12), "Kp 6 is forecast.", null);
    }

    private static AskSnapshot.Topic auroraTopic(String label, LocalDate date, List<String> regions,
            String safetyNote) {
        return new AskSnapshot.Topic("AURORA", label, "Kp 6", date, regions, safetyNote);
    }

    @Test
    @DisplayName("an event is fresh while its topic is live, and its label and safety note are re-joined "
            + "from the live topic, never kept from the stored one")
    void eventLiveAndReDecorated() {
        AskSnapshot live = withEvents(at(ReadyFixtures.FRIDAY_NOON, both(oct(10), northumberland())),
                List.of(auroraTopic("Aurora: Kp 7", oct(12), List.of(), "Wear something warm")), List.of());

        AskReadyFreshness.Verdict verdict = check(
                events("RARE_EVENTS", "Any rare events coming up?", aurora()), live);

        assertThat(verdict.fresh()).isTrue();
        assertThat(verdict.answer().events()).singleElement().satisfies(e -> {
            assertThat(e.label()).isEqualTo("Aurora: Kp 7");
            assertThat(e.safetyNote()).isEqualTo("Wear something warm");
            assertThat(e.why()).isEqualTo("Kp 6 is forecast.");
        });
    }

    @Test
    @DisplayName("withheld when an event's topic has vanished, moved date, or is outside the scope")
    void eventNoLongerLive() {
        AskSnapshot base = at(ReadyFixtures.FRIDAY_NOON, both(oct(10), northumberland()));
        AskReadyStore.Stored stored = events("RARE_EVENTS", "Any rare events coming up?", aurora());

        assertThat(check(stored, withEvents(base, List.of(), List.of())).reason()).contains("no longer live");
        assertThat(check(stored, withEvents(base, List.of(auroraTopic("Aurora", oct(13), List.of(), null)),
                List.of())).fresh()).isFalse();
        assertThat(AskReadyFreshness.check(ReadyQuestion.RARE_EVENTS, stored,
                withEvents(base, List.of(auroraTopic("Aurora", oct(12), List.of("Teesdale"), null)), List.of()),
                Set.of("Northumberland")).fresh()).isFalse();
        assertThat(AskReadyFreshness.check(ReadyQuestion.RARE_EVENTS, stored,
                withEvents(base, List.of(auroraTopic("Aurora", oct(12), List.of("Teesdale"), null)), List.of()),
                Set.of("teesdale")).fresh()).isTrue();
    }

    @Test
    @DisplayName("a legacy SNOW_TOPS row that carries an aurora is withheld at serve time though the aurora "
            + "is live; a row of only snow types is served; a pick row carrying an event is withheld too")
    void legacyRowsWithIrrelevantContentAreWithheld() {
        AskSnapshot live = withEvents(at(ReadyFixtures.FRIDAY_NOON, both(oct(10), northumberland())),
                List.of(auroraTopic("Aurora tonight", oct(12), List.of(), null),
                        new AskSnapshot.Topic("SNOW_TOPS", "Snow on the fells", "d", oct(12), List.of(), null)),
                List.of());
        AskEvent snow = new AskEvent("SNOW_TOPS", "old", oct(12), "Snow.", null);

        AskReadyFreshness.Verdict withAurora = check(
                events("SNOW_TOPS", "Is there snow on the tops?", snow, aurora()), live);
        AskReadyFreshness.Verdict snowOnly = check(events("SNOW_TOPS", "Is there snow on the tops?", snow), live);

        assertThat(withAurora.fresh()).isFalse();
        assertThat(withAurora.reason()).contains("AURORA").contains("not relevant to SNOW_TOPS");
        assertThat(snowOnly.fresh()).isTrue();
        assertThat(snowOnly.answer().events().getFirst().label()).isEqualTo("Snow on the fells");
        assertThat(check(events("RARE_EVENTS", "Any rare events coming up?", snow, aurora()), live).fresh())
                .isTrue();

        AskReadyStore.Stored pickWithEvent = new AskReadyStore.Stored("ALL", "BEST_WEEKEND",
                "Best spot this weekend?", WEEKEND, BUILT, new AskAnswer(true, "Go.",
                List.of(bamburghSaturday()), List.of(aurora()), null));
        assertThat(check(pickWithEvent, withEvents(friday(), List.of(auroraTopic("Aurora", oct(12), List.of(),
                null)), List.of())).reason()).contains("not relevant to BEST_WEEKEND");
    }

    @Test
    @DisplayName("an almanac event is live while its entry is, from its first date: kept at the 90-day "
            + "horizon's last day, withheld one day beyond it or once ended")
    void almanacEventAtTheNinetyDayEdge() {
        AskSnapshot base = at(ReadyFixtures.FRIDAY_NOON, both(oct(10), northumberland()));
        LocalDate today = base.today();
        LocalDate last = today.plusDays(89);
        AskReadyStore.Stored stored = events("RARE_EVENTS", "Any rare events coming up?",
                new AskEvent("SUPERMOON", "Supermoon", last, "Big moon.", null));

        assertThat(check(stored, withEvents(base, List.of(), List.of(
                new AskSnapshot.ComingUp("SUPERMOON", "Supermoon (live)", last, last, "d", null)))).fresh())
                .isTrue();
        AskReadyStore.Stored beyond = events("RARE_EVENTS", "Any rare events coming up?",
                new AskEvent("SUPERMOON", "Supermoon", last.plusDays(1), "Big moon.", null));
        assertThat(check(beyond, withEvents(base, List.of(), List.of(new AskSnapshot.ComingUp("SUPERMOON",
                "Supermoon", last.plusDays(1), last.plusDays(1), "d", null)))).fresh()).isFalse();
        AskReadyStore.Stored ended = events("RARE_EVENTS", "Any rare events coming up?",
                new AskEvent("SUPERMOON", "Supermoon", today.minusDays(2), "Big moon.", null));
        assertThat(check(ended, withEvents(base, List.of(), List.of(new AskSnapshot.ComingUp("SUPERMOON",
                "Supermoon", today.minusDays(2), today.minusDays(1), "d", null)))).fresh()).isFalse();
    }

    @Test
    @DisplayName("an eclipse event keeps its lens-filter warning from the live entry when the stored event "
            + "had none")
    void almanacSafetyNoteIsReJoined() {
        AskSnapshot base = at(ReadyFixtures.FRIDAY_NOON, both(oct(10), northumberland()));
        AskReadyStore.Stored stored = events("RARE_EVENTS", "Any rare events coming up?",
                new AskEvent("ECLIPSE", "Partial solar eclipse", oct(14), "Visible.", null));

        AskReadyFreshness.Verdict verdict = check(stored, withEvents(base, List.of(), List.of(
                new AskSnapshot.ComingUp("eclipse", "Partial solar eclipse", oct(14), oct(14), "d",
                        "Certified solar filter on the lens"))));

        assertThat(verdict.fresh()).isTrue();
        assertThat(verdict.answer().events().getFirst().safetyNote())
                .isEqualTo("Certified solar filter on the lens");
    }

    // -- the rest of what an answer claims --------------------------------------------------

    @Test
    @DisplayName("withheld when the question is now asked differently: a 'tomorrow' stored on one day is "
            + "not served as 'tonight' the next")
    void questionTextChanged() {
        AskReadyStore.Stored stored = new AskReadyStore.Stored("ALL", "BEST_NEXT", "Best spot tomorrow morning?",
                List.of("2026-10-06_sunrise"), BUILT, ReadyFixtures.answer(
                ReadyFixtures.pick(1, 1L, "Bamburgh", "Northumberland", "2026-10-06_sunrise", 5, V5)));
        AskSnapshot tuesdayDawn = at(LocalDateTime.of(2026, 10, 6, 4, 0), both(oct(6), northumberland()));

        AskReadyFreshness.Verdict verdict = check(stored, tuesdayDawn);

        assertThat(verdict.fresh()).isFalse();
        assertThat(verdict.reason()).contains("Best spot this morning?");
    }

    @Test
    @DisplayName("withheld when the question is no longer offered: BEST_SOON once the weekend question "
            + "has taken over")
    void questionNoLongerOffered() {
        AskReadyStore.Stored stored = new AskReadyStore.Stored("ALL", "BEST_SOON", "Best spot in the next few days?",
                List.of("2026-10-09_sunset", SAT_SUNSET), BUILT, ReadyFixtures.answer(bamburghSaturday()));

        AskReadyFreshness.Verdict verdict = check(stored, friday());

        assertThat(verdict.fresh()).isFalse();
        assertThat(verdict.reason()).contains("no longer offered");
    }

    @Test
    @DisplayName("withheld when the BEST BET has moved to another window: a 'best' that disagrees with the "
            + "Plan tab is never served")
    void bestBetMoved() {
        AskSnapshot moved = at(ReadyFixtures.FRIDAY_NOON, day(oct(9), false, true, null, northumberland()),
                both(oct(10), northumberland()),
                day(oct(11), true, true, bestAt("Northumberland", "Bamburgh", 1L), northumberland()));

        AskReadyFreshness.Verdict verdict = check(weekend(ReadyFixtures.answer(bamburghSaturday())), moved);

        assertThat(verdict.fresh()).isFalse();
        assertThat(verdict.reason()).contains("BEST BET is now on " + SUN_SUNSET);
    }

    @Test
    @DisplayName("a coastal-high answer is withheld when its spot is no longer at high water")
    void coastalHighNoLongerHigh() {
        AskReadyStore.Stored stored = new AskReadyStore.Stored("ALL", "COASTAL_HIGH",
                "Best coastal spot at high tide?", List.of("2026-10-05_sunset"), BUILT,
                ReadyFixtures.answer(ReadyFixtures.pick(1, 1L, "Bamburgh", "Northumberland",
                        "2026-10-05_sunset", 5, V5)));

        assertThat(check(stored, at(ReadyFixtures.MONDAY_NOON,
                day(oct(5), false, true, null, ReadyFixtures.coastAt("HIGH", 5)))).fresh()).isTrue();
        assertThat(check(stored, at(ReadyFixtures.MONDAY_NOON,
                day(oct(5), false, true, null, ReadyFixtures.coastAt("LOW", 5)))).fresh()).isFalse();
    }
}
