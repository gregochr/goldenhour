package com.gregochr.goldenhour.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.common.CompositeCustomizer;
import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import io.github.resilience4j.spring6.circuitbreaker.configure.CircuitBreakerConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

/**
 * Builds the {@code anthropic} circuit breaker the way the application does, without a Spring context:
 * the instance settings are read from a COMMITTED profile YAML (bound onto Resilience4j's own
 * {@link CircuitBreakerConfigurationProperties}, then turned into a {@link CircuitBreakerConfig} by
 * Resilience4j's own {@code createCircuitBreakerConfig}), and the real {@link ResilienceConfig}
 * customizer is applied on top. Nothing here copies a number by hand.
 *
 * <p>Test helper only. {@code application.yml} is gitignored and is not read.
 */
public final class ProductionAnthropicBreaker {

    /** The committed profile whose settings production runs (the host's gitignored file may add to them). */
    public static final String PRODUCTION_PROFILE_YAML = "application-prod.yml";

    private ProductionAnthropicBreaker() {
    }

    /**
     * The {@code anthropic} breaker's configuration from the given committed profile YAML, with the real
     * customizer applied.
     *
     * @param yamlOnClasspath the profile file name, for example {@link #PRODUCTION_PROFILE_YAML}
     * @return the effective configuration
     */
    public static CircuitBreakerConfig config(String yamlOnClasspath) {
        CircuitBreakerConfigurationProperties properties = bind(yamlOnClasspath);
        CircuitBreakerConfigCustomizer customizer = new ResilienceConfig().anthropicCircuitBreakerCustomizer();
        return properties.createCircuitBreakerConfig("anthropic", properties.getInstances().get("anthropic"),
                new CompositeCustomizer<>(List.of(customizer)));
    }

    /**
     * A fresh breaker named {@code anthropic} on the production profile's configuration.
     *
     * @return a new CLOSED breaker
     */
    public static CircuitBreaker newBreaker() {
        return CircuitBreaker.of("anthropic", config(PRODUCTION_PROFILE_YAML));
    }

    private static CircuitBreakerConfigurationProperties bind(String yamlOnClasspath) {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load(yamlOnClasspath, new ClassPathResource(yamlOnClasspath));
            return new Binder(ConfigurationPropertySources.from(sources))
                    .bind("resilience4j.circuitbreaker", Bindable.of(CircuitBreakerConfigurationProperties.class))
                    .orElseThrow(() -> new IllegalStateException(
                            "No resilience4j.circuitbreaker section in " + yamlOnClasspath));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + yamlOnClasspath, e);
        }
    }
}
