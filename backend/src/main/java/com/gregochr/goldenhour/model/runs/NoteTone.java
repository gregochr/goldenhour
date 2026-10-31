package com.gregochr.goldenhour.model.runs;

/**
 * How a Runs-list row's one-line note should be tinted.
 */
public enum NoteTone {

    /** Neutral information, such as what a running cycle is waiting on. */
    INFO,

    /** Something to look at: a partial or degraded outcome. */
    WARN,

    /** A failure. */
    ERROR
}
