package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The 200 body of {@code POST /api/ask} (plan §2.9).
 *
 * <p>The pick and event shapes are the Ready response's own ({@link AskReadyResponse.Pick},
 * {@link AskEvent}): the validator has already joined every card fact from the snapshot the engine
 * ran against, so nothing here is the model's except each {@code why} and the summary.
 *
 * @param answerable     false for {@code kind: cant}
 * @param kind           {@code ready} (matched to a fresh Ready answer), {@code own} (an engine answer or a
 *                       cache hit) or {@code cant}
 * @param summary        the one or two sentences, or the can't-answer sentence
 * @param picks          at most three ranked picks; empty for {@code cant}
 * @param events         the event cards; empty for {@code cant}
 * @param missing        what PhotoCast does not have (a {@code cant}); null otherwise, always written
 * @param tryThese       up to two other fresh Ready questions, chosen by the server, serialised as
 *                       {@code try}; only a {@code cant} carries any
 * @param allowanceLeft  the asker's remaining charged questions today; {@code null} (written, not omitted)
 *                       only when the usage read failed after the answer was already paid for, which
 *                       tells the client to re-read the allowance rather than apply a figure
 * @param allowanceLimit the asker's daily allowance
 * @param charged        whether this answer used one of the allowance
 * @param generatedAt    when the briefing the answer was built from was generated (UTC)
 * @param runLabel       {@code HH:mm} Europe/London of {@code generatedAt}
 * @param threadReset    {@code true} when the request carried a thread that was dropped because the
 *                       forecast had been rebuilt since its last typed answer and this answer is fresh;
 *                       absent from the wire otherwise
 * @param threadResetReason the fixed sentence naming why ({@code "forecast updated"}); absent from the
 *                       wire whenever {@code threadReset} is
 */
public record AskResponse(boolean answerable, String kind, String summary,
        List<AskReadyResponse.Pick> picks, List<AskEvent> events,
        @JsonInclude(JsonInclude.Include.ALWAYS) String missing,
        @JsonProperty("try") List<AskReadyResponse.Suggestion> tryThese,
        @JsonInclude(JsonInclude.Include.ALWAYS) Integer allowanceLeft, int allowanceLimit, boolean charged,
        LocalDateTime generatedAt, String runLabel,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean threadReset,
        @JsonInclude(JsonInclude.Include.NON_NULL) String threadResetReason) {

    /** Why a thread was dropped (plan §2.2); the client words its own line. */
    public static final String THREAD_RESET_REASON = "forecast updated";

    /** An answer from the typed engine (or a typed-cache hit). */
    public static final String KIND_OWN = "own";

    /** A question the forecast cannot answer. */
    public static final String KIND_CANT = "cant";

    /** A Ready answer matched to the question. */
    public static final String KIND_READY = "ready";

    /** Canonical constructor: takes immutable copies; a null list reads as empty. */
    public AskResponse {
        picks = picks == null ? List.of() : List.copyOf(picks);
        events = events == null ? List.of() : List.copyOf(events);
        tryThese = tryThese == null ? List.of() : List.copyOf(tryThese);
    }

    /**
     * A response with no thread-reset fields, which is the wire shape of every answer to a fresh
     * question: byte-identical to the shape before threads existed.
     *
     * @param answerable     see the record
     * @param kind           see the record
     * @param summary        see the record
     * @param picks          see the record
     * @param events         see the record
     * @param missing        see the record
     * @param tryThese       see the record
     * @param allowanceLeft  see the record
     * @param allowanceLimit see the record
     * @param charged        see the record
     * @param generatedAt    see the record
     * @param runLabel       see the record
     */
    public AskResponse(boolean answerable, String kind, String summary, List<AskReadyResponse.Pick> picks,
            List<AskEvent> events, String missing, List<AskReadyResponse.Suggestion> tryThese,
            Integer allowanceLeft, int allowanceLimit, boolean charged, LocalDateTime generatedAt,
            String runLabel) {
        this(answerable, kind, summary, picks, events, missing, tryThese, allowanceLeft, allowanceLimit,
                charged, generatedAt, runLabel, null, null);
    }

    /**
     * The same answer marked as given after a thread was dropped because the forecast had been rebuilt.
     *
     * @return a copy carrying {@code threadReset: true} and {@link #THREAD_RESET_REASON}
     */
    public AskResponse withThreadReset() {
        return new AskResponse(answerable, kind, summary, picks, events, missing, tryThese, allowanceLeft,
                allowanceLimit, charged, generatedAt, runLabel, Boolean.TRUE, THREAD_RESET_REASON);
    }
}
