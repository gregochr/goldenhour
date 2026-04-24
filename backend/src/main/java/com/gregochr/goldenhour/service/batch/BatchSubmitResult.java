package com.gregochr.goldenhour.service.batch;

/**
 * Result of a successful batch submission to the Anthropic Batch API.
 *
 * <p>Previously nested inside {@code ScheduledBatchEvaluationService}; promoted to a
 * top-level record so {@link BatchSubmissionService} and multiple caller services can
 * return it without a circular-dependency-prone cross-reference.
 *
 * @param batchId      the Anthropic batch ID
 * @param requestCount number of requests submitted
 */
public record BatchSubmitResult(String batchId, int requestCount) {
}
