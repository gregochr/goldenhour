package com.gregochr.goldenhour.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound to the {@code anthropic} section of {@code application.yml}.
 */
@Component
@ConfigurationProperties(prefix = "anthropic")
@Getter
@Setter
public class AnthropicProperties {

    /** Anthropic API key. */
    private String apiKey;
}
