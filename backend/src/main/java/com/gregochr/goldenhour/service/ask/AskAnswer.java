package com.gregochr.goldenhour.service.ask;

import java.util.List;

/**
 * A validated answer.
 *
 * @param answerable false when the forecast cannot answer; picks and events are then empty
 * @param summary    the cleaned, word-capped summary; never blank
 * @param picks      at most three picks, ranked
 * @param events     the event cards
 * @param missing    what PhotoCast does not have, when not answerable; otherwise null
 */
public record AskAnswer(boolean answerable, String summary, List<AskPick> picks,
        List<AskEvent> events, String missing) {

    /** Canonical constructor: takes immutable copies; a null list reads as empty. */
    public AskAnswer {
        picks = picks == null ? List.of() : List.copyOf(picks);
        events = events == null ? List.of() : List.copyOf(events);
    }
}
