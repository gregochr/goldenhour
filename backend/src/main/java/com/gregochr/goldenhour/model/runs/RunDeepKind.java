package com.gregochr.goldenhour.model.runs;

/**
 * What a Day-view row is backed by, which decides the prefix of its id and where its detail lives.
 */
public enum RunDeepKind {

    /** A pipeline run (id {@code cycle-N}). */
    CYCLE,

    /** A top-level job run (id {@code job-N}). */
    JOB,

    /** A sky-rating eval invocation (id {@code eval-N}). */
    SKY_EVAL,

    /** A scheduler fire still to come (id {@code sched-...}). */
    SCHEDULED
}
