package com.gregochr.goldenhour.service.ask;

import java.util.List;

/**
 * An engine run: the outcome, the tool trace for the admin dry-run, and why it failed.
 *
 * @param outcome the result
 * @param trace   every tool call the model made, in order, errors included
 * @param reason  why the run FAILED, for the log and the dry-run only (never shown to a reader), or
 *                null when it did not fail
 */
public record AskRun(AskOutcome outcome, List<AskTools.ToolCall> trace, String reason) {

    /**
     * The {@code reason} of a run that made no further model call because a paid call's cost could not
     * be recorded (see {@link AskJobRunService}). B4 maps it to 503 {@code TYPED_UNAVAILABLE}, not to
     * 502 {@code ENGINE_FAILED}: the question is refused, not broken, and no question was spent.
     */
    public static final String ACCOUNTING_UNAVAILABLE =
            "accounting unavailable: a model call's cost could not be recorded";

    /**
     * A run that failed. The one place a FAILED outcome is built, so every engine and the service's
     * own "the engine threw" stand-in say it the same way.
     *
     * @param reason   why it failed, for the log and the dry-run only
     * @param turns    how many model turns it took
     * @param personal whether the conversation had used the asker's own drive times
     * @param trace    the tool calls made before it failed
     * @return the FAILED run
     */
    public static AskRun failed(String reason, int turns, boolean personal, List<AskTools.ToolCall> trace) {
        return new AskRun(new AskOutcome(AskOutcome.Status.FAILED, null, personal, turns), trace, reason);
    }

    /**
     * Whether this run stopped because accounting is unavailable.
     *
     * @return true when {@code reason} is {@link #ACCOUNTING_UNAVAILABLE}
     */
    public boolean accountingUnavailable() {
        return ACCOUNTING_UNAVAILABLE.equals(reason);
    }

    /** Canonical constructor: takes an immutable copy of the trace. */
    public AskRun {
        trace = trace == null ? List.of() : List.copyOf(trace);
    }
}
