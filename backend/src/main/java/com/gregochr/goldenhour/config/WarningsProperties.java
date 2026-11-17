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
 * <p>Off by default. {@link #apiKey} is a secret: it is sent only as the {@link #apiKeyHeader} header (default
 * {@code apikey}), is excluded from {@link #toString()} and is never logged or served.
 */
@Component
@ConfigurationProperties(prefix = "photocast.warnings")
@Validated
@Getter
@Setter
public class WarningsProperties {

    /** Whether the poll job runs and the warnings endpoints serve anything. Defaults to {@code false}. */
    private boolean enabled = false;

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
    private int pollStaleMinutes = 30;

    /**
     * Excludes the key: a config dump or a logged properties object must never reveal it.
     *
     * @return a description without the secret
     */
    @Override
    public String toString() {
        return "WarningsProperties[enabled=" + enabled + ", pollStaleMinutes=" + pollStaleMinutes + "]";
    }
}
