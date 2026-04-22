package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Dimension in which a rank-2 best-bet pick differs from rank 1.
 */
public enum DiffersBy {

    DATE("DATE"),
    EVENT("EVENT"),
    REGION("REGION");

    private final String value;

    DiffersBy(String value) {
        this.value = value;
    }

    /**
     * Returns the JSON value for serialization.
     *
     * @return differs-by dimension string
     */
    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * Parses a differs-by string (case-insensitive).
     * Returns {@code null} if the input is null or unrecognised.
     *
     * @param text the differs-by string
     * @return the parsed dimension, or null
     */
    public static DiffersBy fromString(String text) {
        if (text == null) {
            return null;
        }
        for (DiffersBy d : values()) {
            if (d.value.equalsIgnoreCase(text)) {
                return d;
            }
        }
        return null;
    }
}
