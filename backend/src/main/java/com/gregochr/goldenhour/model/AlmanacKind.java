package com.gregochr.goldenhour.model;

/**
 * How certain an almanac entry is, and therefore how far ahead it can be trusted.
 *
 * <p>This is the tab's entire tag vocabulary — two words, deliberately. It is the same axis as the
 * frontend's {@code topicCertainty} chip on the Plan tab, narrowed to the two values a 90-day feed
 * can actually carry: there is no "chance" here, because an unforecastable display cannot be
 * scheduled months out and so never earns a row of its own.
 */
public enum AlmanacKind {

    /**
     * Fixed by ephemeris. A date arrived at by arithmetic over the solar and lunar cycles, correct
     * as far ahead as it is asked for. Tide runs, meteor peaks, supermoons, equinoxes, solstices
     * and season bounds are all this.
     *
     * <p>Note that an ALMANAC entry may still carry numbers it could not derive — a tide run beyond
     * the stored-extremes window keeps its dates and loses its heights. The kind describes the
     * <em>date</em>, not the completeness of the detail.
     */
    ALMANAC,

    /**
     * Weather-driven, and therefore only meaningful a few days out. Reserved for entries the
     * existing ~3-day hot-topic path contributes; nothing in the 90-day feed is currently this,
     * and the value exists so a caller never has to infer certainty from a topic type.
     */
    FORECAST
}
