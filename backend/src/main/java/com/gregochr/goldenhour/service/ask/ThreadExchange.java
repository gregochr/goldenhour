package com.gregochr.goldenhour.service.ask;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One earlier exchange of a conversation, as {@code POST /api/ask} carries it
 * ({@code docs/engineering/ask-thread-plan.md} §2.1): the reader's question, the words the reader was
 * shown, and <em>ids only</em> for the cards beside them. Every card fact is re-joined from the live
 * snapshot by whoever uses an exchange; the client's copy of one is never trusted.
 *
 * <p>The same record is the wire shape and, once {@link AskThreadValidation} has cleaned it, the
 * sanitised value the engines receive inside an {@link AskThread}. Its lists tolerate null entries
 * on purpose: a null entry is a 400 for the validation to refuse, not an exception in a constructor.
 *
 * @param question    the reader's question; re-sanitised with the live question's own rules
 * @param summary     the answer's summary: client-supplied text, never the server's
 * @param picks       the answer's picks as {@code (locationId, windowId)} pairs; null reads as empty
 * @param events      the answer's event cards as {@code (type, date)} pairs; null reads as empty
 * @param generatedAt the briefing {@code generatedAt} the answer was given against
 * @param ready       whether the exchange began as a Ready answer (absent reads as false), whose
 *                    {@code generatedAt} is the precompute's snapshot and is therefore not compared by
 *                    the reset rule
 */
public record ThreadExchange(String question, String summary, List<PickRef> picks, List<EventRef> events,
        LocalDateTime generatedAt, Boolean ready) {

    /**
     * Canonical constructor: unmodifiable copies; a null list reads as empty and an absent
     * {@code ready} as false. ({@code ready} is a wrapper because the JSON mapper reads an absent
     * primitive as a null it refuses, and a client that omits the flag has sent a typed exchange.)
     */
    public ThreadExchange {
        ready = Boolean.TRUE.equals(ready);
        picks = picks == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(picks));
        events = events == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(events));
    }

    /**
     * A pick the answer carried.
     *
     * @param locationId the location's id
     * @param windowId   the window ({@code yyyy-MM-dd_sunrise|sunset})
     */
    public record PickRef(Long locationId, String windowId) {
    }

    /**
     * An event card the answer carried.
     *
     * @param type the served event type, e.g. {@code KING_TIDE}
     * @param date the event's date
     */
    public record EventRef(String type, LocalDate date) {
    }
}
