package com.gregochr.goldenhour.service;

import java.time.LocalDateTime;

/**
 * Decides whether a stored high-water sea state is a "big sea" for the structure it breaks on
 * (docs/engineering/big-seas-plan.md §3 B2). Pure: evaluated at serve time from the stored numbers
 * and never persisted, so retuning a threshold needs no refetch.
 */
public final class BigSeasRule {

    private static final int HALF_TURN = 180;
    private static final int FULL_TURN = 360;
    private static final int OFFSET = 540;

    private BigSeasRule() {
    }

    /**
     * Whether the sea state qualifies. Any null input means it does not.
     *
     * @param waveHeightM      significant wave height, metres
     * @param waveDirectionDeg wave direction of origin, degrees (360 is north, as 0)
     * @param wavePeriodS      mean wave period, seconds
     * @param facesDeg         the bearing the structure faces
     * @param windowStart      start of the sampled window
     * @param windowEnd        end of the sampled window
     * @param properties       the thresholds
     * @return true when height, period and onshore angle all meet their thresholds and the window is non-empty
     */
    public static boolean qualifies(Double waveHeightM, Integer waveDirectionDeg, Double wavePeriodS,
            Integer facesDeg, LocalDateTime windowStart, LocalDateTime windowEnd, BigSeasProperties properties) {
        if (waveHeightM == null || waveDirectionDeg == null || wavePeriodS == null || facesDeg == null
                || windowStart == null || windowEnd == null || !windowStart.isBefore(windowEnd)) {
            return false;
        }
        return waveHeightM >= properties.getMinWaveHeightM()
                && wavePeriodS >= properties.getMinWavePeriodS()
                && angleOffDeg(waveDirectionDeg, facesDeg) <= properties.getMaxOnshoreAngleDeg();
    }

    /**
     * The circular difference between the wave direction and the structure's bearing.
     *
     * @param waveDirectionDeg wave direction of origin, degrees
     * @param facesDeg         the bearing the structure faces
     * @return 0 to 180 degrees
     */
    public static int angleOffDeg(int waveDirectionDeg, int facesDeg) {
        return Math.abs(Math.floorMod(waveDirectionDeg - facesDeg + OFFSET, FULL_TURN) - HALF_TURN);
    }
}
