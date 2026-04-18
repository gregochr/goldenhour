package com.gregochr.goldenhour.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound to the {@code aurora} section of {@code application.yml}.
 *
 * <p>Controls aurora polling, NOAA SWPC endpoint URLs, Met Office scraping,
 * geomagnetic trigger thresholds, Bortle filtering, and the light-pollution
 * enrichment API key.
 */
@Component
@ConfigurationProperties(prefix = "aurora")
@Getter
@Setter
public class AuroraProperties {

    /** Whether the aurora polling job is active. Defaults to {@code true}. */
    private boolean enabled = true;

    /** Minutes between aurora polls. Defaults to 5. */
    private int pollIntervalMinutes = 5;

    /** API key for lightpollutionmap.info QueryRaster endpoint. */
    private String lightPollutionApiKey;

    /** Light pollution API configuration. */
    private LightPollutionConfig lightPollution = new LightPollutionConfig();

    /** NOAA SWPC endpoint configuration. */
    private NoaaConfig noaa = new NoaaConfig();

    /** Thresholds that trigger a NOTIFY from the state machine. */
    private TriggerConfig triggers = new TriggerConfig();

    /** Bortle class upper bounds for aurora candidate selection. */
    private BortleThreshold bortleThreshold = new BortleThreshold();

    /**
     * NOAA SWPC endpoint URLs and cache TTLs.
     */
    @Getter
    @Setter
    public static class NoaaConfig {

        /** Current Kp index endpoint. */
        private String kpUrl =
                "https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json";

        /** 3-day Kp forecast endpoint. */
        private String kpForecastUrl =
                "https://services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json";

        /** OVATION aurora probability grid endpoint (~900KB). */
        private String ovationUrl =
                "https://services.swpc.noaa.gov/json/ovation_aurora_latest.json";

        /** Real-time solar wind magnetic field (Bz) endpoint. */
        private String solarWindUrl =
                "https://services.swpc.noaa.gov/products/solar-wind/mag-1-day.json";

        /** Real-time solar wind plasma (speed and density) endpoint. */
        private String solarWindPlasmaUrl =
                "https://services.swpc.noaa.gov/products/solar-wind/plasma-1-day.json";

        /** Active G-scale watches/warnings/alerts endpoint. */
        private String alertsUrl =
                "https://services.swpc.noaa.gov/products/alerts.json";
    }

    /**
     * Thresholds that trigger a state machine NOTIFY.
     */
    @Getter
    @Setter
    public static class TriggerConfig {

        /** Minimum Kp index to trigger MODERATE alert. Defaults to 5. */
        private double kpThreshold = 5.0;

        /** Minimum OVATION aurora probability (%) at ~55°N to trigger. Defaults to 20. */
        private double ovationProbabilityThreshold = 20.0;

        /** Hours of Kp forecast lookahead for advance-warning trigger. Defaults to 6. */
        private int kpForecastLookaheadHours = 6;

        /** Kp below which de-escalation toward CLEAR is considered. Defaults to 5. */
        private double kpClearThreshold = 5.0;

        /** OVATION probability below which de-escalation toward CLEAR is considered. Defaults to 15. */
        private double ovationClearThreshold = 15.0;
    }

    /**
     * Light pollution API configuration (lightpollutionmap.info).
     */
    @Getter
    @Setter
    public static class LightPollutionConfig {

        /** Base URL for the QueryRaster API. */
        private String apiUrl = "https://www.lightpollutionmap.info/api/queryraster";

        /** Data layer to query (e.g. sb_2025 for Sky Brightness 2025). */
        private String queryLayer = "sb_2025";

        /** Maximum API requests per day. Quota resets at GMT+1. */
        private int dailyLimit = 1000;

        /** Delay in milliseconds between consecutive API calls. */
        private int delayBetweenCallsMs = 500;
    }

    /**
     * Bortle class upper bounds for aurora candidate selection.
     */
    @Getter
    @Setter
    public static class BortleThreshold {

        /** Maximum Bortle class to include for MODERATE alerts. Defaults to 4. */
        private int moderate = 4;

        /** Maximum Bortle class to include for STRONG alerts. Defaults to 5. */
        private int strong = 5;
    }
}
