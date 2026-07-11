package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SeaState} — the WMO/Douglas sea-state banding. Each boundary is tested at,
 * just below, and just above, because the band label is the anomaly context the coastal pills show
 * (a mislabelled band would misrepresent how dramatic the sea is).
 */
class SeaStateTest {

    @Test
    @DisplayName("bands are lower-inclusive / upper-exclusive at each boundary")
    void classifiesBoundaries() {
        assertThat(SeaState.fromHs(0.0)).isEqualTo(SeaState.CALM);
        assertThat(SeaState.fromHs(0.09)).isEqualTo(SeaState.CALM);
        assertThat(SeaState.fromHs(0.10)).isEqualTo(SeaState.SMOOTH);
        assertThat(SeaState.fromHs(0.49)).isEqualTo(SeaState.SMOOTH);
        assertThat(SeaState.fromHs(0.50)).isEqualTo(SeaState.SLIGHT);
        assertThat(SeaState.fromHs(1.24)).isEqualTo(SeaState.SLIGHT);
        assertThat(SeaState.fromHs(1.25)).isEqualTo(SeaState.MODERATE);
        assertThat(SeaState.fromHs(2.49)).isEqualTo(SeaState.MODERATE);
        assertThat(SeaState.fromHs(2.50)).isEqualTo(SeaState.ROUGH);
        assertThat(SeaState.fromHs(3.99)).isEqualTo(SeaState.ROUGH);
        assertThat(SeaState.fromHs(4.00)).isEqualTo(SeaState.VERY_ROUGH);
        assertThat(SeaState.fromHs(5.99)).isEqualTo(SeaState.VERY_ROUGH);
        assertThat(SeaState.fromHs(6.00)).isEqualTo(SeaState.HIGH);
        assertThat(SeaState.fromHs(8.99)).isEqualTo(SeaState.HIGH);
        assertThat(SeaState.fromHs(9.00)).isEqualTo(SeaState.VERY_HIGH);
        assertThat(SeaState.fromHs(13.99)).isEqualTo(SeaState.VERY_HIGH);
        assertThat(SeaState.fromHs(14.00)).isEqualTo(SeaState.PHENOMENAL);
        assertThat(SeaState.fromHs(30.0)).isEqualTo(SeaState.PHENOMENAL);
    }

    @Test
    @DisplayName("the mockup's headline case: 4.2 m is 'very rough'")
    void classifiesTheMockupCase() {
        assertThat(SeaState.fromHs(4.2)).isEqualTo(SeaState.VERY_ROUGH);
        assertThat(SeaState.fromHs(4.2).label()).isEqualTo("very rough");
    }

    @Test
    @DisplayName("labels are the lower-case display strings")
    void exposesLabels() {
        assertThat(SeaState.CALM.label()).isEqualTo("calm");
        assertThat(SeaState.ROUGH.label()).isEqualTo("rough");
        assertThat(SeaState.PHENOMENAL.label()).isEqualTo("phenomenal");
    }
}
