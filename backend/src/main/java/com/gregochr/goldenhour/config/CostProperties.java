package com.gregochr.goldenhour.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound to the {@code cost} section of {@code application.yml}.
 *
 * <p>Defines the cost in pence for each external API call. Used by {@code CostCalculator}
 * to track operational expenses for monitoring and billing.
 */
@Component
@ConfigurationProperties(prefix = "cost")
@Getter
@Setter
public class CostProperties {

    /** Cost of a single Haiku (Claude 3.5 Haiku) API call in pence. */
    private Integer anthropicHaikuPence = 50;

    /** Cost of a single Sonnet (Claude 3.5 Sonnet) API call in pence. */
    private Integer anthropicSonnetPence = 130;

    /** Cost of a single WorldTides API call in pence. */
    private Integer worldTidesPence = 20;

    /** Cost of Open-Meteo API calls in pence (usually 0 — included in subscription). */
    private Integer openMeteoPence = 0;
}
