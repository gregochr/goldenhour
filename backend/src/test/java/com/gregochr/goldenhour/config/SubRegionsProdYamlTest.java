package com.gregochr.goldenhour.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the sub-regions enforcement flag as the COMMITTED profile files declare it (sub-regions plan S6):
 * on in production, off in the example file. No Spring context; the YAML is bound onto
 * {@link SubRegionProperties} directly.
 */
class SubRegionsProdYamlTest {

    private static SubRegionProperties bind(String yaml) throws IOException {
        var sources = new YamlPropertySourceLoader().load(yaml, new ClassPathResource(yaml));
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind("photocast.sub-regions", Bindable.of(SubRegionProperties.class))
                .orElseThrow(() -> new IllegalStateException("No photocast.sub-regions section in " + yaml));
    }

    @Test
    @DisplayName("production profile switches sub-region enforcement on")
    void prodEnforces() throws IOException {
        assertThat(bind("application-prod.yml").isEnforce()).isTrue();
    }

    @Test
    @DisplayName("example profile leaves enforcement off")
    void exampleDoesNotEnforce() throws IOException {
        assertThat(bind("application-example.yml").isEnforce()).isFalse();
    }
}
