package com.gregochr.goldenhour.model;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

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

    /**
     * Averages several trends slot-by-slot into a single trend.
     *
     * <p>Used to read the cloud-approach trend across the three solar-cone bearings rather than
     * from the centre bearing alone, matching the smoothing that
     * {@code DirectionalSamplingGeometry.SOLAR_CONE_HALF_ANGLE_DEG} already applies to the solar
     * horizon cloud reading. Open-Meteo's ~11 km grid means a single 113 km sample can land either
     * side of a cell boundary; {@link #isBuilding()} feeds an absolute rating ceiling in the
     * prompt, so it must not turn on one cell.
     *
     * <p>Null and empty trends are ignored. Slots are aligned by position (all cone points share an
     * event time and target type, so their trajectories are the same length in practice); if
     * lengths differ the result is truncated to the shortest. {@code hoursBeforeEvent} is taken
     * from the first contributing trend. Mid and high cloud average only when every contributing
     * slot captured them — otherwise the averaged slot reports null, preserving the
     * "canvas trajectory not captured" semantics {@link #isClearing()} relies on.
     *
     * @param trends the per-bearing trends to average
     * @return the averaged trend, or {@code null} if no usable trend was supplied
     */
    public static SolarCloudTrend average(List<SolarCloudTrend> trends) {
        if (trends == null) {
            return null;
        }
        List<SolarCloudTrend> usable = trends.stream()
                .filter(t -> t != null && t.slots() != null && !t.slots().isEmpty())
                .toList();
        if (usable.isEmpty()) {
            return null;
        }
        if (usable.size() == 1) {
            return usable.getFirst();
        }

        int slotCount = usable.stream().mapToInt(t -> t.slots().size()).min().orElse(0);
        List<SolarCloudSlot> averaged = new ArrayList<>(slotCount);
        for (int i = 0; i < slotCount; i++) {
            final int idx = i;
            List<SolarCloudSlot> atIndex = usable.stream().map(t -> t.slots().get(idx)).toList();
            averaged.add(new SolarCloudSlot(
                    atIndex.getFirst().hoursBeforeEvent(),
                    meanInt(atIndex, SolarCloudSlot::lowCloudPercent),
                    meanNullable(atIndex, SolarCloudSlot::midCloudPercent),
                    meanNullable(atIndex, SolarCloudSlot::highCloudPercent)));
        }
        return new SolarCloudTrend(averaged);
    }

    /**
     * Integer mean of a required slot field, truncating like the solar-cone average.
     *
     * @param slots    the slots to average
     * @param accessor the field to read
     * @return the truncated mean
     */
    private static int meanInt(List<SolarCloudSlot> slots, ToIntFunction<SolarCloudSlot> accessor) {
        int sum = 0;
        for (SolarCloudSlot slot : slots) {
            sum += accessor.applyAsInt(slot);
        }
        return sum / slots.size();
    }

    /**
     * Integer mean of an optional slot field, or {@code null} if any contributing slot omitted it.
     *
     * @param slots    the slots to average
     * @param accessor the nullable field to read
     * @return the truncated mean, or {@code null} if the field was not captured everywhere
     */
    private static Integer meanNullable(List<SolarCloudSlot> slots,
            Function<SolarCloudSlot, Integer> accessor) {
        int sum = 0;
        for (SolarCloudSlot slot : slots) {
            Integer value = accessor.apply(slot);
            if (value == null) {
                return null;
            }
            sum += value;
        }
        return sum / slots.size();
    }
}
