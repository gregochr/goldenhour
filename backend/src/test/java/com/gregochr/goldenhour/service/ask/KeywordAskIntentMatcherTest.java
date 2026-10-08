package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.gregochr.goldenhour.service.ask.ReadyFixtures.both;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.day;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.northumberland;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.oct;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The Ready intent match end to end over the real {@link AskReadyServing} and its real freshness
 * test: a typed question is served a Ready answer only when the Ready id is stored for the scope,
 * fresh against the live snapshot <em>right now</em>, and the question carries nothing the answer
 * ignores. The snapshot is built through the real builder; only the store is stubbed.
 */
class KeywordAskIntentMatcherTest {

    private static final LocalDateTime BUILT = LocalDateTime.of(2026, 10, 9, 5, 2, 11);
    private static final String SAT_SUNSET = "2026-10-10_sunset";
    private static final List<String> WEEKEND = List.of("2026-10-10_sunrise", SAT_SUNSET,
            "2026-10-11_sunrise", "2026-10-11_sunset");
    private static final DisplayVerdict V5 = DisplayVerdict.resolve(5, Verdict.GO);

    private final AskReadyStore store = mock(AskReadyStore.class);
    private KeywordAskIntentMatcher matcher;
    private AskReadyServing readyServing;

    @BeforeEach
    void setUp() {
        readyServing = new AskReadyServing(mock(AskSnapshotBuilder.class), store);
        matcher = new KeywordAskIntentMatcher(readyServing);
        when(store.findScope("ALL")).thenReturn(List.of(weekendRow()));
        when(store.findScope("1")).thenReturn(List.of());
    }

    private static AskQuestion typed(String text) {
        return typed(text, AskScope.ALL);
    }

    private static AskQuestion typed(String text, AskScope scope) {
        return AskQuestion.of(AskQuestionSanitiser.sanitiseTyped(text), null, scope, "plan");
    }

    private static BriefingWindow.Pick bamburghIsBest() {
        return AskFixtures.pick(BriefingWindow.PickKind.BEST, "Northumberland", "Bamburgh", 1L);
    }

    /** Friday noon: Saturday sunset carries the BEST BET at Bamburgh. */
    private static AskSnapshot friday(BriefingRegion saturday) {
        return ReadyFixtures.at(ReadyFixtures.FRIDAY_NOON, day(oct(9), false, true, null, northumberland()),
                day(oct(10), true, true, bamburghIsBest(), saturday), both(oct(11), northumberland()));
    }

    private static AskSnapshot friday() {
        return friday(northumberland());
    }

    private static AskAnswer bamburghSaturday() {
        return ReadyFixtures.answer(ReadyFixtures.pick(1, 1L, "Bamburgh", "Northumberland", SAT_SUNSET, 5, V5));
    }

    private static AskReadyStore.Stored weekendRow() {
        return new AskReadyStore.Stored("ALL", "BEST_WEEKEND", "Best spot this weekend?", WEEKEND, BUILT,
                bamburghSaturday());
    }

    private Optional<AskReadyResponse.Question> match(String text, AskSnapshot snapshot) {
        return matcher.match(typed(text), snapshot);
    }

    @Test
    @DisplayName("the weekend question, in several phrasings, is served the stored weekend answer")
    void servesTheReadyAnswer() {
        for (String text : List.of("Where's good this weekend?", "Best spot this weekend?",
                "Where should I go at the weekend?", "Where's good Saturday?")) {
            Optional<AskReadyResponse.Question> hit = match(text, friday());

            assertThat(hit).as(text).isPresent();
            assertThat(hit.get().id()).isEqualTo("BEST_WEEKEND");
            assertThat(hit.get().answer().kind()).isEqualTo("ready");
            assertThat(hit.get().answer().picks()).singleElement()
                    .satisfies(p -> assertThat(p.locationName()).isEqualTo("Bamburgh"));
            assertThat(hit.get().generatedAt()).isEqualTo(BUILT);
        }
    }

