package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.model.MoonTransitionData;
import com.gregochr.goldenhour.model.MoonTransitionData.WindowQuality;
import com.gregochr.goldenhour.model.TonightWindow;
import com.gregochr.solarutils.LunarCalculator;
import com.gregochr.solarutils.LunarPhase;
import com.gregochr.solarutils.LunarPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MoonTransitionCalculator}.
 */
@ExtendWith(MockitoExtension.class)
class MoonTransitionCalculatorTest {

    private static final double LAT = 54.776;
    private static final double LON = -1.575;

    @Mock
    private LunarCalculator lunarCalculator;

    // ── Moon below entire window ──

    @Test
    @DisplayName("Moon below horizon for entire window → DARK_ALL_WINDOW")
    void moonBelowAllWindow() {
        ZonedDateTime dusk = ZonedDateTime.of(2026, 4, 10, 20, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime dawn = ZonedDateTime.of(2026, 4, 11, 4, 0, 0, 0, ZoneOffset.UTC);
        TonightWindow window = new TonightWindow(dusk, dawn);

        // All hourly samples: moon below horizon
        when(lunarCalculator.calculate(any(ZonedDateTime.class), anyDouble(), anyDouble()))
                .thenReturn(new LunarPosition(-15.0, 180.0, 0.10, LunarPhase.NEW_MOON, 384400));

        MoonTransitionData data = MoonTransitionCalculator.calculate(
                lunarCalculator, window, LAT, LON);

        assertThat(data).isNotNull();
        assertThat(data.windowQuality()).isEqualTo(WindowQuality.DARK_ALL_WINDOW);
        assertThat(data.moonUpAtStart()).isFalse();
        assertThat(data.moonUpAtEnd()).isFalse();
        assertThat(data.moonRiseTime()).isNull();
        assertThat(data.moonSetTime()).isNull();
        assertThat(data.phase()).isEqualTo(LunarPhase.NEW_MOON);
        assertThat(data.illuminationPct()).isEqualTo(10.0);
    }

    // ── Moon rises mid-window ──

    @Test
    @DisplayName("Moon rises mid-window → DARK_THEN_MOONLIT with moonRiseTime")
    void moonRisesMidWindow() {
        ZonedDateTime dusk = ZonedDateTime.of(2026, 4, 10, 20, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime dawn = ZonedDateTime.of(2026, 4, 11, 4, 0, 0, 0, ZoneOffset.UTC);
        TonightWindow window = new TonightWindow(dusk, dawn);

        // 20:00-22:00 below, 23:00+ above
        when(lunarCalculator.calculate(any(ZonedDateTime.class), anyDouble(), anyDouble()))
                .thenAnswer(inv -> {
                    ZonedDateTime time = inv.getArgument(0);
                    int hour = time.getHour();
                    boolean above = hour >= 23 || hour < 6;
                    double alt = above ? 25.0 : -10.0;
                    return new LunarPosition(alt, 180.0, 0.82,
                            LunarPhase.WAXING_GIBBOUS, 384400);
                });

        MoonTransitionData data = MoonTransitionCalculator.calculate(
                lunarCalculator, window, LAT, LON);

        assertThat(data).isNotNull();
        assertThat(data.windowQuality()).isEqualTo(WindowQuality.DARK_THEN_MOONLIT);
        assertThat(data.moonUpAtStart()).isFalse();
        assertThat(data.moonUpAtEnd()).isTrue();
        // 23:00 UTC — ISO UTC datetime
        assertThat(data.moonRiseTime()).isEqualTo("2026-04-10T23:00:00");
        assertThat(data.moonSetTime()).isNull();
        assertThat(data.illuminationPct()).isEqualTo(82.0);
    }

    // ── Moon sets mid-window ──

    @Test
    @DisplayName("Moon sets mid-window → MOONLIT_THEN_DARK with moonSetTime")
    void moonSetsMidWindow() {
        ZonedDateTime dusk = ZonedDateTime.of(2026, 4, 10, 20, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime dawn = ZonedDateTime.of(2026, 4, 11, 4, 0, 0, 0, ZoneOffset.UTC);
        TonightWindow window = new TonightWindow(dusk, dawn);

        // 20:00-01:00 above, 02:00+ below
        when(lunarCalculator.calculate(any(ZonedDateTime.class), anyDouble(), anyDouble()))
                .thenAnswer(inv -> {
                    ZonedDateTime time = inv.getArgument(0);
                    int hour = time.getHour();
                    boolean above = hour >= 20 || hour <= 1;
                    double alt = above ? 30.0 : -8.0;
                    return new LunarPosition(alt, 180.0, 0.65,
                            LunarPhase.WAXING_GIBBOUS, 384400);
                });

        MoonTransitionData data = MoonTransitionCalculator.calculate(
                lunarCalculator, window, LAT, LON);

        assertThat(data).isNotNull();
        assertThat(data.windowQuality()).isEqualTo(WindowQuality.MOONLIT_THEN_DARK);
        assertThat(data.moonUpAtStart()).isTrue();
        assertThat(data.moonUpAtEnd()).isFalse();
        // 02:00 UTC — ISO UTC datetime
        assertThat(data.moonSetTime()).isEqualTo("2026-04-11T02:00:00");
        assertThat(data.moonRiseTime()).isNull();
    }

    // ── Moon above entire window ──

    @Test
    @DisplayName("Moon above horizon for entire window → MOONLIT_ALL_WINDOW")
    void moonAboveAllWindow() {
        ZonedDateTime dusk = ZonedDateTime.of(2026, 4, 10, 20, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime dawn = ZonedDateTime.of(2026, 4, 11, 4, 0, 0, 0, ZoneOffset.UTC);
        TonightWindow window = new TonightWindow(dusk, dawn);

        when(lunarCalculator.calculate(any(ZonedDateTime.class), anyDouble(), anyDouble()))
                .thenReturn(new LunarPosition(40.0, 180.0, 0.95,
                        LunarPhase.FULL_MOON, 384400));

        MoonTransitionData data = MoonTransitionCalculator.calculate(
                lunarCalculator, window, LAT, LON);

        assertThat(data).isNotNull();
        assertThat(data.windowQuality()).isEqualTo(WindowQuality.MOONLIT_ALL_WINDOW);
        assertThat(data.moonUpAtStart()).isTrue();
        assertThat(data.moonUpAtEnd()).isTrue();
        assertThat(data.moonRiseTime()).isNull();
        assertThat(data.moonSetTime()).isNull();
        assertThat(data.phase()).isEqualTo(LunarPhase.FULL_MOON);
    }

    // ── Null window (real-time path) ──

    @Test
    @DisplayName("Null window falls back to single-point calc — moon above")
    void nullWindow_moonAbove_singlePoint() {
        when(lunarCalculator.calculate(any(ZonedDateTime.class), anyDouble(), anyDouble()))
                .thenReturn(new LunarPosition(30.0, 180.0, 0.50,
                        LunarPhase.FIRST_QUARTER, 384400));

        MoonTransitionData data = MoonTransitionCalculator.calculate(
                lunarCalculator, null, LAT, LON);

        assertThat(data).isNotNull();
        assertThat(data.windowQuality()).isEqualTo(WindowQuality.MOONLIT_ALL_WINDOW);
        assertThat(data.moonUpAtStart()).isTrue();
        assertThat(data.moonUpAtEnd()).isTrue();
        assertThat(data.moonRiseTime()).isNull();
        assertThat(data.moonSetTime()).isNull();
    }

    @Test
    @DisplayName("Null window falls back to single-point calc — moon below")
    void nullWindow_moonBelow_singlePoint() {
        when(lunarCalculator.calculate(any(ZonedDateTime.class), anyDouble(), anyDouble()))
                .thenReturn(new LunarPosition(-20.0, 180.0, 0.03,
                        LunarPhase.NEW_MOON, 384400));

        MoonTransitionData data = MoonTransitionCalculator.calculate(
                lunarCalculator, null, LAT, LON);

        assertThat(data).isNotNull();
        assertThat(data.windowQuality()).isEqualTo(WindowQuality.DARK_ALL_WINDOW);
        assertThat(data.moonUpAtStart()).isFalse();
        assertThat(data.moonUpAtEnd()).isFalse();
    }

    // ── Calculator failure ──

    @Test
    @DisplayName("Calculator failure returns null")
    void calculatorFailure_returnsNull() {
        when(lunarCalculator.calculate(any(ZonedDateTime.class), anyDouble(), anyDouble()))
                .thenThrow(new RuntimeException("Ephemeris error"));

        ZonedDateTime dusk = ZonedDateTime.of(2026, 4, 10, 20, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime dawn = ZonedDateTime.of(2026, 4, 11, 4, 0, 0, 0, ZoneOffset.UTC);
        TonightWindow window = new TonightWindow(dusk, dawn);

        MoonTransitionData data = MoonTransitionCalculator.calculate(
                lunarCalculator, window, LAT, LON);

        assertThat(data).isNull();
    }

    // ── Phase and illumination from first sample ──

    @Test
    @DisplayName("Phase and illumination are from the first sample (dusk), not later samples")
    void phaseAndIlluminationFromFirstSample() {
        ZonedDateTime dusk = ZonedDateTime.of(2026, 4, 10, 20, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime dawn = ZonedDateTime.of(2026, 4, 11, 4, 0, 0, 0, ZoneOffset.UTC);
        TonightWindow window = new TonightWindow(dusk, dawn);

        when(lunarCalculator.calculate(any(ZonedDateTime.class), anyDouble(), anyDouble()))
                .thenAnswer(inv -> {
                    ZonedDateTime time = inv.getArgument(0);
                    if (time.equals(dusk)) {
                        return new LunarPosition(-5.0, 180.0, 0.42,
                                LunarPhase.FIRST_QUARTER, 384400);
                    }
                    // Later samples return different values — should not be used
                    return new LunarPosition(20.0, 200.0, 0.99,
                            LunarPhase.FULL_MOON, 360000);
                });

        MoonTransitionData data = MoonTransitionCalculator.calculate(
                lunarCalculator, window, LAT, LON);

        assertThat(data).isNotNull();
        assertThat(data.phase()).isEqualTo(LunarPhase.FIRST_QUARTER);
        assertThat(data.illuminationPct()).isEqualTo(42.0);
    }

    // ── Winter GMT (no BST offset) ──

    @Test
    @DisplayName("Winter: rise times formatted in GMT (UTC+0), not BST")
    void winterRiseTime_formattedInGmt() {
        // January — UK is in GMT (UTC+0), so UTC time = UK time
        ZonedDateTime dusk = ZonedDateTime.of(2026, 1, 15, 17, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime dawn = ZonedDateTime.of(2026, 1, 16, 7, 0, 0, 0, ZoneOffset.UTC);
        TonightWindow window = new TonightWindow(dusk, dawn);

        // Moon below until 22:00 UTC, then above through dawn
        when(lunarCalculator.calculate(any(ZonedDateTime.class), anyDouble(), anyDouble()))
                .thenAnswer(inv -> {
                    ZonedDateTime time = inv.getArgument(0);
                    boolean above = time.getHour() >= 22 || time.getHour() <= 7;
                    double alt = above ? 25.0 : -10.0;
                    return new LunarPosition(alt, 180.0, 0.60,
                            LunarPhase.WAXING_GIBBOUS, 384400);
                });

        MoonTransitionData data = MoonTransitionCalculator.calculate(
                lunarCalculator, window, LAT, LON);

        // 22:00 UTC — ISO UTC datetime
        assertThat(data.moonRiseTime()).isEqualTo("2026-01-15T22:00:00");
        assertThat(data.windowQuality()).isEqualTo(WindowQuality.DARK_THEN_MOONLIT);
    }

    // ── Only first transition captured ──

    @Test
    @DisplayName("Only the first rise transition is captured even if moon sets and rises again")
    void onlyFirstTransitionCaptured() {
        ZonedDateTime dusk = ZonedDateTime.of(2026, 4, 10, 19, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime dawn = ZonedDateTime.of(2026, 4, 11, 5, 0, 0, 0, ZoneOffset.UTC);
        TonightWindow window = new TonightWindow(dusk, dawn);

        // Moon: below 19-20, above 21-22, below 23-01, above 02-05
        when(lunarCalculator.calculate(any(ZonedDateTime.class), anyDouble(), anyDouble()))
                .thenAnswer(inv -> {
                    ZonedDateTime time = inv.getArgument(0);
                    int hour = time.getHour();
                    boolean above = (hour >= 21 && hour <= 22) || (hour >= 2 && hour <= 5);
                    return new LunarPosition(above ? 15.0 : -10.0, 180.0, 0.40,
                            LunarPhase.FIRST_QUARTER, 384400);
                });

        MoonTransitionData data = MoonTransitionCalculator.calculate(
                lunarCalculator, window, LAT, LON);

        // First rise at 21:00 UTC — ISO UTC datetime
        assertThat(data.moonRiseTime()).isEqualTo("2026-04-10T21:00:00");
        // First set at 23:00 UTC — ISO UTC datetime
        assertThat(data.moonSetTime()).isEqualTo("2026-04-10T23:00:00");
    }
}
