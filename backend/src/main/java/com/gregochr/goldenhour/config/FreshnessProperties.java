package com.gregochr.goldenhour.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound to the {@code photocast.freshness} section of {@code application.yml}.
 *
 * <p>Controls per-stability-level cache freshness thresholds for the overnight
 * batch CACHED gate and intraday refresh candidate selection. Thresholds are
 * anchored in operational meteorology — see inline comments for rationale.
 *
 * <p>Shared by both the batch gate and (future) intraday refresh via
 * {@link com.gregochr.goldenhour.service.FreshnessResolver}.
 */
@Component
@ConfigurationProperties(prefix = "photocast.freshness")
@Getter
@Setter
public class FreshnessProperties {

    /**
     * Max cache age (hours) for SETTLED weather (blocking highs, persistent ridges).
     * Atmospheric blocking highs persist 4-5+ days minimum — 36h captures a
     * once-daily refresh rhythm with headroom for pattern weakening.
     */
    private int settledHours = 36;

    /**
     * Max cache age (hours) for TRANSITIONAL weather (frontal approach, mixed signals).
     * 12h = half a synoptic update cycle, matching 2x the NWS 6h operational
     * forecast cadence — catches frontal transitions without thrashing.
     */
    private int transitionalHours = 12;

    /**
     * Max cache age (hours) for UNSETTLED weather (active fronts, convective systems).
     * 4h sits at the outer edge of the nowcasting regime where cloud advection
     * methods become unreliable — aggressive enough for convective evolution.
     */
    private int unsettledHours = 4;

    /**
     * Absolute minimum cache age (hours) below which no re-evaluation occurs,
     * regardless of stability classification. Prevents rapid successive triggers
     * from intraday + JFDI + admin actions.
     */
    private int safetyFloorHours = 2;

    /** Per-horizon caps applied on top of the stability thresholds above. */
    private final Horizon horizon = new Horizon();

    /**
     * Caps on cache age by forecast horizon, applied as
     * {@code max(safetyFloor, min(stabilityThreshold, horizonCap))}.
     *
     * <p>The stability thresholds above are calibrated on <em>synoptic pattern</em> persistence,
     * which is not the same quantity as <em>rating</em> persistence: the rating turns on low cloud
     * at the solar horizon, which moves freely underneath a stable pattern. Measured over 30 days
     * of {@code evaluation_delta_log}, a SETTLED slot re-evaluated after a 48h gap — a gap the 36h
     * threshold itself permits — moved by a full star 72% of the time.
     *
     * <p>Taking the tighter of cap and stability makes the caps monotone: no horizon can come out
     * <em>staler</em> than the stability threshold alone would allow, so a misconfigured cap
     * degrades to the previous behaviour rather than to something worse.
     */
    @Getter
    @Setter
    public static class Horizon {

        /**
         * Max cache age (hours) at T+0 — the day of the event.
         *
         * <p>Defaults to the safety floor because on the day of the event there is no horizon
         * left to trade against: the only reason not to re-evaluate is cost, and the floor
         * already exists to stop trigger thrash. Any larger value is an arbitrary amount of
         * staleness shown to someone deciding whether to drive out tonight.
         *
         * <p>Note this is <em>independent</em> of {@code safetyFloorHours} despite sharing its
         * default — raise the floor and the floor governs; raise this to 36 and the change rolls
         * back to stability-only behaviour at T+0.
         */
        private int t0Hours = 2;

        /**
         * Max cache age (hours) at T+1.
         *
         * <p>8h is chosen to survive no cycle boundary: with the nightly cycle landing results
         * near 01:00 UTC and the intraday cycle firing at 14:00, a 12h+ gap always exceeds it,
         * so a T+1 slot is refreshed by both rather than being held by whichever ran first.
         */
        private int t1Hours = 8;
    }
}
