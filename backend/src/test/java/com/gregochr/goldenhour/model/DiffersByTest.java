package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DiffersBy}.
 */
class DiffersByTest {

    // ── fromString identity — kills swap mutations ──

    @Test
    @DisplayName("fromString('DATE') returns DATE, not EVENT or REGION")
    void fromString_date_returnsDate() {
        assertThat(DiffersBy.fromString("DATE")).isEqualTo(DiffersBy.DATE);
        assertThat(DiffersBy.fromString("DATE")).isNotEqualTo(DiffersBy.EVENT);
        assertThat(DiffersBy.fromString("DATE")).isNotEqualTo(DiffersBy.REGION);
    }

    @Test
    @DisplayName("fromString('EVENT') returns EVENT, not DATE or REGION")
    void fromString_event_returnsEvent() {
        assertThat(DiffersBy.fromString("EVENT")).isEqualTo(DiffersBy.EVENT);
        assertThat(DiffersBy.fromString("EVENT")).isNotEqualTo(DiffersBy.DATE);
        assertThat(DiffersBy.fromString("EVENT")).isNotEqualTo(DiffersBy.REGION);
    }

    @Test
    @DisplayName("fromString('REGION') returns REGION, not DATE or EVENT")
    void fromString_region_returnsRegion() {
        assertThat(DiffersBy.fromString("REGION")).isEqualTo(DiffersBy.REGION);
        assertThat(DiffersBy.fromString("REGION")).isNotEqualTo(DiffersBy.DATE);
        assertThat(DiffersBy.fromString("REGION")).isNotEqualTo(DiffersBy.EVENT);
    }

    @Test
    @DisplayName("fromString is case-insensitive")
    void fromString_caseInsensitive() {
        assertThat(DiffersBy.fromString("date")).isEqualTo(DiffersBy.DATE);
        assertThat(DiffersBy.fromString("event")).isEqualTo(DiffersBy.EVENT);
        assertThat(DiffersBy.fromString("region")).isEqualTo(DiffersBy.REGION);
    }

    @Test
    @DisplayName("fromString(null) returns null")
    void fromString_null_returnsNull() {
        assertThat(DiffersBy.fromString(null)).isNull();
    }

    @Test
    @DisplayName("fromString unrecognised value returns null")
    void fromString_unrecognised_returnsNull() {
        assertThat(DiffersBy.fromString("BOGUS")).isNull();
        assertThat(DiffersBy.fromString("")).isNull();
    }

    // ── getValue / @JsonValue ──

    @Test
    @DisplayName("DATE getValue returns 'DATE'")
    void getValue_date() {
        assertThat(DiffersBy.DATE.getValue()).isEqualTo("DATE");
    }

    @Test
    @DisplayName("EVENT getValue returns 'EVENT'")
    void getValue_event() {
        assertThat(DiffersBy.EVENT.getValue()).isEqualTo("EVENT");
    }

    @Test
    @DisplayName("REGION getValue returns 'REGION'")
    void getValue_region() {
        assertThat(DiffersBy.REGION.getValue()).isEqualTo("REGION");
    }

    @Test
    @DisplayName("All three values are distinct — kills any swap mutation")
    void values_areAllDistinct() {
        assertThat(DiffersBy.DATE.getValue()).isNotEqualTo(DiffersBy.EVENT.getValue());
        assertThat(DiffersBy.DATE.getValue()).isNotEqualTo(DiffersBy.REGION.getValue());
        assertThat(DiffersBy.EVENT.getValue()).isNotEqualTo(DiffersBy.REGION.getValue());
    }

    // ── enum completeness ──

    @Test
    @DisplayName("Enum has exactly three values")
    void enumHasExactlyThreeValues() {
        assertThat(DiffersBy.values()).hasSize(3);
    }
}
