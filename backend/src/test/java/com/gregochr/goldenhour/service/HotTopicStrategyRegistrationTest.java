package com.gregochr.goldenhour.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms the seven new hot-topic detectors will be auto-collected into
 * {@link HotTopicAggregator} by Spring's by-type component scan: each must both
 * implement {@link HotTopicStrategy} and carry {@link Component}. This is the only
 * wiring required to add a detector — there is no explicit registration list.
 */
class HotTopicStrategyRegistrationTest {

    @ParameterizedTest
    @ValueSource(classes = {
            SupermoonHotTopicStrategy.class,
            EquinoxHotTopicStrategy.class,
            NlcHotTopicStrategy.class,
            MeteorHotTopicStrategy.class,
            InversionHotTopicStrategy.class,
            StormSurgeHotTopicStrategy.class,
            DustHotTopicStrategy.class,
            SnowFreshHotTopicStrategy.class,
            SnowTopsHotTopicStrategy.class})
    @DisplayName("each new detector implements HotTopicStrategy and is a @Component")
    void newDetectors_areAutoCollectibleStrategies(Class<?> detector) {
        assertThat(HotTopicStrategy.class).isAssignableFrom(detector);
        assertThat(detector.isAnnotationPresent(Component.class))
                .as("%s must be @Component to be auto-collected", detector.getSimpleName())
                .isTrue();
    }
}
