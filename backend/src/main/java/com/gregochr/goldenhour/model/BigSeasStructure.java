package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.CoastalStructure;

import java.time.LocalDateTime;

/**
 * One structure in the lead big-seas group of a day: the sea state at its own high water.
 *
 * @param locationId       the structure location's primary key
 * @param label            the chip's short name (the location name when no label is set)
 * @param structure        the kind of structure
 * @param waveHeightM      significant wave height at the peak hour, metres
 * @param waveDirectionDeg wave direction of origin, degrees
 * @param wavePeriodS      mean wave period, seconds
 * @param facesDeg         compass bearing the structure faces
 * @param hwTime           this structure's high water, UTC with no zone
 * @param aligned          whether its window contains its own date's sunrise or sunset; always
 *                         false on the Lite wire
 */
public record BigSeasStructure(
        long locationId,
        String label,
        CoastalStructure structure,
        double waveHeightM,
        int waveDirectionDeg,
        double wavePeriodS,
        int facesDeg,
        LocalDateTime hwTime,
        boolean aligned) {

    /**
     * Returns this structure as a LITE reader may see it: {@code aligned} is false (the ring is
     * drawn for a structure whose window contains a light, which is a Pro fact); every other
     * component, the sea state and the high-water clock included, stays.
     *
     * @return the shaped copy
     */
    public BigSeasStructure forLite() {
        return new BigSeasStructure(locationId, label, structure, waveHeightM, waveDirectionDeg,
                wavePeriodS, facesDeg, hwTime, false);
    }
}
