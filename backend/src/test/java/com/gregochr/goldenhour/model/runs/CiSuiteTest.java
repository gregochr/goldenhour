package com.gregochr.goldenhour.model.runs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The CI suites: their stored codes, labels, stack order and lookup. */
class CiSuiteTest {

    @Test
    @DisplayName("the stack order is sky, ask, then smoke, with the CI tests labels and the stored codes")
    void orderLabelsAndCodes() {
        assertThat(CiSuite.values()).containsExactly(CiSuite.SKY, CiSuite.ASK, CiSuite.SMOKE);
        assertThat(CiSuite.SKY.label()).isEqualTo("CI tests · sky");
        assertThat(CiSuite.ASK.label()).isEqualTo("CI tests · ask");
        assertThat(CiSuite.SMOKE.label()).isEqualTo("CI tests · smoke");
        assertThat(CiSuite.SKY.code()).isEqualTo("sky");
        assertThat(CiSuite.ASK.code()).isEqualTo("ask");
        assertThat(CiSuite.SMOKE.code()).isEqualTo("smoke");
    }

    @Test
    @DisplayName("fromCode finds a stored code and is empty for anything else")
    void fromCode() {
        assertThat(CiSuite.fromCode("sky")).contains(CiSuite.SKY);
        assertThat(CiSuite.fromCode("ask")).contains(CiSuite.ASK);
        assertThat(CiSuite.fromCode("smoke")).contains(CiSuite.SMOKE);
        assertThat(CiSuite.fromCode("SKY")).isEmpty();
        assertThat(CiSuite.fromCode(null)).isEmpty();
        assertThat(CiSuite.fromCode("other")).isEmpty();
    }
}
