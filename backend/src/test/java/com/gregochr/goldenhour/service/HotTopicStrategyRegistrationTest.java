package com.gregochr.goldenhour.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms the hot-topic detectors will be auto-collected into
 * {@link HotTopicAggregator} by Spring's by-type component scan: each must both
 * implement {@link HotTopicStrategy} and carry {@link Component}. This is the only
 * wiring required to add a detector — there is no explicit registration list.
 *
 * <p>{@code EclipseHotTopicStrategy} and {@code LunarEclipseHotTopicStrategy} were added to this
 * list at the lunar eclipse topic's L1 — the solar strategy had never been covered here (a
 * pre-existing gap), so both were added together rather than leaving the gap for the new one
 * alone (lunar-eclipse-plan.md §1 row 14).
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
            SnowTopsHotTopicStrategy.class,
            EclipseHotTopicStrategy.class,
            LunarEclipseHotTopicStrategy.class})
    @DisplayName("each new detector implements HotTopicStrategy and is a @Component")
    void newDetectors_areAutoCollectibleStrategies(Class<?> detector) {
        assertThat(HotTopicStrategy.class).isAssignableFrom(detector);
        assertThat(detector.isAnnotationPresent(Component.class))
                .as("%s must be @Component to be auto-collected", detector.getSimpleName())
                .isTrue();
    }
}
