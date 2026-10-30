package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
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
                AskWindowKind.SUNSET, "2026-10-10_sunset", "Clear sky and the tide.",
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
                AskWindowKind.SUNSET, "2026-10-09_sunset", "Why.", 5,
                com.gregochr.goldenhour.model.DisplayVerdict.WORTH_IT.name());
        return new AskReadyStore.Stored("ALL", "BEST_NEXT", "Best spot this evening?",
                List.of("2026-10-09_sunset"), built,
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
                .isEqualTo("Best spot this evening?");
    }

    @Test
    @DisplayName("with nothing stored for the scope there is nothing to suggest")
    void suggestionsWithNothingStored() {
        when(store.findScope("3")).thenReturn(List.of());

        assertThat(service.suggestions(AskScope.of(List.of(3L), Set.of("Northumberland")), snapshot, 2)).isEmpty();
    }

    // -- NIGHT_SKY (ask-night plan section 2.5) -----------------------------------------------------------------

    private static final Instant ASTRO_RUN = Instant.parse("2026-10-09T14:30:00Z");
    private static final String NIGHT_ID = "2026-10-09_astro";

    /** The Friday snapshot with tonight's astro night, scored by the run at {@code generation}. */
    private AskSnapshot fridayWithNight(Instant generation, AskSnapshot.NightSpot... spots) {
        LocalDate tonight = oct(9);
        AskSnapshot.Night night = new AskSnapshot.Night(NIGHT_ID, tonight, AskWindowKind.ASTRO,
                AskNightFixtures.DUSK, AskNightFixtures.DAWN, generation, List.of(spots));
        return new AskSnapshot(snapshot.generatedAt(), snapshot.runLabel(), snapshot.today(), snapshot.windows(),
                snapshot.hotTopics(), snapshot.comingUp(), false,
                new AskSnapshot.Nights(tonight, List.of(night), null));
    }

    private AskReadyStore.Stored nightSky(String version) {
        AskPick pick = new AskPick(1, 1L, "Whitby", "Coast", oct(9), AskWindowKind.ASTRO, NIGHT_ID, "Clear and dark.",
                5, null);
        return new AskReadyStore.Stored("ALL", "NIGHT_SKY", "Anything for the night sky tonight?", List.of(NIGHT_ID),
                BUILT, new AskAnswer(true, "Whitby is clear tonight.", List.of(pick), List.of(), null), version);
    }

    @Test
    @DisplayName("a fresh NIGHT_SKY is served in catalogue order with its nightVersion on the QUESTION (the "
            + "version it was written under, which is the live one), and no other question carries one")
    void serveCarriesTheNightVersion() throws Exception {
        AskSnapshot live = fridayWithNight(ASTRO_RUN, AskNightFixtures.astro(1L, "Whitby", "Coast", 5, 3));
        String version = "n1;2026-10-09=" + ASTRO_RUN.toEpochMilli();
        when(snapshotBuilder.current()).thenReturn(Optional.of(live));
        when(store.findScope("ALL")).thenReturn(List.of(aurora(BUILT), nightSky(version), tonight(BUILT)));

        AskReadyResponse response = service.serve(AskScope.ALL);

        assertThat(response.questions()).extracting(AskReadyResponse.Question::id)
                .containsExactly("BEST_NEXT", "NIGHT_SKY", "RARE_EVENTS");
        AskReadyResponse.Question night = response.questions().get(1);
        assertThat(night.nightVersion()).isEqualTo(version);
        assertThat(night.tabs()).containsExactly("plan", "map");
        assertThat(night.answer().picks()).singleElement().satisfies(p -> {
            assertThat(p.targetType()).isEqualTo(AskWindowKind.ASTRO);
            assertThat(p.windowId()).isEqualTo(NIGHT_ID);
            assertThat(p.locationName()).isEqualTo("Whitby");
        });
        assertThat(response.questions().get(0).nightVersion()).isNull();
        assertThat(response.questions().get(2).nightVersion()).isNull();
        // The version is the string a typed night answer carries for a user-less reader: the thread's night
        // rule judges both alike.
        assertThat(AskNightVersion.isLive(night.nightVersion(), live, false)).isTrue();
        assertThat(night.nightVersion()).isEqualTo(AskNightVersion.of(live, List.of(oct(9)), false).orElseThrow());
        // On the wire it is a property of the question, and absent where there is none.
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        assertThat(mapper.readTree(mapper.writeValueAsString(night)).get("nightVersion").asText())
                .isEqualTo(version);
        assertThat(mapper.readTree(mapper.writeValueAsString(response.questions().get(0))).has("nightVersion"))
                .isFalse();
    }

    @Test
    @DisplayName("a NIGHT_SKY written under an older astro run is withheld whole, and is not suggested either")
    void serveWithholdsANightSkyFromAnOlderRun() {
        AskSnapshot live = fridayWithNight(ASTRO_RUN.plusSeconds(3600),
                AskNightFixtures.astro(1L, "Whitby", "Coast", 5, 3));
        when(snapshotBuilder.current()).thenReturn(Optional.of(live));
        when(store.findScope("ALL")).thenReturn(List.of(nightSky("n1;2026-10-09=" + ASTRO_RUN.toEpochMilli()),
                tonight(BUILT)));

        AskReadyResponse response = service.serve(AskScope.ALL);

        assertThat(response.questions()).extracting(AskReadyResponse.Question::id).containsExactly("BEST_NEXT");
        assertThat(response.questions().getFirst().answer().tryThese()).isEmpty();
        assertThat(service.suggestions(AskScope.ALL, live, 2)).extracting(AskReadyResponse.Suggestion::id)
                .containsExactly("BEST_NEXT");
    }

    @Test
    @DisplayName("a fresh NIGHT_SKY is one of the suggestions of a question that cannot be answered")
    void aFreshNightSkyIsSuggested() {
        AskSnapshot live = fridayWithNight(ASTRO_RUN, AskNightFixtures.astro(1L, "Whitby", "Coast", 5, 3));
        when(store.findScope("ALL")).thenReturn(List.of(nightSky("n1;2026-10-09=" + ASTRO_RUN.toEpochMilli())));

        assertThat(service.suggestions(AskScope.ALL, live, 2)).singleElement().satisfies(s -> {
            assertThat(s.id()).isEqualTo("NIGHT_SKY");
            assertThat(s.text()).isEqualTo("Anything for the night sky tonight?");
        });
    }

    @Test
    @DisplayName("with no briefing a serve is an empty list, not an error")
    void serveWithNoBriefing() {
        when(snapshotBuilder.current()).thenReturn(Optional.empty());

        assertThat(service.serve(AskScope.ALL).questions()).isEmpty();
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("a served Ready answer carries the window figures it was stored with, on the wire answer")
    void serveCarriesTheWindowFigures() {
        ReadyQuestion.Offer offer = ReadyQuestion.BEST_WEEKEND.offer(snapshot, AskScope.ALL).orElseThrow();
        List<AskAnswer.WindowSpread> windows = offer.coveredWindowIds().stream()
                .map(id -> new AskAnswer.WindowSpread(id, snapshot.spread(id, AskScope.ALL).orElseThrow()))
                .toList();
        AskReadyStore.Stored base = saturdayBest(BUILT, 5);
        AskReadyStore.Stored withFigures = new AskReadyStore.Stored("ALL", base.questionId(), base.questionText(),
                base.windowIds(), BUILT, base.answer().withWindows(windows), null, offer.coveredWindowIds());
        when(snapshotBuilder.current()).thenReturn(Optional.of(snapshot));
        when(store.findScope("ALL")).thenReturn(List.of(withFigures));

        AskReadyResponse response = service.serve(AskScope.ALL);

        assertThat(response.questions()).singleElement().satisfies(q ->
                assertThat(q.answer().windows()).isEqualTo(windows).isNotEmpty());
    }
}