    @Test
    @DisplayName("what is served is exactly what a tap on the Ready question serves: the same decorated "
            + "question, picks and try list")
    void identicalToTheTap() {
        AskSnapshot live = friday();

        Optional<AskReadyResponse.Question> typedMatch = match("Best spot this weekend?", live);
        List<AskReadyResponse.Question> tapped = readyServing.freshAnswers(AskScope.ALL, live);

        assertThat(typedMatch).contains(tapped.getFirst());
    }

    @Test
    @DisplayName("\"Saturday\" is served the weekend answer only when its picks are all on Saturday")
    void aWeekendDayNeedsAllPicksOnThatDay() {
        assertThat(match("Where's good Saturday?", friday())).isPresent();
        assertThat(match("Where's good Sunday?", friday())).isEmpty();
    }

    @Test
    @DisplayName("a Ready id that is not stored for the scope is a miss (and the engine goes on)")
    void notStoredIsAMiss() {
        when(store.findScope("ALL")).thenReturn(List.of());

        assertThat(match("Best spot this weekend?", friday())).isEmpty();
    }

    @Test
    @DisplayName("a stored answer that is no longer fresh is a miss: a changed rating, a changed verdict, "
            + "a passed window, a BEST BET that moved")
    void staleIsAMiss() {
        BriefingRegion ratingDown = AskFixtures.region("Northumberland", true,
                AskFixtures.coastal(1L, "Bamburgh", 4, "HIGH", true), AskFixtures.slot(2L, "Cheviot Edge", 4));
        assertThat(match("Best spot this weekend?", friday(ratingDown))).isEmpty();

        BriefingRegion verdictChanged = AskFixtures.region("Northumberland", true,
                ReadyFixtures.withVerdict(AskFixtures.coastal(1L, "Bamburgh", 5, "HIGH", true),
                        DisplayVerdict.resolve(3, Verdict.GO)),
                AskFixtures.slot(2L, "Cheviot Edge", 4));
        assertThat(match("Best spot this weekend?", friday(verdictChanged))).isEmpty();

        AskSnapshot nextWeek = ReadyFixtures.at(LocalDateTime.of(2026, 10, 12, 12, 0),
                both(oct(13), northumberland()));
        assertThat(match("Best spot this weekend?", nextWeek)).isEmpty();

        AskSnapshot bestMoved = ReadyFixtures.at(ReadyFixtures.FRIDAY_NOON,
                day(oct(9), false, true, null, northumberland()),
                day(oct(10), true, true, AskFixtures.pick(BriefingWindow.PickKind.BEST, "Northumberland",
                        "Cheviot Edge", 2L), northumberland()),
                both(oct(11), northumberland()));
        // The BEST BET is still on Saturday's sunset, so the lead window is unchanged: still fresh.
        assertThat(match("Best spot this weekend?", bestMoved)).isPresent();
        AskSnapshot bestOnSunday = ReadyFixtures.at(ReadyFixtures.FRIDAY_NOON,
                day(oct(9), false, true, null, northumberland()),
                both(oct(10), northumberland()),
                day(oct(11), true, true, AskFixtures.pick(BriefingWindow.PickKind.BEST, "Northumberland",
                        "Bamburgh", 1L), northumberland()));
        assertThat(match("Best spot this weekend?", bestOnSunday)).isEmpty();
    }

    @Test
    @DisplayName("a Ready question that is no longer offered under the same text is a miss: the next-few-days "
            + "row stored on a day that has a weekend")
    void notOfferedAnymoreIsAMiss() {
        when(store.findScope("ALL")).thenReturn(List.of(new AskReadyStore.Stored("ALL", "BEST_SOON",
                "Best spot in the next few days?", WEEKEND, BUILT, bamburghSaturday())));

        assertThat(match("Best spot in the next few days?", friday())).isEmpty();
    }

