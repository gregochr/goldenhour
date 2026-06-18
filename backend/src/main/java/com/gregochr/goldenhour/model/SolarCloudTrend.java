package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * Temporal trend of cloud cover at the solar horizon point across the hours before a solar event.
 *
 * <p>Captures T-3h through T low cloud at the 113 km solar horizon to detect cloud build-up
 * that the single event-time snapshot would miss ({@link #isBuilding()}), and — when the mid/high
 * canvas layers are also captured — cloud clearing into the event ({@link #isClearing()}).
 *
 * <p>Each {@link SolarCloudSlot} carries low cloud (the light blocker) and, optionally, mid and
 * high cloud (the canvas). Mid/high are nullable so legacy/synthetic slots that pre-date the
 * canvas-trajectory work continue to construct via the two-argument convenience constructor and
 * print exactly as before; only slots built from the full forecast carry the canvas trajectory.
 *
 * @param slots time-ordered slots from earliest (T-3h) to event time (T)
 */
public record SolarCloudTrend(List<SolarCloudSlot> slots) {

    /** Low cloud rise (peak vs earliest), in percentage points, that marks a building trend. */
    private static final int BUILDING_LOW_RISE_PP = 20;

    /** Low cloud drop (earliest vs event), in percentage points, that marks a clearing trend. */
    static final int CLEARING_LOW_DROP_PP = 20;

    /** Maximum canvas (mid/high) drop, in percentage points, still considered "canvas holds". */
    static final int CANVAS_COLLAPSE_PP = 20;

    /** Minimum canvas (max of mid/high) at event time for a meaningful canvas to remain. */
    static final int CANVAS_PRESENT_FLOOR_PP = 25;

    /**
     * Compact constructor — defensive copy to satisfy SpotBugs EI_EXPOSE_REP.
     *
     * @param slots the time-ordered slots
     */
    public SolarCloudTrend {
        slots = slots == null ? null : List.copyOf(slots);
    }

    /**
     * A single hourly sample of cloud cover at the solar horizon point.
     *
     * @param hoursBeforeEvent hours before the solar event (0 = event time, 3 = T-3h)
     * @param lowCloudPercent  low cloud cover percentage (0-100) — the light blocker
     * @param midCloudPercent  mid cloud cover percentage (0-100), or null if not captured
     * @param highCloudPercent high cloud cover percentage (0-100), or null if not captured
     */
    public record SolarCloudSlot(int hoursBeforeEvent, int lowCloudPercent,
            Integer midCloudPercent, Integer highCloudPercent) {

        /**
         * Convenience constructor for a low-cloud-only slot (no canvas trajectory captured).
         *
         * @param hoursBeforeEvent hours before the solar event (0 = event time, 3 = T-3h)
         * @param lowCloudPercent  low cloud cover percentage (0-100)
         */
        public SolarCloudSlot(int hoursBeforeEvent, int lowCloudPercent) {
            this(hoursBeforeEvent, lowCloudPercent, null, null);
        }

        /**
         * Returns the canvas strength for this slot — the stronger of the mid and high cloud
         * layers, since either alone can serve as a lit canvas.
         *
         * @return the max of mid and high cloud percent, or null if the canvas was not captured
         */
        Integer canvasPercent() {
            if (midCloudPercent == null || highCloudPercent == null) {
                return null;
            }
            return Math.max(midCloudPercent, highCloudPercent);
        }
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
        return peak - earliest >= BUILDING_LOW_RISE_PP;
    }

    /**
     * Returns {@code true} if this is a genuine clearing-into-the-event trajectory: the low cloud
     * blocker drops into the event <em>while the mid/high canvas survives</em>.
     *
     * <p>This is the photographic "dramatic clearance": the blocker lifts as the light arrives,
     * leaving structured mid/high cloud to catch colour. It is deliberately distinct from a
     * wholesale clear (low <em>and</em> canvas both falling toward bald blue), which is a
     * liability, not an opportunity — so all three conditions must hold:
     * <ul>
     *   <li>low cloud drops from earliest to event by at least {@value #CLEARING_LOW_DROP_PP} pp;</li>
     *   <li>a meaningful canvas remains at event (max of mid/high ≥
     *       {@value #CANVAS_PRESENT_FLOOR_PP}%);</li>
     *   <li>the canvas does not collapse (its earliest-to-event drop is below
     *       {@value #CANVAS_COLLAPSE_PP} pp).</li>
     * </ul>
     *
     * <p>Returns {@code false} when the canvas trajectory was not captured (mid/high null on the
     * earliest or event slot) — clearing can only be asserted against the canvas.
     *
     * @return true if the blocker clears into the event while the canvas holds
     */
    public boolean isClearing() {
        if (slots == null || slots.size() < 2) {
            return false;
        }
        SolarCloudSlot earliest = slots.getFirst();
        SolarCloudSlot event = slots.getLast();

        // The blocker must drop into the event.
        if (earliest.lowCloudPercent() - event.lowCloudPercent() < CLEARING_LOW_DROP_PP) {
            return false;
        }

        // The canvas must have been captured to assert anything about it.
        Integer canvasEarliest = earliest.canvasPercent();
        Integer canvasEvent = event.canvasPercent();
        if (canvasEarliest == null || canvasEvent == null) {
            return false;
        }

        // A meaningful canvas must remain at event (not cleared to bald blue).
        if (canvasEvent < CANVAS_PRESENT_FLOOR_PP) {
            return false;
        }

        // The canvas must not be collapsing alongside the low cloud.
        return canvasEarliest - canvasEvent < CANVAS_COLLAPSE_PP;
    }
}
