package com.gregochr.goldenhour.service.batch;

/**
 * Thrown by {@link AnthropicBatchClient#createBatch} when a retry attempt's duplicate-batch
 * adoption check ({@code findAdoptableBatch}) finds MORE THAN ONE untracked candidate batch
 * matching the pending request.
 *
 * <p>One matching candidate is adopted outright; two or more is a genuine ambiguity this class
 * has no further evidence to resolve — the Anthropic list API exposes no request contents to
 * fingerprint against, only timing and aggregate counts, and by construction both candidates
 * already passed that test. Guessing (e.g. "adopt the newest") risks attaching the WRONG batch id
 * to this request, which corrupts {@code forecast_batch} (and, downstream, every
 * {@code custom_id}-keyed result this bucket's tasks are meant to land against) far worse than
 * simply failing this attempt does. Every candidate id is logged at ERROR as a possible orphan
 * needing manual recovery before this is thrown.
 *
 * <p>Deliberately NOT retried: {@link com.gregochr.goldenhour.config.BatchSubmitRetryPredicate}
 * does not recognise this type, so the {@code "anthropic-batch"} retry instance rethrows it
 * immediately on whichever attempt hits it rather than spending further attempts on an ambiguity
 * no attempt can resolve — the ambiguity is intrinsic to the current list of candidates, and
 * retrying without changing anything about the request cannot make it go away. {@link
 * AnthropicBatchClient#createBatch} still wraps it in the usual {@link
 * BatchRetryExhaustedException}, so {@link BatchSubmissionService} logs and returns {@code null}
 * exactly as it does for any other submission failure.
 */
public class AmbiguousBatchAdoptionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs the exception.
     *
     * @param message description including every candidate batch id, for the caller's own log line
     */
    public AmbiguousBatchAdoptionException(String message) {
        super(message);
    }
}
