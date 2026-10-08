package com.gregochr.goldenhour.service.ask;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.gregochr.goldenhour.service.ask.ReadyFixtures.oct;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AskReadyServing}: what a serve returns (the fresh questions, whole or not at all, each with its own
 * build time, and the suggestions of a typed answer) over the real freshness test and the real snapshot.
 * Moved, with the helpers it needs, out of the former {@code AskReadyServiceTest}.
 */
class AskReadyServingTest {

    private static final LocalDateTime BUILT = LocalDateTime.of(2026, 10, 9, 5, 2, 11);

    private final AskSnapshotBuilder snapshotBuilder = mock(AskSnapshotBuilder.class);
    private final AskReadyStore store = mock(AskReadyStore.class);
    private AskSnapshot snapshot;
    private AskReadyServing service;

    @BeforeEach
    void setUp() {
        snapshot = ReadyFixtures.fridaySnapshot();
        service = new AskReadyServing(snapshotBuilder, store);
    }

    private AskReadyStore.Stored saturdayBest(LocalDateTime built, int rating) {
        AskPick pick = new AskPick(1, 1L, "Bamburgh", "Northumberland", oct(10),
                com.gregochr.goldenhour.entity.TargetType.SUNSET, "2026-10-10_sunset", "Clear sky and the tide.",
                rating, com.gregochr.goldenhour.model.DisplayVerdict.WORTH_IT.name());
        return new AskReadyStore.Stored("ALL", "BEST_WEEKEND", "Best spot this weekend?",
                List.of("2026-10-10_sunrise", "2026-10-10_sunset", "2026-10-11_sunrise", "2026-10-11_sunset"), built,
                new AskAnswer(true, "Bamburgh.", List.of(pick), List.of(), null));
    }

    private AskReadyStore.Stored aurora(LocalDateTime built) {
        return new AskReadyStore.Stored("ALL", "RARE_EVENTS", "Any rare events coming up?", List.of(), built,
                new AskAnswer(true, "Aurora.", List.of(), List.of(new AskEvent("AURORA", "old label", oct(12),
                        "Kp 6.", null)), null));
    }

    private AskReadyStore.Stored tonight(LocalDateTime built) {
        AskPick pick = new AskPick(1, 1L, "Bamburgh", "Northumberland", oct(9),
                com.gregochr.goldenhour.entity.TargetType.SUNSET, "2026-10-09_sunset", "Why.", 5,
                com.gregochr.goldenhour.model.DisplayVerdict.WORTH_IT.name());
        return new AskReadyStore.Stored("ALL", "BEST_NEXT", "Best spot tonight?", List.of("2026-10-09_sunset"), built,
                new AskAnswer(true, "Tonight.", List.of(pick), List.of(), null));
    }

    @Test
    @DisplayName("a serve returns the fresh questions in catalogue order, each with its own generatedAt and "
            + "runLabel from its own row, live names, and two other fresh questions to try")
    void serveReturnsFreshQuestions() {
        LocalDateTime morning = LocalDateTime.of(2026, 10, 9, 5, 2, 11);
        LocalDateTime evening = LocalDateTime.of(2026, 10, 8, 17, 5, 0);
        when(snapshotBuilder.current()).thenReturn(Optional.of(snapshot));
        when(store.findScope("ALL")).thenReturn(List.of(aurora(evening), tonight(morning),
                saturdayBest(morning, 5)));

        AskReadyResponse response = service.serve(AskScope.ALL);

        assertThat(response.scope()).isEqualTo("all");
        assertThat(response.questions()).extracting(AskReadyResponse.Question::id)
                .containsExactly("BEST_WEEKEND", "BEST_NEXT", "RARE_EVENTS");
        AskReadyResponse.Question weekend = response.questions().getFirst();
        assertThat(weekend.runLabel()).isEqualTo("06:02");
        assertThat(weekend.generatedAt()).isEqualTo(morning);
        assertThat(weekend.tabs()).containsExactly("plan", "map");
        assertThat(weekend.answer().kind()).isEqualTo("ready");
        assertThat(weekend.answer().answerable()).isTrue();
        assertThat(weekend.answer().missing()).isNull();
        assertThat(weekend.answer().picks()).singleElement().satisfies(p -> {
            assertThat(p.locationName()).isEqualTo("Bamburgh");
            assertThat(p.why()).isEqualTo("Clear sky and the tide.");
        });
        assertThat(weekend.answer().tryThese()).extracting(AskReadyResponse.Suggestion::id)
                .containsExactly("BEST_NEXT", "RARE_EVENTS");
        AskReadyResponse.Question rare = response.questions().get(2);
        assertThat(rare.runLabel()).isEqualTo("18:05");
        assertThat(rare.answer().events().getFirst().label()).isEqualTo("Aurora tonight");
        assertThat(rare.answer().tryThese()).extracting(AskReadyResponse.Suggestion::id)
                .containsExactly("BEST_WEEKEND", "BEST_NEXT");
        assertThat(rare.answer().tryThese().getFirst().text()).isEqualTo("Best spot this weekend?");
    }

