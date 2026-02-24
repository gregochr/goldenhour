package com.gregochr.goldenhour.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Typed configuration properties for the WorldTides API.
 *
 * <p>Binds from the {@code worldtides} prefix in {@code application.yml}.
 * The API key is loaded from an environment variable via placeholder:
 * <pre>
 * worldtides:
 *   api-key: ${WORLDTIDES_API_KEY:}
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "worldtides")
public class WorldTidesProperties {

    /** WorldTides v3 API key. Set via {@code WORLDTIDES_API_KEY} environment variable. */
    private String apiKey;

    /**
     * Returns the WorldTides API key.
     *
     * @return API key string
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * Sets the WorldTides API key.
     *
     * @param apiKey API key string
     */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
