package com.gregochr.goldenhour.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CoastalMistProperties}: the switch defaults on in the class (production's {@code application.yml} is
 * not in the repository) and binds from {@code photocast.coastal-mist.enabled}.
 */
class CoastalMistPropertiesTest {

    private static CoastalMistProperties bind(Map<String, Object> source) {
        return new Binder(new MapConfigurationPropertySource(source))
                .bind("photocast.coastal-mist", CoastalMistProperties.class)
                .orElseGet(CoastalMistProperties::new);
    }

    @Test
    @DisplayName("the switch defaults to true with no configuration at all")
    void defaultsOn() {
        assertThat(new CoastalMistProperties().isEnabled()).isTrue();
        assertThat(bind(Map.of()).isEnabled()).isTrue();
    }

    @Test
    @DisplayName("the switch binds false from photocast.coastal-mist.enabled")
    void bindsOff() {
        assertThat(bind(Map.of("photocast.coastal-mist.enabled", "false")).isEnabled()).isFalse();
    }
}
