package com.gregochr.goldenhour.model;

/**
 * Heuristic rules applied during weather triage to skip obviously unsuitable conditions.
 */
public enum TriageRule {

    /** Solar low cloud cover exceeds 80%, blocking the sun completely. */
    HIGH_CLOUD,

    /** Precipitation exceeds 2 mm, indicating active rain or heavy drizzle. */
    PRECIPITATION,

    /** Visibility below 5 km, typically fog or heavy haze. */
    LOW_VISIBILITY,

    /** Tide alignment check: preferred tide type does not occur within the golden/blue hour window. */
    TIDE_MISALIGNED
}
