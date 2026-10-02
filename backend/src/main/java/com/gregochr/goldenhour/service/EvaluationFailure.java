package com.gregochr.goldenhour.service;

import com.anthropic.errors.AnthropicServiceException;
import com.gregochr.goldenhour.config.ClaudeRetryPredicate;
import com.gregochr.goldenhour.exception.ClaudeRefusalException;
import com.gregochr.goldenhour.exception.ClaudeReplyUnreadableException;
import com.gregochr.goldenhour.exception.EvaluationFailedException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

/**
 * The one mapping from "a Claude evaluation failed" to the fixed phrase an admin reads on the run
 * panel, the map popup and the completion banner.
 *
 * <p>Two steps, both here so nothing else classifies a failure:
 * <ol>
 *   <li>{@link #errorTypeOf} names a thrown exception as a short {@code errorType} string — the
 *       vocabulary {@code EvaluationResult.Errored} already carried ({@code "anthropic_<status>"}, or
 *       the exception's simple name), plus named types for the cases that used to be told apart only
 *       by their message text ({@value #TYPE_CONTENT_FILTER}, {@value #TYPE_REFUSAL},
 *       {@value #TYPE_REPLY_UNREADABLE}), by a literal in another class ({@value #TYPE_PARSE_ERROR}),
 *       or by a Resilience4j class name ({@value #TYPE_CIRCUIT_OPEN}, {@value #TYPE_BULKHEAD_FULL}).
 *       The string is never persisted for a synchronous call (it is not a column of
 *       {@code api_call_log} on that path) and is read only by this class.</li>
 *   <li>{@link #fromErrorType} maps that string to one constant, which owns its phrase.</li>
 * </ol>
 *
 * <p>What the code can tell apart, and what it cannot: the HTTP status of an Anthropic error (401 and
 * 403 together, 429, 529, and every other 5xx), the SDK's I/O exception (a timeout or a refused
 * connection, which the SDK does not distinguish from each other), a refusal stop reason, a content
 * filter 400, a truncated or empty reply, and a reply the parser rejected. It cannot tell a timeout
 * from a dropped connection, an authentication failure from a revoked key, or a spent credit balance
 * from a rejected request. The two Resilience4j refusals, where no call was made at all, are their
 * own kinds ({@link #CIRCUIT_OPEN}, {@link #BULKHEAD_FULL}). Anything unrecognised is {@link #UNKNOWN}.
 *
 * <p>The phrases are fixed text, never an exception message or class name: they reach every browser
 * subscribed to the run, and an SDK message can carry a response body.
 */
public enum EvaluationFailure {

    /** HTTP 401 or 403: the key is invalid, revoked or not permitted. Every further call would fail the same way. */
    KEY_REJECTED("Claude rejected the API key."),

    /** HTTP 429. */
    RATE_LIMITED("Claude's rate limit was reached."),

    /** HTTP 529. */
    OVERLOADED("Claude was overloaded."),

    /** Any other 5xx. */
    SERVER_ERROR("Claude returned a server error."),

    /** The SDK's I/O exception: a timeout or a connection failure. */
    UNREACHABLE("Claude could not be reached."),

    /** A reply that was cut short, empty, or that the parser could not read. */
    UNREADABLE("Claude's reply could not be read."),

    /** A refusal stop reason, or the content-filter 400. */
    DECLINED("Claude declined to evaluate this place."),

    /**
     * The {@code anthropic} circuit breaker refused the call (open after repeated failures, or half-open
     * with its permits taken): no request was made. Does not stop the run.
     */
    CIRCUIT_OPEN("Not attempted: Claude calls are paused after repeated failures. Try again in a minute."),

    /**
     * The {@code claude} bulkhead refused the call after its wait: no request was made. Does not stop
     * the run.
     */
    BULKHEAD_FULL("Not attempted: too many Claude calls were already waiting."),

    /** Anything not recognised above. */
    UNKNOWN(EvaluationFailure.FALLBACK_REASON);

    /** The phrase for a failure the code cannot classify (also the sweep's floor for a task left in EVALUATING). */
    public static final String FALLBACK_REASON = "Evaluation failed (see server log).";

    /** Reason on a place the run never attempted because the key was rejected earlier in the run. */
    public static final String REASON_NOT_ATTEMPTED =
            "Not attempted: the run stopped because Claude rejected the API key.";

    /** Run-level reason when the run was stopped on a rejected key. */
    public static final String REASON_RUN_STOPPED =
            "Claude rejected the API key. The run was stopped; no further places were attempted.";

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
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_RATE_LIMITED = 429;
    private static final int HTTP_OVERLOADED = 529;
    private static final int HTTP_SERVER_ERROR_FIRST = 500;
    private static final int HTTP_SERVER_ERROR_LAST = 599;

    /** How far down a cause chain {@link #of} looks (it also bounds a cyclic chain). */
    private static final int MAX_CAUSE_DEPTH = 5;

    private final String reason;

    EvaluationFailure(String reason) {
        this.reason = reason;
    }

    /**
     * The fixed phrase for this failure.
     *
     * @return the reason shown to the admin
     */
    public String reason() {
        return reason;
    }

    /**
     * Whether this failure means every further Claude call in the run would fail the same way, so
     * the run should stop.
     *
     * @return {@code true} only for {@link #KEY_REJECTED}
     */
    public boolean stopsRun() {
        return this == KEY_REJECTED;
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
     * Maps an {@code errorType} string to a failure kind.
     *
     * @param errorType the engine's short classification; may be null
     * @return the matching kind, {@link #UNKNOWN} when it is not recognised
     */
    public static EvaluationFailure fromErrorType(String errorType) {
        if (errorType == null) {
            return UNKNOWN;
        }
        if (errorType.startsWith(ANTHROPIC_PREFIX)) {
            return fromStatus(errorType.substring(ANTHROPIC_PREFIX.length()));
        }
        return switch (errorType) {
            case TYPE_CONTENT_FILTER, TYPE_REFUSAL -> DECLINED;
            case TYPE_PARSE_ERROR, TYPE_REPLY_UNREADABLE -> UNREADABLE;
            case TYPE_CIRCUIT_OPEN -> CIRCUIT_OPEN;
            case TYPE_BULKHEAD_FULL -> BULKHEAD_FULL;
            case "AnthropicIoException" -> UNREACHABLE;
            default -> UNKNOWN;
        };
    }

    /**
     * Classifies what a failed evaluation threw, looking down its cause chain for the first thing
     * that is recognised.
     *
     * @param failure the exception that ended the evaluation
     * @return the matching kind, {@link #UNKNOWN} when nothing in the chain is recognised
     */
    public static EvaluationFailure of(Throwable failure) {
        Throwable t = failure;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
            String errorType = t instanceof EvaluationFailedException failed
                    ? failed.getErrorType() : errorTypeOf(t);
            EvaluationFailure kind = fromErrorType(errorType);
            if (kind != UNKNOWN) {
                return kind;
            }
            t = t.getCause();
        }
        return UNKNOWN;
    }

    private static EvaluationFailure fromStatus(String status) {
        int code;
        try {
            code = Integer.parseInt(status);
        } catch (NumberFormatException e) {
            return UNKNOWN;
        }
        if (code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN) {
            return KEY_REJECTED;
        }
        if (code == HTTP_RATE_LIMITED) {
            return RATE_LIMITED;
        }
        if (code == HTTP_OVERLOADED) {
            return OVERLOADED;
        }
        if (code >= HTTP_SERVER_ERROR_FIRST && code <= HTTP_SERVER_ERROR_LAST) {
            return SERVER_ERROR;
        }
        return UNKNOWN;
    }
}
