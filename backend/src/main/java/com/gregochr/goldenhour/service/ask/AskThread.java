package com.gregochr.goldenhour.service.ask;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * The validated thread of a typed question: the session's earlier exchanges, oldest first, already
 * sanitised and checked by {@link AskThreadValidation}, and already reconciled with the live forecast
 * (a thread that has outlived its forecast run is dropped whole, never carried here).
 *
 * <p>An immutable value that travels to the engine inside {@link AskRunOptions}. Phase T1 only
 * <em>carries</em> it to the engine boundary; the prompt and the validator read it from T2.
 *
 * @param exchanges the exchanges, oldest first; null reads as none
 */
public record AskThread(List<ThreadExchange> exchanges) {

    /** No earlier exchanges: a fresh question, which every non-typed conversation is. */
    public static final AskThread EMPTY = new AskThread(List.of());

    /** Canonical constructor: an immutable copy; null reads as none. */
    public AskThread {
        exchanges = exchanges == null ? List.of() : List.copyOf(exchanges);
    }

    /**
     * Whether there is nothing earlier in the conversation.
     *
     * @return true for a fresh question
     */
    public boolean isEmpty() {
        return exchanges.isEmpty();
    }

    /**
     * How many exchanges the thread holds.
     *
     * @return the count
     */
    public int size() {
        return exchanges.size();
    }

    /**
     * The reset rule (plan §2.2): whether any <em>typed</em> exchange was answered against a briefing
     * run other than the live one. A Ready-origin exchange is never compared: its
     * {@code generatedAt} is the precompute's snapshot, which a manual briefing rebuild leaves older
     * than the live run while its answer is still fresh by the Ready rule.
     *
     * @param liveGeneratedAt the live snapshot's {@code generatedAt}
     * @return true when the thread must be dropped and the question answered fresh
     */
    public boolean staleAgainst(LocalDateTime liveGeneratedAt) {
        return exchanges.stream()
                .filter(exchange -> !exchange.ready())
                .anyMatch(exchange -> !Objects.equals(exchange.generatedAt(), liveGeneratedAt));
    }
}
