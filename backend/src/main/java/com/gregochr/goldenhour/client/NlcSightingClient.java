package com.gregochr.goldenhour.client;

import com.gregochr.goldenhour.config.NlcProperties;
import com.gregochr.goldenhour.model.NlcSightingReport;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scrapes noctilucent-cloud (NLC) observer reports from the NLCNET sightings page.
 *
 * <p>This is the realistic NLC data source: <strong>crowdsourced observer reports</strong>, not
 * a model. No public or paid API delivers a mesospheric (~80&nbsp;km) forecast, so PhotoCast can
 * only react to what someone actually saw. The page is fetched as static HTML and parsed with
 * Jsoup; each sighting caption follows the verified NLCNET format:
 *
 * <pre>{@code Alan C Tough from Elgin, Scotland on 2023, 06, 11-12 from 01:15 - 01:45 UT. NLC forms: …}</pre>
 *
 * <p><strong>Best-effort and fail-open.</strong> Web scraping is inherently fragile — if the page
 * is unreachable, restructured, or a caption does not match, that report (or the whole scrape) is
 * skipped and the previous cached list is retained. Callers therefore never see an exception; the
 * banner simply stays dark. Results are cached for {@link NlcProperties#getCacheTtlMinutes()} so
 * the scheduled scrape (not user requests) drives the fetch cadence.
 */
@Component
public class NlcSightingClient {

    private static final Logger LOG = LoggerFactory.getLogger(NlcSightingClient.class);

    /**
     * Matches an NLCNET sighting caption, capturing observer, location, date and start time.
     * Trailing content ("- HH:MM UT. NLC forms: …") is ignored. Tolerant of a single-day date
     * ({@code 06, 11}) or a night-spanning range ({@code 06, 11-12}) and of colon-less times
     * ({@code 0540}).
     */
    private static final Pattern CAPTION = Pattern.compile(
            "^(.+?)\\s+from\\s+(.+?)\\s+on\\s+(\\d{4}),\\s*(\\d{1,2}),\\s*(\\d{1,2})"
                    + "(?:\\s*-\\s*\\d{1,2})?\\s+from\\s+(\\d{1,2}):?(\\d{2})");

    /** Hour-of-day below which a reported time is treated as the following morning. */
    private static final int MORNING_HOUR_CUTOFF = 12;

    private final RestClient restClient;
    private final NlcProperties properties;

    private volatile CachedReports cache;

    /**
     * Constructs the client.
     *
     * @param restClient shared outbound HTTP client
     * @param properties NLC configuration (sightings URL, cache TTL)
     */
    public NlcSightingClient(RestClient restClient, NlcProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /**
     * Returns the parsed sighting reports, newest first, using the TTL cache.
     *
     * <p>On the first call (or once the cache is stale) the NLCNET page is fetched and parsed;
     * on any failure the previous cached list is returned, or an empty list if none exists yet.
     *
     * @return sighting reports ordered newest first; never null
     */
    public List<NlcSightingReport> getReports() {
        CachedReports current = cache;
        if (isFresh(current)) {
            return current.reports();
        }
        return refresh();
    }

    /**
     * Forces a re-scrape of the NLCNET page and refreshes the cache.
     *
     * <p>Called by the scheduled scrape job. Fails open: on error the previous cached list is
     * retained and returned.
     *
     * @return the freshly scraped reports, or the retained cache on failure; never null
     */
    public List<NlcSightingReport> refresh() {
        CachedReports current = cache;
        try {
            String html = restClient.get()
                    .uri(properties.getSightingsUrl())
                    .retrieve()
                    .body(String.class);
            List<NlcSightingReport> reports = parse(html);
            cache = new CachedReports(reports, ZonedDateTime.now(ZoneOffset.UTC));
            LOG.debug("NLC sighting scrape parsed {} report(s) from {}",
                    reports.size(), properties.getSightingsUrl());
            return reports;
        } catch (Exception e) {
            LOG.warn("NLC sighting scrape failed (retaining cached): {}", e.getMessage());
            return current != null ? current.reports() : List.of();
        }
    }

    /**
     * Parses NLCNET sighting captions from a page's HTML into reports, newest first.
     *
     * <p>Package-private so the parser can be unit-tested against captured HTML without network.
     *
     * @param html the NLCNET page HTML
     * @return parsed reports ordered newest first; captions that do not match are skipped
     */
    List<NlcSightingReport> parse(String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        Document doc = Jsoup.parse(html);
        List<NlcSightingReport> reports = new ArrayList<>();
        for (Element caption : doc.select("div.caption")) {
            NlcSightingReport report = parseCaption(caption.text());
            if (report != null) {
                reports.add(report);
            }
        }
        reports.sort(Comparator.comparing(NlcSightingReport::reportedAt).reversed());
        return List.copyOf(reports);
    }

    private static NlcSightingReport parseCaption(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = CAPTION.matcher(text.trim());
        if (!m.find()) {
            return null;
        }
        String observer = m.group(1).trim();
        String location = m.group(2).trim();
        if (location.isEmpty()) {
            return null;
        }
        Instant reportedAt = toInstant(
                Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)),
                Integer.parseInt(m.group(7)));
        if (reportedAt == null) {
            return null;
        }
        return new NlcSightingReport(observer, location, reportedAt);
    }

    /**
     * Builds the UTC instant of a sighting from its reported evening date and start time.
     *
     * <p>NLCNET logs the <em>evening</em> date; a small-hours time (before noon) therefore falls
     * on the following calendar day, so the day is advanced by one in that case. Returns null for
     * an out-of-range date/time (a malformed caption).
     */
    private static Instant toInstant(int year, int month, int day, int hour, int minute) {
        try {
            LocalDate date = LocalDate.of(year, month, day);
            if (hour < MORNING_HOUR_CUTOFF) {
                date = date.plusDays(1);
            }
            return date.atTime(hour, minute).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean isFresh(CachedReports cached) {
        return cached != null
                && ZonedDateTime.now(ZoneOffset.UTC)
                        .isBefore(cached.fetchedAt().plusMinutes(properties.getCacheTtlMinutes()));
    }

    /** Immutable cache entry pairing the parsed reports with their fetch time. */
    private record CachedReports(List<NlcSightingReport> reports, ZonedDateTime fetchedAt) {
    }
}
