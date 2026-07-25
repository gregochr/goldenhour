package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDateTime;

/**
 * A past evaluation awaiting cloud verification, with just the fields needed to sample it.
 *
 * <p>The azimuth and event time are what locate the two points and the hour to read: the solar
 * horizon 113 km along {@code azimuthDeg} (the gap) and the observer's own coordinate (the
 * canvas), both at the {@code solarEventTime} hour.
 *
 * @param evaluationId   the evaluation to verify
 * @param lat            observer latitude
 * @param lon            observer longitude
 * @param azimuthDeg     solar azimuth used by the original forecast
 * @param solarEventTime UTC time of the solar event
 * @param targetType     SUNRISE or SUNSET
 */
public record VerificationCandidate(
        Long evaluationId,
        double lat,
        double lon,
        Integer azimuthDeg,
        LocalDateTime solarEventTime,
        TargetType targetType) {
}
