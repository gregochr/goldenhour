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
     * URL of the NLCNET sightings page scraped for observer reports.
     *
     * <p>Defaults to the <strong>live per-month real-time season table</strong>. The URL may
     * contain {@code {year}} / {@code {month}} placeholders, which
     * {@link com.gregochr.goldenhour.client.NlcSightingClient#resolveUrl(java.time.LocalDate)}
     * substitutes from the current date at scrape time (e.g. {@code …/2026-july}), so the scraper
     * follows the season across May–August without a config change. Off-season the resolved page
     * simply 404s and the scrape fails open. To pin a fixed page instead — e.g. the NLCNET gallery
     * archive {@code https://ed-co.net/nlcnet/} — set a placeholder-free URL; the parser reads both
     * the table and the older gallery caption layout.
     */
    private String sightingsUrl = "https://ed-co.net/nlcnet/{year}-{month}";

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
