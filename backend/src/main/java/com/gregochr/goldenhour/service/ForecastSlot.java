package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;

/**
 * One forecast slot: a named location, a target date and a solar event. The unit a hand-started run
 * evaluates, and so the unit "Retry failed" re-runs.
 *
 * <p>A slot is identified by the location's <em>name</em>, as the run's progress tracker identifies
 * its tasks: names are unique, and the tracker holds nothing else of the location.
 *
 * @param locationName the location's name
 * @param date         the target date
 * @param targetType   SUNRISE or SUNSET
 */
public record ForecastSlot(String locationName, LocalDate date, TargetType targetType) {
}
