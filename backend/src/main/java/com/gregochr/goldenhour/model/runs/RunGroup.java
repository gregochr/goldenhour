package com.gregochr.goldenhour.model.runs;

import java.util.Locale;

/**
 * The server-assigned group a Runs-list row belongs to. Doubles as the {@code kind} filter, where an
 * absent filter (the {@code ALL} chip) means every group.
 */
public enum RunGroup {

    /** Pipeline runs (overnight, afternoon and evening cycles). */
    CYCLES,

    /** Top-level forecast Anthropic batch runs. */
    BATCHES,

    /** Tide data refreshes. */
    TIDE,

    /** Daily briefing builds. */
    BRIEFING,

    /** Aurora evaluations and aurora Anthropic batches. */
    AURORA,

    /** Everything else, including run types added later. */
    OTHER;

    /** The request value meaning "no group filter". */
    public static final String ALL = "ALL";

    /**
     * Parses a {@code kind} request value.
     *
     * @param value the request value, case-insensitive; {@code ALL} or blank means no filter
     * @return the group, or {@code null} for no filter
     * @throws IllegalArgumentException if the value names no group
     */
    public static RunGroup parseKind(String value) {
        if (value == null || value.isBlank() || ALL.equalsIgnoreCase(value.trim())) {
            return null;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
