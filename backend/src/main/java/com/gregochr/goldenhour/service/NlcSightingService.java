package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.NlcSightingClient;
import com.gregochr.goldenhour.config.NlcProperties;
import com.gregochr.goldenhour.model.NlcNightClarity;
import com.gregochr.goldenhour.model.NlcSightingReport;
import com.gregochr.goldenhour.model.NlcSightingResponse;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Decides whether there is a noctilucent-cloud (NLC) sighting worth surfacing right now.
 *
 * <p>The banner is a <strong>reactive community signal, not a forecast</strong>. It fires only
 * when all of the agreed conditions hold:
 * <ol>
 *   <li><strong>In season</strong> — late May to early August (shared with
 *       {@link NlcClarityService#isNlcSeason(LocalDate)}).</li>
 *   <li><strong>Fresh report</strong> — a scraped NLCNET observer report from within the last
 *       {@link NlcProperties#getFreshnessHours()} hours; stale reports are history, not news.</li>
 *   <li><strong>Local skies clear tonight</strong> — the {@link NlcClarityService} scan found at
 *       least one dark-sky location with a clear northern horizon tonight. Without a confirmed
 *       clear night the banner stays dark, because its copy asserts "clear skies tonight" and
 *       PhotoCast must not claim what it cannot confirm.</li>
 * </ol>
 *
 * <p>When any gate fails the response is {@link NlcSightingResponse#inactive()} and the banner
 * renders nothing. The scrape itself runs on the {@code nlc_sighting_scrape} scheduled job, not
 * on user requests, and fails open — so this read path is cheap and never throws.
 */
@Service
public class NlcSightingService {

    private static final Logger LOG = LoggerFactory.getLogger(NlcSightingService.class);

    /** Scheduler job key for the periodic NLCNET scrape (seeded by V120). */
    public static final String SCRAPE_JOB_KEY = "nlc_sighting_scrape";

    /** UK local zone — "tonight" and the season gate are evaluated in local time. */
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    /** Fixed look direction: NLC glow low on the northern horizon, NW after dusk / NE before dawn. */
    private static final String LOOK_DIRECTION = "N–NW";

    /** Country tokens stripped when deriving the descriptive region from a reported location. */
    private static final List<String> COUNTRY_TOKENS = List.of("UK", "United Kingdom");

    private final NlcSightingClient client;
    private final NlcClarityService clarityService;
    private final NlcProperties properties;
    private final DynamicSchedulerService schedulerService;
    private final Clock clock;

    /**
     * Constructs the service.
     *
     * @param client           the NLCNET sighting scraper
     * @param clarityService   the NLC clear-night scan (season + clear-sky gate)
     * @param properties       NLC configuration
     * @param schedulerService the dynamic scheduler for scrape-job registration
     * @param clock            clock used to resolve "today" and the freshness cutoff
     */
    public NlcSightingService(NlcSightingClient client, NlcClarityService clarityService,
            NlcProperties properties, DynamicSchedulerService schedulerService, Clock clock) {
        this.client = client;
        this.clarityService = clarityService;
        this.properties = properties;
        this.schedulerService = schedulerService;
        this.clock = clock;
    }

    /**
     * Registers the NLCNET scrape target with the dynamic scheduler.
     */
    @PostConstruct
    void registerJob() {
        schedulerService.registerJobTarget(SCRAPE_JOB_KEY, this::scrapeIfEnabled);
    }

    /** Re-scrapes NLCNET when the feature is enabled; a no-op otherwise. */
    void scrapeIfEnabled() {
        if (properties.isSightingEnabled()) {
            client.refresh();
        }
    }

    /**
     * Returns the current NLC sighting signal, applying the season / freshness / clear-sky gates.
     *
     * @return an active response when a fresh, relevant sighting exists under clear skies;
     *     otherwise {@link NlcSightingResponse#inactive()}
     */
    public NlcSightingResponse currentSighting() {
        if (!properties.isSightingEnabled()) {
            return NlcSightingResponse.inactive();
        }

        LocalDate today = LocalDate.now(clock.withZone(LONDON));
        if (!clarityService.isNlcSeason(today)) {
            return NlcSightingResponse.inactive();
        }

        NlcSightingReport report = freshestReport();
        if (report == null) {
            return NlcSightingResponse.inactive();
        }

        NlcNightClarity.ClearNight clearNight = clearNightTonight(today);
        if (clearNight == null) {
            // No confirmed clear dark-sky location tonight — cannot honestly claim clear skies.
            return NlcSightingResponse.inactive();
        }

        String observerLocation = observerPlace(report.location());
        String region = describableRegion(report.location());
        LOG.debug("NLC sighting active: report {} over {} ({} clear dark-sky location(s))",
                report.reportedAt(), region, clearNight.clearLocationCount());

        return new NlcSightingResponse(
                true,
                report.reportedAt(),
                observerLocation,
                region,
                properties.getSource(),
                Boolean.TRUE,
                clearNight.clearLocationCount(),
                LOOK_DIRECTION,
                properties.getHexColour(),
                "Noctilucent cloud reported over " + region);
    }

    /** Returns the newest report within the freshness window, or null when none is fresh. */
    private NlcSightingReport freshestReport() {
        List<NlcSightingReport> reports = client.getReports();
        if (reports.isEmpty()) {
            return null;
        }
        NlcSightingReport newest = reports.get(0); // client returns newest-first
        Instant cutoff = clock.instant().minus(Duration.ofHours(properties.getFreshnessHours()));
        return newest.reportedAt().isBefore(cutoff) ? null : newest;
    }

    /** Returns tonight's clear-night entry from the clarity cache, or null when tonight is not clear. */
    private NlcNightClarity.ClearNight clearNightTonight(LocalDate today) {
        NlcNightClarity clarity = clarityService.getCached();
        if (clarity == null) {
            return null;
        }
        return clarity.clearNights().stream()
                .filter(n -> n.date().isEqual(today))
                .findFirst()
                .orElse(null);
    }

    /** The observer's place — the text before the first comma, e.g. "Elgin" from "Elgin, Scotland". */
    private static String observerPlace(String location) {
        int comma = location.indexOf(',');
        return comma > 0 ? location.substring(0, comma).trim() : location.trim();
    }

    /**
     * The broader region used in the description sentence — the last location token, skipping a
     * trailing country token, e.g. "Scotland" from "Broughty Ferry, Dundee, Scotland, UK".
     */
    private static String describableRegion(String location) {
        String[] parts = location.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {
            String token = parts[i].trim();
            if (!token.isEmpty() && !isCountryToken(token)) {
                return token;
            }
        }
        return location.trim();
    }

    private static boolean isCountryToken(String token) {
        return COUNTRY_TOKENS.stream().anyMatch(token::equalsIgnoreCase);
    }
}
