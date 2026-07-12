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
 * Unit tests for {@link OverheadCloudSampler} — the whole-sky (at-location, total-column) cloud
 * sampler used by the meteor clarity scan, distinct from the northern-horizon transect.
 */
@ExtendWith(MockitoExtension.class)
class OverheadCloudSamplerTest {

    private static final String HOUR = "2026-08-13T00:00";

    @Mock
    private OpenMeteoClient openMeteoClient;

    private OverheadCloudSampler sampler;

    @BeforeEach
    void setUp() {
        sampler = new OverheadCloudSampler(openMeteoClient);
    }

    private static LocationEntity loc(long id, double lat, double lon) {
        return LocationEntity.builder().id(id).name("L" + id).lat(lat).lon(lon).build();
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
        assertThat(sampler.sample(List.of(), List.of(HOUR))).isEmpty();
        assertThat(sampler.sample(List.of(loc(1, 55, -1.5)), List.of())).isEmpty();
        verifyNoInteractions(openMeteoClient);
    }

    @Test
    @DisplayName("samples cloud AT the location itself, not offset toward a horizon")
    void sample_atLocationNotOffset() {
        stubUniform(respAt(HOUR, 0, 0, 0));

        sampler.sample(List.of(loc(1, 55.0, -1.5)), List.of(HOUR));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<double[]>> captor = ArgumentCaptor.forClass(List.class);
        verify(openMeteoClient).fetchCloudOnlyBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        // The sampled coordinate is the site's own lat/lon — overhead, not 50–150 km due north.
        assertThat(captor.getValue().get(0)[0]).isEqualTo(55.0);
        assertThat(captor.getValue().get(0)[1]).isEqualTo(-1.5);
    }

    @Test
    @DisplayName("reduces the three layers via SUM_CAPPED (total column)")
    void sample_sumCapped() {
        stubUniform(respAt(HOUR, 10, 20, 30));
        Map<LocationEntity, int[]> out = sampler.sample(List.of(loc(1, 55, -1.5)), List.of(HOUR));
        assertThat(out.values().iterator().next()[0]).isEqualTo(60);
    }

    @Test
    @DisplayName("SUM_CAPPED caps the total column at 100")
    void sample_sumCapped_capsAt100() {
        stubUniform(respAt(HOUR, 40, 40, 40));
        Map<LocationEntity, int[]> out = sampler.sample(List.of(loc(1, 55, -1.5)), List.of(HOUR));
        assertThat(out.values().iterator().next()[0]).isEqualTo(100);
    }

    @Test
    @DisplayName("null grid responses default to overcast")
    void sample_nullResponses_overcast() {
        when(openMeteoClient.fetchCloudOnlyBatch(any()))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).stream()
                        .map(c -> (OpenMeteoForecastResponse) null).toList());
        Map<LocationEntity, int[]> out = sampler.sample(List.of(loc(1, 55, -1.5)), List.of(HOUR));
        assertThat(out.values().iterator().next()[0])
                .isEqualTo(OverheadCloudSampler.DEFAULT_OVERCAST_PERCENT);
    }

    @Test
    @DisplayName("a batch exception defaults every sample to overcast")
    void sample_batchThrows_overcast() {
        when(openMeteoClient.fetchCloudOnlyBatch(any())).thenThrow(new RuntimeException("down"));
        Map<LocationEntity, int[]> out = sampler.sample(List.of(loc(1, 55, -1.5)), List.of(HOUR));
        assertThat(out.values().iterator().next()[0])
                .isEqualTo(OverheadCloudSampler.DEFAULT_OVERCAST_PERCENT);
    }

    @Test
    @DisplayName("an hour missing from the forecast defaults to overcast")
    void sample_missingHour_overcast() {
        stubUniform(respAt("2026-08-13T12:00", 0, 0, 0));
        Map<LocationEntity, int[]> out = sampler.sample(List.of(loc(1, 55, -1.5)), List.of(HOUR));
        assertThat(out.values().iterator().next()[0])
                .isEqualTo(OverheadCloudSampler.DEFAULT_OVERCAST_PERCENT);
    }

    @Test
    @DisplayName("co-located sites share one deduplicated grid cell — one fetch, not two")
    void sample_dedupsGridCells() {
        stubUniform(respAt(HOUR, 0, 0, 0));

        sampler.sample(List.of(loc(1, 55.0, -1.5), loc(2, 55.0, -1.5)), List.of(HOUR));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<double[]>> captor = ArgumentCaptor.forClass(List.class);
        verify(openMeteoClient).fetchCloudOnlyBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
    }
}
