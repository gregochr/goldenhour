package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.FreshnessProperties;
import com.gregochr.goldenhour.entity.ForecastStability;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Resolves the maximum cache age for a given weather stability level.
 *
 * <p>Shared primitive for both the overnight batch CACHED gate and (future)
 * intraday refresh candidate selection. One source of truth for how
 * stability maps to freshness — prevents threshold drift between flows.
 *
 * <p>Applies a safety floor so very recent entries are never re-evaluated
 * regardless of stability classification.
 */
@Component
public class FreshnessResolver {

    private static final Logger LOG = LoggerFactory.getLogger(FreshnessResolver.class);

    private final FreshnessProperties props;

    /**
     * Constructs the resolver with the given freshness configuration.
     *
     * @param props freshness properties bound from application config
     */
    public FreshnessResolver(FreshnessProperties props) {
        this.props = props;
    }

    /**
     * Logs effective freshness thresholds at startup.
     */
    @PostConstruct
    void logThresholds() {
        LOG.info("[FRESHNESS] Cache freshness thresholds — SETTLED={}h (blocking persistence), "
                        + "TRANSITIONAL={}h (half synoptic cycle), UNSETTLED={}h (nowcasting window), "
                        + "safety floor={}h | horizon caps — T+0={}h, T+1={}h, T+2 and beyond "
                        + "uncapped (stability governs)",
                props.getSettledHours(), props.getTransitionalHours(),
                props.getUnsettledHours(), props.getSafetyFloorHours(),
                props.getHorizon().getT0Hours(), props.getHorizon().getT1Hours());
    }

    /**
     * Returns the maximum age at which a cache entry is considered fresh
     * for a given stability level, with no horizon cap applied.
     *
     * <p>Callers that know the forecast horizon should prefer
     * {@link #maxAgeFor(int, ForecastStability)} — an evaluation for tonight and one for four
     * days out are not equally allowed to be stale. This overload is retained for the three
     * call sites where no horizon exists to pass: the two stability-breakdown log lines in
     * {@code BriefingCandidateCollector}, and {@code BriefingEvaluationService}'s delta-log
     * write, where the value is deliberately a stability-band stamp rather than a record of
     * the threshold the gate applied.
     *
     * @param level the weather stability level
     * @return the maximum cache age as a Duration
     */
    public Duration maxAgeFor(ForecastStability level) {
        return applyFloor(stabilityBase(level));
    }

    /**
     * Returns the maximum age at which a cache entry is considered fresh for a given forecast
     * horizon and stability level — {@code max(safetyFloor, min(stabilityThreshold, horizonCap))}.
     *
     * <p>Taking the <em>tighter</em> of the two is what makes this safe to roll out: the result
     * can never exceed {@link #maxAgeFor(ForecastStability)} for the same stability, so no slot
     * at any horizon becomes staler than it was before horizon caps existed. A misconfigured cap
     * degrades to the previous behaviour rather than to something worse.
     *
     * @param daysAhead forecast horizon in days, T+0 = 0. Values below zero are treated as T+0;
     *                  past dates are filtered out well before this point, so this is defensive
     *                  rather than meaningful
     * @param level     the weather stability level
     * @return the maximum cache age as a Duration
     */
    public Duration maxAgeFor(int daysAhead, ForecastStability level) {
        Duration base = stabilityBase(level);
        Duration cap = horizonCap(daysAhead);
        Duration tighter = (cap != null && cap.compareTo(base) < 0) ? cap : base;
        return applyFloor(tighter);
    }

    /**
     * The stability-derived threshold, before any horizon cap or floor.
     *
     * @param level the weather stability level
     * @return the configured duration for that band
     */
    private Duration stabilityBase(ForecastStability level) {
        return switch (level) {
            case SETTLED -> Duration.ofHours(props.getSettledHours());
            case TRANSITIONAL -> Duration.ofHours(props.getTransitionalHours());
            case UNSETTLED -> Duration.ofHours(props.getUnsettledHours());
        };
    }

    /**
     * The horizon cap, or {@code null} when this horizon is uncapped and stability alone governs.
     *
     * @param daysAhead forecast horizon in days, T+0 = 0
     * @return the cap, or null for T+2 and beyond
     */
    private Duration horizonCap(int daysAhead) {
        if (daysAhead <= 0) {
            return Duration.ofHours(props.getHorizon().getT0Hours());
        }
        if (daysAhead == 1) {
            return Duration.ofHours(props.getHorizon().getT1Hours());
        }
        return null;
    }

    /**
     * Raises a threshold to the safety floor if it falls below it.
     *
     * @param threshold the resolved threshold
     * @return the threshold, or the floor if the threshold is tighter
     */
    private Duration applyFloor(Duration threshold) {
        Duration floor = Duration.ofHours(props.getSafetyFloorHours());
        return threshold.compareTo(floor) < 0 ? floor : threshold;
    }
}
