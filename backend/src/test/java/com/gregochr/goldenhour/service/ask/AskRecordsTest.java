package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.service.ask.AskEvidence.EventFact;
import com.gregochr.goldenhour.service.ask.AskEvidence.Pair;
import com.gregochr.goldenhour.service.ask.AskEvidence.ThreadFact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the Ask value types guarantee at construction: a null collection reads as empty, a supplied one is copied
 * so the caller cannot change it afterwards, and the few derived accessors answer from what they were given.
 */
class AskRecordsTest {

    private static final LocalDate DAY = LocalDate.of(2031, 3, 10);

    private static AskSnapshot.NightSpot spot(long id) {
        return new AskSnapshot.NightSpot(id, "Spot " + id, "Region", 55.0, -1.5, false, 3, null, 4, null,
                null, null, null, null, null, null, null, false, null, null, null);
    }

    // ── AskResponse ───────────────────────────────────────────────────────────

    private static AskResponse response(List<AskReadyResponse.Pick> picks, List<AskEvent> events,
            List<AskReadyResponse.Suggestion> tryThese, List<AskAnswer.WindowSpread> windows) {
        return new AskResponse(true, AskResponse.KIND_OWN, "s", picks, events, null, tryThese, 3, 5, true,
                LocalDateTime.of(2031, 3, 10, 6, 0), "run", null, null, null, windows);
    }

    @Test
    @DisplayName("an AskResponse built with null lists carries four empty lists")
    void responseNullListsReadAsEmpty() {
        AskResponse r = response(null, null, null, null);

        assertThat(r.picks()).isEmpty();
        assertThat(r.events()).isEmpty();
        assertThat(r.tryThese()).isEmpty();
        assertThat(r.windows()).isEmpty();
    }

