package com.gregochr.goldenhour.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves every hot-topic type in the rarity roster has a window to land on.
 *
 * <p>{@code PlanWindowProjector.keysFor} puts a topic on a Plan window only if its type is
 * day-scoped or it carries an event. A type with neither is dropped from every card without any
 * error, which is how storm surge vanished from the Plan in August 2026. This is the declared-policy
 * half of the proof; each detector's own test proves that it actually stamps.
 */
class HotTopicWindowAnchorRosterTest {

    /**
     * Rostered types with no live strategy: nothing emits them, so there is nothing to land. They
     * are named here rather than anchored so that giving one a strategy fails the test below and
     * forces an anchor to be chosen.
     */
    private static final Set<String> NO_LIVE_STRATEGY = Set.of("CLEARANCE");

    private static boolean isAnchored(String type) {
        return PlanWindowProjector.isDayScoped(type)
                || HotTopicEventEnricher.fixedEventFor(type) != null
                || HotTopicEventEnricher.isStampedByDetector(type);
    }

    @Test
    @DisplayName("every rostered type with a live strategy is anchored to a window")
    void everyLiveTypeIsAnchored() {
        Set<String> unanchored = new TreeSet<>(TopicRarity.rankedTypes().keySet());
        unanchored.removeAll(NO_LIVE_STRATEGY);
        unanchored.removeIf(HotTopicWindowAnchorRosterTest::isAnchored);

        assertThat(unanchored)
                .as("%s would be dropped from every Plan card — see PlanWindowProjector.keysFor",
                        unanchored)
                .isEmpty();
    }

    @Test
    @DisplayName("a type named as having no live strategy is not anchored (else drop it from the list)")
    void exceptionsAreNotAnchored() {
        assertThat(NO_LIVE_STRATEGY).allSatisfy(type -> assertThat(isAnchored(type))
                .as("%s is anchored, so it no longer needs to be an exception", type).isFalse());
        assertThat(TopicRarity.rankedTypes().keySet()).containsAll(NO_LIVE_STRATEGY);
    }

    @Test
    @DisplayName("STORM_SURGE, the type that fell out of every card, is anchored by its detector")
    void stormSurgeIsAnchored() {
        assertThat(isAnchored("STORM_SURGE")).isTrue();
    }
}
