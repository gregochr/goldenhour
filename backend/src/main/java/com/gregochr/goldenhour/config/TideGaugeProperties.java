package com.gregochr.goldenhour.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration of the weekly Environment Agency gauge check (tide-source-integrity-plan P4), bound
 * to {@code photocast.tide.gauges}.
 *
 * <p>The thresholds ship as defaults with their reasoning here and can be tuned after a few weeks of
 * stored checks. Gauges are matched to a location by NAME at runtime: ids differ between local H2 and
 * production, and a name that matches nothing is skipped and logged.
 */
@Component
@ConfigurationProperties(prefix = "photocast.tide.gauges")
@Getter
@Setter
public class TideGaugeProperties {

    /** Master switch. When false the check does nothing. */
    private boolean enabled = true;

    /** Fewest paired high waters a gauge needs in the window for a check to be recorded. */
    private int minPairs = 8;

    /** How many previous runs form the trailing median a new run is compared with. */
    private int trailingRuns = 4;

    /**
     * Change in median timing error, in minutes, from the trailing median that flags a gauge. The
     * absolute error is not zero (gauges sit in harbours and "Mean" readings are stamped at either
     * end of their period), so only movement is judged.
     */
    private double flagMinutes = 10.0;

    /** The gauge-to-location mappings. */
    private List<Measure> measures = new ArrayList<>();

    /** One gauge mapping. */
    @Getter
    @Setter
    public static class Measure {

        /** Environment Agency measure id, e.g. {@code E70939-level-tidal_level-Mean-15_min-mAOD}. */
        private String measureId;

        /** Name of the location the gauge stands for. */
        private String locationName;
    }
}
