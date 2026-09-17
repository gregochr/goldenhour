package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TideType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared vocabulary the tide-run pill and the Plan tab's tide row both state numbers in.
 *
 * <p>Pinned as strings rather than through either caller, because the property that matters is that
 * the two surfaces spell the same measurement the same way. A change here that only one caller's
 * tests notice is exactly the drift this class exists to prevent.
 */
class TideWordingTest {

    @Test
    @DisplayName("an offset under the rounding threshold states the event, not a duration")
    void waterOnTheEventStatesNoDuration() {
        // "0m after sunrise" is a null statement dressed as a measurement, and it fires on the
        // single most emphatic morning a run can have.
        assertThat(TideWording.offsetPhrase(0, "sunrise")).isEqualTo("at sunrise");
        assertThat(TideWording.offsetPhrase(5, "sunrise")).isEqualTo("at sunrise");
        assertThat(TideWording.offsetPhrase(-5, "sunset")).isEqualTo("at sunset");
    }

    @Test
    @DisplayName("one minute past the threshold starts stating minutes")
    void justPastTheThresholdStatesMinutes() {
        assertThat(TideWording.offsetPhrase(6, "sunrise")).isEqualTo("6m after sunrise");
        assertThat(TideWording.offsetPhrase(-6, "sunrise")).isEqualTo("6m before sunrise");
    }

    @Test
    @DisplayName("an hour or more reads as hours and zero-padded minutes")
    void anHourOrMoreReadsAsHours() {
        assertThat(TideWording.offsetPhrase(59, "sunset")).isEqualTo("59m after sunset");
        assertThat(TideWording.offsetPhrase(60, "sunset")).isEqualTo("1h00 after sunset");
        assertThat(TideWording.offsetPhrase(-103, "sunset")).isEqualTo("1h43 before sunset");
        assertThat(TideWording.offsetPhrase(151, "sunrise")).isEqualTo("2h31 after sunrise");
    }

    @Test
    @DisplayName("the sign says which side of the event the water falls")
    void signDecidesBeforeOrAfter() {
        assertThat(TideWording.offsetPhrase(90, "sunrise")).endsWith("after sunrise");
        assertThat(TideWording.offsetPhrase(-90, "sunrise")).endsWith("before sunrise");
    }

    @Test
    void clockReadsAsATwentyFourHourTime() {
        assertThat(TideWording.clock(0)).isEqualTo("00:00");
        assertThat(TideWording.clock(490)).isEqualTo("08:10");
        assertThat(TideWording.clock(1439)).isEqualTo("23:59");
    }

    @Test
    @DisplayName("a reading outside the day wraps rather than throwing")
    void clockWrapsRatherThanThrowing() {
        // Both callers measure extremes as offsets from one day's midnight, so a neighbouring
        // day's water legitimately arrives as a negative or over-1440 value. It must render as
        // its own clock time, not blow up the row it sits in.
        assertThat(TideWording.clock(1445)).isEqualTo("00:05");
        assertThat(TideWording.clock(-15)).isEqualTo("23:45");
    }

    @Test
    void metresReadToOneDecimalPlace() {
        assertThat(TideWording.metres(4.9)).isEqualTo("4.9 m");
        assertThat(TideWording.metres(0.0)).isEqualTo("0.0 m");
        assertThat(TideWording.metres(4.85)).isEqualTo("4.9 m");
    }

    // ── tideGatePhrase — the served reason a coastal slot was withheld from Claude ──────────

    @Test
    @DisplayName("the gate names the event, the wanted water, the actual water and the nearest extreme")
    void gateStatesBothSidesOfTheMismatch() {
        // "mid tide" alone does not tell a reader whether that is a problem HERE; the wanted
        // state is what makes it a mismatch rather than a fact.
        assertThat(TideWording.tideGatePhrase(
                Set.of(TideType.LOW), "MID", "HW 09:19 · 2h35 after sunrise", "sunrise"))
                .isEqualTo("Tide not right at sunrise · needs low water, mid tide instead"
                        + " · HW 09:19 · 2h35 after sunrise");
    }

    @Test
    @DisplayName("several wanted states read in a fixed HIGH, MID, LOW order whatever the set's order")
    void wantedStatesReadInFixedOrder() {
        assertThat(TideWording.tideGatePhrase(Set.of(TideType.LOW, TideType.HIGH), "MID", null, "sunset"))
                .isEqualTo("Tide not right at sunset · needs high water or low water, mid tide instead");
        assertThat(TideWording.tideGatePhrase(
                Set.of(TideType.MID, TideType.LOW, TideType.HIGH), "LOW", null, "sunset"))
                .isEqualTo("Tide not right at sunset · needs high water, mid tide or low water,"
                        + " low water instead");
    }

    @Test
    @DisplayName("no configured preference drops the wants clause rather than inventing one")
    void noPreferenceDropsTheWantsClause() {
        // No "instead" either — it contrasts with a stated preference, and there is none.
        assertThat(TideWording.tideGatePhrase(Set.of(), "HIGH", null, "sunset"))
                .isEqualTo("Tide not right at sunset · high water");
        assertThat(TideWording.tideGatePhrase(null, "HIGH", null, "sunset"))
                .isEqualTo("Tide not right at sunset · high water");
    }

    @Test
    @DisplayName("a blank nearest-extreme phrase drops the clock clause")
    void blankNearestDropsTheClockClause() {
        assertThat(TideWording.tideGatePhrase(Set.of(TideType.HIGH), "MID", "  ", "sunrise"))
                .isEqualTo("Tide not right at sunrise · needs high water, mid tide instead");
    }

    @Test
    @DisplayName("an unrecognised or null tide state is named as unknown, never printed raw or thrown on")
    void unknownTideStateIsNamedAsUnknown() {
        // Unreachable from the builder (the gate requires a derived state), but this is served
        // text with a documented contract, and the raw enum name must never leak into a sentence.
        assertThat(TideWording.tideGatePhrase(Set.of(TideType.HIGH), "SLACK", null, "sunrise"))
                .isEqualTo("Tide not right at sunrise · needs high water, an unknown tide instead");
        assertThat(TideWording.tideGatePhrase(Set.of(), null, null, "sunrise"))
                .isEqualTo("Tide not right at sunrise · an unknown tide");
    }
}
