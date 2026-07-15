package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NorthwardTransectSampler}.
 */
@ExtendWith(MockitoExtension.class)
class NorthwardTransectSamplerTest {

    private static final String HOUR = "2026-06-17T00:00";

    @Mock
    private OpenMeteoClient openMeteoClient;

    private NorthwardTransectSampler sampler;

    @BeforeEach
    void setUp() {
        sampler = new NorthwardTransectSampler(openMeteoClient);
    }

    private static LocationEntity loc(long id, double lat, double lon) {
        return LocationEntity.builder().id(id).name("L" + id).lat(lat).lon(lon).build();
    }

    /** A single-hour cloud-only forecast at {@link #HOUR}. */
    private static OpenMeteoForecastResponse resp(int low, int mid, int high) {
        return respAt(HOUR, low, mid, high);
    }

    private static OpenMeteoForecastResponse respAt(String hour, int low, int mid, int high) {
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
        hourly.setTime(List.of(hour));
        hourly.setCloudCoverLow(List.of(low));
        hourly.setCloudCoverMid(List.of(mid));
        hourly.setCloudCoverHigh(List.of(high));
        OpenMeteoForecastResponse r = new OpenMeteoForecastResponse();
        r.setHourly(hourly);
        return r;
    }

    /** Stubs the batch to return the same forecast for every requested coordinate. */
    private void stubUniform(OpenMeteoForecastResponse r) {
        when(openMeteoClient.fetchCloudOnlyBatch(any()))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).stream().map(c -> r).toList());
    }

    @Test
    @DisplayName("no locations or no hours makes no API call and returns empty")
    void sample_empty_noApiCall() {
        assertThat(sampler.sample(List.of(), List.of(HOUR),
                NorthwardTransectSampler.LayerCombiner.MAX_LAYER)).isEmpty();
        assertThat(sampler.sample(List.of(loc(1, 55, -1.5)), List.of(),
                NorthwardTransectSampler.LayerCombiner.MAX_LAYER)).isEmpty();
        verifyNoInteractions(openMeteoClient);
    }

    @Test
    @DisplayName("SUM_CAPPED combiner sums the three layers")
    void sample_sumCapped() {
        stubUniform(resp(10, 20, 30));
        Map<LocationEntity, int[]> out = sampler.sample(List.of(loc(1, 55, -1.5)), List.of(HOUR),
                NorthwardTransectSampler.LayerCombiner.SUM_CAPPED);
        assertThat(out.values().iterator().next()[0]).isEqualTo(60);
    }

    @Test
    @DisplayName("SUM_CAPPED combiner caps at 100")
    void sample_sumCapped_capsAt100() {
        stubUniform(resp(40, 40, 40));
        Map<LocationEntity, int[]> out = sampler.sample(List.of(loc(1, 55, -1.5)), List.of(HOUR),
                NorthwardTransectSampler.LayerCombiner.SUM_CAPPED);
        assertThat(out.values().iterator().next()[0]).isEqualTo(100);
    }

    @Test
    @DisplayName("MAX_LAYER combiner takes the worst single layer")
    void sample_maxLayer() {
        stubUniform(resp(10, 20, 30));
        Map<LocationEntity, int[]> out = sampler.sample(List.of(loc(1, 55, -1.5)), List.of(HOUR),
                NorthwardTransectSampler.LayerCombiner.MAX_LAYER);
        assertThat(out.values().iterator().next()[0]).isEqualTo(30);
    }

    @Test
    @DisplayName("null grid responses default to overcast")
    void sample_nullResponses_overcast() {
        when(openMeteoClient.fetchCloudOnlyBatch(any()))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).stream()
                        .map(c -> (OpenMeteoForecastResponse) null).toList());
        Map<LocationEntity, int[]> out = sampler.sample(List.of(loc(1, 55, -1.5)), List.of(HOUR),
                NorthwardTransectSampler.LayerCombiner.MAX_LAYER);
        assertThat(out.values().iterator().next()[0])
                .isEqualTo(CloudScoringRules.OVERCAST_PERCENT);
    }

    @Test
    @DisplayName("a batch exception defaults every sample to overcast")
    void sample_batchThrows_overcast() {
        when(openMeteoClient.fetchCloudOnlyBatch(any())).thenThrow(new RuntimeException("down"));
        Map<LocationEntity, int[]> out = sampler.sample(List.of(loc(1, 55, -1.5)), List.of(HOUR),
                NorthwardTransectSampler.LayerCombiner.MAX_LAYER);
        assertThat(out.values().iterator().next()[0])
                .isEqualTo(CloudScoringRules.OVERCAST_PERCENT);
    }

    @Test
    @DisplayName("an hour missing from the forecast defaults to overcast")
    void sample_missingHour_overcast() {
        stubUniform(respAt("2026-06-17T12:00", 0, 0, 0));
        Map<LocationEntity, int[]> out = sampler.sample(List.of(loc(1, 55, -1.5)), List.of(HOUR),
                NorthwardTransectSampler.LayerCombiner.MAX_LAYER);
        assertThat(out.values().iterator().next()[0])
                .isEqualTo(CloudScoringRules.OVERCAST_PERCENT);
    }

    @Test
    @DisplayName("averages combined cloud across the three transect points")
    void sample_averagesAcrossTransect() {
        // Three distinct grid responses (50/100/150 km points), returned in fetch order.
        when(openMeteoClient.fetchCloudOnlyBatch(any())).thenReturn(List.of(
                resp(10, 0, 0), resp(40, 0, 0), resp(70, 0, 0)));
        Map<LocationEntity, int[]> out = sampler.sample(List.of(loc(1, 55, -1.5)), List.of(HOUR),
                NorthwardTransectSampler.LayerCombiner.MAX_LAYER);
        // (10 + 40 + 70) / 3 = 40
        assertThat(out.values().iterator().next()[0]).isEqualTo(40);
    }

    @Test
    @DisplayName("co-located locations share deduplicated grid points — one fetch per unique cell")
    void sample_dedupsGridPoints() {
        stubUniform(resp(0, 0, 0));
        // Two locations at the same coordinates: their north transects are identical, so the six
        // points collapse to three unique grid cells (one batch, not six).
        sampler.sample(List.of(loc(1, 55.0, -1.5), loc(2, 55.0, -1.5)), List.of(HOUR),
                NorthwardTransectSampler.LayerCombiner.MAX_LAYER);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<double[]>> captor = ArgumentCaptor.forClass(List.class);
        verify(openMeteoClient).fetchCloudOnlyBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(3);
    }
}
