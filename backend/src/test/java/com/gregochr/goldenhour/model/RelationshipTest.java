package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Relationship}.
 */
class RelationshipTest {

    // ── fromString identity ──

    @Test
    @DisplayName("fromString('SAME_SLOT') returns SAME_SLOT, not DIFFERENT_SLOT")
    void fromString_sameSlot_returnsSameSlot() {
        assertThat(Relationship.fromString("SAME_SLOT")).isEqualTo(Relationship.SAME_SLOT);
        assertThat(Relationship.fromString("SAME_SLOT")).isNotEqualTo(Relationship.DIFFERENT_SLOT);
    }

    @Test
    @DisplayName("fromString('DIFFERENT_SLOT') returns DIFFERENT_SLOT, not SAME_SLOT")
    void fromString_differentSlot_returnsDifferentSlot() {
        assertThat(Relationship.fromString("DIFFERENT_SLOT")).isEqualTo(Relationship.DIFFERENT_SLOT);
        assertThat(Relationship.fromString("DIFFERENT_SLOT")).isNotEqualTo(Relationship.SAME_SLOT);
    }

    @Test
    @DisplayName("fromString is case-insensitive")
    void fromString_caseInsensitive() {
        assertThat(Relationship.fromString("same_slot")).isEqualTo(Relationship.SAME_SLOT);
        assertThat(Relationship.fromString("different_slot")).isEqualTo(Relationship.DIFFERENT_SLOT);
    }

    @Test
    @DisplayName("fromString(null) returns null")
    void fromString_null_returnsNull() {
        assertThat(Relationship.fromString(null)).isNull();
    }

    @Test
    @DisplayName("fromString unrecognised value returns null")
    void fromString_unrecognised_returnsNull() {
        assertThat(Relationship.fromString("BOGUS")).isNull();
        assertThat(Relationship.fromString("")).isNull();
    }

    // ── getValue / @JsonValue ──

    @Test
    @DisplayName("SAME_SLOT getValue returns 'SAME_SLOT'")
    void getValue_sameSlot() {
        assertThat(Relationship.SAME_SLOT.getValue()).isEqualTo("SAME_SLOT");
    }

    @Test
    @DisplayName("DIFFERENT_SLOT getValue returns 'DIFFERENT_SLOT'")
    void getValue_differentSlot() {
        assertThat(Relationship.DIFFERENT_SLOT.getValue()).isEqualTo("DIFFERENT_SLOT");
    }

    @Test
    @DisplayName("Values are distinct — kills swap mutation")
    void values_areDistinct() {
        assertThat(Relationship.SAME_SLOT.getValue())
                .isNotEqualTo(Relationship.DIFFERENT_SLOT.getValue());
    }

    // ── enum completeness ──

    @Test
    @DisplayName("Enum has exactly two values")
    void enumHasExactlyTwoValues() {
        assertThat(Relationship.values()).hasSize(2);
    }
}
