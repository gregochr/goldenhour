package com.gregochr.goldenhour.entity;

/**
 * Scheduled job names tracked in the metrics system.
 */
public enum JobName {
    /** Sonnet (dual 0–100 score) evaluation job — runs every 6 h. */
    SONNET,

    /** Opus (dual 0–100 score, highest accuracy) evaluation job. */
    OPUS,

    /** Haiku (1–5 rating) evaluation job — runs every 12 h. */
    HAIKU,

    /** Weather (comfort-only meteorological data) evaluation job — runs every 12 h. */
    WEATHER,

    /** Tide extremes refresh job — runs weekly. */
    TIDE
}
