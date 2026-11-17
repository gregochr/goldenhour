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
 * <p>Off by default. {@link #apiKey} is a secret: it is sent only as the {@code x-api-key} header, is excluded from
 * {@link #toString()} and is never logged or served.
 */
@Component
@ConfigurationProperties(prefix = "photocast.warnings")
@Validated
@Getter
@Setter
public class WarningsProperties {

    /** Whether the poll job runs and the warnings endpoints serve anything. Defaults to {@code false}. */
    private boolean enabled = false;

    /** The Atom feed URL supplied with the NSWWS API key. Detail links are read from the feed itself. */
    private String baseUrl = "";

    /** The NSWWS API key, sent as {@code x-api-key}. Never logged, never served. */
    private String apiKey = "";

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
