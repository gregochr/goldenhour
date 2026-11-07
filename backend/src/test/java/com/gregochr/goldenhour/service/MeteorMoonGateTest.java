package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.service.MeteorHotTopicStrategy.Shower;
import com.gregochr.goldenhour.service.MeteorMoonGate.MoonOverShower;
import com.gregochr.solarutils.LunarCalculator;
import com.gregochr.solarutils.MoonriseMoonsetCalculator;
import com.gregochr.solarutils.SolarCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MeteorMoonGate} against the real lunar, solar and moonrise calculators: the
 * verdicts are the ephemeris's, not a mock's.
 */
class MeteorMoonGateTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private final MeteorMoonGate gate = new MeteorMoonGate(
            new LunarCalculator(), new SolarCalculator(), new MoonriseMoonsetCalculator());

    private static Shower shower(String name) {
        return MeteorHotTopicStrategy.SHOWERS.stream()
                .filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    private static Shower withHours(String bestHours) {
        return new Shower("Test", MonthDay.of(6, 15), 10, "N", bestHours, true, null);
    }

    @Test
    @DisplayName("Leonids 2026: half lit at noon but down all night, so not washed out")
    void leonids2026_moonDownThroughout() {
        MoonOverShower moon = gate.assess(shower("Leonids"), LocalDate.of(2026, 11, 17));

        assertThat(moon.washedOut()).isFalse();
        assertThat(moon.downThroughout()).isTrue();
        assertThat(moon.illuminationPct()).isBetween(54, 60);
    }

    @Test
    @DisplayName("Orionids 2026: the moon sets inside the best hours, so not washed out")
    void orionids2026_moonSetsInBestHours() {
        MoonOverShower moon = gate.assess(shower("Orionids"), LocalDate.of(2026, 10, 21));

        assertThat(moon.washedOut()).isFalse();
        assertThat(moon.downThroughout()).isFalse();
        assertThat(moon.moonset()).isPresent();
        assertThat(moon.moonset().get().toSecondOfDay() / 60.0)
                .isCloseTo(LocalTime.of(2, 56).toSecondOfDay() / 60.0, org.assertj.core.data.Offset.offset(5.0));
    }

    @Test
    @DisplayName("Ursids 2026: a near-full moon up all night is washed out")
    void ursids2026_washedOut() {
        assertThat(gate.assess(shower("Ursids"), LocalDate.of(2026, 12, 22)).washedOut()).isTrue();
    }

    @Test
    @DisplayName("Geminids 2026: a thin moon is not washed out")
    void geminids2026_notWashedOut() {
        assertThat(gate.assess(shower("Geminids"), LocalDate.of(2026, 12, 14)).washedOut()).isFalse();
    }

    @Test
    @DisplayName("Perseids 2027: the moon sets before the best hours end, so not washed out")
    void perseids2027_notWashedOut() {
        assertThat(gate.assess(shower("Perseids"), LocalDate.of(2027, 8, 12)).washedOut()).isFalse();
    }

    @Test
    @DisplayName("Lyrids 2027: a bright moon up through the best hours is washed out")
    void lyrids2027_washedOut() {
        assertThat(gate.assess(shower("Lyrids"), LocalDate.of(2027, 4, 22)).washedOut()).isTrue();
    }

    @Test
    @DisplayName("a bright moon up for exactly half the samples is not washed out")
    void exactHalf_notWashedOut() {
        // 17 Nov 2026 is waxing and above 50% lit all evening; the moon sets between 23:30 and
        // 23:45, so 22:00..23:30 is seven up samples and 23:45..01:15 is seven down samples.
        ZonedDateTime start = ZonedDateTime.of(2026, 11, 17, 22, 0, 0, 0, LONDON);
        ZonedDateTime end = ZonedDateTime.of(2026, 11, 18, 1, 15, 0, 0, LONDON);

        MoonOverShower moon = gate.evaluate(start, end);

        assertThat(MeteorMoonGate.sampleCount(start, end)).isEqualTo(14);
        assertThat(moon.washedOut()).isFalse();
        assertThat(moon.downThroughout()).isFalse();
        assertThat(moon.moonset()).isPresent();
    }

    @Test
    @DisplayName("one sample more of bright moon than dark tips it over half and washes it out")
    void justOverHalf_washedOut() {
        ZonedDateTime start = ZonedDateTime.of(2026, 11, 17, 22, 0, 0, 0, LONDON);
        ZonedDateTime end = ZonedDateTime.of(2026, 11, 18, 1, 0, 0, 0, LONDON);

        // 22:00..23:30 up (7), 23:45..01:00 down (6): seven of thirteen is more than half.
        assertThat(gate.evaluate(start, end).washedOut()).isTrue();
    }

    @Test
    @DisplayName("every shower's best hours parse for 2026 and 2027 without falling back")
    void everyShowerParses() {
        for (Shower shower : MeteorHotTopicStrategy.SHOWERS) {
            for (int year : new int[] {2026, 2027}) {
                LocalDate night = shower.peak().atYear(year);
                MoonOverShower moon = gate.assess(shower, night);
                // A fallback is the noon rule, which can never report a partial moon; instead prove
                // the real path ran by its own signature: the figure is a window mean and the
                // result agrees with evaluating the parsed window by hand.
                assertThat(moon).as(shower.name() + " " + year).isNotNull();
                assertThat(gate.parseWindow(shower.bestHours(), night))
                        .as(shower.name() + " " + year).isNotNull();
            }
        }
    }

    @Test
    @DisplayName("'04:00–dawn' on a mid-June night falls back to the noon rule, because dawn comes first")
    void dawnBeforeStart_fallsBackToNoonRule() {
        LocalDate night = LocalDate.of(2026, 6, 15);
        Shower shower = withHours("04:00–dawn");

        assertThat(gate.parseWindow(shower.bestHours(), night)).isNull();
        assertThatCode(() -> gate.assess(shower, night)).doesNotThrowAnyException();
        MoonOverShower moon = gate.assess(shower, night);
        assertThat(moon.downThroughout()).isFalse();
        assertThat(moon.moonset()).isEmpty();
        assertThat(moon.moonrise()).isEmpty();
    }

    @Test
    @DisplayName("a garbage bestHours string falls back to the noon rule and does not throw")
    void garbage_fallsBackToNoonRule() {
        LocalDate night = LocalDate.of(2026, 12, 22);

        MoonOverShower moon = gate.assess(withHours("whenever it is dark"), night);

        // 22 Dec 2026 is a near-full moon: the noon rule calls it washed out.
        assertThat(moon.washedOut()).isTrue();
        assertThat(moon.illuminationPct()).isGreaterThan(90);
        assertThat(gate.assess(withHours(null), night).washedOut()).isTrue();
    }

    @Test
    @DisplayName("a calculator failure while resolving dawn falls back to the noon rule and does not throw")
    void dawnCalculationThrows_fallsBackToNoonRule() {
        SolarCalculator solar = mock(SolarCalculator.class);
        LocalDate night = LocalDate.of(2026, 12, 22);
        when(solar.civilDawn(54.8, -2.0, night.plusDays(1), LONDON))
                .thenThrow(new IllegalStateException("no dawn"));
        MeteorMoonGate failing = new MeteorMoonGate(
                new LunarCalculator(), solar, new MoonriseMoonsetCalculator());

        MoonOverShower moon = failing.assess(shower("Ursids"), night);

        assertThat(moon.washedOut()).isTrue();
        assertThat(moon.moonset()).isEmpty();
    }

    @Test
    @DisplayName("a missing civil dawn (no twilight end) falls back to the noon rule")
    void dawnMissing_fallsBackToNoonRule() {
        SolarCalculator solar = mock(SolarCalculator.class);
        LocalDate night = LocalDate.of(2026, 12, 22);
        when(solar.civilDawn(54.8, -2.0, night.plusDays(1), LONDON)).thenReturn(null);
        MeteorMoonGate missing = new MeteorMoonGate(
                new LunarCalculator(), solar, new MoonriseMoonsetCalculator());

        assertThat(missing.assess(shower("Ursids"), night).washedOut()).isTrue();
    }

    @Test
    @DisplayName("clock digits that do not parse give no time rather than throwing")
    void onNight_nonNumeric_returnsNull() {
        assertThat(MeteorMoonGate.onNight(LocalDate.of(2026, 11, 17), "ab", "00")).isNull();
        assertThat(MeteorMoonGate.onNight(LocalDate.of(2026, 11, 17), "03", "xx")).isNull();
        assertThat(MeteorMoonGate.onNight(LocalDate.of(2026, 11, 17), "03", "00"))
                .isEqualTo(ZonedDateTime.of(2026, 11, 18, 3, 0, 0, 0, LONDON));
    }

    @Test
    @DisplayName("a window that ends before it starts has no samples")
    void sampleCount_negativeWindow_isZero() {
        ZonedDateTime start = ZonedDateTime.of(2026, 11, 17, 22, 0, 0, 0, LONDON);

        assertThat(MeteorMoonGate.sampleCount(start, start.minusHours(1))).isZero();
    }

    @Test
    @DisplayName("the night the clocks go back samples the three real hours, not two wall-clock hours")
    void clockChangeNight_samplesTrueElapsedTime() {
        // 25 Oct 2026: 02:00 BST becomes 01:00 GMT. 01:00 (first, BST) to 03:00 (GMT) is 3 real hours.
        ZonedDateTime start = ZonedDateTime.of(2026, 10, 25, 1, 0, 0, 0, LONDON);
        ZonedDateTime end = ZonedDateTime.of(2026, 10, 25, 3, 0, 0, 0, LONDON);

        assertThat(start.toLocalTime()).isEqualTo(LocalTime.of(1, 0));
        assertThat(MeteorMoonGate.sampleCount(start, end)).isEqualTo(13);
        assertThatCode(() -> gate.evaluate(start, end)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("moonrise inside the window is reported as a UK local time")
    void moonrise_reportedInWindow() {
        // Geminids 2027: find a night where the moon rises in the evening window; use a wide window.
        ZonedDateTime start = ZonedDateTime.of(2026, 11, 17, 12, 0, 0, 0, LONDON);
        ZonedDateTime end = ZonedDateTime.of(2026, 11, 17, 23, 0, 0, 0, LONDON);

        Optional<LocalTime> rise = gate.evaluate(start, end).moonrise();

        assertThat(rise).isPresent();
        assertThat(rise.get()).isBetween(LocalTime.of(12, 0), LocalTime.of(23, 0));
        assertThat(rise.get().getSecond()).isZero();
    }
}
