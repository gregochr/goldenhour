package com.gregochr.goldenhour.client;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import jakarta.annotation.PostConstruct;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.ZoneOffset;

/**
 * Scrapes the Met Office specialist space weather forecast page for human-readable text.
 *
 * <p>The Met Office page at {@code https://weather.metoffice.gov.uk/specialist-forecasts/space-weather/}
 * provides a 7-day UK-specific space weather narrative written by Met Office forecasters.
 * This text is passed to Claude as additional qualitative context during aurora triage.
 *
 * <p>The page is scraped on a configurable interval (default 60 minutes) and the result
 * is cached in memory. On scrape failure the previous cached text is retained.
 */
@Component
public class MetOfficeSpaceWeatherScraper {

    private static final Logger LOG = LoggerFactory.getLogger(MetOfficeSpaceWeatherScraper.class);

    /**
     * Maximum number of characters to retain from the scraped narrative.
     * The Met Office page can be verbose; we truncate to keep prompt size manageable.
     */
    private static final int MAX_TEXT_LENGTH = 2000;

    private final AuroraProperties properties;
    private final DynamicSchedulerService dynamicSchedulerService;

    /** Cached forecast text. Null until the first successful scrape. */
    private volatile String cachedForecastText;

    /** When the cache was last populated. */
    private volatile ZonedDateTime lastScrapedAt;

    /**
     * Constructs the scraper with aurora configuration.
     *
     * @param properties              aurora configuration (Met Office URL + scrape interval)
     * @param dynamicSchedulerService the dynamic scheduler for job registration
     */
    public MetOfficeSpaceWeatherScraper(AuroraProperties properties,
            DynamicSchedulerService dynamicSchedulerService) {
        this.properties = properties;
        this.dynamicSchedulerService = dynamicSchedulerService;
    }

    /**
     * Registers the Met Office scrape job with the dynamic scheduler.
     */
    @PostConstruct
    void registerJob() {
        dynamicSchedulerService.registerJobTarget("met_office_scrape", this::scheduledScrape);
    }

    /**
     * Returns the cached Met Office space weather forecast text.
     *
     * <p>Triggers a fresh scrape if the cache has expired or is empty.
     *
     * @return forecast narrative text, or {@code null} if no successful scrape has occurred
     */
    public String getForecastText() {
        if (isCacheFresh()) {
            return cachedForecastText;
        }
        return scrapeNow();
    }

    /**
     * Returns the time of the last successful scrape, or {@code null} if never scraped.
     *
     * @return last scrape timestamp or {@code null}
     */
    public ZonedDateTime getLastScrapedAt() {
        return lastScrapedAt;
    }

    /**
     * Scheduled re-scrape of the Met Office page.
     *
     * <p>The fixed-delay schedule prevents overlap if a scrape takes longer than expected.
     * Initial delay of 5 minutes avoids hitting the page immediately on startup.
     */
    public void scheduledScrape() {
        if (!properties.isEnabled()) {
            return;
        }
        scrapeNow();
    }

    /**
     * Performs a fresh scrape and updates the cache.
     *
     * @return the scraped forecast text, or the previous cached text on failure
     */
    String scrapeNow() {
        try {
            Document doc = Jsoup.connect(properties.getMetOffice().getSpaceWeatherUrl())
                    .userAgent("GoldenHour-Aurora/1.0 (aurora-monitoring; +https://photocast.online)")
                    .timeout(10_000)
                    .get();

            String text = extractForecastText(doc);
            if (text != null && !text.isBlank()) {
                cachedForecastText = text;
                lastScrapedAt = ZonedDateTime.now(ZoneOffset.UTC);
                LOG.info("Met Office space weather scraped: {} chars", text.length());
            } else {
                LOG.warn("Met Office scrape returned empty text — retaining cached");
            }
        } catch (Exception e) {
            LOG.warn("Met Office space weather scrape failed (retaining cached): {}", e.getMessage());
        }
        return cachedForecastText;
    }

    /**
     * Extracts the main forecast narrative from the scraped HTML document.
     *
     * <p>Strategy: look for the primary content area (a {@code <main>} element or the
     * first large {@code <div>} with meaningful paragraph content). Concatenates all
     * {@code <p>} text within that area, then truncates to {@value #MAX_TEXT_LENGTH} chars.
     *
     * @param doc parsed Jsoup document
     * @return extracted text, or {@code null} if nothing useful was found
     */
    String extractForecastText(Document doc) {
        // Try the <main> element first
        Element main = doc.selectFirst("main");
        if (main != null) {
            String text = main.select("p").text();
            if (!text.isBlank()) {
                return truncate(text);
            }
        }

        // Fall back to the largest block of paragraph text on the page
        String text = doc.select("article p, .forecast p, .content p, section p").text();
        if (!text.isBlank()) {
            return truncate(text);
        }

        // Last resort: all paragraphs
        text = doc.select("p").text();
        return text.isBlank() ? null : truncate(text);
    }

    private boolean isCacheFresh() {
        if (cachedForecastText == null || lastScrapedAt == null) {
            return false;
        }
        return ZonedDateTime.now(ZoneOffset.UTC)
                .isBefore(lastScrapedAt.plusMinutes(
                        properties.getMetOffice().getScrapeIntervalMinutes()));
    }

    private String truncate(String text) {
        if (text.length() <= MAX_TEXT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_TEXT_LENGTH) + "…";
    }
}
