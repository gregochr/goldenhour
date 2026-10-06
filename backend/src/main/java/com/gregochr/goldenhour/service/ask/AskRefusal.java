package com.gregochr.goldenhour.service.ask;

import java.util.Map;

/**
 * A typed question that was turned away, carrying the one error shape every refusal shares:
 * {@code {"error": "<human sentence>", "code": "<CODE>"}} (plan §2.9). The controller turns it into
 * the response through {@link #body()}; nothing else builds an error body for this endpoint.
 *
 * <p>Carries no stack trace: a refusal is ordinary control flow, not a fault.
 */
public class AskRefusal extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final AskErrorCode code;

    /**
     * A refusal with the code's own sentence.
     *
     * @param code the code
     */
    public AskRefusal(AskErrorCode code) {
        this(code, code.message());
    }

    /**
     * A refusal with its own sentence (for {@code INVALID}, which says what was wrong).
     *
     * @param code    the code
     * @param message the sentence a client may show
     */
    public AskRefusal(AskErrorCode code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    /**
     * The code.
     *
     * @return the code, which fixes the HTTP status
     */
    public AskErrorCode code() {
        return code;
    }

    /**
     * The response body.
     *
     * @return {@code {"error": sentence, "code": CODE}}
     */
    public Map<String, String> body() {
        return Map.of("error", getMessage(), "code", code.name());
    }
}
