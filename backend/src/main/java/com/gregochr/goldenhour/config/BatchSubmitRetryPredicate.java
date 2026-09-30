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
 *   <li>{@link AnthropicIoException} or a raw {@link IOException} (including {@link
 *       java.io.InterruptedIOException}, i.e. a network timeout) — checked anywhere in the
 *       cause chain, since the SDK sometimes wraps the underlying I/O failure</li>
 * </ul>
 *
 * <p>Deliberately does NOT retry any 4xx status — 400, 401, 403, 404, 413 or 429. 429 already
 * carries its own {@code Retry-After} handling inside the SDK client's own retry layer (see
 * {@code AppConfig.anthropicClient}), and every other 4xx is a client-side mistake a retry
 * cannot fix.
 */
public class BatchSubmitRetryPredicate implements Predicate<Throwable> {

    /** Defensive bound on cause-chain traversal, against a pathological cyclic cause. */
    private static final int MAX_CAUSE_DEPTH = 8;

    private static final int SERVER_ERROR_THRESHOLD = 500;

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
            return ex.statusCode() >= SERVER_ERROR_THRESHOLD;
        }
        return throwable instanceof AnthropicIoException || throwable instanceof IOException;
    }
}
