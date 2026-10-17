package com.gregochr.goldenhour.service.batch;

/**
 * Thrown by {@link AnthropicBatchClient#createBatch} when batch creation ultimately failed —
 * either every retry attempt was exhausted, or the first attempt failed with an error the
 * {@code "anthropic-batch"} retry instance's predicate does not consider retryable.
 *
 * <p>Carries the number of attempts actually made so {@link BatchSubmissionService} can log a
 * more informative failure line than "submission failed" alone. Note that a single attempt
 * counted here can already represent several HTTP round trips: the Anthropic SDK client itself
 * retries 408/409/429/5xx and I/O failures internally before this class's attempts even begin
 * (see {@code AppConfig.anthropicClient}).
 */
public class BatchRetryExhaustedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int attempts;

    /**
     * Constructs the exception.
     *
     * @param attempts number of {@link AnthropicBatchClient#createBatch} attempts made
     * @param cause    the last failure
     */
    public BatchRetryExhaustedException(int attempts, Throwable cause) {
        super("Batch creation failed after " + attempts + " attempt(s): " + cause.getMessage(), cause);
        this.attempts = attempts;
    }

    /**
     * Returns how many attempts were made before this exception was thrown.
     *
     * @return the attempt count, always {@code >= 1}
     */
    public int getAttempts() {
        return attempts;
    }
}
