package com.gregochr.goldenhour.model;

/**
 * The (location, score product) pair of one {@code forecast_score} row written by a pipeline
 * cycle: the projection {@code CycleLocationOutcomeResolver} reads as independent evidence that a
 * place was scored, whatever happened to the best-effort {@code api_call_log} audit rows.
 *
 * @param locationId     the scored place's id
 * @param forecastTypeId the {@code ForecastType} lookup id of the component row
 */
public record CycleScoredComponent(Long locationId, Long forecastTypeId) {
}
