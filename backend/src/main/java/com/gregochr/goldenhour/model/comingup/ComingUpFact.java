package com.gregochr.goldenhour.model.comingup;

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
 */
public record ComingUpFact(List<Segment> segments) {

    /** Normalises {@code segments} so a consumer never has to null-check it. */
    public ComingUpFact {
        segments = segments == null ? List.of() : List.copyOf(segments);
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
