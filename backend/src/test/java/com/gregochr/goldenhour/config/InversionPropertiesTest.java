package com.gregochr.goldenhour.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InversionProperties}: both v2 switches default on and bind from {@code photocast.inversion.v2.*}
 * (including the environment-variable spelling an operator would use to revert).
 */
class InversionPropertiesTest {

    private static InversionProperties bind(Map<String, Object> source) {
        return new Binder(new MapConfigurationPropertySource(source))
                .bind("photocast.inversion", InversionProperties.class)
                .orElseGet(InversionProperties::new);
    }

    @Test
    @DisplayName("both switches default to true")
    void defaults() {
        InversionProperties p = bind(Map.of());

        assertThat(p.getV2().isEnabled()).isTrue();
        assertThat(p.getV2().isRaiseCapEnabled()).isTrue();
    }

    @Test
    @DisplayName("both switches bind from photocast.inversion.v2.*, independently")
    void binds() {
        InversionProperties off = bind(Map.of("photocast.inversion.v2.enabled", "false"));
        InversionProperties uncapped = bind(Map.of("photocast.inversion.v2.raise-cap-enabled", "false"));

        assertThat(off.getV2().isEnabled()).isFalse();
        assertThat(off.getV2().isRaiseCapEnabled()).isTrue();
        assertThat(uncapped.getV2().isEnabled()).isTrue();
        assertThat(uncapped.getV2().isRaiseCapEnabled()).isFalse();
    }
}
