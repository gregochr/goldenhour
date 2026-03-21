package com.gregochr.goldenhour.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound to the {@code aurora} section of {@code application.yml}.
 *
 * <p>Controls AuroraWatch polling, Bortle thresholds for candidate filtering,
 * and the light-pollution enrichment API key.
 */
@Component
@ConfigurationProperties(prefix = "aurora")
@Getter
@Setter
public class AuroraProperties {

    /** Whether the aurora polling job is active. Defaults to {@code true}. */
    private boolean enabled = true;

    /** Minutes between AuroraWatch polls. Defaults to 5. */
    private int pollIntervalMinutes = 5;

    /** AuroraWatch API status URL. */
    private String aurorawatchStatusUrl =
            "http://aurorawatch-api.lancs.ac.uk/0.2/status/current-status.xml";

    /** Minimum seconds between AuroraWatch re-fetches (respects Expires). Defaults to 180. */
    private int minPollIntervalSeconds = 180;

    /** API key for lightpollutionmap.info QueryRaster endpoint. */
    private String lightPollutionApiKey;

    /** Bortle thresholds for selecting candidate locations. */
    private BortleThreshold bortleThreshold = new BortleThreshold();

    /**
     * Bortle class upper bounds for aurora candidate selection.
     */
    @Getter
    @Setter
    public static class BortleThreshold {

        /** Maximum Bortle class to include for AMBER alerts. Defaults to 4. */
        private int amber = 4;

        /** Maximum Bortle class to include for RED alerts. Defaults to 5. */
        private int red = 5;
    }
}
