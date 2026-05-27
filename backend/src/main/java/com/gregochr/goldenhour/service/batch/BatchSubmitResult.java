package com.gregochr.goldenhour.service.batch;

/**
 * Result of a successful batch submission to the Anthropic Batch API.
 *
 * <p>Previously nested inside {@code ScheduledBatchEvaluationService}; promoted to a
 * top-level record so {@link BatchSubmissionService} and multiple caller services can
 * return it without a circular-dependency-prone cross-reference.
 *
 * <p>{@code jobRunId} was added in the disposition-write fix: prior to that the
 * {@code BatchSubmissionService} created a {@code JobRunEntity} but dropped its id
 * before returning, so downstream callers — notably the disposition persistence
 * path in {@code ScheduledBatchEvaluationService} — saw a null
 * {@code EvaluationHandle.jobRunId} and silently no-opped on the write. The field
 * is nullable because the no-op submission paths (empty request list, Anthropic
 * exception) return {@code null} for the whole result, not just for this field.
 *
 * @param jobRunId     id of the {@code JobRunEntity} created for this submission,
 *                     or {@code null} if no job run was created (e.g. the JobRun
 *                     creation itself failed but the batch still went out)
 * @param batchId      the Anthropic batch ID
 * @param requestCount number of requests submitted
 */
public record BatchSubmitResult(Long jobRunId, String batchId, int requestCount) {
}