    @Test
    @DisplayName("a question that fails freshness is withheld whole and the others are still served; with "
            + "one question left there is nothing else to try")
    void serveWithholdsTheStaleOne() {
        when(snapshotBuilder.current()).thenReturn(Optional.of(snapshot));
        when(store.findScope("ALL")).thenReturn(List.of(saturdayBest(BUILT, 4), tonight(BUILT)));

        AskReadyResponse response = service.serve(AskScope.ALL);

        assertThat(response.questions()).extracting(AskReadyResponse.Question::id).containsExactly("BEST_NEXT");
        assertThat(response.questions().getFirst().answer().tryThese()).isEmpty();
    }

    @Test
    @DisplayName("a region scope echoes its key and is checked against that region's names; a row for a "
            + "question the catalogue no longer has is ignored")
    void serveRegionScopeAndUnknownRows() {
        when(snapshotBuilder.current()).thenReturn(Optional.of(snapshot));
        AskReadyStore.Stored bogus = new AskReadyStore.Stored("1", "BOGUS", "?", List.of(), BUILT,
                new AskAnswer(true, "x", List.of(), List.of(), null));
        when(store.findScope("1")).thenReturn(List.of(bogus, scopedTonight("1")));

        AskReadyResponse response = service.serve(AskScope.of(List.of(1L), Set.of("Northumberland")));

        assertThat(response.scope()).isEqualTo("1");
        assertThat(response.questions()).extracting(AskReadyResponse.Question::id).containsExactly("BEST_NEXT");
    }

    private AskReadyStore.Stored scopedTonight(String scopeKey) {
        AskReadyStore.Stored base = tonight(BUILT);
        return new AskReadyStore.Stored(scopeKey, base.questionId(), base.questionText(), base.windowIds(),
                base.briefingGeneratedAt(), base.answer());
    }

    @Test
    @DisplayName("suggestions are the first fresh questions in catalogue order, at most the limit, and a stale "
            + "one is never suggested")
    void suggestionsAreFreshAndBounded() {
        LocalDateTime morning = LocalDateTime.of(2026, 10, 9, 5, 2, 11);
        when(store.findScope("ALL")).thenReturn(List.of(aurora(morning), tonight(morning),
                saturdayBest(morning, 4)));

        // The weekend answer was stored at 4★ and the live rating differs: stale, so it is not suggested.
        assertThat(service.suggestions(AskScope.ALL, snapshot, 2)).extracting(
                AskReadyResponse.Suggestion::id).containsExactly("BEST_NEXT", "RARE_EVENTS");
        assertThat(service.suggestions(AskScope.ALL, snapshot, 1)).extracting(
                AskReadyResponse.Suggestion::id).containsExactly("BEST_NEXT");
        assertThat(service.suggestions(AskScope.ALL, snapshot, 0)).isEmpty();
        assertThat(service.suggestions(AskScope.ALL, snapshot, -1)).isEmpty();
        assertThat(service.suggestions(AskScope.ALL, snapshot, 2).getFirst().text())
                .isEqualTo("Best spot tonight?");
    }

    @Test
    @DisplayName("with nothing stored for the scope there is nothing to suggest")
    void suggestionsWithNothingStored() {
        when(store.findScope("3")).thenReturn(List.of());

        assertThat(service.suggestions(AskScope.of(List.of(3L), Set.of("Northumberland")), snapshot, 2)).isEmpty();
    }

    @Test
    @DisplayName("with no briefing a serve is an empty list, not an error")
    void serveWithNoBriefing() {
        when(snapshotBuilder.current()).thenReturn(Optional.empty());

        assertThat(service.serve(AskScope.ALL).questions()).isEmpty();
        verifyNoInteractions(store);
    }
}
