package com.gregochr.goldenhour.service.runs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pins the arithmetic shared by the app's cost trends and the CI spend block, written before the code moved out of
 * {@code RunsCostService} so the extraction cannot change a figure.
 */
class CostArithmeticTest {

    @Test
    @DisplayName("percent: a rise, a fall and no change round to whole percents")
    void percentWholeNumbers() {
        assertThat(CostArithmetic.percent(15, 10)).isEqualTo(50);
        assertThat(CostArithmetic.percent(5, 10)).isEqualTo(-50);
        assertThat(CostArithmetic.percent(10, 10)).isZero();
        assertThat(CostArithmetic.percent(10.4, 10)).isEqualTo(4);
        assertThat(CostArithmetic.percent(10.05, 10)).isEqualTo(1);
    }

    @Test
    @DisplayName("percent: a zero or negative base has no percentage")
    void percentWithoutBase() {
        assertThat(CostArithmetic.percent(5, 0)).isNull();
        assertThat(CostArithmetic.percent(5, -1)).isNull();
        assertThat(CostArithmetic.percent(0, 0)).isNull();
    }

    @Test
    @DisplayName("perRunMultiple: cost per run now over cost per run before")
    void multiple() {
        assertThat(CostArithmetic.perRunMultiple(10, 5, 4, 4)).isCloseTo(2.0, within(1e-9));
        assertThat(CostArithmetic.perRunMultiple(3, 3, 6, 3)).isCloseTo(0.5, within(1e-9));
    }

    @Test
    @DisplayName("perRunMultiple: no runs in either period, or no prior spend, has no multiple")
    void multipleWithoutBase() {
        assertThat(CostArithmetic.perRunMultiple(10, 0, 4, 4)).isNull();
        assertThat(CostArithmetic.perRunMultiple(10, 5, 4, 0)).isNull();
        assertThat(CostArithmetic.perRunMultiple(10, 5, 0, 4)).isNull();
        assertThat(CostArithmetic.perRunMultiple(10, 5, -1, 4)).isNull();
    }
}
