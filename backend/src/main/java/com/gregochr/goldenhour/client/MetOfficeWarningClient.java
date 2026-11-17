package com.gregochr.goldenhour.client;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * Declarative HTTP interface for the Met Office National Severe Weather Warning Service (NSWWS) v1.1 API.
 *
 * <p>The base URL ({@code photocast.warnings.base-url}) and the API key header
 * ({@code photocast.warnings.api-key-header}, default {@code apikey}, sent only when a key exists) are set once on
 * the underlying {@code RestClient} (see {@code AppConfig}), so neither is a parameter and the key never appears in a
 * log line. All paths end in a slash, as the API requires. The bodies come back raw so that all parsing lives in
 * one class ({@code WarningFeedParser}). There is no retry here: the next poll is the retry. A non-2xx answer
 * surfaces as a {@code RestClientResponseException} carrying the status.
 */
@HttpExchange
public interface MetOfficeWarningClient {

    /** The media types the GeoJSON documents may come back as. */
    String GEO_JSON = "application/vnd.geo+json, application/geo+json, application/json";

    /**
     * Reads the Atom feed of recent change events (the feed remembers about 24 hours).
     *
     * @return the raw Atom XML
     */
    @GetExchange(value = "/objects/feed/", accept = "application/atom+xml, application/xml, text/xml")
    String getFeed();

    /**
     * Reads one change event's GeoJSON {@code FeatureCollection} of the warnings it added or changed.
     *
     * @param uuid the change-event uuid taken from a feed entry
     * @return the raw GeoJSON text
     */
    @GetExchange(value = "/objects/updated/{uuid}/", accept = GEO_JSON)
    String getUpdated(@PathVariable("uuid") String uuid);

    /**
     * Reads the snapshot of the latest version of every currently valid warning.
     *
     * @param uuid the snapshot uuid taken from the feed's {@code related} link
     * @return the raw GeoJSON text
     */
    @GetExchange(value = "/objects/issued/{uuid}/", accept = GEO_JSON)
    String getIssued(@PathVariable("uuid") String uuid);
}
