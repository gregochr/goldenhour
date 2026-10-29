package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;

/**
 * The columns of an {@code api_call_log} row that {@code BatchSummaryDeriver} reads, so the row's
 * large request and response text columns are never loaded.
 *
 * @param service         the service called
 * @param targetDate      the forecast date the call was for, or {@code null}
 * @param targetType      sunrise or sunset, or {@code null}
 * @param evaluationModel the model that evaluated, or {@code null}
 * @param customId        the request's custom id, or {@code null}
 */
public record BatchSummaryCall(ServiceName service, LocalDate targetDate, TargetType targetType,
        EvaluationModel evaluationModel, String customId) {
}
