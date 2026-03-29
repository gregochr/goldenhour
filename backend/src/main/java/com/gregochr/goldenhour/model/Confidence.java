package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Confidence level for a best-bet photography recommendation.
 */
public enum Confidence {

    HIGH("high"),
    MEDIUM("medium"),
    LOW("low");

    private final String value;

    Confidence(String value) {
        this.value = value;
    }

    /**
     * Returns the lowercase JSON value for serialization.
     *
     * @return lowercase confidence string
     */
    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * Parses a confidence string (case-insensitive) into a {@link Confidence} enum.
     * Returns {@link #MEDIUM} if the input is null or unrecognised.
     *
     * @param text the confidence string
     * @return the parsed confidence level
     */
    public static Confidence fromString(String text) {
        if (text == null) {
            return MEDIUM;
        }
        for (Confidence c : values()) {
            if (c.value.equalsIgnoreCase(text)) {
                return c;
            }
        }
        return MEDIUM;
    }
}
