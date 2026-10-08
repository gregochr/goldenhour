package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.BestAnchor;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.Raw;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.Result;

import java.util.ArrayList;
import java.util.List;

/**
 * The frame every engine's conversation shares: what happens before the first tool call and after
 * the answer is submitted. It is a collaborator both engines hold, not a base class they extend, so
 * the Claude engine's SDK loop and the stub's keyword script stay what they are and cannot drift on
 * the parts that are not theirs.
 *
 * <p><b>Opening</b> ({@link #open}) is, in order: the options must match the kind of conversation
 * ({@link AskRunOptions#requireConsistentWith}, loud), a blank question is a FAILED run before any
 * tool exists, then the conversation's {@link AskTools} over the question's one scope, and which
 * events question it is ({@link ReadyIntentRules#eventsQuestion}). <b>Closing</b> is
 * {@link #submit} (validate the submitted answer, then OK, CANT or FAILED), {@link #rejectSubmission}
 * (a {@code submit_answer} that could not be read) and {@link #fail}, and every FAILED run reports
 * whether the asker's own drive times were used, as the tools saw it.
 *
 * <p>The validator decides everything: nothing a caller passes to {@link #submit} reaches a reader
 * without going through it.
 */
final class AskConversation {

    /**
     * What a conversation is built from, held once by each engine.
     *
     * @param validator  holds a submitted answer to what the tools returned
     * @param driveTimes the asker's own drive times, for {@code maxDriveMinutes}
     * @param mapper     serialises tool results
     */
    record Deps(AskAnswerValidator validator, DriveTimeResolver driveTimes, ObjectMapper mapper) {
    }

    /** How opening ended: a conversation to run, or a FAILED run that ends it before it starts. */
    sealed interface Opening {

        /**
         * A conversation ready to run.
         *
         * @param conversation the conversation
         */
        record Open(AskConversation conversation) implements Opening {
        }

        /**
         * A conversation refused before it began.
         *
         * @param run the FAILED run to return
         */
        record Refused(AskRun run) implements Opening {
        }
    }

    private final AskSnapshot snapshot;
    private final AskScope scope;
    private final AskRunOptions options;
    private final AskTools tools;
    private final ReadyQuestion eventsQuestion;
    private final AskAnswerValidator validator;

    private AskConversation(AskSnapshot snapshot, AskScope scope, AskRunOptions options, AskTools tools,
            ReadyQuestion eventsQuestion, AskAnswerValidator validator) {
        this.snapshot = snapshot;
        this.scope = scope;
        this.options = options;
        this.tools = tools;
        this.eventsQuestion = eventsQuestion;
        this.validator = validator;
    }

    /**
     * Opens a conversation.
     *
     * @param question the question, carrying its one resolved scope
     * @param snapshot the snapshot the conversation runs against
     * @param user     who is asking; a user-less context is a Ready conversation
     * @param options  what the caller adds beyond the question; null reads as none
     * @param deps     what the tools and the validator are built from
     * @return the conversation, or the FAILED run for a blank question
     * @throws IllegalArgumentException when the options do not match the conversation: a user-less
     *         (Ready) conversation needs a Ready job run and a typed one must not carry one. That is
     *         a caller bug, so it is loud rather than a quiet FAILED, and it is checked first
     */
    static Opening open(AskQuestion question, AskSnapshot snapshot, AskUserContext user,
            AskRunOptions options, Deps deps) {
        AskRunOptions opts = options == null ? AskRunOptions.none() : options;
        opts.requireConsistentWith(user);
        if (question.sanitised() == null || question.sanitised().isBlank()) {
            return new Opening.Refused(AskRun.failed("the question is empty", 0, false, List.of()));
        }
        AskScope scope = question.scope();
        AskTools tools = new AskTools(snapshot, user, scope, deps.driveTimes(), deps.mapper());
        ReadyQuestion eventsQuestion = ReadyIntentRules.eventsQuestion(question).orElse(null);
        return new Opening.Open(new AskConversation(snapshot, scope, opts, tools, eventsQuestion,
                deps.validator()));
    }

    /**
     * The conversation's tools, which hold what it has been told and whether it went personal.
     *
     * @return the tools
     */
    AskTools tools() {
        return tools;
    }

    /**
     * The question's scope, the one the tools and the validator share.
     *
     * @return the scope
     */
    AskScope scope() {
        return scope;
    }

    /**
     * The options this conversation runs under (never null).
     *
     * @return the options
     */
    AskRunOptions options() {
        return options;
    }

    /**
     * The Ready {@code BEST_*} rule, if this is such a question.
     *
     * @return the anchor, or null
     */
    BestAnchor anchor() {
        return options.anchor();
    }

    /**
     * Ends the conversation with the model's (or the script's) answer: records the synthetic
     * {@code submit_answer} call, holds the answer to the validator and returns OK, CANT or FAILED.
     *
     * @param raw   the submitted answer
     * @param turns how many model turns it took
     * @param trace the tool calls made so far, which are not changed
     * @return the run
     */
    AskRun submit(Raw raw, int turns, List<AskTools.ToolCall> trace) {
        List<AskTools.ToolCall> full = withSubmit(trace, false);
        Result result = validator.validate(raw, snapshot, tools.evidence(), scope, options.anchor(),
                eventsQuestion);
        if (!result.accepted()) {
            return fail("the answer was discarded: " + result.reason(), turns, full);
        }
        AskOutcome.Status status = result.answer().answerable() ? AskOutcome.Status.OK
                : AskOutcome.Status.CANT;
        return new AskRun(new AskOutcome(status, result.answer(), tools.personal(), turns), full, null);
    }

    /**
     * Ends the conversation with a {@code submit_answer} that could not be read: records it as an
     * errored call and fails.
     *
     * @param parseError why it is malformed
     * @param turns      how many model turns it took
     * @param trace      the tool calls made so far, which are not changed
     * @return the FAILED run
     */
    AskRun rejectSubmission(String parseError, int turns, List<AskTools.ToolCall> trace) {
        return fail("submit_answer was malformed: " + parseError, turns, withSubmit(trace, true));
    }

    /**
     * Ends the conversation with a failure, reporting whether the asker's own drive times were used.
     *
     * @param reason why it failed, for the log and the dry-run only
     * @param turns  how many model turns it took
     * @param trace  the tool calls made so far
     * @return the FAILED run
     */
    AskRun fail(String reason, int turns, List<AskTools.ToolCall> trace) {
        return AskRun.failed(reason, turns, tools.personal(), trace);
    }

    private static List<AskTools.ToolCall> withSubmit(List<AskTools.ToolCall> trace, boolean error) {
        List<AskTools.ToolCall> full = new ArrayList<>(trace);
        full.add(new AskTools.ToolCall(AskToolSchemas.SUBMIT_ANSWER, error, 0));
        return full;
    }
}
