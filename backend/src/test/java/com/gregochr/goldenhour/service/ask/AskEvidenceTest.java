package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.service.ask.AskEvidence.EventFact;
import com.gregochr.goldenhour.service.ask.AskEvidence.Pair;
import com.gregochr.goldenhour.service.ask.AskEvidence.ThreadFact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link AskEvidence} and its {@link ThreadFact}: what a conversation may name. */
class AskEvidenceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 10);
    private static final Pair FROM_TOOL = new Pair(1L, "2026-10-10_sunrise");
    private static final Pair FROM_THREAD = new Pair(2L, "2026-10-10_sunrise");
    private static final Pair GONE = new Pair(3L, "2026-10-10_sunrise");
    private static final EventFact TOOL_EVENT = new EventFact("AURORA", "Aurora", DAY);
    private static final EventFact THREAD_EVENT = new EventFact("KING_TIDE", "King tide", DAY, "note");

    @Test
    @DisplayName("with no thread a pick is offered only if a tool returned it, exactly as before")
    void noThread_onlyToolPairsAreOffered() {
        AskEvidence evidence = new AskEvidence(Set.of(FROM_TOOL), Set.of(TOOL_EVENT), 1);

        assertThat(evidence.thread()).isSameAs(ThreadFact.NONE);
        assertThat(evidence.offers(FROM_TOOL)).isTrue();
        assertThat(evidence.offers(FROM_THREAD)).isFalse();
        assertThat(evidence.offeredEvents()).containsExactly(TOOL_EVENT);
    }

    @Test
    @DisplayName("a pick is offered when a tool returned it or the thread offered it, never when the thread "
            + "dropped it")
    void threadOffersAddToTheTools() {
        AskEvidence evidence = new AskEvidence(Set.of(FROM_TOOL), Set.of(TOOL_EVENT), 1).withThread(
                new ThreadFact(Set.of(FROM_THREAD), Set.of(GONE), Set.of(THREAD_EVENT)));

        assertThat(evidence.offers(FROM_TOOL)).isTrue();
        assertThat(evidence.offers(FROM_THREAD)).isTrue();
        assertThat(evidence.offers(GONE)).isFalse();
        assertThat(evidence.offeredEvents()).containsExactlyInAnyOrder(TOOL_EVENT, THREAD_EVENT);
    }

    @Test
    @DisplayName("the thread's offers do not count as a tool call")
    void threadIsNotAToolCall() {
        AskEvidence evidence = new AskEvidence(Set.of(), Set.of(), 0).withThread(
                new ThreadFact(Set.of(FROM_THREAD), Set.of(), Set.of()));

        assertThat(evidence.anyToolCalled()).isFalse();
        assertThat(evidence.toolCalls()).isZero();
        assertThat(evidence.pairs()).isEmpty();
    }

    @Test
    @DisplayName("null parts read as empty and a null thread as none")
    void nullsReadAsEmpty() {
        AskEvidence evidence = new AskEvidence(null, null, 0, null);
        ThreadFact fact = new ThreadFact(null, null, null);

        assertThat(evidence.pairs()).isEmpty();
        assertThat(evidence.events()).isEmpty();
        assertThat(evidence.thread()).isSameAs(ThreadFact.NONE);
        assertThat(fact.picks()).isEmpty();
        assertThat(fact.droppedPicks()).isEmpty();
        assertThat(fact.events()).isEmpty();
    }

    @Test
    @DisplayName("stale only: every submitted pair is a dropped earlier pick")
    void staleOnly_everySubmittedPairWasDropped() {
        ThreadFact fact = new ThreadFact(Set.of(), Set.of(GONE, FROM_THREAD), Set.of());

        assertThat(fact.staleOnly(Set.of(GONE))).isTrue();
        assertThat(fact.staleOnly(Set.of(GONE, FROM_THREAD))).isTrue();
        assertThat(fact.staleOnly(Set.of(GONE, FROM_TOOL))).as("a pair no thread named").isFalse();
    }

    @Test
    @DisplayName("stale only with nothing submitted: only when no earlier pick survives to rank instead")
    void staleOnly_nothingSubmitted() {
        assertThat(new ThreadFact(Set.of(), Set.of(GONE), Set.of()).staleOnly(Set.of())).isTrue();
        assertThat(new ThreadFact(Set.of(FROM_THREAD), Set.of(GONE), Set.of()).staleOnly(Set.of()))
                .as("something survives, so a pick-less answer is the model's fault").isFalse();
    }

    @Test
    @DisplayName("never stale without a dropped earlier pick: a fresh question or a thread of live picks")
    void staleOnly_needsADroppedPick() {
        assertThat(ThreadFact.NONE.staleOnly(Set.of())).isFalse();
        assertThat(ThreadFact.NONE.staleOnly(Set.of(GONE))).isFalse();
        assertThat(new ThreadFact(Set.of(FROM_THREAD), Set.of(), Set.of()).staleOnly(Set.of())).isFalse();
    }
}
