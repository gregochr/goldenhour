package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.NlcWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NlcTwilightWindowCalculator}.
 *
 * <p>The astronomy is cross-checked against {@code solar-utils} civil twilight — a wholly
 * independent implementation — so the hand-rolled solar-altitude routine is validated, not merely
 * exercised. The window structure (NW after dusk, NE before dawn, ordered times) is then asserted
 * directly.
 */
class NlcTwilightWindowCalculatorTest {

    private static final double NORTHUMBERLAND_LAT = 55.3;
    private static final double NORTHUMBERLAND_LON = -2.1;

    private final SolarService solarService = new SolarService();
    private final NlcTwilightWindowCalculator calculator =
            new NlcTwilightWindowCalculator(solarService);

    @Test
    @DisplayName("solar altitude at civil dusk is ≈ −6° (cross-checked against solar-utils)")
    void solarAltitude_atCivilDusk_isMinusSix() {
        LocalDate date = LocalDate.of(2026, 9, 21);
        Instant civilDusk = solarService.civilDuskUtc(NORTHUMBERLAND_LAT, NORTHUMBERLAND_LON, date)
                .toInstant(ZoneOffset.UTC);

        double altitude = NlcTwilightWindowCalculator.solarAltitudeDeg(
                NORTHUMBERLAND_LAT, NORTHUMBERLAND_LON, civilDusk);

        assertThat(altitude).isCloseTo(-6.0, org.assertj.core.data.Offset.offset(0.6));
    }

    @Test
    @DisplayName("solar altitude at civil dawn is ≈ −6° (cross-checked against solar-utils)")
    void solarAltitude_atCivilDawn_isMinusSix() {
        LocalDate date = LocalDate.of(2026, 9, 21);
        Instant civilDawn = solarService.civilDawnUtc(NORTHUMBERLAND_LAT, NORTHUMBERLAND_LON, date)
                .toInstant(ZoneOffset.UTC);

        double altitude = NlcTwilightWindowCalculator.solarAltitudeDeg(
                NORTHUMBERLAND_LAT, NORTHUMBERLAND_LON, civilDawn);

        assertThat(altitude).isCloseTo(-6.0, org.assertj.core.data.Offset.offset(0.6));
    }

    @Test
    @DisplayName("solar altitude at local noon is high and positive")
    void solarAltitude_atNoon_isHigh() {
        // ~12:00 UTC ≈ solar noon at this near-Greenwich longitude in high summer.
        Instant noon = LocalDate.of(2026, 6, 21).atTime(12, 0).toInstant(ZoneOffset.UTC);

        double altitude = NlcTwilightWindowCalculator.solarAltitudeDeg(
                NORTHUMBERLAND_LAT, NORTHUMBERLAND_LON, noon);

        assertThat(altitude).isGreaterThan(50.0);
    }

    @Test
    @DisplayName("an autumn night yields an evening NW window and a morning NE window, times ordered")
    void compute_autumnNight_bothWindowsWithExpectedAzimuths() {
        NlcTwilightWindowCalculator.NlcWindows windows = calculator.compute(
                NORTHUMBERLAND_LAT, NORTHUMBERLAND_LON, LocalDate.of(2026, 9, 21));

        assertThat(windows.hasAny()).isTrue();
        NlcWindow evening = windows.evening();
        NlcWindow morning = windows.morning();
        assertThat(evening).isNotNull();
        assertThat(morning).isNotNull();
        assertThat(evening.azimuth()).isEqualTo("NW");
        assertThat(morning.azimuth()).isEqualTo("NE");
        // Within each window the start precedes the end (times are same-side of midnight).
        assertThat(evening.start().compareTo(evening.end())).isLessThan(0);
        assertThat(morning.start().compareTo(morning.end())).isLessThan(0);
        // Evening is late, morning is small-hours.
        assertThat(evening.start()).isGreaterThan("18:00");
        assertThat(morning.end()).isLessThan("09:00");
    }

    @Test
    @DisplayName("a deep-summer lat-55 night still has at least a partial window (geometry exists)")
    void compute_midsummer_hasAtLeastOneWindow() {
        NlcTwilightWindowCalculator.NlcWindows windows = calculator.compute(
                NORTHUMBERLAND_LAT, NORTHUMBERLAND_LON, LocalDate.of(2026, 6, 21));

        assertThat(windows.hasAny()).isTrue();
    }
}
