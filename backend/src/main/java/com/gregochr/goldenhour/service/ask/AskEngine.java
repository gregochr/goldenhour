package com.gregochr.goldenhour.service.ask;

/**
 * Answers one Ask question against one snapshot (plan §2.3).
 *
 * <p>The Claude implementation is {@link ClaudeAskEngine}; a stub that makes no client call is
 * selected by {@code photocast.ask.stub} (B2b). Implementations never throw for a bad model reply,
 * a timeout or a refusal: every way a run can go wrong is a {@link AskOutcome.Status#FAILED}
 * outcome, so the caller's only job on failure is to refund the question.
 */
public interface AskEngine {

    /**
     * Runs one conversation and reports how it went, with the tool trace.
     *
     * @param question the sanitised question, its scope and its context window
     * @param snapshot the snapshot the tools and the validator read
     * @param user     who is asking; {@link AskUserContext#userLess()} for a Ready conversation
     * @param options  the Ready {@code BEST_*} anchor and the Ready job run, both optional
     * @return the outcome and the trace; never null
     */
    AskRun run(AskQuestion question, AskSnapshot snapshot, AskUserContext user,
            AskRunOptions options);

    /**
     * Runs one conversation with no anchor, as a typed question is.
     *
     * @param question the sanitised question
     * @param snapshot the snapshot
     * @param user     who is asking
     * @return the outcome
     */
    default AskOutcome answer(AskQuestion question, AskSnapshot snapshot, AskUserContext user) {
        return run(question, snapshot, user, AskRunOptions.none()).outcome();
    }
}
