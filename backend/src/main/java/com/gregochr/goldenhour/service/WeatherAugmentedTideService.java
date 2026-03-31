package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.CoastalParameters;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Augments astronomical tide data with weather-driven storm surge.
 *
 * <p>This service sits between the existing forecast pipeline and the
 * PromptBuilder/BriefingBestBetAdvisor. It takes the existing TideSnapshot
 * (lunar-driven, deterministic) and the weather forecast (pressure, wind)
 * and produces an augmented view that includes storm surge components.
 *
 * <h3>Tide-surge interaction caveat (Horsburgh &amp; Wilson 2007):</h3>
 * <p>The adjusted range is calculated as astronomical range + surge magnitude.
 * This is an <b>upper-bound estimate</b>. In the North Sea, tide-surge
 * interaction causes surge peaks to cluster 3–5 hours before high water,
 * meaning the actual high water level is typically less than simple addition.
 * The adjusted range should be presented as "up to X.Xm" not "exactly X.Xm".
 */
@Service
public class WeatherAugmentedTideService {

    private static final Logger LOG = LoggerFactory.getLogger(WeatherAugmentedTideService.class);

    private final StormSurgeService stormSurgeService;

    /**
     * Constructs a {@code WeatherAugmentedTideService}.
     *
     * @param stormSurgeService the storm surge calculation service
     */
    public WeatherAugmentedTideService(StormSurgeService stormSurgeService) {
        this.stormSurgeService = stormSurgeService;
    }

    /**
     * Calculate surge and produce augmented tide information.
     *
     * @param pressureHpa          MSLP from Open-Meteo at time of next high tide (hPa)
     * @param windSpeedMs          10m wind speed at time of next high tide (m/s)
     * @param windDirectionDegrees wind direction at time of next high tide (degrees FROM)
     * @param coastalParams        per-location coastal parameters
     * @param lunarTideType        "KING_TIDE", "SPRING_TIDE", or "REGULAR_TIDE"
     * @param astronomicalRangeM   predicted tidal range (m)
     * @param nextHighTideHeightM  predicted next high tide height (m)
     * @return result containing surge breakdown + adjusted values
     */
    public AugmentedTideResult augment(
            double pressureHpa,
            double windSpeedMs,
            double windDirectionDegrees,
            CoastalParameters coastalParams,
            String lunarTideType,
            double astronomicalRangeM,
            double nextHighTideHeightM) {

        StormSurgeBreakdown surge = stormSurgeService.calculate(
                pressureHpa, windSpeedMs, windDirectionDegrees,
                coastalParams, lunarTideType);

        double adjustedRange = astronomicalRangeM + surge.totalSurgeMetres();
        double adjustedHighTide = nextHighTideHeightM + surge.totalSurgeMetres();

        LOG.debug("Augmented tide: astro_range={}m, adjusted={}m (+{}m surge)",
                astronomicalRangeM, adjustedRange, surge.totalSurgeMetres());

        return new AugmentedTideResult(surge, adjustedRange, adjustedHighTide);
    }

    /**
     * Result record wrapping the surge breakdown with adjusted tide values.
     *
     * @param surgeBreakdown         detailed surge component breakdown
     * @param adjustedRangeMetres    astronomical range + total surge (upper bound)
     * @param adjustedHighTideMetres predicted high tide + total surge (upper bound)
     */
    public record AugmentedTideResult(
            StormSurgeBreakdown surgeBreakdown,
            double adjustedRangeMetres,
            double adjustedHighTideMetres
    ) {

        /**
         * Whether the surge is significant enough to affect tide assessment.
         *
         * @return true if the surge exceeds the significance threshold
         */
        public boolean hasMeaningfulSurge() {
            return surgeBreakdown.isSignificant();
        }

        /**
         * Whether this combination represents a rare opportunity:
         * spring/king tide with meaningful positive surge.
         *
         * @param lunarTideType the lunar tide classification string
         * @return true if this is a rare spring/king + surge event
         */
        public boolean isRareOpportunity(String lunarTideType) {
            return hasMeaningfulSurge()
                    && ("KING_TIDE".equals(lunarTideType) || "SPRING_TIDE".equals(lunarTideType))
                    && surgeBreakdown.totalSurgeMetres() >= 0.15;
        }
    }
}
