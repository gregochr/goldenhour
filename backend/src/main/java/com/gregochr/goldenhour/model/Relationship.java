package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Describes how a rank-2 best-bet pick relates to the rank-1 pick.
 *
 * <ul>
 *   <li>{@link #SAME_SLOT} — same date and event type, different region (Tier 1)</li>
 *   <li>{@link #DIFFERENT_SLOT} — differs by date, event type, or both (Tier 2)</li>
 * </ul>
 */
public enum Relationship {

    SAME_SLOT("SAME_SLOT"),
    DIFFERENT_SLOT("DIFFERENT_SLOT");

    private final String value;

    Relationship(String value) {
        this.value = value;
    }

    /**
     * Returns the JSON value for serialization.
     *
     * @return relationship string
     */
    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * Parses a relationship string (case-insensitive).
     * Returns {@code null} if the input is null or unrecognised.
     *
     * @param text the relationship string
     * @return the parsed relationship, or null
     */
    public static Relationship fromString(String text) {
        if (text == null) {
            return null;
        }
        for (Relationship r : values()) {
            if (r.value.equalsIgnoreCase(text)) {
                return r;
            }
        }
        return null;
    }
}
