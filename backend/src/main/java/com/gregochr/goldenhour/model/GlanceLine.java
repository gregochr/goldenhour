package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The Coming up strip's chip for one hot topic (coming-up-glance plan §2).
 *
 * @param value      desktop second line, e.g. {@code "HW 16:02 · +0.72 m"}; null = name and badge only
 * @param shortValue phone second line, e.g. {@code "+0.72 m pk"}; null = name only
 * @param badge      {@code "PEAK"}, {@code "FULL"} or null
 * @param weather    true when the topic is weather-driven (only knowable from the forecast)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GlanceLine(
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String value,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String shortValue,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String badge,
        boolean weather) {

    /** Badge on the storm surge or tide day that is the run's peak. */
    public static final String BADGE_PEAK = "PEAK";

    /** Badge on the supermoon's full-moon date. */
    public static final String BADGE_FULL = "FULL";

    /**
     * Canonical constructor with Jackson annotations.
     *
     * @param value      desktop second line, or null
     * @param shortValue phone second line, or null
     * @param badge      badge text, or null
     * @param weather    whether the topic is weather-driven
     */
    @JsonCreator
    public GlanceLine(
            @JsonProperty("value") String value,
            @JsonProperty("shortValue") String shortValue,
            @JsonProperty("badge") String badge,
            @JsonProperty("weather") boolean weather) {
        this.value = value;
        this.shortValue = shortValue;
        this.badge = badge;
        this.weather = weather;
    }
}
