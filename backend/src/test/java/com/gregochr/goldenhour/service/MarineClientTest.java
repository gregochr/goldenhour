package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.OpenMeteoMarineApi;
import com.gregochr.goldenhour.model.MarineWavePeak;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    // -- peakInWindow (big seas) --------------------------------------------------------------

    private static OpenMeteoMarineResponse withPeriods(List<String> times, List<Double> waveHeights,
            List<Integer> directions, List<Double> periods, List<Double> swellPeriods) {
        OpenMeteoMarineResponse response = response(times, waveHeights,
                waveHeights.stream().map(h -> h == null ? null : h - 1.0).toList(), directions);
        response.getHourly().setWavePeriod(periods);
        response.getHourly().setSwellWavePeriod(swellPeriods);
        return response;
    }

    private static final List<String> FIVE_HOURS = List.of("2026-12-10T10:00", "2026-12-10T11:00",
            "2026-12-10T12:00", "2026-12-10T13:00", "2026-12-10T14:00");

    @Test
    @DisplayName("the peak is the highest hour inside the window, carrying that hour's direction and periods")
    void peakInWindow_highestInside() {
        OpenMeteoMarineResponse response = withPeriods(FIVE_HOURS,
                List.of(9.0, 3.0, 4.2, 3.5, 9.0), List.of(10, 20, 30, 40, 50),
                List.of(5.0, 8.0, 11.0, 9.0, 5.0), List.of(6.0, 9.0, 12.0, 10.0, 6.0));

        // 11:00-13:00 inclusive: the 9.0 m hours at 10:00 and 14:00 are outside and must not win.
        Optional<MarineWavePeak> peak = client.peakInWindow(response,
                LocalDateTime.of(2026, 12, 10, 11, 0), LocalDateTime.of(2026, 12, 10, 13, 0));

        assertThat(peak).contains(new MarineWavePeak(
                LocalDateTime.of(2026, 12, 10, 12, 0), 4.2, 30, 11.0, 3.2, 12.0));
    }

    @Test
    @DisplayName("a tie goes to the earlier hour")
    void peakInWindow_tieGoesToEarlier() {
        OpenMeteoMarineResponse response = withPeriods(FIVE_HOURS,
                List.of(1.0, 4.0, 3.0, 4.0, 1.0), List.of(10, 20, 30, 40, 50),
                List.of(5.0, 8.0, 11.0, 9.0, 5.0), List.of(6.0, 9.0, 12.0, 10.0, 6.0));

        Optional<MarineWavePeak> peak = client.peakInWindow(response,
                LocalDateTime.of(2026, 12, 10, 10, 0), LocalDateTime.of(2026, 12, 10, 14, 0));

        assertThat(peak).isPresent();
        assertThat(peak.get().peakTime()).isEqualTo(LocalDateTime.of(2026, 12, 10, 11, 0));
        assertThat(peak.get().direction()).isEqualTo(20);
    }

    @Test
    @DisplayName("a window holding no hourly stamp falls back to the hour nearest its midpoint")
    void peakInWindow_fallsBackToNearestHour() {
        OpenMeteoMarineResponse response = withPeriods(FIVE_HOURS,
                List.of(1.0, 2.0, 3.0, 4.0, 5.0), List.of(10, 20, 30, 40, 50),
                List.of(5.0, 8.0, 11.0, 9.0, 5.0), List.of(6.0, 9.0, 12.0, 10.0, 6.0));

        // 12:10-12:40 holds no stamp; the midpoint 12:25 is nearest 12:00 (not the higher 13:00 hour).
        Optional<MarineWavePeak> peak = client.peakInWindow(response,
                LocalDateTime.of(2026, 12, 10, 12, 10), LocalDateTime.of(2026, 12, 10, 12, 40));

        assertThat(peak).isPresent();
        assertThat(peak.get().peakTime()).isEqualTo(LocalDateTime.of(2026, 12, 10, 12, 0));
        assertThat(peak.get().waveHeight()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("the fallback rounds the midpoint to the NEAREST hour: 12:45 reaches 13:00, not 12:00")
    void peakInWindow_fallbackRoundsUp() {
        OpenMeteoMarineResponse response = withPeriods(FIVE_HOURS,
                List.of(1.0, 2.0, 3.0, 4.0, 5.0), List.of(10, 20, 30, 40, 50),
                List.of(5.0, 8.0, 11.0, 9.0, 5.0), List.of(6.0, 9.0, 12.0, 10.0, 6.0));

        Optional<MarineWavePeak> peak = client.peakInWindow(response,
                LocalDateTime.of(2026, 12, 10, 12, 35), LocalDateTime.of(2026, 12, 10, 12, 55));

        assertThat(peak).isPresent();
        assertThat(peak.get().peakTime()).isEqualTo(LocalDateTime.of(2026, 12, 10, 13, 0));
    }

    @Test
    @DisplayName("a peak exactly on the window's start stamp is inside it (the bounds are inclusive)")
    void peakInWindow_peakOnFromStamp() {
        OpenMeteoMarineResponse response = withPeriods(FIVE_HOURS,
                List.of(9.0, 9.5, 3.0, 2.0, 1.0), List.of(10, 20, 30, 40, 50),
                List.of(5.0, 8.0, 11.0, 9.0, 5.0), List.of(6.0, 9.0, 12.0, 10.0, 6.0));

        Optional<MarineWavePeak> peak = client.peakInWindow(response,
                LocalDateTime.of(2026, 12, 10, 11, 0), LocalDateTime.of(2026, 12, 10, 13, 0));

        assertThat(peak).isPresent();
        assertThat(peak.get().peakTime()).isEqualTo(LocalDateTime.of(2026, 12, 10, 11, 0));
        assertThat(peak.get().waveHeight()).isEqualTo(9.5);
    }

    @Test
    @DisplayName("the fallback is empty when the nearest hour is outside the fetched series")
    void peakInWindow_fallbackOutsideSeriesIsEmpty() {
        OpenMeteoMarineResponse response = withPeriods(FIVE_HOURS,
                List.of(1.0, 2.0, 3.0, 4.0, 5.0), List.of(10, 20, 30, 40, 50),
                List.of(5.0, 8.0, 11.0, 9.0, 5.0), List.of(6.0, 9.0, 12.0, 10.0, 6.0));

        assertThat(client.peakInWindow(response,
                LocalDateTime.of(2026, 12, 11, 12, 10), LocalDateTime.of(2026, 12, 11, 12, 40))).isEmpty();
    }

    @Test
    @DisplayName("empty for a land cell, where every wave value is null")
    void peakInWindow_landCell() {
        OpenMeteoMarineResponse response = withPeriods(FIVE_HOURS,
                Arrays.asList(null, null, null, null, null), Arrays.asList(null, null, null, null, null),
                Arrays.asList(null, null, null, null, null), Arrays.asList(null, null, null, null, null));

        assertThat(client.peakInWindow(response,
                LocalDateTime.of(2026, 12, 10, 10, 0), LocalDateTime.of(2026, 12, 10, 14, 0))).isEmpty();
    }

    @Test
    @DisplayName("a missing period list gives a peak with null periods, not an empty result")
    void peakInWindow_missingPeriodLists() {
        OpenMeteoMarineResponse response = response(FIVE_HOURS,
                List.of(1.0, 2.0, 3.0, 4.0, 5.0), List.of(0.5, 1.0, 1.5, 2.0, 2.5), List.of(10, 20, 30, 40, 50));

        Optional<MarineWavePeak> peak = client.peakInWindow(response,
                LocalDateTime.of(2026, 12, 10, 10, 0), LocalDateTime.of(2026, 12, 10, 14, 0));

        assertThat(peak).contains(new MarineWavePeak(
                LocalDateTime.of(2026, 12, 10, 14, 0), 5.0, 50, null, 2.5, null));
    }

    @Test
    @DisplayName("empty for null inputs, an inverted window, no series, or an unparseable stamp")
    void peakInWindow_degenerateInputs() {
        LocalDateTime from = LocalDateTime.of(2026, 12, 10, 10, 0);
        LocalDateTime to = LocalDateTime.of(2026, 12, 10, 14, 0);
        OpenMeteoMarineResponse response = response(FIVE_HOURS,
                List.of(1.0, 2.0, 3.0, 4.0, 5.0), List.of(0.5, 1.0, 1.5, 2.0, 2.5), List.of(10, 20, 30, 40, 50));

        assertThat(client.peakInWindow(null, from, to)).isEmpty();
        assertThat(client.peakInWindow(new OpenMeteoMarineResponse(), from, to)).isEmpty();
        assertThat(client.peakInWindow(response, null, to)).isEmpty();
        assertThat(client.peakInWindow(response, from, null)).isEmpty();
        assertThat(client.peakInWindow(response, to, from)).isEmpty();
        assertThat(client.peakInWindow(response(List.of(), List.of(), List.of(), List.of()), from, to)).isEmpty();
        assertThat(client.peakInWindow(
                response(List.of("not-a-time"), List.of(4.0), List.of(1.0), List.of(10)), from, to)).isEmpty();
    }

    @Test
    @DisplayName("fetchMarineBatch sends one comma-joined request for two or more points, in order")
    void fetchMarineBatch_multiplePoints_oneRequest() {
        OpenMeteoMarineApi api = mock(OpenMeteoMarineApi.class);
        OpenMeteoMarineResponse first = new OpenMeteoMarineResponse();
        OpenMeteoMarineResponse second = new OpenMeteoMarineResponse();
        when(api.getMarineBatch("55.6,54.9", "-1.7,-1.35",
                "wave_height,swell_wave_height,wave_direction,wave_period,swell_wave_period", "UTC", 7))
                .thenReturn(List.of(first, second));

        List<OpenMeteoMarineResponse> result = new MarineClient(api).fetchMarineBatch(
                List.of(new MarineClient.Point(55.6, -1.7), new MarineClient.Point(54.9, -1.35)));

        assertThat(result).containsExactly(first, second);
    }

    @Test
    @DisplayName("fetchMarineBatch sends a single point through the single-point exchange (bare object)")
    void fetchMarineBatch_singlePoint_usesSingleExchange() {
        OpenMeteoMarineApi api = mock(OpenMeteoMarineApi.class);
        OpenMeteoMarineResponse only = new OpenMeteoMarineResponse();
        when(api.getMarine(55.6, -1.7,
                "wave_height,swell_wave_height,wave_direction,wave_period,swell_wave_period", "UTC", 7))
                .thenReturn(only);

        List<OpenMeteoMarineResponse> result =
                new MarineClient(api).fetchMarineBatch(List.of(new MarineClient.Point(55.6, -1.7)));

        assertThat(result).containsExactly(only);
    }

    @Test
    @DisplayName("fetchMarineBatch rejects a response whose length differs from the request")
    void fetchMarineBatch_lengthMismatch_throws() {
        OpenMeteoMarineApi api = mock(OpenMeteoMarineApi.class);
        when(api.getMarineBatch("55.6,54.9", "-1.7,-1.35",
                "wave_height,swell_wave_height,wave_direction,wave_period,swell_wave_period", "UTC", 7))
                .thenReturn(List.of(new OpenMeteoMarineResponse()));

        assertThatThrownBy(() -> new MarineClient(api).fetchMarineBatch(
                List.of(new MarineClient.Point(55.6, -1.7), new MarineClient.Point(54.9, -1.35))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 responses for 2 coordinates");
    }

    @Test
    @DisplayName("fetchMarineBatch rejects a null body")
    void fetchMarineBatch_nullBody_throws() {
        OpenMeteoMarineApi api = mock(OpenMeteoMarineApi.class);
        when(api.getMarineBatch("55.6,54.9", "-1.7,-1.35",
                "wave_height,swell_wave_height,wave_direction,wave_period,swell_wave_period", "UTC", 7))
                .thenReturn(null);

        assertThatThrownBy(() -> new MarineClient(api).fetchMarineBatch(
                List.of(new MarineClient.Point(55.6, -1.7), new MarineClient.Point(54.9, -1.35))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no body");
    }
}
