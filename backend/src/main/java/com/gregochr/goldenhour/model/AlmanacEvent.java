package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One entry in the 90-day "Coming up" feed.
 *
 * <p><strong>Why this is not a {@link HotTopic}.</strong> A hot topic is a single date — it answers
 * "what is worth knowing about this day", and the Plan tab renders one card per day in date order.
 * An almanac entry is a <em>span</em>: a spring tide run is four days, an NLC season is eleven
 * weeks, and collapsing either into one row per day would bury a three-month feed under hundreds of
 * near-identical cards. So this carries {@code startDate} and {@code endDate} where {@code HotTopic}
 * carries {@code date}, and the two types are deliberately separate rather than one being projected
 * from the other.
 *
 * <p><strong>The degrade rule lives in {@code meta}.</strong> Dates come from ephemeris and are
 * always present. Numbers — a tidal range, a peak height, an alignment verdict — come from stored
 * data and may not exist for a date beyond whatever the tide fetch window last reached. When they
 * cannot be derived the key is simply absent. Nothing is ever synthesised to fill the gap, and a
 * consumer must render a bare date-and-title row rather than a placeholder figure.
 *
 * @param startDate first day of the span, inclusive — never null
 * @param endDate   last day of the span, inclusive; equal to {@code startDate} for a single-day
 *                  entry, never null and never before {@code startDate}
 * @param kind      whether the date is fixed by ephemeris or driven by a forecast
 * @param type      stable machine-readable discriminator, e.g. {@code spring-tide}, {@code meteor}.
 *                  Lets a client pick an icon or a colour without parsing the title
 * @param title     the short human label, e.g. {@code "Spring tide run"}
 * @param detail    one sentence of plain-language context
 * @param meta      derived facts, absent when they could not be derived — see the degrade rule above
 * @param regions   region names the entry applies to; empty means sky-wide rather than unknown
 */
public record AlmanacEvent(
        LocalDate startDate,
        LocalDate endDate,
        AlmanacKind kind,
        String type,
        String title,
        String detail,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, String> meta,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<String> regions) implements Comparable<AlmanacEvent> {

    /**
     * Normalises the collections so a consumer never has to null-check them, and rejects a span
     * that runs backwards.
     */
    public AlmanacEvent {
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(endDate, "endDate");
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException(
                    "endDate " + endDate + " is before startDate " + startDate);
        }
        meta = meta == null ? Map.of() : Map.copyOf(meta);
        regions = regions == null ? List.of() : List.copyOf(regions);
    }

    /**
     * Creates a single-day entry.
     *
     * @param date    the day
     * @param kind    certainty of the date
     * @param type    machine-readable discriminator
     * @param title   short human label
     * @param detail  one sentence of context
     * @param meta    derived facts, may be null
     * @param regions applicable regions, may be null
     * @return an entry whose span is that one day
     */
    public static AlmanacEvent onDay(LocalDate date, AlmanacKind kind, String type, String title,
            String detail, Map<String, String> meta, List<String> regions) {
        return new AlmanacEvent(date, date, kind, type, title, detail, meta, regions);
    }

    /**
     * Returns the span length in days, counting both ends.
     *
     * @return 1 for a single-day entry
     */
    public long dayCount() {
        return java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;
    }

    /**
     * Returns true when this entry carries no derived figures.
     *
     * <p>A consumer uses this to decide between a full row and a bare date-and-title one, rather
     * than probing individual keys and guessing which ones matter.
     *
     * @return true when {@code meta} is empty
     */
    public boolean isDatesOnly() {
        return meta.isEmpty();
    }

    /**
     * Orders by start date, then by end date so the shorter span of two entries starting on the
     * same day comes first, then by type so the order is total and the feed is stable across
     * rebuilds.
     *
     * @param other the entry to compare against
     * @return standard comparator contract
     */
    @Override
    public int compareTo(AlmanacEvent other) {
        int byStart = startDate.compareTo(other.startDate);
        if (byStart != 0) {
            return byStart;
        }
        int byEnd = endDate.compareTo(other.endDate);
        if (byEnd != 0) {
            return byEnd;
        }
        return String.valueOf(type).compareTo(String.valueOf(other.type));
    }

    /**
     * Builds a {@code meta} map from alternating key/value pairs, dropping any pair whose value is
     * null or blank.
     *
     * <p>This is the degrade rule made mechanical: a caller assembles every fact it might have and
     * the ones it could not derive fall out here, so no source has to write the same null-check
     * five times and none can accidentally emit an empty string as if it were a measurement.
     *
     * @param keyValuePairs alternating keys and values
     * @return an insertion-ordered map of the pairs that had values
     * @throws IllegalArgumentException if an odd number of arguments is supplied
     */
    public static Map<String, String> metaOf(String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "metaOf needs alternating key/value pairs, got " + keyValuePairs.length);
        }
        Map<String, String> built = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            String value = keyValuePairs[i + 1];
            if (value != null && !value.isBlank()) {
                built.put(keyValuePairs[i], value);
            }
        }
        return built;
    }
}
