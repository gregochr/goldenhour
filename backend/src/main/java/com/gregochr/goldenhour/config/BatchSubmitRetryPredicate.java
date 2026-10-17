package com.gregochr.goldenhour.config;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;

import java.io.IOException;
import java.util.function.Predicate;

/**
 * Retry predicate for the {@code "anthropic-batch"} Resilience4j instance, used by {@link
 * com.gregochr.goldenhour.service.batch.AnthropicBatchClient} around Anthropic Batch API
 * creation calls.
 *
 * <p>Retries on:
 * <ul>
 *   <li>{@link AnthropicServiceException} with status &gt;= 500 — covers 500, 502, 503, 504 and
 *       529 (529 "overloaded" has no dedicated SDK exception type and surfaces as {@code
 *       UnexpectedStatusCodeException}, which is still &gt;= 500)</li>
 *   <li>{@link AnthropicServiceException} with status 429 — see the note below; this is the ONE
 *       4xx this predicate retries</li>
 *   <li>{@link AnthropicIoException} or a raw {@link IOException} (including {@link
 *       java.io.InterruptedIOException}, i.e. a network timeout) — checked anywhere in the
 *       cause chain, since the SDK sometimes wraps the underlying I/O failure</li>
 * </ul>
 *
 * <p>Deliberately does NOT retry 400, 401, 403, 404 or 413 — every one of those is a client-side
 * mistake a retry cannot fix. Deliberately does NOT retry 408 or 409 either, though both are
 * nominally transient: this predicate governs batch CREATION specifically, which neither status
 * is a realistic response to.
 *
 * <p><b>Why 429 IS retried here, unlike {@link ClaudeRetryPredicate}.</b> {@link
 * com.gregochr.goldenhour.service.batch.AnthropicBatchClient} runs {@code create()} (and the
 * adoption check's {@code list()}) through a client with the SDK's own transport-level retries
 * disabled (round 2, P1-B of a Codex review of #949) — precisely so that every actual HTTP
 * attempt is one this predicate's retry loop, and the duplicate-batch adoption guard, can see and
 * react to; a 429 handled invisibly inside the transport layer would defeat that. With the
 * transport layer no longer retrying 429 for this call, something has to, and it is safe to be
 * this predicate: a 429 means the request was rejected BEFORE any batch was created — unlike a
 * 5xx or a timeout, there is no possibility Anthropic actually created the batch and this JVM
 * merely failed to observe it, so retrying carries none of the duplicate-batch risk {@link
 * AnthropicBatchClient}'s adoption guard exists for. Every other caller of the shared {@code
 * AnthropicClient} (the synchronous per-location path via {@link ClaudeRetryPredicate}, in
 * particular) keeps the transport layer's own retry-with-{@code Retry-After} handling untouched.
 */
public class BatchSubmitRetryPredicate implements Predicate<Throwable> {

    /** Defensive bound on cause-chain traversal, against a pathological cyclic cause. */
    private static final int MAX_CAUSE_DEPTH = 8;

    private static final int SERVER_ERROR_THRESHOLD = 500;

    /** The one 4xx this predicate retries — see the class javadoc for why it is safe here. */
    private static final int RATE_LIMIT_STATUS = 429;

    @Override
    public boolean test(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (isRetryable(current)) {
                return true;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return false;
    }

    private boolean isRetryable(Throwable throwable) {
        if (throwable instanceof AnthropicServiceException ex) {
            int statusCode = ex.statusCode();
            return statusCode >= SERVER_ERROR_THRESHOLD || statusCode == RATE_LIMIT_STATUS;
        }
        return throwable instanceof AnthropicIoException || throwable instanceof IOException;
    }
}
