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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scrapes noctilucent-cloud (NLC) observer reports from the NLCNET sightings page.
 *
 * <p>This is the realistic NLC data source: <strong>crowdsourced observer reports</strong>, not
 * a model. No public or paid API delivers a mesospheric (~80&nbsp;km) forecast, so PhotoCast can
 * only react to what someone actually saw. The page is fetched as static HTML and parsed with
 * Jsoup. Two NLCNET layouts are supported:
 *
 * <ul>
 *   <li>the <strong>live real-time season table</strong> (the current source), whose rows carry
 *       {@code <td title="Observer">}, {@code title="Location"}, {@code title="Country"},
 *       {@code title="yy|mm|dd"} and {@code title="UT-Start"} cells — selected by their
 *       {@code title} attribute so column reordering does not break the parse; and</li>
 *   <li>the older <strong>gallery caption</strong> format,
 *       {@code Alan C Tough from Elgin, Scotland on 2023, 06, 11-12 from 01:15 UT …}, kept as a
 *       cheap fallback so an archive URL still parses.</li>
 * </ul>
 *
 * <p>Because the live sightings live on a per-month page ({@code …/2026-july}), the configured URL
 * may contain {@code {year}} / {@code {month}} placeholders, resolved from the current date at
 * scrape time (see {@link #resolveUrl(LocalDate)}), so the scraper follows the season across
 * May–August without a config change.
 *
 * <p><strong>Best-effort and fail-open.</strong> Web scraping is inherently fragile — if the page
 * is unreachable, restructured, or a row does not match, that report (or the whole scrape) is
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

    /** Matches a UT time cell — {@code 00:17} or colon-less {@code 0017}. */
    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):?(\\d{2})");

    /** Matches the first day number in a {@code dd} cell — {@code 01} or a range {@code 01-02}. */
    private static final Pattern FIRST_DAY = Pattern.compile("(\\d{1,2})");

    /** Hour-of-day below which a reported time is treated as the following morning. */
    private static final int MORNING_HOUR_CUTOFF = 12;

    /** UK local zone — the season page URL is resolved against the local date. */
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

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
        String url = resolveUrl(LocalDate.now(LONDON));
        try {
            String html = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);
            List<NlcSightingReport> reports = parse(html);
            cache = new CachedReports(reports, ZonedDateTime.now(ZoneOffset.UTC));
            LOG.debug("NLC sighting scrape parsed {} report(s) from {}", reports.size(), url);
            return reports;
        } catch (Exception e) {
            LOG.warn("NLC sighting scrape failed for {} (retaining cached): {}", url, e.getMessage());
            return current != null ? current.reports() : List.of();
        }
    }

    /**
     * Resolves the configured sightings URL for a given date, substituting {@code {year}} and
     * {@code {month}} placeholders (the current-season page lives at {@code …/2026-july}). A URL
     * with no placeholders — e.g. a fixed archive page — is returned unchanged.
     *
     * <p>Package-private so the season-tracking substitution can be unit-tested without network.
     *
     * @param today the date whose year and month name drive the substitution
     * @return the resolved URL
     */
    String resolveUrl(LocalDate today) {
        String url = properties.getSightingsUrl();
        if (url == null || (!url.contains("{year}") && !url.contains("{month}"))) {
            return url;
        }
        String month = today.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                .toLowerCase(Locale.ENGLISH);
        return url.replace("{year}", Integer.toString(today.getYear())).replace("{month}", month);
    }

    /**
     * Parses NLCNET sighting reports from a page's HTML, newest first.
     *
     * <p>Reads both supported layouts — the live real-time season {@code <table>} (rows keyed by
     * cell {@code title} attribute) and the older {@code div.caption} gallery format — so the same
     * client handles the current source and an archive fallback. Rows/captions that do not match
     * are skipped rather than throwing. Package-private so the parser can be unit-tested against
     * captured HTML without network.
     *
     * @param html the NLCNET page HTML
     * @return parsed reports ordered newest first; unmatched rows are skipped
     */
    List<NlcSightingReport> parse(String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        Document doc = Jsoup.parse(html);
        List<NlcSightingReport> reports = new ArrayList<>();
        for (Element row : doc.select("table tr")) {
            NlcSightingReport report = parseTableRow(row);
            if (report != null) {
                reports.add(report);
            }
        }
        for (Element caption : doc.select("div.caption")) {
            NlcSightingReport report = parseCaption(caption.text());
            if (report != null) {
                reports.add(report);
            }
        }
        reports.sort(Comparator.comparing(NlcSightingReport::reportedAt).reversed());
        return List.copyOf(reports);
    }

    /**
     * Parses a live-table row into a report, or null when the row is the header or is malformed.
     * Cells are located by their {@code title} attribute, so the header row (which uses {@code th})
     * and any incomplete row are skipped naturally.
     */
    private static NlcSightingReport parseTableRow(Element row) {
        String location = cellText(row, "Location");
        String yy = cellText(row, "yy");
        String mm = cellText(row, "mm");
        String dd = cellText(row, "dd");
        String utStart = cellText(row, "UT-Start");
        if (location == null || location.isEmpty()
                || yy == null || mm == null || dd == null || utStart == null) {
            return null;
        }
        Integer year = parseIntOrNull(yy);
        Integer month = parseIntOrNull(mm);
        Integer day = firstMatch(FIRST_DAY, dd);
        int[] time = parseTime(utStart);
        if (year == null || month == null || day == null || time == null) {
            return null;
        }
        Instant reportedAt = toInstant(year, month, day, time[0], time[1]);
        if (reportedAt == null) {
            return null;
        }
        String observer = orEmpty(cellText(row, "Observer"));
        String country = cellText(row, "Country");
        String fullLocation = (country != null && !country.isEmpty())
                ? location + ", " + country
                : location;
        return new NlcSightingReport(observer, fullLocation, reportedAt);
    }

    /** Returns the trimmed text of the {@code td} in {@code row} named by {@code title}, or null. */
    private static String cellText(Element row, String title) {
        Element cell = row.selectFirst("td[title=\"" + title + "\"]");
        return cell != null ? cell.text().trim() : null;
    }

    /** Parses a {@code HH:MM} / {@code HHMM} time cell into {@code [hour, minute]}, or null. */
    private static int[] parseTime(String text) {
        Matcher m = TIME.matcher(text);
        return m.find() ? new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))} : null;
    }

    private static Integer firstMatch(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private static Integer parseIntOrNull(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String orEmpty(String value) {
        return value != null ? value : "";
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
