package com.gregochr.goldenhour.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the OpenRouteService driving-time API.
 *
 * <p>Set {@code openrouteservice.api-key} in {@code application.yml}.
 * If the key is blank or {@code enabled} is false, drive-time refresh is a no-op.
 */
@Component
@ConfigurationProperties(prefix = "openrouteservice")
public class OrsProperties {

    private String apiKey;
    private boolean enabled = true;

    /** @return the ORS API key */
    public String getApiKey() {
        return apiKey;
    }

    /** @param apiKey the ORS API key */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /** @return whether ORS integration is active */
    public boolean isEnabled() {
        return enabled;
    }

    /** @param enabled whether ORS integration is active */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** @return true if ORS is configured and enabled */
    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
