package com.gregochr.goldenhour.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OpenMeteoResponseParser#integerAt(List, int)}.
 */
class OpenMeteoResponseParserIntegerAtTest {

    @Test
    @DisplayName("integerAt returns the element at a valid index")
    void integerAt_validIndex_returnsElement() {
        assertThat(OpenMeteoResponseParser.integerAt(List.of(10, 20, 30), 2)).isEqualTo(30);
    }

    @Test
    @DisplayName("integerAt returns null for a null list")
    void integerAt_nullList_returnsNull() {
        assertThat(OpenMeteoResponseParser.integerAt(null, 0)).isNull();
    }

    @Test
    @DisplayName("integerAt returns null for a negative index")
    void integerAt_negativeIndex_returnsNull() {
        assertThat(OpenMeteoResponseParser.integerAt(List.of(10, 20), -1)).isNull();
    }

    @Test
    @DisplayName("integerAt returns null for an index at or past the size")
    void integerAt_indexAtOrPastSize_returnsNull() {
        assertThat(OpenMeteoResponseParser.integerAt(List.of(10, 20), 2)).isNull();
        assertThat(OpenMeteoResponseParser.integerAt(List.of(10, 20), 99)).isNull();
    }

    @Test
    @DisplayName("integerAt returns null for a null element")
    void integerAt_nullElement_returnsNull() {
        assertThat(OpenMeteoResponseParser.integerAt(Arrays.asList(10, null, 30), 1)).isNull();
    }
}
