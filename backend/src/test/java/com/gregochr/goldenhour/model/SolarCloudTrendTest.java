package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SolarCloudTrend}.
 */
class SolarCloudTrendTest {

    @Test
    @DisplayName("isBuilding() returns true when last - first >= 20pp")
    void isBuilding_risingTrend_returnsTrue() {
        var trend = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 5),
                new SolarCloudTrend.SolarCloudSlot(2, 12),
                new SolarCloudTrend.SolarCloudSlot(1, 25),
                new SolarCloudTrend.SolarCloudSlot(0, 30)));

        assertThat(trend.isBuilding()).isTrue();
    }

    @Test
    @DisplayName("isBuilding() returns true at exactly 20pp threshold")
    void isBuilding_exactlyTwentyPp_returnsTrue() {
        var trend = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 10),
                new SolarCloudTrend.SolarCloudSlot(0, 30)));

        assertThat(trend.isBuilding()).isTrue();
    }

    @Test
    @DisplayName("isBuilding() returns false when increase is less than 20pp")
    void isBuilding_smallIncrease_returnsFalse() {
        var trend = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 10),
                new SolarCloudTrend.SolarCloudSlot(2, 12),
                new SolarCloudTrend.SolarCloudSlot(1, 15),
                new SolarCloudTrend.SolarCloudSlot(0, 18)));

        assertThat(trend.isBuilding()).isFalse();
    }

    @Test
    @DisplayName("isBuilding() returns false when cloud is decreasing")
    void isBuilding_decreasingTrend_returnsFalse() {
        var trend = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 80),
                new SolarCloudTrend.SolarCloudSlot(2, 60),
                new SolarCloudTrend.SolarCloudSlot(1, 30),
                new SolarCloudTrend.SolarCloudSlot(0, 10)));

        assertThat(trend.isBuilding()).isFalse();
    }

    @Test
    @DisplayName("isBuilding() returns false for single slot")
    void isBuilding_singleSlot_returnsFalse() {
        var trend = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(0, 50)));

        assertThat(trend.isBuilding()).isFalse();
    }

    @Test
    @DisplayName("isBuilding() returns false for empty slots")
    void isBuilding_emptySlots_returnsFalse() {
        var trend = new SolarCloudTrend(List.of());

        assertThat(trend.isBuilding()).isFalse();
    }

    @Test
    @DisplayName("isBuilding() returns false for null slots")
    void isBuilding_nullSlots_returnsFalse() {
        var trend = new SolarCloudTrend(null);

        assertThat(trend.isBuilding()).isFalse();
    }

    // --- isClearing(): genuine dramatic clearance (blocker drops, canvas holds) ---

    @Test
    @DisplayName("isClearing() true when low drops sharply while mid/high canvas persists")
    void isClearing_blockerClearsCanvasHolds_returnsTrue() {
        // low 90 -> 10 (drop 80); canvas (max mid/high) 60 -> 55 (holds, well above floor)
        var trend = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 90, 60, 40),
                new SolarCloudTrend.SolarCloudSlot(2, 70, 58, 45),
                new SolarCloudTrend.SolarCloudSlot(1, 35, 55, 50),
                new SolarCloudTrend.SolarCloudSlot(0, 10, 50, 55)));

        assertThat(trend.isClearing()).isTrue();
        assertThat(trend.isBuilding()).isFalse();
    }

    // --- isClearing(): wholesale clear (NEGATIVE CONTROL — the reason #3 over #2) ---

    @Test
    @DisplayName("isClearing() false when low AND canvas both drop toward bald blue")
    void isClearing_wholesaleClear_returnsFalse() {
        // low 90 -> 8 (drop 82) but canvas 70 -> 6 collapses below the floor: NOT a clearance
        var trend = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 90, 70, 60),
                new SolarCloudTrend.SolarCloudSlot(2, 60, 40, 30),
                new SolarCloudTrend.SolarCloudSlot(1, 25, 15, 10),
                new SolarCloudTrend.SolarCloudSlot(0, 8, 6, 5)));

        assertThat(trend.isClearing()).isFalse();
    }

    @Test
    @DisplayName("isClearing() false when canvas remains present but collapses by >= 20pp")
    void isClearing_canvasCollapses_returnsFalse() {
        // low drops 80pp; canvas 90 -> 60 stays above floor (60 >= 25) but drops 30pp (>= 20): collapsing
        var trend = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 90, 90, 40),
                new SolarCloudTrend.SolarCloudSlot(0, 10, 60, 30)));

        assertThat(trend.isClearing()).isFalse();
    }

    @Test
    @DisplayName("isClearing() false when canvas at event is below the present floor")
    void isClearing_canvasBelowFloorAtEvent_returnsFalse() {
        // low drops 80pp; canvas holds steady (no collapse) but only 20% at event (< 25 floor)
        var trend = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 90, 22, 18),
                new SolarCloudTrend.SolarCloudSlot(0, 10, 20, 15)));

        assertThat(trend.isClearing()).isFalse();
    }

    // --- isClearing(): building / no-trajectory / legacy data ---

    @Test
    @DisplayName("isClearing() false on a building trend even when canvas present")
    void isClearing_buildingTrend_returnsFalse() {
        var trend = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 10, 60, 50),
                new SolarCloudTrend.SolarCloudSlot(0, 80, 60, 50)));

        assertThat(trend.isClearing()).isFalse();
        assertThat(trend.isBuilding()).isTrue();
    }

    @Test
    @DisplayName("isClearing() false when low drop is below the 20pp threshold")
    void isClearing_smallLowDrop_returnsFalse() {
        // low 40 -> 25 is only 15pp; canvas healthy
        var trend = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 40, 60, 50),
                new SolarCloudTrend.SolarCloudSlot(0, 25, 60, 50)));

        assertThat(trend.isClearing()).isFalse();
    }

    @Test
    @DisplayName("isClearing() false when the canvas trajectory was not captured (legacy slots)")
    void isClearing_nullCanvas_returnsFalse() {
        var trend = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 90),
                new SolarCloudTrend.SolarCloudSlot(0, 10)));

        assertThat(trend.isClearing()).isFalse();
    }

    // --- isClearing(): boundary cases at N (low drop) and M (canvas collapse) and the floor ---

    @Test
    @DisplayName("isClearing() true at exactly the 20pp low-drop threshold")
    void isClearing_exactlyTwentyPpLowDrop_returnsTrue() {
        // low 45 -> 25 is exactly 20pp; canvas 50 -> 50 holds, above floor
        var trend = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 45, 50, 30),
                new SolarCloudTrend.SolarCloudSlot(0, 25, 50, 30)));

        assertThat(trend.isClearing()).isTrue();
    }

    @Test
    @DisplayName("isClearing() false when canvas drop is exactly the 20pp collapse threshold")
    void isClearing_canvasDropExactlyTwentyPp_returnsFalse() {
        // canvas 50 -> 30 is exactly 20pp drop: collapse threshold is inclusive (>= 20 = not holding)
        var trend = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 90, 50, 20),
                new SolarCloudTrend.SolarCloudSlot(0, 10, 30, 15)));

        assertThat(trend.isClearing()).isFalse();
    }

    @Test
    @DisplayName("isClearing() true when canvas drop is just below the 20pp collapse threshold")
    void isClearing_canvasDropNineteenPp_returnsTrue() {
        // canvas 50 -> 31 is 19pp drop (< 20), event canvas 31 >= 25 floor
        var trend = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 90, 50, 20),
                new SolarCloudTrend.SolarCloudSlot(0, 10, 31, 15)));

        assertThat(trend.isClearing()).isTrue();
    }

    @Test
    @DisplayName("isClearing() true when event canvas sits exactly on the 25% present floor")
    void isClearing_canvasExactlyAtFloor_returnsTrue() {
        // canvas 40 -> 25 (drop 15 < 20, event 25 == floor)
        var trend = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 90, 40, 20),
                new SolarCloudTrend.SolarCloudSlot(0, 10, 25, 20)));

        assertThat(trend.isClearing()).isTrue();
    }

    @Test
    @DisplayName("isClearing() false for single slot and null slots")
    void isClearing_degenerateSlots_returnsFalse() {
        assertThat(new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(0, 10, 50, 40))).isClearing()).isFalse();
        assertThat(new SolarCloudTrend(List.of()).isClearing()).isFalse();
        assertThat(new SolarCloudTrend(null).isClearing()).isFalse();
    }

    @Test
    @DisplayName("average() damps a single-cone-point spike that would otherwise trip isBuilding()")
    void average_singlePointSpike_doesNotTripBuilding() {
        // The centre bearing lands on a grid cell showing a sharp build (10 -> 40 = 30pp), while
        // both flanking bearings 15 degrees either side stay flat. Reading the centre alone trips
        // the [BUILDING] flag, which feeds an absolute rating ceiling in the prompt; the coned
        // average (10 -> 20 = 10pp) does not. This is the adjacent-location divergence guard.
        var flankLow = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 10),
                new SolarCloudTrend.SolarCloudSlot(0, 10)));
        var centre = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 10),
                new SolarCloudTrend.SolarCloudSlot(0, 40)));
        var flankHigh = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 10),
                new SolarCloudTrend.SolarCloudSlot(0, 10)));

        assertThat(centre.isBuilding()).isTrue();

        var averaged = SolarCloudTrend.average(List.of(flankLow, centre, flankHigh));

        assertThat(averaged.slots()).extracting(SolarCloudTrend.SolarCloudSlot::lowCloudPercent)
                .containsExactly(10, 20);
        assertThat(averaged.isBuilding()).isFalse();
    }

    @Test
    @DisplayName("average() keeps a genuine build that all three cone points agree on")
    void average_agreedBuild_stillTripsBuilding() {
        var trend = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 10),
                new SolarCloudTrend.SolarCloudSlot(0, 70)));

        var averaged = SolarCloudTrend.average(List.of(trend, trend, trend));

        assertThat(averaged.isBuilding()).isTrue();
    }

    @Test
    @DisplayName("average() preserves hoursBeforeEvent and averages the canvas layers")
    void average_averagesCanvasLayers() {
        var a = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 10, 40, 20),
                new SolarCloudTrend.SolarCloudSlot(0, 20, 60, 30)));
        var b = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 20, 50, 30),
                new SolarCloudTrend.SolarCloudSlot(0, 40, 80, 50)));

        var averaged = SolarCloudTrend.average(List.of(a, b));

        assertThat(averaged.slots()).containsExactly(
                new SolarCloudTrend.SolarCloudSlot(3, 15, 45, 25),
                new SolarCloudTrend.SolarCloudSlot(0, 30, 70, 40));
    }

    @Test
    @DisplayName("average() reports a null canvas when any contributing slot omitted it")
    void average_partialCanvas_returnsNullCanvas() {
        var withCanvas = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 10, 40, 20),
                new SolarCloudTrend.SolarCloudSlot(0, 20, 60, 30)));
        var withoutCanvas = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 20),
                new SolarCloudTrend.SolarCloudSlot(0, 40)));

        var averaged = SolarCloudTrend.average(List.of(withCanvas, withoutCanvas));

        assertThat(averaged.slots().getFirst().midCloudPercent()).isNull();
        assertThat(averaged.slots().getFirst().highCloudPercent()).isNull();
        // Canvas cannot be asserted against, so clearing must stay false.
        assertThat(averaged.isClearing()).isFalse();
    }

    @Test
    @DisplayName("average() skips null and empty trends, keeping the usable ones")
    void average_skipsNullAndEmptyTrends() {
        var usable = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 10),
                new SolarCloudTrend.SolarCloudSlot(0, 30)));

        var averaged = SolarCloudTrend.average(
                Arrays.asList(null, new SolarCloudTrend(List.of()), usable));

        assertThat(averaged).isEqualTo(usable);
    }

    @Test
    @DisplayName("average() returns null when no usable trend is supplied")
    void average_noUsableTrend_returnsNull() {
        assertThat(SolarCloudTrend.average(null)).isNull();
        assertThat(SolarCloudTrend.average(List.of())).isNull();
        assertThat(SolarCloudTrend.average(
                Arrays.asList(null, new SolarCloudTrend(List.of())))).isNull();
    }

    @Test
    @DisplayName("average() truncates to the shortest trajectory when lengths differ")
    void average_differingLengths_truncatesToShortest() {
        var longer = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 10),
                new SolarCloudTrend.SolarCloudSlot(2, 20),
                new SolarCloudTrend.SolarCloudSlot(0, 30)));
        var shorter = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 20),
                new SolarCloudTrend.SolarCloudSlot(2, 40)));

        var averaged = SolarCloudTrend.average(List.of(longer, shorter));

        assertThat(averaged.slots()).containsExactly(
                new SolarCloudTrend.SolarCloudSlot(3, 15),
                new SolarCloudTrend.SolarCloudSlot(2, 30));
    }
}
