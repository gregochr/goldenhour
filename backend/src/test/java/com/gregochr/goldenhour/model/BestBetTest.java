package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BestBet}.
 */
class BestBetTest {

    // ── Convenience constructor defaults ──

    @Test
    @DisplayName("10-arg convenience constructor sets relationship to null")
    void convenienceConstructor_relationshipIsNull() {
        BestBet bet = new BestBet(1, "H", "D", "e", "r",
                Confidence.HIGH, null, null, null, null);
        assertThat(bet.relationship()).isNull();
    }

    @Test
    @DisplayName("10-arg convenience constructor sets differsBy to empty list, not null")
    void convenienceConstructor_differsByIsEmptyNotNull() {
        BestBet bet = new BestBet(1, "H", "D", "e", "r",
                Confidence.HIGH, null, null, null, null);
        assertThat(bet.differsBy()).isNotNull();
        assertThat(bet.differsBy()).isEmpty();
    }

    // ── 12-arg canonical constructor ──

    @Test
    @DisplayName("12-arg constructor preserves relationship")
    void canonicalConstructor_preservesRelationship() {
        BestBet bet = new BestBet(2, "H", "D", "e", "r",
                Confidence.HIGH, null, null, null, null,
                Relationship.DIFFERENT_SLOT, List.of(DiffersBy.DATE));
        assertThat(bet.relationship()).isEqualTo(Relationship.DIFFERENT_SLOT);
    }

    @Test
    @DisplayName("12-arg constructor preserves differsBy list contents and order")
    void canonicalConstructor_preservesDiffersBy() {
        BestBet bet = new BestBet(2, "H", "D", "e", "r",
                Confidence.HIGH, null, null, null, null,
                Relationship.DIFFERENT_SLOT,
                List.of(DiffersBy.DATE, DiffersBy.EVENT, DiffersBy.REGION));
        assertThat(bet.differsBy())
                .containsExactly(DiffersBy.DATE, DiffersBy.EVENT, DiffersBy.REGION);
    }

    @Test
    @DisplayName("12-arg constructor with SAME_SLOT and empty differsBy")
    void canonicalConstructor_sameSlotEmptyDiffersBy() {
        BestBet bet = new BestBet(2, "H", "D", "e", "r",
                Confidence.HIGH, null, null, null, null,
                Relationship.SAME_SLOT, List.of());
        assertThat(bet.relationship()).isEqualTo(Relationship.SAME_SLOT);
        assertThat(bet.differsBy()).isEmpty();
    }

    // ── Parameter ordering: relationship is not swapped with differsBy ──

    @Test
    @DisplayName("Parameter ordering: relationship is 11th, differsBy is 12th — not swapped")
    void parameterOrdering_relationshipNotSwappedWithDiffersBy() {
        BestBet bet = new BestBet(2, "H", "D", "e", "r",
                Confidence.HIGH, null, null, null, null,
                Relationship.SAME_SLOT, List.of(DiffersBy.REGION));

        // If params were swapped, relationship would be something wrong
        // and differsBy would be empty or wrong
        assertThat(bet.relationship()).isEqualTo(Relationship.SAME_SLOT);
        assertThat(bet.differsBy()).containsExactly(DiffersBy.REGION);
    }

    // ── Existing fields preserved ──

    @Test
    @DisplayName("All 10 original fields preserved in 12-arg constructor")
    void canonicalConstructor_preservesOriginalFields() {
        BestBet bet = new BestBet(2, "headline", "detail", "2026-04-22_sunset",
                "Northumberland", Confidence.MEDIUM, 45,
                "Tomorrow", "sunset", "20:28",
                Relationship.DIFFERENT_SLOT, List.of(DiffersBy.DATE));

        assertThat(bet.rank()).isEqualTo(2);
        assertThat(bet.headline()).isEqualTo("headline");
        assertThat(bet.detail()).isEqualTo("detail");
        assertThat(bet.event()).isEqualTo("2026-04-22_sunset");
        assertThat(bet.region()).isEqualTo("Northumberland");
        assertThat(bet.confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(bet.nearestDriveMinutes()).isEqualTo(45);
        assertThat(bet.dayName()).isEqualTo("Tomorrow");
        assertThat(bet.eventType()).isEqualTo("sunset");
        assertThat(bet.eventTime()).isEqualTo("20:28");
    }
}
