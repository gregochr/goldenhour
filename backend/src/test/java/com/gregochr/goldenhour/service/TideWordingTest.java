package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TideType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
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

    // ── tideFitPhrase — the map tab's tide-fit block body, both tiers ──────────────────────
    //
    // tideGatePhrase (the served reason a coastal slot was withheld from Claude) was removed with
    // the tide gate lift (2026-09-18, docs/engineering/tide-window-plan.md §6 Q1) — no producer
    // calls it any more (BriefingGatingPolicy.HARD_CONSTRAINT_REASONS is empty, so
    // BriefingSlotBuilder's evaluationGate switch never reaches a TIDE_MISMATCH case). The fixed
    // HIGH/MID/LOW want-word ordering and the "never repeat the nearest-extreme offset" rule it
    // used to pin are still exercised below, through tideFitPhrase's miss form, which shares the
    // same orderedWantWords helper.

    /** 18:16 UTC on this date is 18:16 London — outside BST, so clock arithmetic reads bare. */
    private static final LocalDateTime SOLAR_EVENT = LocalDateTime.of(2026, 1, 27, 18, 16);

    @Test
    @DisplayName("match: state, direction, the nearest-extreme phrase, then the height — the "
            + "spec's own worked example")
    void matchStatesStateDirectionOffsetThenHeight() {
        assertThat(TideWording.tideFitPhrase(true, Set.of(TideType.HIGH), "HIGH", "FALLING",
                "HW 19:52 · 36m before sunset", SOLAR_EVENT, "3.9 m", "4.3 m"))
                .isEqualTo("high water, falling · HW 19:52 · 36m before sunset · 3.9 m");
    }

    @Test
    @DisplayName("miss: wants clause, state and direction at the light's own clock, then the "
            + "height against the day's high — the spec's own worked example")
    void missStatesWantsStateDirectionClockThenHeightOfDayHigh() {
        assertThat(TideWording.tideFitPhrase(false, Set.of(TideType.LOW), "MID", "RISING",
                "HW 09:19 · 2h35 after sunrise", LocalDateTime.of(2026, 1, 27, 5, 42),
                "2.6 m", "4.3 m"))
                .isEqualTo("wants low water · mid tide, rising at 05:42 · 2.6 m of 4.3 m");
    }

    @Test
    @DisplayName("a match with no nearest-extreme phrase drops that clause rather than a blank one")
    void matchWithNoNearestPhraseDropsThatClause() {
        assertThat(TideWording.tideFitPhrase(true, Set.of(TideType.HIGH), "HIGH", "FALLING",
                null, SOLAR_EVENT, "3.9 m", "4.3 m"))
                .isEqualTo("high water, falling · 3.9 m");
        assertThat(TideWording.tideFitPhrase(true, Set.of(TideType.HIGH), "HIGH", "FALLING",
                "  ", SOLAR_EVENT, "3.9 m", "4.3 m"))
                .isEqualTo("high water, falling · 3.9 m");
    }

    @Test
    @DisplayName("a miss with no configured preference drops the wants clause rather than "
            + "inventing one")
    void missWithNoPreferenceDropsTheWantsClause() {
        assertThat(TideWording.tideFitPhrase(false, Set.of(), "MID", "RISING", null,
                SOLAR_EVENT, "2.6 m", "4.3 m"))
                .isEqualTo("mid tide, rising at 18:16 · 2.6 m of 4.3 m");
    }

    @Test
    @DisplayName("several wanted states on a miss read in the same fixed HIGH, MID, LOW order "
            + "as the gate phrase")
    void missWantsReadInFixedOrder() {
        assertThat(TideWording.tideFitPhrase(false, Set.of(TideType.LOW, TideType.HIGH), "MID",
                "FALLING", null, SOLAR_EVENT, "2.6 m", "4.3 m"))
                .isEqualTo("wants high water or low water · mid tide, falling at 18:16 · "
                        + "2.6 m of 4.3 m");
    }

    @Test
    @DisplayName("the miss form never repeats the nearest-extreme offset")
    void missNeverRepeatsTheNearestExtremeOffset() {
        // The nearest-solar-offset phrase is passed but must not appear anywhere in the miss
        // form (CLAUDE.md's "no fact twice" rule) — the miss states the light's own clock time
        // instead.
        String phrase = TideWording.tideFitPhrase(false, Set.of(TideType.LOW), "MID", "RISING",
                "HW 09:19 · 2h35 after sunrise", SOLAR_EVENT, "2.6 m", "4.3 m");
        assertThat(phrase).doesNotContain("HW 09:19").doesNotContain("2h35");
    }

    @Test
    @DisplayName("an unrecognised or null direction is named as moving, never printed raw or "
            + "thrown on")
    void unknownDirectionIsNamedAsMoving() {
        assertThat(TideWording.tideFitPhrase(true, Set.of(TideType.HIGH), "HIGH", "SLACK", null,
                SOLAR_EVENT, "3.9 m", "4.3 m"))
                .isEqualTo("high water, moving · 3.9 m");
        assertThat(TideWording.tideFitPhrase(true, Set.of(TideType.HIGH), "HIGH", null, null,
                SOLAR_EVENT, "3.9 m", "4.3 m"))
                .isEqualTo("high water, moving · 3.9 m");
    }

    @Test
    @DisplayName("an unrecognised or null tide state is named as unknown, never printed raw or "
            + "thrown on — stateWord's sibling fail-soft default, exercised via tideFitPhrase "
            + "now that tideGatePhrase (its only other caller) is gone")
    void unknownTideStateIsNamedAsUnknown() {
        // This coverage used to run through the deleted tideGatePhrase; stateWord's own contract
        // (never leak a raw enum name into served text) still needs a live exerciser now that
        // tideFitPhrase is its only caller (adversarial review — found missing after the
        // tide gate lift's test deletions, the same asymmetry unknownDirectionIsNamedAsMoving
        // above already guards for directionWord).
        assertThat(TideWording.tideFitPhrase(true, Set.of(TideType.HIGH), "SLACK", "FALLING", null,
                SOLAR_EVENT, "3.9 m", "4.3 m"))
                .isEqualTo("an unknown tide, falling · 3.9 m");
        assertThat(TideWording.tideFitPhrase(true, Set.of(TideType.HIGH), null, "FALLING", null,
                SOLAR_EVENT, "3.9 m", "4.3 m"))
                .isEqualTo("an unknown tide, falling · 3.9 m");
    }
}
