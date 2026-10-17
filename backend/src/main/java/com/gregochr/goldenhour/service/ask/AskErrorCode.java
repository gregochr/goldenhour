package com.gregochr.goldenhour.service.ask;

import org.springframework.http.HttpStatus;

/**
 * The error codes of {@code POST /api/ask} and the status each answers with (plan §2.9's table).
 * One definition, so the status, the code and the sentence a client shows cannot drift apart: the
 * three 429s are told apart by {@link #name()}, never by their text, and so are the 400/502/503.
 */
public enum AskErrorCode {

    /** The question or the request is not acceptable. The sentence says why. */
    INVALID(HttpStatus.BAD_REQUEST, "The question could not be used."),

    /** More than the per-minute limit. Nothing was used. */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Slow down a moment."),

    /** The day's allowance of charged questions is used up. */
    ALLOWANCE_EXHAUSTED(HttpStatus.TOO_MANY_REQUESTS,
            "You've used today's questions. Ready questions are still available."),

    /** The never-refunded daily ceiling on engine runs is reached. */
    DAILY_LIMIT(HttpStatus.TOO_MANY_REQUESTS,
            "That's the most questions we can take from you today. Ready questions are still available."),

    /** The engine failed. The question was refunded. */
    ENGINE_FAILED(HttpStatus.BAD_GATEWAY, "Couldn't answer just now. No question used."),

    /** Typed questions are off for everyone for now (spend cap, accounting, no briefing, database). */
    TYPED_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE,
            "Typed questions are unavailable right now. Ready questions are still available."),

    /** The token names a user that no longer exists. Not in the plan's table: nothing else fits. */
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Please sign in again.");

    private final HttpStatus status;
    private final String message;

    AskErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    /**
     * The HTTP status.
     *
     * @return the status this code answers with
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * The default human sentence.
     *
     * @return the sentence a client may show
     */
    public String message() {
        return message;
    }
}
