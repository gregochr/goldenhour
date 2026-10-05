package com.gregochr.goldenhour.service.ask;

import java.time.LocalDate;
import java.util.Set;

/**
 * What a conversation's tools actually returned — the only things an answer may name (plan §1 #5).
 *
 * @param pairs     the (location, window) pairs {@code rank_spots} returned
 * @param events    the events {@code get_hot_topics} and {@code get_coming_up} returned
 * @param toolCalls how many tool calls the conversation made (errors included)
 */
public record AskEvidence(Set<Pair> pairs, Set<EventFact> events, int toolCalls) {

    /** Canonical constructor: takes immutable copies; a null set reads as empty. */
    public AskEvidence {
        pairs = pairs == null ? Set.of() : Set.copyOf(pairs);
        events = events == null ? Set.of() : Set.copyOf(events);
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
