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

    /** Canonical constructor: takes an immutable copy of the trace. */
    public AskRun {
        trace = trace == null ? List.of() : List.copyOf(trace);
    }
}
