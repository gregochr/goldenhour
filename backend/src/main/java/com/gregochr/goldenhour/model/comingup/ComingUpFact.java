package com.gregochr.goldenhour.model.comingup;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * One ordered fact row on a chronology entry (plan §13) — the server-authored replacement for
 * {@code comingUpFeed.factsFor}'s client-side derivation.
 *
 * <p>No HTML crosses the wire. The design's {@code <b>}/{@code <em>} emphasis becomes a
 * {@link Segment#tone()} the client maps to its own styling, so the server states meaning
 * ("this clause is the topic's own colour") rather than markup.
 *
 * @param segments the row's text, in display order
 * @param kind     in-memory only ({@code @JsonIgnore}, never on the wire): {@link Kind#EVENT_WATER} marks
 *                 a sunrise or sunset high-water row, which states where the water falls against the
 *                 light and is Pro and Admin only (tide-light-pro-plan.md §3 B2); null for every other
 *                 row. A missing kind (a JSON-reloaded fact) is read as "not a light row", so the
 *                 shaper never guesses from text
 */
public record ComingUpFact(List<Segment> segments, @JsonIgnore Kind kind) {

    /** What a fact row is about, for the role shaper only. */
    public enum Kind {
        /** A sunrise or sunset high-water row: the water against the light (Pro and Admin). */
        EVENT_WATER
    }

    /** Normalises {@code segments} so a consumer never has to null-check it. */
    public ComingUpFact {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    /**
     * Builds an ordinary fact row (no {@link #kind}).
     *
     * @param segments the row's text, in display order
     */
    public ComingUpFact(List<Segment> segments) {
        this(segments, null);
    }

    /**
     * One run of text within a fact row.
     *
     * @param text the segment's text
     * @param tone {@code base} | {@code strong} | {@code accent} — {@code accent} is the design's
     *             {@code <em>}, rendered in the entry's own topic colour
     */
    public record Segment(String text, String tone) {
    }
}
