package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * Temporal trend of low cloud cover at the solar horizon point across the hours before a solar event.
 *
 * <p>Captures T-3h through T low cloud at the 113 km solar horizon to detect cloud build-up
 * that the single event-time snapshot would miss.
 *
 * @param slots time-ordered slots from earliest (T-3h) to event time (T)
 */
public record SolarCloudTrend(List<SolarCloudSlot> slots) {

    /**
     * Compact constructor — defensive copy to satisfy SpotBugs EI_EXPOSE_REP.
     *
     * @param slots the time-ordered slots
     */
    public SolarCloudTrend {
        slots = slots == null ? null : List.copyOf(slots);
    }

    /**
     * A single hourly sample of low cloud cover at the solar horizon point.
     *
     * @param hoursBeforeEvent hours before the solar event (0 = event time, 3 = T-3h)
     * @param lowCloudPercent  low cloud cover percentage (0-100)
     */
    public record SolarCloudSlot(int hoursBeforeEvent, int lowCloudPercent) {
    }

    /**
     * Returns {@code true} if low cloud is building toward the event — defined as the
     * peak low cloud across any slot exceeding the earliest slot by at least 20
     * percentage points.
     *
     * <p>A building trend indicates the model's event-time value may be understated,
     * as cloud is approaching from the solar direction. The peak is used rather than
     * only the event-time slot because the forecast model may optimistically predict
     * clearing at event time.
     *
     * @return true if a building trend of 20+ pp is detected
     */
    public boolean isBuilding() {
        if (slots == null || slots.size() < 2) {
            return false;
        }
        int earliest = slots.getFirst().lowCloudPercent();
        int peak = slots.stream()
                .mapToInt(SolarCloudSlot::lowCloudPercent)
                .max()
                .orElse(earliest);
        return peak - earliest >= 20;
    }
}
