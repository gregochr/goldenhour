package com.gregochr.goldenhour.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound to the {@code cost} section of {@code application.yml}.
 *
 * <p>Defines the cost for each external API call in units of 1/10th pence (to support fractional pence).
 * For example: 0.5p = 5 units, 1.3p = 13 units. Used by {@code CostCalculator}
 * to track operational expenses for monitoring and billing.
 */
@Component
@ConfigurationProperties(prefix = "cost")
@Getter
@Setter
public class CostProperties {

    /** Cost of a single Haiku (Claude 3.5 Haiku) API call (0.5p = 5 units of 1/10th pence). */
    private Integer anthropicHaikuPence = 5;

    /** Cost of a single Sonnet (Claude 3.5 Sonnet) API call (1.3p = 13 units of 1/10th pence). */
    private Integer anthropicSonnetPence = 13;

    /** Cost of a single Opus (Claude 4 Opus) API call (7.5p = 75 units of 1/10th pence). */
    private Integer anthropicOpusPence = 75;

    /** Cost of a single WorldTides API call (0.2p = 2 units of 1/10th pence). */
    private Integer worldTidesPence = 2;

    /** Cost of Open-Meteo API calls (0p = 0 units, included in subscription). */
    private Integer openMeteoPence = 0;
}
