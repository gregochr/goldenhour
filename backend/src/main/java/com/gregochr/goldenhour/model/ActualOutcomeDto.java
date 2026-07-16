package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.ActualOutcomeEntity;
import com.gregochr.goldenhour.entity.TargetType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * API response shape for a recorded outcome. Field names match the previous raw-entity
 * serialisation byte-for-byte (the entity's fields minus the {@code location} relation,
 * plus the derived {@code locationName}), so the frontend contract is unchanged while the
 * JPA entity stays out of the web layer.
 *
 * @param id               database primary key
 * @param locationLat      latitude of the location in decimal degrees
 * @param locationLon      longitude of the location in decimal degrees
 * @param outcomeDate      the date the event was observed
 * @param targetType       whether this outcome is for sunrise or sunset
 * @param wentOut          whether the photographer actually went out to shoot
 * @param actualRating     the photographer's own 1-5 rating of the actual colour
 * @param fierySkyActual   photographer's fiery sky score (0–100) for the actual event
 * @param goldenHourActual photographer's golden hour score (0–100) for the actual event
 * @param notes            free-text observations about the shoot
 * @param recordedAt       UTC timestamp when this record was created
 * @param locationName     the owning location's name, or {@code null}
 */
public record ActualOutcomeDto(
        Long id,
        BigDecimal locationLat,
        BigDecimal locationLon,
        LocalDate outcomeDate,
        TargetType targetType,
        Boolean wentOut,
        Integer actualRating,
        Integer fierySkyActual,
        Integer goldenHourActual,
        String notes,
        LocalDateTime recordedAt,
        String locationName) {

    /**
     * Maps a persisted outcome entity to its API shape.
     *
     * @param entity the saved outcome entity
     * @return the response DTO
     */
    public static ActualOutcomeDto from(ActualOutcomeEntity entity) {
        return new ActualOutcomeDto(
                entity.getId(),
                entity.getLocationLat(),
                entity.getLocationLon(),
                entity.getOutcomeDate(),
                entity.getTargetType(),
                entity.getWentOut(),
                entity.getActualRating(),
                entity.getFierySkyActual(),
                entity.getGoldenHourActual(),
                entity.getNotes(),
                entity.getRecordedAt(),
                entity.getLocationName());
    }
}
