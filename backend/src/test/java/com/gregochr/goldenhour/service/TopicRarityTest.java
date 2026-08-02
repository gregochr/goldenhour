package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The fixed rarity ordinal that decides which coincidence gets promoted. */
class TopicRarityTest {

    /**
     * Every topic kind a strategy emits today, as a literal.
     *
     * <p>Deliberately a hand-written list rather than a derivation from the map under test — a test
     * that reads its expectation from the thing it is testing cannot fail. Re-derive it with:
     * {@code grep -rhoE '"[A-Z_]+"' src/main/java/**\/*HotTopicStrategy.java}.
     */
    private static final List<String> EMITTED_TYPES = List.of(
            "AURORA", "BLUEBELL", "CLEARANCE", "DUST", "EQUINOX", "INVERSION", "KING_TIDE",
            "METEOR", "NLC", "SNOW_FRESH", "SNOW_MIST", "SNOW_TOPS", "SPRING_TIDE",
            "STORM_SURGE", "SUPERMOON");

    @Test
    @DisplayName("every kind a strategy emits has an explicit rank")
    void everyEmittedTypeIsRanked() {
        // The failure this catches: a new strategy ships, nobody takes a rarity decision, and its
        // topic silently sorts last — so a genuinely rare coincidence loses the strip to a common
        // one and nothing anywhere says why.
        assertThat(EMITTED_TYPES)
                .allSatisfy(type -> assertThat(TopicRarity.rankOf(type))
                        .as("rank of %s", type)
                        .isNotEqualTo(TopicRarity.UNKNOWN_RANK));
    }

    @Test
    @DisplayName("the table ranks nothing that no strategy emits")
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
