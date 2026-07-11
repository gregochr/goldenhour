package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.OpenMeteoMarineApi;
import com.gregochr.goldenhour.model.MarineWaveSample;
import com.gregochr.goldenhour.model.OpenMeteoMarineResponse;
import com.gregochr.goldenhour.model.SeaState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link MarineClient#sampleAt} — selecting the significant wave height at the event
 * hour from a fetched marine series, and degrading gracefully for missing hours and land grid cells.
 */
class MarineClientTest {

    private final MarineClient client = new MarineClient(mock(OpenMeteoMarineApi.class));

    private static OpenMeteoMarineResponse response(List<String> times, List<Double> waveHeights,
            List<Double> swell, List<Integer> directions) {
        OpenMeteoMarineResponse response = new OpenMeteoMarineResponse();
        OpenMeteoMarineResponse.Hourly hourly = new OpenMeteoMarineResponse.Hourly();
        hourly.setTime(times);
        hourly.setWaveHeight(waveHeights);
        hourly.setSwellWaveHeight(swell);
        hourly.setWaveDirection(directions);
        response.setHourly(hourly);
        return response;
    }

    @Test
    @DisplayName("an event time rounds to the nearest hour and reads that hour's Hs")
    void samplesAtNearestHour() {
        OpenMeteoMarineResponse response = response(
                List.of("2026-07-11T06:00", "2026-07-11T07:00", "2026-07-11T08:00"),
                List.of(3.9, 4.2, 4.5), List.of(1.0, 1.1, 1.2), List.of(240, 250, 260));

        // 06:42 rounds up to 07:00 (index 1).
        Optional<MarineWaveSample> sample = client.sampleAt(response, LocalDateTime.of(2026, 7, 11, 6, 42));

        assertThat(sample).isPresent();
        assertThat(sample.get().significantWaveHeightMetres()).isEqualTo(4.2);
        assertThat(sample.get().swellWaveHeightMetres()).isEqualTo(1.1);
        assertThat(sample.get().waveDirectionDegrees()).isEqualTo(250);
        assertThat(sample.get().seaState()).isEqualTo(SeaState.VERY_ROUGH);
    }

    @Test
    @DisplayName("an event before the half hour rounds down")
    void roundsDownBeforeHalfHour() {
        OpenMeteoMarineResponse response = response(
                List.of("2026-07-11T06:00", "2026-07-11T07:00"),
                List.of(3.9, 4.2), List.of(1.0, 1.1), List.of(240, 250));

        // 06:29 rounds down to 06:00 (index 0).
        Optional<MarineWaveSample> sample = client.sampleAt(response, LocalDateTime.of(2026, 7, 11, 6, 29));

        assertThat(sample).isPresent();
        assertThat(sample.get().significantWaveHeightMetres()).isEqualTo(3.9);
    }

    @Test
    @DisplayName("empty when the event hour is outside the fetched window")
    void emptyWhenHourMissing() {
        OpenMeteoMarineResponse response = response(
                List.of("2026-07-11T06:00", "2026-07-11T07:00"),
                List.of(3.9, 4.2), List.of(1.0, 1.1), List.of(240, 250));

        assertThat(client.sampleAt(response, LocalDateTime.of(2026, 7, 12, 6, 0))).isEmpty();
    }

    @Test
    @DisplayName("empty for a land grid cell that returned a null wave value")
    void emptyForLandCell() {
        OpenMeteoMarineResponse response = response(
                List.of("2026-07-11T06:00", "2026-07-11T07:00"),
                Arrays.asList(null, null), Arrays.asList(null, null), Arrays.asList(null, null));

        assertThat(client.sampleAt(response, LocalDateTime.of(2026, 7, 11, 6, 0))).isEmpty();
    }

    @Test
    @DisplayName("empty for null response or null event time")
    void emptyForNullInputs() {
        assertThat(client.sampleAt(null, LocalDateTime.of(2026, 7, 11, 6, 0))).isEmpty();
        OpenMeteoMarineResponse response = response(
                List.of("2026-07-11T06:00"), List.of(3.9), List.of(1.0), List.of(240));
        assertThat(client.sampleAt(response, null)).isEmpty();
    }
}
