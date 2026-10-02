package com.gregochr.goldenhour.model;

/**
 * One individual batch request's recorded outcome — the projection of an {@code api_call_log}
 * row that {@code CycleLocationOutcomeResolver} reads, so the row's large text columns are never
 * loaded.
 *
 * @param customId  the request's custom id ({@code fc-{locationId}-…}, {@code bb-…}, {@code wl-…})
 * @param succeeded whether the request produced a usable result
 * @param errorType the failure's short type code, or {@code null} for a success
 */
public record BatchCallOutcome(String customId, Boolean succeeded, String errorType) {
}
