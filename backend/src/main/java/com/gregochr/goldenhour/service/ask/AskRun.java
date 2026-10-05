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
