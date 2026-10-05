package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.service.ask.AskAnswerValidator.BestAnchor;

/**
 * What a caller may add to one engine run beyond the question.
 *
 * <p>A typed question (a user, no options) is billed to the day's {@code ASK} job run. A Ready
 * precompute conversation (no user) is billed to the {@code ASK_READY} run its caller started, so
 * the two never mix: the typed spend cap counts {@code ASK} runs only. The two are told apart by
 * whether the conversation has a user, and the engine refuses a mismatch rather than guess which
 * ledger a call belongs on.
 *
 * @param anchor        the Ready {@code BEST_*} rule, or null for every other question; supplied by
 *                      the Ready service, never invented by the engine
 * @param readyJobRunId the {@code ASK_READY} job run to log this conversation's calls against;
 *                      required for a user-less conversation and forbidden for a typed one
 */
public record AskRunOptions(BestAnchor anchor, Long readyJobRunId) {

    /**
     * No anchor and no Ready run: a typed question.
     *
     * @return the options of a typed question
     */
    public static AskRunOptions none() {
        return new AskRunOptions(null, null);
    }

    /**
     * Checks that these options belong to this kind of conversation: a user-less (Ready)
     * conversation needs the Ready job run to bill and a typed one must not carry one. Shared by
     * every engine, so the stub refuses exactly what the Claude engine refuses and a caller bug
     * shows up locally rather than in production.
     *
     * @param user who is asking
     * @throws IllegalArgumentException on a mismatch: a caller bug, so loud rather than a quiet
     *         FAILED
     */
    public void requireConsistentWith(AskUserContext user) {
        boolean ready = !user.hasUser();
        if (ready && readyJobRunId == null) {
            throw new IllegalArgumentException(
                    "a user-less (Ready) conversation needs the ASK_READY job run to bill");
        }
        if (!ready && readyJobRunId != null) {
            throw new IllegalArgumentException("a typed conversation is billed to the daily ASK run, "
                    + "not to a Ready run");
        }
    }

    /**
     * A Ready precompute conversation.
     *
     * @param readyJobRunId the {@code ASK_READY} run to bill
     * @param anchor        the {@code BEST_*} rule, or null
     * @return the options
     */
    public static AskRunOptions ready(long readyJobRunId, BestAnchor anchor) {
        return new AskRunOptions(anchor, readyJobRunId);
    }
}
