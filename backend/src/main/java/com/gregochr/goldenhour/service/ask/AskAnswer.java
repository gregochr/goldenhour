package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * A validated answer.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)}: a stored Ready answer is read back by
 * {@link AskReadyStore}'s plain mapper, which throws on a property it does not know. A field added
 * after this one is read fail-soft by a build that has this annotation, so a rollback never drops a
 * stored row for a property it merely does not know (the build that introduced {@code windows} is the
 * first to tolerate; any earlier build still drops such a row until the next precompute rewrites it).
 *
 * @param answerable false when the forecast cannot answer; picks and events are then empty
 * @param summary    the cleaned, word-capped summary; never blank
 * @param picks      at most three picks, ranked
 * @param events     the event cards
 * @param missing    what PhotoCast does not have, when not answerable; otherwise null
 * @param windows    the rating distribution of every window whose figures the answer's prose may have
 *                   quoted: each window {@code list_windows} returned in the conversation, each pick's
 *                   solar window and, for a Ready question, each window the question covers. Built by
 *                   the validator from the live snapshot (never by the model), served beside the
 *                   summary and compared on every serve ({@link AskReadyFreshness}); empty on an answer
 *                   with none and on a row written before the field existed, which reads as "unknown,
 *                   not stale"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AskAnswer(boolean answerable, String summary, List<AskPick> picks,
        List<AskEvent> events, String missing, List<WindowSpread> windows) {

    /**
     * One carried window: its id and the whole spread the answer was given against.
     *
     * @param windowId the solar window id
     * @param spread   the window's rating distribution over the question's scope
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WindowSpread(String windowId, AskSnapshot.Spread spread) {
    }

    /** Canonical constructor: takes immutable copies; a null list reads as empty. */
    public AskAnswer {
        picks = picks == null ? List.of() : List.copyOf(picks);
        events = events == null ? List.of() : List.copyOf(events);
        windows = windows == null ? List.of() : List.copyOf(windows);
    }

    /**
     * An answer that carries no window figures.
     *
     * @param answerable false when the forecast cannot answer
     * @param summary    the cleaned summary
     * @param picks      the picks
     * @param events     the event cards
     * @param missing    what PhotoCast does not have, or null
     */
    public AskAnswer(boolean answerable, String summary, List<AskPick> picks, List<AskEvent> events,
            String missing) {
        this(answerable, summary, picks, events, missing, List.of());
    }

    /**
     * The same answer carrying the given window figures.
     *
     * @param newWindows the carried windows
     * @return a copy with {@code windows} replaced
     */
    public AskAnswer withWindows(List<WindowSpread> newWindows) {
        return new AskAnswer(answerable, summary, picks, events, missing, newWindows);
    }
}
