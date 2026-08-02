package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The fixed rarity ordinal that decides which coincidence gets promoted. */
class TopicRarityTest {

    /**
     * Every topic kind that can reach a window today, as a literal.
     *
     * <p>Deliberately hand-written rather than derived from the map under test — a test that reads
     * its expectation from the thing it tests cannot fail. Re-derive with
     * {@code grep -rhoE '"[A-Z_]{3,}"' src/main/java/**\/*HotTopicStrategy.java}, then add
     * {@code CLEARANCE}, which no strategy emits: it comes from {@code HotTopicSimulationService}
     * and is styled by the frontend, so it needs a rank even though the production pipeline never
     * produces it.
     */
    private static final List<String> EMITTED_TYPES = List.of(
            "AURORA", "BLUEBELL", "CLEARANCE", "DUST", "EQUINOX", "INVERSION", "KING_TIDE",
            "METEOR", "NLC", "SNOW_FRESH", "SNOW_MIST", "SNOW_TOPS", "SPRING_TIDE",
            "STORM_SURGE", "SUPERMOON");

    @Test
    @DisplayName("every kind that can reach a window has an explicit rank")
    void everyEmittedTypeIsRanked() {
        // Be precise about what this can and cannot catch. HotTopicStrategy exposes only
        // detect(from, to) — there is no programmatic roster of strategies — so a NEW strategy
        // touches neither this list nor the rank table, and both stay green while its topic sorts
        // last. What this pair does catch is the drift that follows: someone adds the kind here
        // and forgets the rank, or ranks it and never adds it here.
        assertThat(EMITTED_TYPES)
                .allSatisfy(type -> assertThat(TopicRarity.rankOf(type))
                        .as("rank of %s", type)
                        .isNotEqualTo(TopicRarity.UNKNOWN_RANK));
    }

    @Test
    @DisplayName("the table ranks nothing that cannot reach a window")
    void noRankedTypeIsUnreachable() {
        assertThat(TopicRarity.rankedTypes().keySet()).containsExactlyInAnyOrderElementsOf(EMITTED_TYPES);
    }

    @Test
    @DisplayName("ranks are a strict ordering — no two kinds share a place")
    void ranksAreDistinct() {
        assertThat(TopicRarity.rankedTypes().values()).doesNotHaveDuplicates();
    }

    @Test
    void rarerKindsOutrankCommonerOnes() {
        // The whole point of the ordinal, stated as a comparison rather than as positions: a
        // supermoon is fixed by orbital mechanics and rare; spring tides run around every new and
        // full moon, so roughly a third of the calendar.
        assertThat(TopicRarity.rankOf("SUPERMOON")).isLessThan(TopicRarity.rankOf("SPRING_TIDE"));
        assertThat(TopicRarity.rankOf("AURORA")).isLessThan(TopicRarity.rankOf("BLUEBELL"));
        assertThat(TopicRarity.rankOf("KING_TIDE")).isLessThan(TopicRarity.rankOf("SPRING_TIDE"));
    }

    @Test
    void anUnmappedKindSortsLast() {
        assertThat(TopicRarity.rankOf("VOLCANIC_SUNSET")).isEqualTo(TopicRarity.UNKNOWN_RANK);
    }

    @Test
    void aNullKindSortsLastRatherThanThrowing() {
        // Reachable: HotTopic.type has no non-null guarantee, and the projector ranks before it
        // could check.
        assertThat(TopicRarity.rankOf(null)).isEqualTo(TopicRarity.UNKNOWN_RANK);
    }
}
