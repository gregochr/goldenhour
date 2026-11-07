package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.util.GeoUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;

/**
 * Failure and malformed-data edges of {@link NorthwardTransectSampler}: short batches, a partly recovered or
 * failing retry, forecasts missing their hourly block or layers, and layers shorter than the time series.
 */
class NorthwardTransectSamplerEdgeTest {

    private static final String HOUR = "2026-06-17T00:00";
    private static final List<String> HOURS = List.of(HOUR);
    private static final double LAT = 55.0;
    private static final double LON = -1.5;
    private static final double[] OFFSETS_M = {50_000.0, 100_000.0, 150_000.0};

    private OpenMeteoClient client;
    private NorthwardTransectSampler sampler;
    private LocationEntity location;

    @BeforeEach
    void setUp() {
        client = mock(OpenMeteoClient.class);
        sampler = new NorthwardTransectSampler(client);
        location = LocationEntity.builder().id(1L).name("L1").lat(LAT).lon(LON).build();
    }

    private static double[] point(int index) {
        return GeoUtils.offsetPoint(LAT, LON, 0.0, OFFSETS_M[index]);
    }

    /** Matches a request for exactly these transect points, in order. */
    private static ArgumentMatcher<List<double[]>> points(int... indexes) {
        return actual -> {
            if (actual == null || actual.size() != indexes.length) {
                return false;
            }
            for (int i = 0; i < indexes.length; i++) {
                if (!Arrays.equals(actual.get(i), point(indexes[i]))) {
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

    private static OpenMeteoForecastResponse cloud(int low) {
        return forecast(HOURS, List.of(low), List.of(0), List.of(0));
    }

    private static List<OpenMeteoForecastResponse> responses(OpenMeteoForecastResponse... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    @Test
    @DisplayName("a retry that returns fewer answers than failed points recovers only those it answers")
    void sampleMeasured_shortRetry_recoversWhatItCan() {
        when(client.fetchCloudOnlyBatch(argThat(points(0, 1, 2))))
                .thenReturn(responses(cloud(20), null, null));
        when(client.fetchCloudOnlyBatch(argThat(points(1, 2)))).thenReturn(responses(cloud(40)));

        Map<LocationEntity, int[]> out = sampler.sampleMeasured(List.of(location), HOURS,
                NorthwardTransectSampler.LayerCombiner.MAX_LAYER);

        assertThat(out.get(location)).containsExactly(30);
    }

    @Test
    @DisplayName("a retry that throws leaves the failed points unknown and the measured one standing")
    void sampleMeasured_retryThrows_pointsStayUnknown() {
        when(client.fetchCloudOnlyBatch(argThat(points(0, 1, 2))))
                .thenReturn(responses(cloud(20), null, null));
        when(client.fetchCloudOnlyBatch(argThat(points(1, 2)))).thenThrow(new IllegalStateException("429"));

        Map<LocationEntity, int[]> out = sampler.sampleMeasured(List.of(location), HOURS,
                NorthwardTransectSampler.LayerCombiner.MAX_LAYER);

        assertThat(out.get(location)).containsExactly(20);
    }

    @Test
    @DisplayName("a retry that answers null for a point leaves it unknown")
    void sampleMeasured_retryAnswersNull_stillUnknown() {
        when(client.fetchCloudOnlyBatch(argThat(points(0, 1, 2))))
                .thenReturn(responses(cloud(20), cloud(60), null));
        when(client.fetchCloudOnlyBatch(argThat(points(2)))).thenReturn(responses((OpenMeteoForecastResponse) null));

        Map<LocationEntity, int[]> out = sampler.sampleMeasured(List.of(location), HOURS,
                NorthwardTransectSampler.LayerCombiner.MAX_LAYER);

        assertThat(out.get(location)).containsExactly(40);
    }

    @Test
    @DisplayName("measured sampling of a batch shorter than the request pads the missing points as failed, "
            + "and a location with no measured point is absent")
    void sampleMeasured_shortBatch_everythingUnknown() {
        when(client.fetchCloudOnlyBatch(argThat(points(0, 1, 2)))).thenReturn(responses());

        Map<LocationEntity, int[]> out = sampler.sampleMeasured(List.of(location), HOURS,
                NorthwardTransectSampler.LayerCombiner.MAX_LAYER);

        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("default sampling pads a short batch with failed points, which read as overcast")
    void sample_shortBatch_missingPointsAreOvercast() {
        when(client.fetchCloudOnlyBatch(argThat(points(0, 1, 2)))).thenReturn(responses(cloud(20)));

        Map<LocationEntity, int[]> out = sampler.sample(List.of(location), HOURS,
                NorthwardTransectSampler.LayerCombiner.MAX_LAYER);

        assertThat(out.get(location)).containsExactly((20 + 75 + 75) / 3);
    }

    @Test
    @DisplayName("forecasts missing their hourly block, time axis or low layer read as overcast")
    void sample_malformedForecasts_overcast() {
        OpenMeteoForecastResponse noHourly = new OpenMeteoForecastResponse();
        OpenMeteoForecastResponse noTime = forecast(null, List.of(10), List.of(10), List.of(10));
        OpenMeteoForecastResponse noLow = forecast(HOURS, null, List.of(10), List.of(10));
        when(client.fetchCloudOnlyBatch(argThat(points(0, 1, 2)))).thenReturn(responses(noHourly, noTime, noLow));

        Map<LocationEntity, int[]> out = sampler.sample(List.of(location), HOURS,
                NorthwardTransectSampler.LayerCombiner.MAX_LAYER);

        assertThat(out.get(location)).containsExactly(75);
    }

    @Test
    @DisplayName("absent, short or null-holding layers count as zero cloud for that layer")
    void sample_missingLayersCountAsZero() {
        OpenMeteoForecastResponse lowOnly = forecast(HOURS, List.of(20), null, List.of());
        OpenMeteoForecastResponse nullMid = forecast(HOURS, List.of(30), Arrays.asList((Integer) null), null);
        OpenMeteoForecastResponse highWins = forecast(HOURS, List.of(10), List.of(5), List.of(70));
        when(client.fetchCloudOnlyBatch(argThat(points(0, 1, 2))))
                .thenReturn(responses(lowOnly, nullMid, highWins));

        Map<LocationEntity, int[]> out = sampler.sample(List.of(location), HOURS,
                NorthwardTransectSampler.LayerCombiner.MAX_LAYER);

        assertThat(out.get(location)).containsExactly((20 + 30 + 70) / 3);
    }

    @Test
    @DisplayName("an hour missing from the forecast's time axis reads as overcast")
    void sample_hourNotInForecast_overcast() {
        OpenMeteoForecastResponse otherHour = forecast(List.of("2026-06-17T05:00"), List.of(0), List.of(0),
                List.of(0));
        when(client.fetchCloudOnlyBatch(argThat(points(0, 1, 2))))
                .thenReturn(responses(otherHour, otherHour, otherHour));

        Map<LocationEntity, int[]> out = sampler.sample(List.of(location), HOURS,
                NorthwardTransectSampler.LayerCombiner.MAX_LAYER);

        assertThat(out.get(location)).containsExactly(75);
    }
}
