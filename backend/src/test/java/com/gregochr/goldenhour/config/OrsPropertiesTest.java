package com.gregochr.goldenhour.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** When {@link OrsProperties} counts OpenRouteService as usable: switched on and holding a non-blank key. */
class OrsPropertiesTest {

    @Test
    @DisplayName("a new instance is enabled but has no key, so it is not configured")
    void defaults_enabledButUnconfigured() {
        OrsProperties properties = new OrsProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getApiKey()).isNull();
        assertThat(properties.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("an enabled instance with a real key is configured")
    void enabledWithKey_isConfigured() {
        OrsProperties properties = new OrsProperties();
        properties.setApiKey("ors-key");

        assertThat(properties.getApiKey()).isEqualTo("ors-key");
        assertThat(properties.isConfigured()).isTrue();
    }

    @Test
    @DisplayName("a blank key is not a key")
    void blankKey_isNotConfigured() {
        OrsProperties properties = new OrsProperties();
        properties.setApiKey("   ");

        assertThat(properties.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("switching it off un-configures it even with a key")
    void disabledWithKey_isNotConfigured() {
        OrsProperties properties = new OrsProperties();
        properties.setApiKey("ors-key");
        properties.setEnabled(false);

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isConfigured()).isFalse();
    }
}
