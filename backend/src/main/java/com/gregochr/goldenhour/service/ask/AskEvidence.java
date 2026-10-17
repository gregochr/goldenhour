package com.gregochr.goldenhour.service.ask;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What a conversation's tools actually returned, and what its thread offered — the only things an
 * answer may name (plan §1 #5).
 *
 * <p>A pick or an event is <em>offered</em> to the model two ways: a tool returned it in this
 * conversation, or the thread's earlier answers carried it and it is still there to be named
 * ({@code docs/engineering/ask-thread-plan.md} §2.3 item 3). Offering is never serving: the
 * validator still holds every pick to the live snapshot.
 *
 * @param pairs     the (location, window) pairs {@code rank_spots} or {@code rank_night_spots} returned
 * @param events    the events {@code get_hot_topics} and {@code get_coming_up} returned
 * @param toolCalls how many tool calls the conversation made (errors included)
 * @param thread    what the thread offered; null reads as nothing
 */
public record AskEvidence(Set<Pair> pairs, Set<EventFact> events, int toolCalls, ThreadFact thread) {

    /** Canonical constructor: takes immutable copies; a null set reads as empty. */
    public AskEvidence {
        pairs = pairs == null ? Set.of() : Set.copyOf(pairs);
        events = events == null ? Set.of() : Set.copyOf(events);
        thread = thread == null ? ThreadFact.NONE : thread;
    }

    /**
     * Evidence with nothing offered by a thread.
     *
     * @param pairs     the (location, window) pairs {@code rank_spots} or {@code rank_night_spots} returned
     * @param events    the events the event tools returned
     * @param toolCalls how many tool calls the conversation made
     */
    public AskEvidence(Set<Pair> pairs, Set<EventFact> events, int toolCalls) {
        this(pairs, events, toolCalls, ThreadFact.NONE);
    }

    /**
     * The same evidence with a thread's offers added.
     *
     * @param offered what the thread offered
     * @return the combined evidence
     */
    public AskEvidence withThread(ThreadFact offered) {
        return new AskEvidence(pairs, events, toolCalls, offered);
    }

    /**
     * Whether a pick was offered: returned by {@code rank_spots} or {@code rank_night_spots} in this
     * conversation, or offered by
     * the thread.
     *
     * @param pair the (location, window) pair
     * @return true when a tool returned it or the thread offered it
     */
    public boolean offers(Pair pair) {
        return pairs.contains(pair) || thread.picks().contains(pair);
    }

    /**
     * The events that may be named: those an event tool returned and those the thread offered.
     *
     * @return the union, for the validator to match a type against
     */
    public Set<EventFact> offeredEvents() {
        if (thread.events().isEmpty()) {
            return events;
        }
        Set<EventFact> all = new LinkedHashSet<>(events);
        all.addAll(thread.events());
        return all;
    }

    /**
     * What a thread's earlier answers offer a follow-up, reconciled with the live forecast when the
     * conversation opened.
     *
     * @param picks        the earlier picks that are still in scope and pick-eligible, as
     *                     {@code (location, window)} pairs
     * @param droppedPicks the earlier picks that are not: passed, disabled, rated down or outside the
     *                     question's scope
     * @param events       the earlier events still flagged in scope, as the live forecast serves them
     *                     (label, date and safety note are the served ones)
     * @param turnedDown   the locations of every earlier pick, when the follow-up turns the earlier places
     *                     down ({@code ReadyIntentRules.turnsDownEarlierPlaces}); empty otherwise. The
     *                     validator drops any submitted pick at one of them, at every window
     * @param heldBack     the subset of {@code droppedPicks} dropped for one reason alone: the neighbour
     *                     guard marked the slot as rated far above the places beside it (its rating has
     *                     not fallen, so "no longer in the forecast" would be false)
     */
    public record ThreadFact(Set<Pair> picks, Set<Pair> droppedPicks, Set<EventFact> events,
            Set<Long> turnedDown, Set<Pair> heldBack) {

        /** No thread: nothing offered. */
        public static final ThreadFact NONE = new ThreadFact(Set.of(), Set.of(), Set.of(), Set.of(), Set.of());

        /** Why a stale-thread can't-answer is given: what became of the places the answer rested on. */
        public enum StaleKind {
            /** None is held back: they have left the forecast, been rated down or left the scope. */
            GONE,
            /** Every one is held back by the neighbour guard. */
            HELD_BACK,
            /** Some are held back and some are not. */
            MIXED
        }

        /** Canonical constructor: immutable copies, in order; null reads as empty. */
        public ThreadFact {
            picks = picks == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(picks));
            droppedPicks = droppedPicks == null ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(droppedPicks));
            events = events == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(events));
            turnedDown = turnedDown == null ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(turnedDown));
            heldBack = heldBack == null ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(heldBack));
        }

        /**
         * A thread fact with nothing held back by the neighbour guard.
         *
         * @param picks        the earlier picks still offered
         * @param droppedPicks the earlier picks that are not
         * @param events       the earlier events still flagged
         * @param turnedDown   the locations the follow-up turns down
         */
        public ThreadFact(Set<Pair> picks, Set<Pair> droppedPicks, Set<EventFact> events,
                Set<Long> turnedDown) {
            this(picks, droppedPicks, events, turnedDown, Set.of());
        }

        /**
         * A thread fact that turns nothing down.
         *
         * @param picks        the earlier picks still offered
         * @param droppedPicks the earlier picks that are not
         * @param events       the earlier events still flagged
         */
        public ThreadFact(Set<Pair> picks, Set<Pair> droppedPicks, Set<EventFact> events) {
            this(picks, droppedPicks, events, Set.of());
        }

        /**
         * Whether an answer rested only on places the thread named and has since lost: every pair the
         * model submitted is a dropped earlier pick, and there was something to submit (or no earlier
         * pick survives for the model to rank instead).
         *
         * @param submitted the pairs the model submitted
         * @return true when the stale-thread outcome applies
         */
        public boolean staleOnly(Set<Pair> submitted) {
            if (droppedPicks.isEmpty() || !droppedPicks.containsAll(submitted)) {
                return false;
            }
            return !submitted.isEmpty() || picks.isEmpty();
        }

        /**
         * Why the stale-thread can't-answer is given, for choosing its words: judged over the pairs the
         * model submitted, or over every dropped earlier pick when it submitted none.
         *
         * @param submitted the pairs the model submitted
         * @return {@link StaleKind#HELD_BACK} when every one is held back by the neighbour guard,
         *         {@link StaleKind#MIXED} when only some are, otherwise {@link StaleKind#GONE}
         */
        public StaleKind staleKind(Set<Pair> submitted) {
            Set<Pair> judged = submitted.isEmpty() ? droppedPicks : submitted;
            if (!judged.isEmpty() && heldBack.containsAll(judged)) {
                return StaleKind.HELD_BACK;
            }
            return judged.stream().anyMatch(heldBack::contains) ? StaleKind.MIXED : StaleKind.GONE;
        }
    }

    /**
     * A (location, window) pair a tool returned.
     *
     * @param locationId the location id
     * @param windowId   the window id
     */
    public record Pair(long locationId, String windowId) {
    }

    /**
     * An event a tool returned, with the label and date it was served under.
     *
     * @param type  the served type, upper-cased
     * @param label the served label or title
     * @param date  the served date
     * @param safetyNote the served warning that must accompany this event, or null
     */
    public record EventFact(String type, String label, LocalDate date, String safetyNote) {

        /**
         * An event with no served safety note.
         *
         * @param type  the served type, upper-cased
         * @param label the served label or title
         * @param date  the served date
         */
        public EventFact(String type, String label, LocalDate date) {
            this(type, label, date, null);
        }
    }

    /**
     * Whether the conversation made at least one tool call.
     *
     * @return true when any tool was called
     */
    public boolean anyToolCalled() {
        return toolCalls > 0;
    }
}
