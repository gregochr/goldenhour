package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RunType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One Anthropic {@code api_call_log} row reduced to the columns the Run Config cost profile reads,
 * so the large request and response text columns are never loaded.
 *
 * @param runType            the owning job run's type
 * @param jobRunStartedAt    the owning job run's start (naive UTC)
 * @param requestUrl         the logged request label (for example {@code briefing-best-bet})
 * @param customId           the Anthropic batch custom id, or null for a synchronous call
 * @param isBatch            whether the call went through the Batch API
 * @param succeeded          whether the call produced a usable result
 * @param evaluationModel    the model the call ran on
 * @param targetDate         the forecast date the call was for, or null
 * @param inputTokens        standard input tokens, or null
 * @param outputTokens       output tokens, or null
 * @param cacheCreationTokens tokens written to the prompt cache, or null
 * @param cacheReadTokens    tokens read from the prompt cache, or null
 * @param costMicroDollars   the recorded cost, or null
 * @param jobRunId           the owning job run's id
 * @param pipelineRunId      the pipeline cycle the call's batch belongs to, or null (sync calls and
 *                           ad-hoc batches)
 * @param cycleTriggerTime    when that pipeline cycle started, or null; the reference date for
 *                           near/far bucketing, since a retry job run can start after UK midnight
 */
public record ApiCallCostRow(
        RunType runType,
        LocalDateTime jobRunStartedAt,
        String requestUrl,
        String customId,
        Boolean isBatch,
        Boolean succeeded,
        EvaluationModel evaluationModel,
        LocalDate targetDate,
        Long inputTokens,
        Long outputTokens,
        Long cacheCreationTokens,
        Long cacheReadTokens,
        Long costMicroDollars,
        Long jobRunId,
        Long pipelineRunId,
        Instant cycleTriggerTime) {

    /**
     * Row without a cycle trigger time (bucketing falls back to the job run's start).
     *
     * @param runType             the owning job run's type
     * @param jobRunStartedAt     the owning job run's start (naive UTC)
     * @param requestUrl          the logged request label
     * @param customId            the batch custom id, or null
     * @param isBatch             whether the call went through the Batch API
     * @param succeeded           whether the call succeeded
     * @param evaluationModel     the model
     * @param targetDate          the forecast date, or null
     * @param inputTokens         input tokens
     * @param outputTokens        output tokens
     * @param cacheCreationTokens cache-write tokens
     * @param cacheReadTokens     cache-read tokens
     * @param costMicroDollars    recorded cost
     * @param jobRunId            owning job run id
     * @param pipelineRunId       pipeline cycle id, or null
     */
    public ApiCallCostRow(RunType runType, LocalDateTime jobRunStartedAt, String requestUrl, String customId,
            Boolean isBatch, Boolean succeeded, EvaluationModel evaluationModel, LocalDate targetDate,
            Long inputTokens, Long outputTokens, Long cacheCreationTokens, Long cacheReadTokens,
            Long costMicroDollars, Long jobRunId, Long pipelineRunId) {
        this(runType, jobRunStartedAt, requestUrl, customId, isBatch, succeeded, evaluationModel, targetDate,
                inputTokens, outputTokens, cacheCreationTokens, cacheReadTokens, costMicroDollars, jobRunId,
                pipelineRunId, null);
    }
}
