package com.gregochr.goldenhour.model;

import java.time.LocalDate;

/**
 * Request payload to create a travel-day range.
 *
 * @param startDate first day of the away period (inclusive)
 * @param endDate   last day of the away period (inclusive)
 * @param note      optional free-text note, e.g. "London — work"
 */
public record TravelDayRequest(LocalDate startDate, LocalDate endDate, String note) {
}
