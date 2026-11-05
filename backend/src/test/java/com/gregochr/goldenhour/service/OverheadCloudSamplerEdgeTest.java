package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;

/**
 * Edge cases of {@link OverheadCloudSampler}: a batch shorter than the request, forecasts missing their hourly
 * block or layers, and layers shorter than the time axis.
 */
class OverheadCloudSamplerEdgeTest {

    private static final String HOUR = "2026-08-13T00:00";
    private static final List<String> HOURS = List.of(HOUR);

    private OpenMeteoClient client;
    private OverheadCloudSampler sampler;
    private LocationEntity first;
    private LocationEntity second;
    private LocationEntity third;

    @BeforeEach
    void setUp() {
        client = mock(OpenMeteoClient.class);
        sampler = new OverheadCloudSampler(client);
        first = LocationEntity.builder().id(1L).name("L1").lat(55.0).lon(-1.5).build();
        second = LocationEntity.builder().id(2L).name("L2").lat(55.5).lon(-2.5).build();
        third = LocationEntity.builder().id(3L).name("L3").lat(56.0).lon(-3.5).build();
    }

    /** Matches a request for exactly these [lat, lon] points, in order. */
    private static ArgumentMatcher<List<double[]>> points(double[]... expected) {
        return actual -> {
            if (actual == null || actual.size() != expected.length) {
                return false;
            }
            for (int i = 0; i < expected.length; i++) {
                if (!Arrays.equals(actual.get(i), expected[i])) {
                    return false;
                }
            }
            return true;
        };
    }

    private static OpenMeteoForecastResponse forecast(List<String> times, List<Integer> low, List<Integer> mid,
            List<Integer> high) {
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
        hourly.setTime(times);
        hourly.setCloudCoverLow(low);
        hourly.setCloudCoverMid(mid);
        hourly.setCloudCoverHigh(high);
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        response.setHourly(hourly);
        return response;
    }

    @Test
    @DisplayName("a batch shorter than the request leaves the unanswered locations at the overcast default")
    void sample_shortBatch_unansweredAreOvercast() {
        when(client.fetchCloudOnlyBatch(argThat(points(new double[]{55.0, -1.5}, new double[]{55.5, -2.5}))))
                .thenReturn(List.of(forecast(HOURS, List.of(10), List.of(20), List.of(30))));

        Map<LocationEntity, int[]> out = sampler.sample(List.of(first, second), HOURS);

        assertThat(out.get(first)).containsExactly(60);
        assertThat(out.get(second)).containsExactly(75);
    }

    @Test
    @DisplayName("forecasts missing their hourly block, time axis or low layer read as overcast")
    void sample_malformedForecasts_overcast() {
        when(client.fetchCloudOnlyBatch(argThat(points(
                new double[]{55.0, -1.5}, new double[]{55.5, -2.5}, new double[]{56.0, -3.5}))))
                .thenReturn(List.of(new OpenMeteoForecastResponse(),
                        forecast(null, List.of(10), List.of(10), List.of(10)),
                        forecast(HOURS, null, List.of(10), List.of(10))));

        Map<LocationEntity, int[]> out = sampler.sample(List.of(first, second, third), HOURS);

        assertThat(out.get(first)).containsExactly(75);
        assertThat(out.get(second)).containsExactly(75);
        assertThat(out.get(third)).containsExactly(75);
    }

    @Test
    @DisplayName("absent, short or null-holding layers count as zero cloud for that layer")
    void sample_missingLayersCountAsZero() {
        when(client.fetchCloudOnlyBatch(argThat(points(
                new double[]{55.0, -1.5}, new double[]{55.5, -2.5}, new double[]{56.0, -3.5}))))
                .thenReturn(List.of(
                        forecast(HOURS, List.of(20), null, List.of()),
                        forecast(HOURS, List.of(30), Arrays.asList((Integer) null), null),
                        forecast(HOURS, List.of(10), List.of(5), List.of(70))));

        Map<LocationEntity, int[]> out = sampler.sample(List.of(first, second, third), HOURS);

        assertThat(out.get(first)).containsExactly(20);
        assertThat(out.get(second)).containsExactly(30);
        assertThat(out.get(third)).containsExactly(85);
    }

    @Test
    @DisplayName("an hour missing from the forecast's time axis reads as overcast")
    void sample_hourNotInForecast_overcast() {
        when(client.fetchCloudOnlyBatch(argThat(points(new double[]{55.0, -1.5}))))
                .thenReturn(List.of(forecast(List.of("2026-08-13T05:00"), List.of(0), List.of(0), List.of(0))));

        Map<LocationEntity, int[]> out = sampler.sample(List.of(first), HOURS);

        assertThat(out.get(first)).containsExactly(75);
    }
}
