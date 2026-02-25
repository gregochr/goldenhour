package com.gregochr.goldenhour.entity;

/**
 * Scheduled job names tracked in the metrics system.
 */
public enum JobName {
    /** Sonnet (dual 0–100 score) evaluation job — runs every 6 h. */
    SONNET,

    /** Haiku (1–5 rating) evaluation job — runs every 12 h. */
    HAIKU,

    /** Wildlife (comfort-only) evaluation job — runs every 12 h. */
    WILDLIFE,

    /** Tide extremes refresh job — runs weekly. */
    TIDE
}
