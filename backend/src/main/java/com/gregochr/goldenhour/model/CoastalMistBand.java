package com.gregochr.goldenhour.model;

/**
 * The coastal mist band for an event slot at a coastal, non-{@code mist_prone} site
 * (coastal-mist-plan Phase 1), banded by {@code CoastalMistCalculator}.
 *
 * <p>Stored as a plain string on {@code slot_atmosphere.coastal_mist_band} (no {@code @Enumerated}, so no
 * {@code LocalH2EnumWidener} entry), the {@code mist_potential} convention.
 */
public enum CoastalMistBand {

    /** No coastal mist signal: clear air, or not enough to say. Never shown to the model. */
    NONE,

    /** Event-slot visibility 5 to 10 km with near-saturated air: a light sea mist. The only band shown. */
    LIGHT,

    /**
     * Event-slot visibility under 5 km. Stored and logged for tuning only: triage already stands these slots
     * down, and "fret versus thick mist" is not separable below 3 km on the evidence.
     */
    THICK;

    /**
     * Parses a stored band name, tolerating a null or unknown one.
     *
     * @param name the {@code slot_atmosphere.coastal_mist_band} value, or null
     * @return the band, or null for a null or unrecognised name
     */
    public static CoastalMistBand parse(String name) {
        if (name == null) {
            return null;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
