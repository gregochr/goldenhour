package com.gregochr.goldenhour.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
