package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.util.TimeSlotUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link OpenMeteoResponseParser#resolveEventWind}.
 *
 * <p>Guards the invariant that keeps the cloud-approach veto alive: the wind used to <em>place</em>
 * the upwind sample point (by the prefetchers) must equal the wind used to <em>re-derive</em> it
 * (by {@code CloudPointCacheReader}). They are matched by a grid-snapped exact coordinate lookup,
 * so any divergence makes the lookup miss and the upwind sample silently become null — disabling
 * one of the veto's two triggers with no error.
 */
class OpenMeteoResponseParserEventWindTest {

    private static final List<String> TIMES = List.of(
            "2026-03-11T19:00", "2026-03-11T20:00", "2026-03-11T21:00", "2026-03-11T22:00");

    @Test
    @DisplayName("resolveEventWind uses the same slot rule as extractAtmosphericData, not nearest")
    void resolveEventWind_matchesFindBestIndexNotNearest() {
        // A 21:37 sunset sits closer to 22:00 (23 min) than to 21:00 (37 min), so the two slot
        // rules disagree — this is the ~half of events where the old code broke the cache lookup.
        LocalDateTime sunset = LocalDateTime.of(2026, 3, 11, 21, 37);

        int bestIdx = TimeSlotUtils.findBestIndex(TIMES, sunset, TargetType.SUNSET);
        int nearestIdx = TimeSlotUtils.findNearestIndex(TIMES, sunset);
        assertThat(bestIdx).isNotEqualTo(nearestIdx);
        assertThat(TIMES.get(bestIdx)).isEqualTo("2026-03-11T21:00");
        assertThat(TIMES.get(nearestIdx)).isEqualTo("2026-03-11T22:00");

        var wind = OpenMeteoResponseParser.resolveEventWind(
                hourly(List.of(200, 210, 220, 250), List.of(5.0, 5.5, 6.0, 9.0)),
                sunset, TargetType.SUNSET);

        // Must take the 21:00 slot — the one extractAtmosphericData will hand the reader.
        assertThat(wind).isNotNull();
        assertThat(wind.windFromDeg()).isEqualTo(220);
        assertThat(wind.windSpeedMs()).isEqualTo(6.0);
    }

    @Test
    @DisplayName("resolveEventWind takes the at-or-after slot for a sunrise")
    void resolveEventWind_sunriseTakesAtOrAfterSlot() {
        LocalDateTime sunrise = LocalDateTime.of(2026, 3, 11, 20, 10);

        var wind = OpenMeteoResponseParser.resolveEventWind(
                hourly(List.of(200, 210, 220, 250), List.of(5.0, 5.5, 6.0, 9.0)),
                sunrise, TargetType.SUNRISE);

        // 20:10 is nearest 20:00, but a sunrise must not use a pre-dawn slot — 21:00 wins.
        assertThat(wind).isNotNull();
        assertThat(wind.windFromDeg()).isEqualTo(220);
    }

    @Test
    @DisplayName("resolveEventWind returns null rather than a partial wind when data is missing")
    void resolveEventWind_missingData_returnsNull() {
        LocalDateTime sunset = LocalDateTime.of(2026, 3, 11, 21, 0);

        assertThat(OpenMeteoResponseParser.resolveEventWind(null, sunset, TargetType.SUNSET))
                .isNull();

        OpenMeteoForecastResponse.Hourly noWind = new OpenMeteoForecastResponse.Hourly();
        noWind.setTime(TIMES);
        assertThat(OpenMeteoResponseParser.resolveEventWind(noWind, sunset, TargetType.SUNSET))
                .isNull();

        // A null entry at the resolved slot must not become a bogus zero bearing.
        var nullEntry = hourly(Arrays.asList(200, 210, null, 250), List.of(5.0, 5.5, 6.0, 9.0));
        assertThat(OpenMeteoResponseParser.resolveEventWind(nullEntry, sunset, TargetType.SUNSET))
                .isNull();
    }

    private OpenMeteoForecastResponse.Hourly hourly(List<Integer> dirs, List<Double> speeds) {
        OpenMeteoForecastResponse.Hourly h = new OpenMeteoForecastResponse.Hourly();
        h.setTime(TIMES);
        h.setWindDirection10m(dirs);
        h.setWindSpeed10m(speeds);
        return h;
    }
}
