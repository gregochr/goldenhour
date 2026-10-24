package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.CoastalStructure;

import java.time.LocalDateTime;

/**
 * One stored daylight high water joined to its structure location, as the big-seas builder reads it
 * in a single query (a JPQL constructor projection, so no lazy loads).
 *
 * @param locationId       the structure location's primary key
 * @param locationName     the location's name (the label fallback)
 * @param structure        the kind of structure
 * @param label            the short chip name, or null
 * @param facesDeg         the bearing the structure faces
 * @param lat              latitude, for the location's own sunrise and sunset
 * @param lon              longitude
 * @param hwTime           UTC high water
 * @param windowStart      UTC start of the sampled window
 * @param windowEnd        UTC end of the sampled window
 * @param waveHeightM      significant wave height at the peak hour, or null
 * @param wavePeriodS      mean wave period at the peak hour, or null
 * @param waveDirectionDeg wave direction of origin at the peak hour, or null
 */
public record BigSeasCandidate(
        Long locationId,
        String locationName,
        CoastalStructure structure,
        String label,
        Integer facesDeg,
        double lat,
        double lon,
        LocalDateTime hwTime,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        Double waveHeightM,
        Double wavePeriodS,
        Integer waveDirectionDeg) {
}
