package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.gregochr.goldenhour.client.OpenMeteoMarineApi;
import com.gregochr.goldenhour.model.MarineWavePeak;
import com.gregochr.goldenhour.model.MarineWaveSample;
import com.gregochr.goldenhour.model.OpenMeteoMarineResponse;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Missing, short and malformed series handling in {@link MarineClient#sampleAt} and
 * {@link MarineClient#peakInWindow}.
 */
class MarineClientMissingDataTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 12, 10, 10, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 12, 10, 12, 0);

    private final MarineClient client = new MarineClient(mock(OpenMeteoMarineApi.class));

    private static OpenMeteoMarineResponse withHourly(OpenMeteoMarineResponse.Hourly hourly) {
        OpenMeteoMarineResponse response = new OpenMeteoMarineResponse();
        response.setHourly(hourly);
        return response;
    }

    private static OpenMeteoMarineResponse.Hourly hourly(List<String> times, List<Double> waves,
            List<Integer> directions) {
        OpenMeteoMarineResponse.Hourly hourly = new OpenMeteoMarineResponse.Hourly();
        hourly.setTime(times);
        hourly.setWaveHeight(waves);
        hourly.setWaveDirection(directions);
        return hourly;
    }

    @Test
    @DisplayName("sampleAt is empty when the response carries no hourly block, no times or empty times")
    void sampleAt_noSeries() {
        LocalDateTime event = LocalDateTime.of(2026, 12, 10, 10, 0);

        assertThat(client.sampleAt(new OpenMeteoMarineResponse(), event)).isEmpty();
        assertThat(client.sampleAt(withHourly(hourly(null, List.of(1.0), List.of(10))), event)).isEmpty();
        assertThat(client.sampleAt(withHourly(hourly(List.of(), List.of(1.0), List.of(10))), event)).isEmpty();
    }

    @Test
    @DisplayName("sampleAt keeps the wave height when the swell and direction series are absent")
    void sampleAt_optionalSeriesAbsent() {
        OpenMeteoMarineResponse.Hourly hourly = hourly(List.of("2026-12-10T10:00"), List.of(2.5), null);

        Optional<MarineWaveSample> sample = client.sampleAt(withHourly(hourly), FROM);

        assertThat(sample).isPresent();
        assertThat(sample.get().significantWaveHeightMetres()).isEqualTo(2.5);
        assertThat(sample.get().swellWaveHeightMetres()).isNull();
        assertThat(sample.get().waveDirectionDegrees()).isNull();
    }

    @Test
    @DisplayName("sampleAt tolerates a direction series shorter than the time series")
    void sampleAt_shortDirectionSeries() {
        OpenMeteoMarineResponse.Hourly hourly = hourly(
                List.of("2026-12-10T10:00", "2026-12-10T11:00"), List.of(1.0, 2.0), List.of(10));

        Optional<MarineWaveSample> sample = client.sampleAt(withHourly(hourly), LocalDateTime.of(2026, 12, 10, 11, 0));

        assertThat(sample).isPresent();
        assertThat(sample.get().significantWaveHeightMetres()).isEqualTo(2.0);
        assertThat(sample.get().waveDirectionDegrees()).isNull();
    }

    @Test
    @DisplayName("peakInWindow is empty without a response, hourly block, bounds or with an inverted window")
    void peakInWindow_invalidInputs() {
        OpenMeteoMarineResponse good = withHourly(hourly(List.of("2026-12-10T10:00"), List.of(2.0), List.of(10)));

        assertThat(client.peakInWindow(null, FROM, TO)).isEmpty();
        assertThat(client.peakInWindow(new OpenMeteoMarineResponse(), FROM, TO)).isEmpty();
        assertThat(client.peakInWindow(good, null, TO)).isEmpty();
        assertThat(client.peakInWindow(good, FROM, null)).isEmpty();
        assertThat(client.peakInWindow(good, TO, FROM)).isEmpty();
    }

    @Test
    @DisplayName("peakInWindow is empty when the series has no times or empty times")
    void peakInWindow_noTimes() {
        assertThat(client.peakInWindow(withHourly(hourly(null, List.of(1.0), List.of(10))), FROM, TO)).isEmpty();
        assertThat(client.peakInWindow(withHourly(hourly(List.of(), List.of(1.0), List.of(10))), FROM, TO))
                .isEmpty();
    }

    @Test
    @DisplayName("null and unparseable stamps are skipped; the best parseable hour inside the window wins")
    void peakInWindow_skipsBadStamps() {
        OpenMeteoMarineResponse.Hourly hourly = hourly(
                Arrays.asList(null, "not-a-time", "2026-12-10T11:00"), List.of(9.0, 9.0, 2.5), List.of(1, 2, 3));

        Optional<MarineWavePeak> peak = client.peakInWindow(withHourly(hourly), FROM, TO);

        assertThat(peak).contains(new MarineWavePeak(
                LocalDateTime.of(2026, 12, 10, 11, 0), 2.5, 3, null, null, null));
    }

    @Test
    @DisplayName("hours inside the window without a wave value give no peak (land cell)")
    void peakInWindow_noWaveValuesInside() {
        OpenMeteoMarineResponse.Hourly hourly = hourly(
                List.of("2026-12-10T10:00", "2026-12-10T11:00"), Arrays.asList(null, null), List.of(1, 2));

        assertThat(client.peakInWindow(withHourly(hourly), FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("a wave series shorter than the time series ignores the uncovered hours; a missing one gives no peak")
    void peakInWindow_shortAndMissingWaveSeries() {
        List<String> times = List.of("2026-12-10T10:00", "2026-12-10T11:00");

        Optional<MarineWavePeak> peak = client.peakInWindow(
                withHourly(hourly(times, List.of(1.5), List.of(7))), FROM, TO);
        assertThat(peak).contains(new MarineWavePeak(
                LocalDateTime.of(2026, 12, 10, 10, 0), 1.5, 7, null, null, null));

        assertThat(client.peakInWindow(withHourly(hourly(times, null, List.of(7))), FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("a direction series shorter than the time series leaves the direction null")
    void peakInWindow_shortDirectionSeries() {
        OpenMeteoMarineResponse.Hourly hourly = hourly(
                List.of("2026-12-10T10:00", "2026-12-10T11:00"), List.of(1.0, 3.0), List.of(7));

        Optional<MarineWavePeak> peak = client.peakInWindow(withHourly(hourly), FROM, TO);

        assertThat(peak).contains(new MarineWavePeak(
                LocalDateTime.of(2026, 12, 10, 11, 0), 3.0, null, null, null, null));
    }
}
