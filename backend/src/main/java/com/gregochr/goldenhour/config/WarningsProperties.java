package com.gregochr.goldenhour.config;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Bound to the {@code photocast.warnings} section of {@code application.yml}: the Met Office weather-warnings
 * ingest ({@code WarningPollingJob}).
 *
 * <p>On by default (owner decision 2026-11-18), but the feature is <em>active</em> only when it is both enabled
 * and has an API key ({@link #isActive()}): a build without a key is completely dormant and never calls the
 * Met Office. {@code enabled=false} remains the kill switch.
 *
 * <p>{@link #apiKey} is a secret: it is sent only as the {@link #apiKeyHeader} header (default {@code apikey}), is
 * excluded from {@link #toString()} and is never logged or served.
 */
@Component
@ConfigurationProperties(prefix = "photocast.warnings")
@Validated
@Getter
@Setter
public class WarningsProperties {

    /**
     * The kill switch: whether the feature may run at all. Defaults to {@code true}; whether it actually does is
     * {@link #isActive()}, which also needs an API key.
     */
    private boolean enabled = true;

    /** The NSWWS v1.1 base URL; the client appends {@code /objects/feed/}, {@code /objects/updated/{uuid}/} etc. */
    private String baseUrl = "https://data.hub.api.metoffice.gov.uk/nswws/v1.1";

    /** The NSWWS API key. Never logged, never served. */
    private String apiKey = "";

    /** The request header the key is sent in. The DataHub documents {@code apikey}; some pages say x-api-key. */
    private String apiKeyHeader = "apikey";

    /** The public Met Office warnings page every warning links to (the feed carries no per-warning URL). */
    private String publicUrl = "https://www.metoffice.gov.uk/public/weather/warnings/";

    /** The attribution text served with the warnings (required by the NSWWS licence). */
    private String attribution = "Met Office Weather Warnings \u00a9 Crown Copyright 2026";

    /** Minutes after the last successful poll at which {@code GET /api/warnings} reports {@code stale}. */
    @Min(1)
    private int pollStaleMinutes = 10;

    /**
     * Whether the API key is set (non-blank).
     *
     * @return true when a key is present
     */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * The one effective-on notion every user-visible read uses: the kill switch is on <em>and</em> an API key is set.
     * Without a key the Met Office is never called and every user surface behaves exactly as with the switch off.
     *
     * @return true when warnings should poll and be served
     */
    public boolean isActive() {
        return enabled && hasApiKey();
    }

    /**
     * Excludes the key: a config dump or a logged properties object must never reveal it.
     *
     * @return a description without the secret
     */
    @Override
    public String toString() {
        return "WarningsProperties[enabled=" + enabled + ", keyConfigured=" + hasApiKey()
                + ", pollStaleMinutes=" + pollStaleMinutes + "]";
    }
}