    @Test
    @DisplayName("a question with a word no Ready rule knows (a place, a drive, a number) is declined with no "
            + "read at all")
    void unknownWordsAreDeclinedWithoutARead() {
        for (String text : List.of("Best spot this weekend near me?", "Best spot this weekend within an hour?",
                "Best spot this weekend in Whitby?", "Best spot this weekend from home?",
                "Best spot this weekend, 30 miles?", "Best spot this weekend with a dog?")) {
            assertThat(match(text, friday())).as(text).isEmpty();
        }
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("a question made of known words but a different subject (another weekend, a time of day) is "
            + "read for and still declined")
    void knownButDifferentWordsAreDeclined() {
        for (String text : List.of("Best spot next weekend?", "Best spot Saturday morning?",
                "Best spot this weekend at sunrise?", "Best spot tonight?")) {
            assertThat(match(text, friday())).as(text).isEmpty();
        }
    }

    @Test
    @DisplayName("a location or region name made of ordinary words still stops a match")
    void ordinaryWordNamesStopAMatch() {
        BriefingRegion withAGoodSpot = AskFixtures.region("Northumberland", true,
                AskFixtures.coastal(1L, "Bamburgh", 5, "HIGH", true), AskFixtures.slot(2L, "Cheviot Edge", 4),
                AskFixtures.slot(9L, "Good Spot", 3));

        assertThat(match("Where's good this weekend?", friday(withAGoodSpot))).isPresent();
        assertThat(match("Where's the good spot this weekend?", friday(withAGoodSpot))).isEmpty();
        assertThat(KeywordAskIntentMatcher.namesAPlace("wheres good spot weekend", friday(withAGoodSpot)))
                .isTrue();
        assertThat(KeywordAskIntentMatcher.namesAPlace("best spot this weekend", friday(withAGoodSpot)))
                .isFalse();
        // Whole words only: a name inside a longer word does not count.
        assertThat(KeywordAskIntentMatcher.namesAPlace("good spotter", friday(withAGoodSpot))).isFalse();
    }

    @Test
    @DisplayName("the scope is the request's: a single region reads that region's stored row, not ALL's")
    void usesTheRequestsScope() {
        AskReadyStore.Stored scoped = new AskReadyStore.Stored("1", "BEST_WEEKEND", "Best spot this weekend?",
                WEEKEND, BUILT, bamburghSaturday());
        when(store.findScope("1")).thenReturn(List.of(scoped));
        when(store.findScope("ALL")).thenReturn(List.of());

        AskScope northumberland = AskScope.of(List.of(1L), Set.of("Northumberland"));
        assertThat(matcher.match(typed("Best spot this weekend?", northumberland), friday())).isPresent();
        assertThat(matcher.match(typed("Best spot this weekend?"), friday())).isEmpty();
        // The region's answer is judged against the region, not the whole catalogue.
        AskScope teesdale = AskScope.of(List.of(1L), Set.of("Teesdale"));
        assertThat(matcher.match(typed("Best spot this weekend?", teesdale), friday())).isEmpty();
    }

    @Test
    @DisplayName("a question about several regions reads the whole catalogue's row, as the service has always "
            + "routed it")
    void severalRegionsReadAll() {
        AskScope two = AskScope.of(List.of(1L, 2L), Set.of("Northumberland", "Teesdale"));
        when(store.findScope("1,2")).thenReturn(List.of());

        assertThat(matcher.match(typed("Best spot this weekend?", two), friday())).isPresent();
        verify(store, never()).findScope("1,2");
    }

    @Test
    @DisplayName("a long, empty or null question and a null snapshot are declined without a read")
    void degenerateInput() {
        String longQuestion = "best spot this weekend " + "where ".repeat(KeywordAskIntentMatcher.MAX_WORDS);

        assertThat(match(longQuestion, friday())).isEmpty();
        assertThat(matcher.match(null, friday())).isEmpty();
        assertThat(matcher.match(new AskQuestion("x", null, null, AskScope.ALL, "plan"), friday())).isEmpty();
        assertThat(matcher.match(typed("Best spot this weekend?"), null)).isEmpty();
        assertThat(matcher.match(new AskQuestion("?", "", null, AskScope.ALL, "plan"), friday())).isEmpty();
        verify(store, never()).findScope("ALL");
    }

    @Test
    @DisplayName("a stored row whose id is not in the catalogue is never served, and is not an error")
    void unknownStoredIdIsIgnored() {
        when(store.findScope("ALL")).thenReturn(List.of(new AskReadyStore.Stored("ALL", "NOT_A_QUESTION",
                "Best spot this weekend?", WEEKEND, BUILT, bamburghSaturday())));

        assertThat(match("Best spot this weekend?", friday())).isEmpty();
    }
}
