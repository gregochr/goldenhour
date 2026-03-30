package com.gregochr.goldenhour.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers health probe beans for external service monitoring.
 *
 * <p>These are defined as {@code @Bean} methods rather than {@code @Component} classes
 * so they are excluded from {@code @WebMvcTest} component scanning, which would fail
 * to resolve their dependencies.
 */
@Configuration
public class HealthProbeConfig {

    /**
     * Open-Meteo weather API health probe.
     *
     * @return health indicator bean
     */
    @Bean("openMeteo")
    public OpenMeteoHealthIndicator openMeteoHealthIndicator() {
        return new OpenMeteoHealthIndicator();
    }

    /**
     * WorldTides API health probe.
     *
     * @param worldTidesProperties WorldTides configuration
     * @return health indicator bean
     */
    @Bean("tideCheck")
    public TideCheckHealthIndicator tideCheckHealthIndicator(WorldTidesProperties worldTidesProperties) {
        return new TideCheckHealthIndicator(worldTidesProperties);
    }

    /**
     * Anthropic Claude API health probe.
     *
     * @param anthropicProperties Anthropic configuration
     * @return health indicator bean
     */
    @Bean("claudeApi")
    public ClaudeApiHealthIndicator claudeApiHealthIndicator(AnthropicProperties anthropicProperties) {
        return new ClaudeApiHealthIndicator(anthropicProperties);
    }
}
