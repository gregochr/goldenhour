package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gregochr.goldenhour.entity.TideState;

import java.util.List;

/**
 * One shooting window's tide, rolled up for the window-first Plan tab's tide row.
 *
 * <p><b>It describes one coastal location, and says which.</b> The row states an offset to the
 * minute and a range to a tenth of a metre over a roster its own action line calls "61 coastal
 * locations". Alignment differs by ~20–30 minutes across a coastline — enough to flip the sign of
 * the one claim a coastal photographer acts on — so an unattributed high-water time is a claim this
 * project cannot make. {@link #locationName} is the honest form of that caveat and is never null.
 *
 * <p><b>Every time and every metre here belongs to that location, including the solar event.</b>
 * The window's own {@code eventTime} is the earliest slot across every region, a different anchor;
 * mixing one place's high water with another place's sunset would make the offset arithmetic
 * describe nowhere. The same rule already governs {@link BriefingWindow.Badge}: render one anchor or
 * the other, never both side by side.
 *
 * <p><b>Derived at serve time and never persisted.</b> It rides {@link BriefingWindow}, which rides
 * {@code days} — passed straight through by {@code BriefingService.getCachedBriefing} — so it needs
 * no carrier and no migration.
 *
 * <p><b>Absent rather than approximate.</b> No resolvable coastal location, or no day with both a
 * high and a low water at it, means no rollup at all: the row falls back to the per-location tide
 * fact line on {@code BriefingSlot.tide}. Nothing here is ever synthesised to fill a gap.
 *
 * @param locationName   the coastal location every figure below is measured at; never null
 * @param state          the tide state at the window, classified at the same window the per-slot
 *                       tide facts use — half the blue+golden span at this location, on this date,
 *                       for this event — so the row and the drill-down beneath it cannot call the
 *                       same water HIGH and MID
 * @param direction      whether the water is rising or falling through the window
 * @param nearestType    which extreme it is, {@code "HW"} or {@code "LW"} — the nearest of
 *                       <em>either</em> kind, because the row's job is to name the water closest to
 *                       the light
 * @param nearestTime    that extreme's Europe/London clock time, {@code "19:28"}
 * @param nearestOffset  its offset from this window's own solar event, {@code "1h43 before sunset"}
 * @param range          the day's tidal range at this location, {@code "4.9 m"}
 * @param rangeAnomaly   how that range compares with the location's own mean, {@code "1.2 m above an
 *                       average tide"} or {@code "about average"}; null when no historical baseline
 *                       exists, which is not the same statement as "average"
 * @param seas           significant wave height with its sea-state band, {@code "0.3 m · smooth"};
 *                       <b>independently nullable</b> — {@code marine_wave} reaches only T+4 while
 *                       {@code tide_extreme} reaches T+13, so a window past T+4 has a full rollup
 *                       and no sea state. A missing sea state must never suppress the row
 * @param curve          the day's tide shape: heights sampled at even intervals across the local
 *                       day, normalised to 0.0 at the series' lowest water and 1.0 at its highest.
 *                       Shape rather than metres, for the reason {@code TideRunRow} plots a boolean:
 *                       a sparkline this small cannot carry an absolute scale, and the metres are
 *                       stated in words beside it. A {@code List}, never an array — this record is
 *                       compared by {@code equals} through {@link BriefingWindow} whenever the
 *                       payload is round-tripped through JSON, and an array component would compare
 *                       by identity and never match across two instances. Note the mechanism is
 *                       <em>not</em> the one CLAUDE.md's carrier rule describes: this rides
 *                       {@code days}, which {@code BriefingService.getCachedBriefing}'s
 *                       cache-equality shortcut never compares. Different mechanism, same
 *                       value-comparability requirement
 * @param windowPosition where the solar event falls through the local day, 0.0 at midnight to 1.0 at
 *                       the next
 * @param windowLevel    the normalised water level at that instant, on the same 0–1 scale as
 *                       {@link #curve} so the mark sits on the line by construction. Clamped: the
 *                       curve is normalised over its own samples, and the event rarely falls on
 *                       one, so a peak between two samples could otherwise place the mark a
 *                       fraction outside the range this contract promises
 */
public record BriefingWindowTide(
        String locationName,
        TideState state,
        Direction direction,
        String nearestType,
        String nearestTime,
        String nearestOffset,
        String range,
        @JsonInclude(JsonInclude.Include.NON_NULL) String rangeAnomaly,
        @JsonInclude(JsonInclude.Include.NON_NULL) String seas,
        List<Double> curve,
        double windowPosition,
        double windowLevel) {

    public BriefingWindowTide {
        curve = curve == null ? List.of() : List.copyOf(curve);
    }

    /**
     * Which way the water is moving through the window.
     *
     * <p>A separate axis from {@link TideState}, whose Javadoc says outright that it describes where
     * the tide is and not which way it is going. Both are needed: "mid tide" alone does not tell a
     * photographer whether the sand is about to be covered or uncovered.
     */
    public enum Direction {

        /** The next extreme is a high water. */
        RISING,

        /** The next extreme is a low water. */
        FALLING
    }
}
