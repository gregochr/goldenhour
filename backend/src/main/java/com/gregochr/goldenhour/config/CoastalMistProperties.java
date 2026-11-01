package com.gregochr.goldenhour.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound to the {@code photocast.coastal-mist} section of {@code application.yml} (coastal-mist-plan Phase 1).
 *
 * <p>Read once at startup, so flipping it is a configuration change plus a backend restart. It defaults to
 * {@code true} here in the class because production's {@code application.yml} is not in the repository. With
 * {@code enabled} false the calculator is never run, nothing is logged, the prompt is byte-identical to before
 * the feature, and {@code slot_atmosphere} gets a null band and {@code coastal_mist_scored = false}.
 */
@Component
@ConfigurationProperties(prefix = "photocast.coastal-mist")
@Getter
@Setter
public class CoastalMistProperties {

    /** Whether the coastal mist band is computed, stored and put in the prompt. Defaults to {@code true}. */
    private boolean enabled = true;
}
