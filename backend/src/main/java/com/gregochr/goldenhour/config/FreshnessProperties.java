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
}