    @Test
    @DisplayName("an AskResponse copies the lists it is given, so editing the originals changes nothing")
    void responseCopiesItsLists() {
        List<AskReadyResponse.Pick> picks = new ArrayList<>(List.of(new AskReadyResponse.Pick(1, 7L, "Spot",
                "Region", DAY, AskWindowKind.SUNSET, "w", "why")));
        List<AskEvent> events = new ArrayList<>(List.of(new AskEvent("t", "l", DAY, "w", null)));
        List<AskReadyResponse.Suggestion> tryThese = new ArrayList<>(List.of(new AskReadyResponse.Suggestion("a",
                "Anything?")));
        List<AskAnswer.WindowSpread> windows = new ArrayList<>(List.of(new AskAnswer.WindowSpread("w", null)));

        AskResponse r = response(picks, events, tryThese, windows);
        picks.clear();
        events.clear();
        tryThese.clear();
        windows.clear();

        assertThat(r.picks()).hasSize(1);
        assertThat(r.events()).hasSize(1);
        assertThat(r.tryThese()).hasSize(1);
        assertThat(r.windows()).hasSize(1);
        assertThatThrownBy(() -> r.picks().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    // ── AskReadyResponse.Answer ───────────────────────────────────────────────

    @Test
    @DisplayName("a Ready answer reads null windows as empty and copies windows it is given")
    void readyAnswerWindows() {
        AskReadyResponse.Answer none = new AskReadyResponse.Answer(true, AskResponse.KIND_READY, "s", null, null,
                null, null, null);
        List<AskAnswer.WindowSpread> windows = new ArrayList<>(List.of(new AskAnswer.WindowSpread("w", null)));
        AskReadyResponse.Answer some = new AskReadyResponse.Answer(true, AskResponse.KIND_READY, "s", List.of(),
                List.of(), null, List.of(), windows);
        windows.clear();

        assertThat(none.windows()).isEmpty();
        assertThat(some.windows()).extracting(AskAnswer.WindowSpread::windowId).containsExactly("w");
    }

    // ── AskRun and AskOutcome ─────────────────────────────────────────────────

    @Test
    @DisplayName("an AskRun reads a null trace as empty and copies one it is given")
    void runTrace() {
        AskOutcome outcome = new AskOutcome(AskOutcome.Status.FAILED, null, false, 1);
        List<AskTools.ToolCall> trace = new ArrayList<>(List.of(new AskTools.ToolCall("list_windows", false, 120)));

        AskRun none = new AskRun(outcome, null, "r");
        AskRun some = new AskRun(outcome, trace, "r");
        trace.clear();

        assertThat(none.trace()).isEmpty();
        assertThat(some.trace()).hasSize(1);
    }

    @Test
    @DisplayName("an AskOutcome reads null nights as none and keeps the nights it is given in date order")
    void outcomeNights() {
        AskOutcome none = new AskOutcome(AskOutcome.Status.OK, null, false, 1, false, null);
        Set<LocalDate> unordered = new LinkedHashSet<>(List.of(DAY.plusDays(1), DAY));
        AskOutcome some = new AskOutcome(AskOutcome.Status.OK, null, false, 1, true, unordered);
        unordered.clear();

        assertThat(none.nights()).isEmpty();
        assertThat(some.nights()).containsExactly(DAY, DAY.plusDays(1));
        assertThat(some.uncacheable()).isTrue();
    }

    // ── AskSnapshot and its parts ─────────────────────────────────────────────

    @Test
    @DisplayName("a snapshot built with nothing reads as empty lists, no nights and no aliases")
    void snapshotNulls() {
        AskSnapshot snapshot = new AskSnapshot(LocalDateTime.of(2031, 3, 10, 6, 0), "run", DAY, null, null, null,
                false, null, null, null, null);

        assertThat(snapshot.windows()).isEmpty();
        assertThat(snapshot.hotTopics()).isEmpty();
        assertThat(snapshot.comingUp()).isEmpty();
        assertThat(snapshot.nights()).isSameAs(AskSnapshot.Nights.NONE);
        assertThat(snapshot.areas()).isEmpty();
        assertThat(snapshot.locationAliases()).isEmpty();
        assertThat(snapshot.laterWindows()).isEmpty();
    }

    @Test
    @DisplayName("a snapshot keeps the nights it is given")
    void snapshotKeepsNights() {
        AskSnapshot.Nights nights = new AskSnapshot.Nights(DAY, List.of(), null);

        AskSnapshot snapshot = new AskSnapshot(LocalDateTime.of(2031, 3, 10, 6, 0), "run", DAY, List.of(), List.of(),
                List.of(), false, nights, List.of(), Map.of(), List.of());

        assertThat(snapshot.nights()).isSameAs(nights);
    }

    @Test
    @DisplayName("a later window reads null tides as empty and copies tides it is given")
    void laterWindowTides() {
        AskSnapshot.LaterWindow none = new AskSnapshot.LaterWindow("w", DAY, TargetType.SUNSET, null, null);
        List<AskSnapshot.LaterTide> tides = new ArrayList<>(List.of(new AskSnapshot.LaterTide(1L, "Bamburgh",
                "Northumberland", "HIGH", true, true, "suits", "St Mary's")));
        AskSnapshot.LaterWindow some = new AskSnapshot.LaterWindow("w", DAY, TargetType.SUNSET, null, tides);
        tides.clear();

        assertThat(none.tides()).isEmpty();
        assertThat(some.tides()).extracting(AskSnapshot.LaterTide::name).containsExactly("Bamburgh");
    }

    @Test
    @DisplayName("a coming-up entry reads null coincident types as empty and copies those it is given")
    void comingUpCoincidentTypes() {
        List<String> types = new ArrayList<>(List.of("spring-tide"));
        AskSnapshot.ComingUp none = new AskSnapshot.ComingUp("t", "Title", DAY, DAY, "d", null, null);
        AskSnapshot.ComingUp some = new AskSnapshot.ComingUp("t", "Title", DAY, DAY, "d", null, types);
        types.clear();

        assertThat(none.coincidentTypes()).isEmpty();
        assertThat(some.coincidentTypes()).containsExactly("spring-tide");
    }

    @Test
    @DisplayName("a night reads null spots as empty and copies spots it is given")
    void nightSpots() {
        List<AskSnapshot.NightSpot> spots = new ArrayList<>(List.of(spot(1)));
        AskSnapshot.Night none = new AskSnapshot.Night(DAY + "_astro", DAY, AskWindowKind.ASTRO, null, null, null,
                null);
        AskSnapshot.Night some = new AskSnapshot.Night(DAY + "_astro", DAY, AskWindowKind.ASTRO, null, null,
                Instant.parse("2031-03-10T00:00:00Z"), spots);
        spots.clear();

        assertThat(none.spots()).isEmpty();
        assertThat(some.spots()).extracting(AskSnapshot.NightSpot::locationId).containsExactly(1L);
    }

    @Test
    @DisplayName("Nights reads a null list as empty, and dates() lists each date once, earliest first")
    void nightsDates() {
        AskSnapshot.Nights none = new AskSnapshot.Nights(DAY, null, null);
        AskSnapshot.Night later = new AskSnapshot.Night(DAY.plusDays(1) + "_astro", DAY.plusDays(1),
                AskWindowKind.ASTRO, null, null, null, List.of());
        AskSnapshot.Night astro = new AskSnapshot.Night(DAY + "_astro", DAY, AskWindowKind.ASTRO, null, null, null,
                List.of());
        AskSnapshot.Night aurora = new AskSnapshot.Night(DAY + "_aurora", DAY, AskWindowKind.AURORA, null, null, null,
                List.of());
        AskSnapshot.Nights some = new AskSnapshot.Nights(DAY, List.of(later, astro, aurora), null);

        assertThat(none.nights()).isEmpty();
        assertThat(none.dates()).isEmpty();
        assertThat(some.dates()).containsExactly(DAY, DAY.plusDays(1));
    }

    @Test
    @DisplayName("a topic with no date has no last date; a night-event topic runs into the next day")
    void topicLastDate() {
        AskSnapshot.Topic undated = new AskSnapshot.Topic("type", "label", "detail", null, List.of(), null, null);
        AskSnapshot.Topic dated = new AskSnapshot.Topic("type", "label", "detail", DAY, List.of(), null, null);
        AskSnapshot.Topic night = new AskSnapshot.Topic("type", "label", "detail", DAY, List.of(), null,
                com.gregochr.goldenhour.model.HotTopic.EVENT_NIGHT);

        assertThat(undated.lastDate()).isNull();
        assertThat(dated.lastDate()).isEqualTo(DAY);
        assertThat(night.lastDate()).isEqualTo(DAY.plusDays(1));
    }

    @Test
    @DisplayName("folding two topics keeps the first safety note, else takes the later one, and unions the regions")
    void topicFolding() {
        AskSnapshot.Topic first = new AskSnapshot.Topic("t", "l", "d", DAY, List.of("North"), "first note", null);
        AskSnapshot.Topic second = new AskSnapshot.Topic("t", "l", "d", DAY, List.of("South", "North"), "second", null);
        AskSnapshot.Topic noNote = new AskSnapshot.Topic("t", "l", "d", DAY, List.of("North"), null, null);
        AskSnapshot.Topic regionless = new AskSnapshot.Topic("t", "l", "d", DAY, List.of(), null, null);

        assertThat(first.foldedWith(second).safetyNote()).isEqualTo("first note");
        assertThat(first.foldedWith(second).regions()).containsExactly("North", "South");
        assertThat(noNote.foldedWith(second).safetyNote()).isEqualTo("second");
        assertThat(first.foldedWith(regionless).regions()).isEmpty();
    }

    // ── AskEvidence ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("evidence built with nulls carries empty sets and no thread")
    void evidenceNulls() {
        AskEvidence evidence = new AskEvidence(null, null, 0, null, null);

        assertThat(evidence.pairs()).isEmpty();
        assertThat(evidence.events()).isEmpty();
        assertThat(evidence.thread()).isSameAs(ThreadFact.NONE);
        assertThat(evidence.listedWindows()).isEmpty();
    }

    @Test
    @DisplayName("evidence copies the sets it is given")
    void evidenceCopies() {
        Set<Pair> pairs = new LinkedHashSet<>(List.of(new Pair(1L, "w")));
        Set<EventFact> events = new LinkedHashSet<>(List.of(new EventFact("t", "l", DAY, null)));
        Set<String> listed = new LinkedHashSet<>(List.of("w"));
        AskEvidence evidence = new AskEvidence(pairs, events, 2, ThreadFact.NONE, listed);
        pairs.clear();
        events.clear();
        listed.clear();

        assertThat(evidence.pairs()).containsExactly(new Pair(1L, "w"));
        assertThat(evidence.events()).hasSize(1);
        assertThat(evidence.listedWindows()).containsExactly("w");
    }

    @Test
    @DisplayName("a thread fact built with nulls carries empty sets")
    void threadFactNulls() {
        ThreadFact fact = new ThreadFact(null, null, null, null, null);

        assertThat(fact.picks()).isEmpty();
        assertThat(fact.droppedPicks()).isEmpty();
        assertThat(fact.events()).isEmpty();
        assertThat(fact.turnedDown()).isEmpty();
        assertThat(fact.heldBack()).isEmpty();
    }

    @Test
    @DisplayName("a thread fact keeps, in order, the turned-down and held-back sets it is given")
    void threadFactKeepsSets() {
        Pair held = new Pair(2L, "w");
        ThreadFact fact = new ThreadFact(Set.of(), Set.of(held), Set.of(), new LinkedHashSet<>(List.of(5L, 4L)),
                new LinkedHashSet<>(List.of(held)));

        assertThat(fact.turnedDown()).containsExactly(5L, 4L);
        assertThat(fact.heldBack()).containsExactly(held);
    }

    @Test
    @DisplayName("with nothing submitted and nothing dropped, the stale kind is GONE")
    void staleKindWithNothingToJudge() {
        ThreadFact fact = new ThreadFact(Set.of(new Pair(1L, "w")), Set.of(), Set.of());

        assertThat(fact.staleKind(Set.of())).isEqualTo(ThreadFact.StaleKind.GONE);
    }

    @Test
    @DisplayName("the stale kind is HELD_BACK when every judged pick is held back, MIXED when only some are")
    void staleKinds() {
        Pair held = new Pair(1L, "w");
        Pair gone = new Pair(2L, "w");
        ThreadFact fact = new ThreadFact(Set.of(), Set.of(held, gone), Set.of(), Set.of(), Set.of(held));

        assertThat(fact.staleKind(Set.of(held))).isEqualTo(ThreadFact.StaleKind.HELD_BACK);
        assertThat(fact.staleKind(Set.of(held, gone))).isEqualTo(ThreadFact.StaleKind.MIXED);
        assertThat(fact.staleKind(Set.of(gone))).isEqualTo(ThreadFact.StaleKind.GONE);
        // Nothing submitted: judged over every dropped pick.
        assertThat(fact.staleKind(Set.of())).isEqualTo(ThreadFact.StaleKind.MIXED);
    }

    // ── AskScope and AskQuestion ──────────────────────────────────────────────

    @Test
    @DisplayName("scopes are equal only when their key, region ids and names all match")
    void scopeEquality() {
        AskScope base = AskScope.of(List.of(1L, 2L), List.of("North", "South"));

        assertThat(base).isEqualTo(AskScope.of(List.of(1L, 2L), List.of("South", "North")));
        assertThat(base).isNotEqualTo(AskScope.of(List.of(1L, 3L), List.of("North", "South")));
        // Same key (ids sorted) but listed in another order: not the same scope.
        assertThat(base).isNotEqualTo(AskScope.of(List.of(2L, 1L), List.of("North", "South")));
        // Same ids, other names.
        assertThat(base).isNotEqualTo(AskScope.of(List.of(1L, 2L), List.of("North", "East")));
        assertThat(base).isNotEqualTo("1,2");
        assertThat(base).isNotEqualTo(null);
    }

    @Test
    @DisplayName("a question built from a sanitiser result with no normalised form derives it from the cleaned text")
    void questionFromLenientResult() {
        AskQuestionSanitiser.Result lenient = new AskQuestionSanitiser.Result("Where is good?", null);

        AskQuestion question = AskQuestion.of(lenient, "w", AskScope.ALL, "map");

        assertThat(question.sanitised()).isEqualTo("Where is good?");
        assertThat(question.normalised()).isEqualTo(AskQuestionSanitiser.normalise("Where is good?"));
    }

    @Test
    @DisplayName("a question built from a result that has a normalised form keeps that form")
    void questionKeepsTheSanitisersNormalisedForm() {
        AskQuestionSanitiser.Result typed = new AskQuestionSanitiser.Result("Where is good?", "sanitiser's own",
                null);

        AskQuestion question = AskQuestion.of(typed, null, null, "plan");

        assertThat(question.normalised()).isEqualTo("sanitiser's own");
        assertThat(question.scope()).isSameAs(AskScope.ALL);
    }

    // ── AskLocationNames ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a memoised location lookup reads its delegate once, however often it is asked")
    void memoisedLookupReadsOnce() {
        int[] reads = {0};
        AskLocationNames delegate = () -> {
            reads[0]++;
            return Map.of(1L, new AskLocationNames.Place("Bamburgh", 55.6, -1.7));
        };
        AskLocationNames memo = AskLocationNames.memoised(delegate);

        assertThat(memo.byId()).containsKey(1L);
        assertThat(memo.byId()).containsKey(1L);
        assertThat(reads[0]).isEqualTo(1);
    }
}
