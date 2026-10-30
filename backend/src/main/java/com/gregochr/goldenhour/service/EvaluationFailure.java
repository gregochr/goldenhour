package com.gregochr.goldenhour.service;

import com.anthropic.errors.AnthropicServiceException;
import com.gregochr.goldenhour.config.ClaudeRetryPredicate;
import com.gregochr.goldenhour.exception.ClaudeRefusalException;
import com.gregochr.goldenhour.exception.ClaudeReplyUnreadableException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

/**
 * The one place that names a "Claude evaluation failed" exception as a short {@code errorType} string.
 *
 * <p>{@link #errorTypeOf} names a thrown exception with the vocabulary {@code EvaluationResult.Errored}
 * already carried ({@code "anthropic_<status>"}, or the exception's simple name), plus named types for
 * the cases that used to be told apart only by their message text ({@value #TYPE_CONTENT_FILTER},
 * {@value #TYPE_REFUSAL}, {@value #TYPE_REPLY_UNREADABLE}), by a literal in another class
 * ({@value #TYPE_PARSE_ERROR}), or by a Resilience4j class name ({@value #TYPE_CIRCUIT_OPEN},
 * {@value #TYPE_BULKHEAD_FULL}). A synchronous call's failure is logged with this string as the
 * {@code api_call_log}'s {@code error_type}, beside {@link #httpStatusOf}'s status, so a failed row says
 * why.
 *
 * <p>This was an enum whose constants carried the admin-facing phrase for each kind; nothing read them
 * once the run panel took its text elsewhere, so only the classification and the shared constants remain.
 */
public final class EvaluationFailure {

    /** The phrase for a failure the code cannot classify (also the sweep's floor for a task left in EVALUATING). */
    public static final String FALLBACK_REASON = "Evaluation failed (see server log).";

    /** {@code errorType} for a content-filter 400. */
    public static final String TYPE_CONTENT_FILTER = "content_filter";

    /** {@code errorType} for a refusal stop reason. */
    public static final String TYPE_REFUSAL = "refusal";

    /** {@code errorType} for a truncated or empty reply. */
    public static final String TYPE_REPLY_UNREADABLE = "reply_unreadable";

    /** {@code errorType} for a call the circuit breaker refused. */
    public static final String TYPE_CIRCUIT_OPEN = "circuit_open";

    /** {@code errorType} for a call the bulkhead refused. */
    public static final String TYPE_BULKHEAD_FULL = "bulkhead_full";

    /** {@code errorType} for a reply the evaluation parser rejected. */
    public static final String TYPE_PARSE_ERROR = "parse_error";

    private static final String ANTHROPIC_PREFIX = "anthropic_";

    private EvaluationFailure() {
    }

    /**
     * Names a thrown exception as the {@code errorType} the evaluation engine reports.
     *
     * @param e what the Claude call or its reply handling threw
     * @return {@code "anthropic_<status>"} for an Anthropic service error (or {@value #TYPE_CONTENT_FILTER}
     *         for the content-filter 400), {@value #TYPE_REFUSAL} or {@value #TYPE_REPLY_UNREADABLE}
     *         for the two reply exceptions, otherwise the exception's simple name
     */
    public static String errorTypeOf(Throwable e) {
        if (e instanceof AnthropicServiceException svc) {
            return ClaudeRetryPredicate.isContentFilter(svc)
                    ? TYPE_CONTENT_FILTER
                    : ANTHROPIC_PREFIX + svc.statusCode();
        }
        if (e instanceof ClaudeRefusalException) {
            return TYPE_REFUSAL;
        }
        if (e instanceof ClaudeReplyUnreadableException) {
            return TYPE_REPLY_UNREADABLE;
        }
        if (e instanceof CallNotPermittedException) {
            return TYPE_CIRCUIT_OPEN;
        }
        if (e instanceof BulkheadFullException) {
            return TYPE_BULKHEAD_FULL;
        }
        return e.getClass().getSimpleName();
    }

    /**
     * The HTTP status Anthropic answered with, when the failure is an Anthropic service error.
     *
     * <p>{@code null} for everything that has no HTTP status of its own: the SDK's I/O failure (the
     * request never got an answer), a refusal or an unreadable reply (the HTTP call itself returned
     * 200, and a 200 on a failed row would read as a success), and a call a Resilience4j breaker or
     * bulkhead refused (nothing was sent). {@code null} is also what the batch path writes for its
     * failures, and what the column's own documentation says for a non-HTTP call.
     *
     * @param e what the Claude call or its reply handling threw
     * @return the HTTP status, or {@code null} when the failure carried none
     */
    public static Integer httpStatusOf(Throwable e) {
        return e instanceof AnthropicServiceException svc ? svc.statusCode() : null;
    }
}
