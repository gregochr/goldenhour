package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;

/**
 * Response DTO for a single external API call logged during a job run.
 *
 * <p>Deliberately omits request-side fields ({@code requestMethod}, {@code requestUrl},
 * {@code requestBody}) which can carry secrets, plus other internal columns not needed
 * by the admin metrics UI.
 *
 * @param id                       the database identifier
 * @param jobRunId                 foreign key to the parent job run
 * @param service                  the external service that was called
 * @param durationMs               duration of the call in milliseconds
 * @param responseBody             response body on error, or null on success
 * @param succeeded                whether the call succeeded
 * @param errorMessage             error message on failure, or null
 * @param evaluationModel          evaluation model for Anthropic calls, else null
 * @param targetDate               target date for forecast calls, else null
 * @param targetType               target type for forecast calls, else null
 * @param inputTokens              standard input tokens consumed (Anthropic only)
 * @param outputTokens             output tokens generated (Anthropic only)
 * @param cacheCreationInputTokens tokens written to the prompt cache (Anthropic only)
 * @param cacheReadInputTokens     tokens read from the prompt cache (Anthropic only)
 * @param costMicroDollars         cost of this call in micro-dollars
 * @param customId                 Anthropic batch custom ID, or null for SSE calls
 * @param errorType                Anthropic error type, or null on success
 */
public record ApiCallLogDto(
        Long id,
        Long jobRunId,
        ServiceName service,
        Long durationMs,
        String responseBody,
        Boolean succeeded,
        String errorMessage,
        EvaluationModel evaluationModel,
        LocalDate targetDate,
        TargetType targetType,
        Long inputTokens,
        Long outputTokens,
        Long cacheCreationInputTokens,
        Long cacheReadInputTokens,
        Long costMicroDollars,
        String customId,
        String errorType
) {

    /**
     * Builds an {@code ApiCallLogDto} from an {@link ApiCallLogEntity}, copying only exposed fields.
     *
     * @param e the source entity
     * @return the populated DTO
     */
    public static ApiCallLogDto from(ApiCallLogEntity e) {
        return new ApiCallLogDto(
                e.getId(),
                e.getJobRunId(),
                e.getService(),
                e.getDurationMs(),
                e.getResponseBody(),
                e.getSucceeded(),
                e.getErrorMessage(),
                e.getEvaluationModel(),
                e.getTargetDate(),
                e.getTargetType(),
                e.getInputTokens(),
                e.getOutputTokens(),
                e.getCacheCreationInputTokens(),
                e.getCacheReadInputTokens(),
                e.getCostMicroDollars(),
                e.getCustomId(),
                e.getErrorType()
        );
    }
}
