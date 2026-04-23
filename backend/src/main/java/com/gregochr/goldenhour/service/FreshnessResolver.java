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
                        + "safety floor={}h",
                props.getSettledHours(), props.getTransitionalHours(),
                props.getUnsettledHours(), props.getSafetyFloorHours());
    }

    /**
     * Returns the maximum age at which a cache entry is considered fresh
     * for a given stability level. Applies a safety floor so very recent
     * entries are never re-evaluated regardless of stability classification.
     *
     * @param level the weather stability level
     * @return the maximum cache age as a Duration
     */
    public Duration maxAgeFor(ForecastStability level) {
        Duration base = switch (level) {
            case SETTLED -> Duration.ofHours(props.getSettledHours());
            case TRANSITIONAL -> Duration.ofHours(props.getTransitionalHours());
            case UNSETTLED -> Duration.ofHours(props.getUnsettledHours());
        };
        Duration floor = Duration.ofHours(props.getSafetyFloorHours());
        return base.compareTo(floor) < 0 ? floor : base;
    }
}
