package com.gregochr.goldenhour.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound to the {@code nlc} section of {@code application.yml}.
 *
 * <p>Controls the noctilucent-cloud (NLC) sighting scraper — a REACTIVE community
 * signal (crowdsourced observer reports from NLCNET), not a geophysical forecast.
 * No public model reaches the mesosphere (~80&nbsp;km), so PhotoCast only surfaces
 * that <em>someone reported a sighting</em>, gated on freshness, season and local
 * clear skies. See {@code docs} and the NLC handoff for the honesty rationale.
 */
@Component
@ConfigurationProperties(prefix = "nlc")
@Getter
@Setter
public class NlcProperties {

    /** Whether the NLC sighting scraper and endpoint are active. Defaults to {@code true}. */
    private boolean sightingEnabled = true;

    /**
     * URL of the NLCNET real-time sightings page scraped for observer reports.
     *
     * <p>During season this should point at the live real-time sightings table; the
     * default is the NLCNET gallery, whose caption format the parser targets. Because
     * only reports fresher than {@link #freshnessHours} ever surface, pointing at an
     * archive page is safe — stale entries never activate the banner.
     */
    private String sightingsUrl = "https://ed-co.net/nlcnet/";

    /**
     * Maximum age (hours) of a report for it to count as a live sighting. Reports older
     * than this are treated as history and never surface. Defaults to 6 hours.
     */
    private int freshnessHours = 6;

    /** Minutes the scraped report list is cached before a re-scrape. Defaults to 10. */
    private int cacheTtlMinutes = 10;

    /** Attribution shown on the banner as "… via {source}". Defaults to {@code NLCNET}. */
    private String source = "NLCNET";

    /** NLC violet accent colour driving the banner's {@code --nlc-accent}. */
    private String hexColour = "#8E86D6";
}
