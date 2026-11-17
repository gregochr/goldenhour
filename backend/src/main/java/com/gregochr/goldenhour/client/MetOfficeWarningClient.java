package com.gregochr.goldenhour.client;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.net.URI;

/**
 * Declarative HTTP interface for the Met Office National Severe Weather Warning Service (NSWWS) public API.
 *
 * <p>Both calls take an absolute {@link URI}: the Atom feed URL comes with the API key
 * ({@code photocast.warnings.base-url}) and every detail link is read from the feed itself, as the API requires.
 * The {@code x-api-key} header is set once on the underlying {@code RestClient} (see {@code AppConfig}), so it is
 * never a parameter and never appears in a log line. The bodies come back raw so that all feed parsing lives in one
 * class ({@code WarningFeedParser}). There is no retry here: the next poll is the retry. A non-2xx answer surfaces
 * as a {@code RestClientResponseException} carrying the status.
 */
@HttpExchange
public interface MetOfficeWarningClient {

    /**
     * Reads the Atom feed of current warnings.
     *
     * @param feedUri the feed URL supplied with the key
     * @return the raw Atom XML
     */
    @GetExchange(accept = "application/atom+xml, application/xml, text/xml")
    String getFeed(URI feedUri);

    /**
     * Reads one warning's GeoJSON detail.
     *
     * @param detailUri a detail link taken from a feed entry
     * @return the raw GeoJSON text
     */
    @GetExchange(accept = "application/geo+json, application/json")
    String getWarning(URI detailUri);
}
