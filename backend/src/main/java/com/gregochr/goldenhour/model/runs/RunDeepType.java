package com.gregochr.goldenhour.model.runs;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.RunType;

/**
 * The seven types the Runs "Day" view tells apart, served as a key with the label the server chose.
 *
 * <p>The mapping from the stored {@link RunType} and {@link CycleType} is total: the switches below have
 * no default branch, so adding a run type without deciding which display type it is stops the build.
 */
public enum RunDeepType {

    /** The nightly forecast cycle. */
    OVERNIGHT("Overnight cycle"),

    /** The intraday (sun-relative) forecast cycle. */
    AFTERNOON("Afternoon refresh"),

    /** The evening refresh cycle. */
    EVENING("Evening refresh"),

    /** The Ask PhotoCast ready-answer precompute. */
    ASK_READY("Ask ready answers"),

    /** The daily briefing build. */
    BRIEFING("Daily briefing"),

    /** A sky-rating eval invocation (the test harness; it writes no job run). */
    SKY_EVAL("Sky eval"),

    /** Every other job: tides, wildlife and astro conditions, aurora, light pollution, Ask, ad-hoc batches. */
    OTHER("Other jobs");

    private final String label;

    RunDeepType(String label) {
        this.label = label;
    }

    /**
     * The label the server serves for this type.
     *
     * @return the label
     */
    public String label() {
        return label;
    }

    /**
     * Whether this type is one of the three forecast cycles.
     *
     * @return true for OVERNIGHT, AFTERNOON and EVENING
     */
    public boolean isCycle() {
        return this == OVERNIGHT || this == AFTERNOON || this == EVENING;
    }

    /**
     * The display type of a job run. A SCHEDULED_BATCH reaching here belongs to no cycle (a cycle's batch
     * runs are folded into the cycle), so it is an aurora or JFDI batch: OTHER.
     *
     * @param runType the stored run type
     * @return the display type
     */
    public static RunDeepType of(RunType runType) {
        return switch (runType) {
            case BRIEFING -> BRIEFING;
            case ASK_READY -> ASK_READY;
            // BATCH_NEAR_TERM, BATCH_FAR_TERM, BRIEFING_GLOSS, BRIEFING_BEST_BET, AURORA_GLOSS and
            // BLUEBELL_GLOSS are model_selection keys, never written as job runs: listed so the switch is total.
            case SCHEDULED_BATCH, TIDE, WEATHER, LIGHT_POLLUTION, AURORA_EVALUATION, ASK,
                 BATCH_NEAR_TERM, BATCH_FAR_TERM, BRIEFING_BEST_BET, BRIEFING_GLOSS, AURORA_GLOSS,
                 BLUEBELL_GLOSS -> OTHER;
        };
    }

    /**
     * The display type of a cycle.
     *
     * @param cycleType the stored cycle type
     * @return the display type
     */
    public static RunDeepType ofCycle(CycleType cycleType) {
        return switch (cycleType) {
            case NIGHTLY -> OVERNIGHT;
            case INTRADAY -> AFTERNOON;
            case EVENING -> EVENING;
        };
    }
}
