package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single "science showing" fact chip on a hot-topic pill's enriched second line.
 *
 * <p>Each fact is a compact, monospace chip carrying a measured quantity and its context — for
 * example {@code high water 5.8 m}, {@code +0.7 m over spring}, or {@code waves 4.2 m · very rough}.
 * The frontend renders the pieces distinctly: {@code key} as a muted lead-in label, {@code value}
 * as the chip body (bold in the ink colour when {@code emphasis} is set), and {@code dir} as an
 * accent-coloured direction or imperative (a compass point gains a look-direction arrow glyph).
 *
 * <p><strong>Anomaly-first:</strong> a figure should always be paired with its "vs normal" so it
 * reads as a fact, not a bare number — e.g. a tide height beside its spring-tide baseline, or a
 * wave height beside its sea-state band. Formatting stays in the view layer; this record carries
 * only the pieces.
 *
 * <p>{@code optional} marks the topic's least-critical chip: on narrow (mobile) viewports it is
 * dropped so the line stays to the headline metric plus the "where to look" note. The headline
 * metric and the note are never marked optional.
 *
 * @param key      optional muted lead-in label (e.g. {@code "high water"}, {@code "Kp"}); may be null
 * @param value    the chip body text — the measured quantity and any inline context (required)
 * @param dir      optional accent-coloured direction or imperative (e.g. {@code "S"}, {@code "NE"},
 *                 {@code "get above it"}); a compass point is prefixed with a look arrow by the view;
 *                 may be null
 * @param emphasis whether {@code value} renders bold in the ink colour (the headline quantity) rather
 *                 than in the muted secondary colour
 * @param optional whether this chip is dropped on narrow viewports (the topic's least-critical fact)
 */
public record HotTopicFact(
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String key,
        String value,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String dir,
        boolean emphasis,
        boolean optional) {

    /**
     * Canonical constructor with Jackson bindings so cached briefing JSON round-trips.
     *
     * @param key      optional muted lead-in label; may be null
     * @param value    the chip body text (required)
     * @param dir      optional accent direction/imperative; may be null
     * @param emphasis whether {@code value} renders bold
     * @param optional whether the chip drops on narrow viewports
     */
    @JsonCreator
    public HotTopicFact(
            @JsonProperty("key") String key,
            @JsonProperty("value") String value,
            @JsonProperty("dir") String dir,
            @JsonProperty("emphasis") boolean emphasis,
            @JsonProperty("optional") boolean optional) {
        this.key = key;
        this.value = value;
        this.dir = dir;
        this.emphasis = emphasis;
        this.optional = optional;
    }

    /**
     * A headline metric chip: a muted key with an emphasised (bold) value.
     *
     * @param key   the muted lead-in label
     * @param value the emphasised quantity
     * @return an emphasised, non-optional fact with no direction
     */
    public static HotTopicFact metric(String key, String value) {
        return new HotTopicFact(key, value, null, true, false);
    }

    /**
     * A plain context chip: a value in the muted secondary colour, no key, no emphasis.
     *
     * @param value the context text (e.g. {@code "+0.7 m over spring"})
     * @return a non-emphasised, keyless, non-optional fact
     */
    public static HotTopicFact context(String value) {
        return new HotTopicFact(null, value, null, false, false);
    }

    /**
     * A directional chip: a value trailed by an accent-coloured direction or imperative.
     *
     * @param key      optional muted lead-in label; may be null
     * @param value    the chip body text
     * @param dir      the accent direction/imperative
     * @param emphasis whether {@code value} renders bold
     * @return a non-optional fact carrying a direction
     */
    public static HotTopicFact directional(String key, String value, String dir, boolean emphasis) {
        return new HotTopicFact(key, value, dir, emphasis, false);
    }

    /**
     * Returns a copy of this fact marked optional — dropped on narrow viewports.
     *
     * @return an optional copy of this fact
     */
    public HotTopicFact asOptional() {
        return new HotTopicFact(key, value, dir, emphasis, true);
    }
}
