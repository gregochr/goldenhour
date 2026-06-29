package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TravelDayEntity;

import java.time.LocalDate;

/**
 * API view of a single travel-day range.
 *
 * @param id        surrogate identifier
 * @param startDate first day of the away period (inclusive)
 * @param endDate   last day of the away period (inclusive)
 * @param note      optional free-text note (may be null)
 */
public record TravelDayResponse(Long id, LocalDate startDate, LocalDate endDate, String note) {

    /**
     * Maps an entity to its API view.
     *
     * @param entity the persisted range
     * @return the response DTO
     */
    public static TravelDayResponse from(TravelDayEntity entity) {
        return new TravelDayResponse(
                entity.getId(), entity.getStartDate(), entity.getEndDate(), entity.getNote());
    }
}
