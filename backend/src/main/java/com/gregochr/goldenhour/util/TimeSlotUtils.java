package com.gregochr.goldenhour.util;

import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Utility methods for selecting the best hourly forecast slot relative to a solar event.
 */
public final class TimeSlotUtils {

    private TimeSlotUtils() {
    }

    /**
     * Finds the best hourly slot index for a solar event, respecting event direction.
     *
     * <p>For sunset, selects the latest slot at or before the event time (the slot after
     * sunset has 0 radiation and is useless). For sunrise, selects the earliest slot at
     * or after the event time (the slot before sunrise is pre-dawn darkness).
     * Falls back to the absolute nearest slot if no valid slot exists on the preferred side.
     *
     * @param times      list of ISO-8601 time strings from the API response
     * @param targetTime the solar event time
     * @param targetType SUNRISE or SUNSET
     * @return the index of the best matching slot
     */
    public static int findBestIndex(List<String> times, LocalDateTime targetTime,
            TargetType targetType) {
        int bestIdx = -1;
        long bestDiff = Long.MAX_VALUE;

        for (int i = 0; i < times.size(); i++) {
            LocalDateTime slotTime = LocalDateTime.parse(times.get(i));
            long diffSeconds = ChronoUnit.SECONDS.between(slotTime, targetTime);
            // diffSeconds > 0 means slot is before targetTime; < 0 means slot is after

            boolean validSide = targetType == TargetType.SUNSET
                    ? diffSeconds >= 0   // slot at or before sunset
                    : diffSeconds <= 0;  // slot at or after sunrise

            long absDiff = Math.abs(diffSeconds);
            if (validSide && absDiff < bestDiff) {
                bestDiff = absDiff;
                bestIdx = i;
            }
        }

        // Fall back to absolute nearest if no valid slot on the preferred side
        if (bestIdx == -1) {
            bestIdx = 0;
            long minDiff = Long.MAX_VALUE;
            for (int i = 0; i < times.size(); i++) {
                long diff = Math.abs(ChronoUnit.SECONDS.between(
                        LocalDateTime.parse(times.get(i)), targetTime));
                if (diff < minDiff) {
                    minDiff = diff;
                    bestIdx = i;
                }
            }
        }

        return bestIdx;
    }

    /**
     * Finds the index of the hourly slot nearest to the target time, with no directional bias.
     *
     * @param times      list of ISO-8601 time strings from the API response
     * @param targetTime the target time to match
     * @return the index of the nearest slot
     */
    public static int findNearestIndex(List<String> times, LocalDateTime targetTime) {
        int bestIdx = 0;
        long bestDiff = Long.MAX_VALUE;

        for (int i = 0; i < times.size(); i++) {
            long diff = Math.abs(ChronoUnit.SECONDS.between(
                    LocalDateTime.parse(times.get(i)), targetTime));
            if (diff < bestDiff) {
                bestDiff = diff;
                bestIdx = i;
            }
        }
        return bestIdx;
    }
}
