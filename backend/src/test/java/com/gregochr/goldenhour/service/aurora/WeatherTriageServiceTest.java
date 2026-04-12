package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.service.OpenMeteoClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WeatherTriageService} cloud-based location triage.
 */
@ExtendWith(MockitoExtension.class)
class WeatherTriageServiceTest {

    @Mock
    private OpenMeteoClient openMeteoClient;

    private WeatherTriageService service;

    @BeforeEach
    void setUp() {
        service = new WeatherTriageService(openMeteoClient);
    }

    // -------------------------------------------------------------------------
    // Fallback paths (HTTP error → default cloud = 75 = overcast → rejected)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("null batch entries use defaultCloud — location treated as overcast and rejected")
    void triage_nullEntryInBatch_treatedAsDefaultCloud() {
        // One location → 3 northward transect points → batch has exactly 3 coords.
        // All 3 batch entries are null → defaultCloud (75%) for every transect point
        // → averagedHourly all 75% → no viable hour → rejected.
        LocationEntity loc = buildLocation(11L, "Loc A", 55.0, -2.0, 2);

        when(openMeteoClient.fetchCloudOnlyBatch(
                org.mockito.ArgumentMatchers.argThat(
                        (java.util.List<double[]> coords) -> coords.size() == 3)))
                .thenReturn(java.util.Arrays.asList(null, null, null));

        WeatherTriageService.TriageResult result = service.triage(List.of(loc));

        assertThat(result.rejected()).containsExactly(loc);
        assertThat(result.viable()).isEmpty();
        assertThat(result.cloudByLocation().get(loc)).isEqualTo(75);
    }

    @Test
    @DisplayName("triage rejects location when batch fetch throws exception")
    void triage_httpException_locationRejected() {
        when(openMeteoClient.fetchCloudOnlyBatch(any()))
                .thenThrow(new RuntimeException("timeout"));

        LocationEntity loc = buildLocation(1L, "Northumberland", 55.2, -2.0, 2);

        WeatherTriageService.TriageResult result = service.triage(List.of(loc));

        assertThat(result.viable()).isEmpty();
        assertThat(result.rejected()).containsExactly(loc);
        assertThat(result.cloudByLocation()).containsKey(loc);
        assertThat(result.cloudByLocation().get(loc)).isEqualTo(75);
    }

    // -------------------------------------------------------------------------
    // Viable vs rejected discrimination
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("triage passes location when at least one hour has cloud < 75%")
    void triage_anyHourClear_locationIsViable() {
        int[] cloudValues = {80, 90, 20, 85, 80, 75, 80};
        stubBatchReturnsCloudData(cloudValues);

        LocationEntity loc = buildLocation(3L, "Cairngorms", 57.1, -3.8, 1);

        WeatherTriageService.TriageResult result = service.triage(List.of(loc));

        assertThat(result.viable()).containsExactly(loc);
        assertThat(result.rejected()).isEmpty();
    }

    @Test
    @DisplayName("triage rejects location when all hours have cloud >= 75%")
    void triage_allHoursOvercast_locationRejected() {
        int[] cloudValues = {80, 90, 75, 85, 80, 75, 80};
        stubBatchReturnsCloudData(cloudValues);

        LocationEntity loc = buildLocation(4L, "Skye", 57.5, -6.2, 2);

        WeatherTriageService.TriageResult result = service.triage(List.of(loc));

        assertThat(result.viable()).isEmpty();
        assertThat(result.rejected()).containsExactly(loc);
    }

    @Test
    @DisplayName("triage computes average cloud across transect and stores in cloudByLocation")
    void triage_storesAverageCloud() {
        int[] cloudValues = {20, 20, 20, 20, 20, 20, 20};
        stubBatchReturnsCloudData(cloudValues);

        LocationEntity loc = buildLocation(5L, "Kielder", 55.2, -2.6, 2);

        WeatherTriageService.TriageResult result = service.triage(List.of(loc));

        assertThat(result.cloudByLocation()).containsKey(loc);
        // low 20 + mid 20 + high 20 = 60 per hour; 3 transect points all identical → avg 60;
        // 7-hour average of 60 = 60
        assertThat(result.cloudByLocation().get(loc)).isEqualTo(60);
    }

    // -------------------------------------------------------------------------
    // Multiple locations
    // -------------------------------------------------------------------------
    // Note: stubBatchReturnsCloudData applies the same cloud data to all
    // grid points, so both locations always get the same triage result.
    // Per-location discrimination requires per-coordinate stubbing.

    @Test
    @DisplayName("triage handles two locations independently when both overcast")
    void triage_twoLocations_bothRejectedWhenOvercast() {
        int[] overcast = {80, 80, 80, 80, 80, 80, 80};
        stubBatchReturnsCloudData(overcast);

        LocationEntity locA = buildLocation(6L, "A", 55.0, -2.0, 2);
        LocationEntity locB = buildLocation(7L, "B", 57.0, -4.0, 2);

        WeatherTriageService.TriageResult result = service.triage(List.of(locA, locB));

        assertThat(result.viable()).isEmpty();
        assertThat(result.rejected()).containsExactlyInAnyOrder(locA, locB);
    }

    @Test
    @DisplayName("triage handles two locations independently when both clear")
    void triage_twoLocations_bothViableWhenClear() {
        int[] clear = {20, 20, 20, 20, 20, 20, 20};
        stubBatchReturnsCloudData(clear);

        LocationEntity locA = buildLocation(8L, "A", 55.0, -2.0, 2);
        LocationEntity locB = buildLocation(9L, "B", 57.0, -4.0, 2);

        WeatherTriageService.TriageResult result = service.triage(List.of(locA, locB));

        assertThat(result.viable()).containsExactlyInAnyOrder(locA, locB);
        assertThat(result.rejected()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Overcast threshold boundary (75%)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Overcast threshold boundary (75%)")
    class OvercastThresholdBoundaryTests {

        @Test
        @DisplayName("All hours at exactly 75% — rejected (threshold is strictly < 75)")
        void allHoursExactly75_rejected() {
            int[] allAt75 = {75, 75, 75, 75, 75, 75, 75};
            stubBatchReturnsCloudData(allAt75);

            LocationEntity loc = buildLocation(20L, "Boundary", 55.0, -2.0, 2);
            WeatherTriageService.TriageResult result = service.triage(List.of(loc));

            assertThat(result.rejected()).containsExactly(loc);
            assertThat(result.viable()).isEmpty();
        }

        @Test
        @DisplayName("One hour combined at 72% (24+24+24), rest at 78% — viable (one hour below threshold)")
        void oneHourAt74_viable() {
            // Stub sets all three layers to the same value; combined = 3×value.
            // 24 per layer → 72% combined (< 75, viable); 26 per layer → 78% (≥ 75, overcast).
            int[] oneBelow = {26, 26, 24, 26, 26, 26, 26};
            stubBatchReturnsCloudData(oneBelow);

            LocationEntity loc = buildLocation(21L, "JustViable", 55.0, -2.0, 2);
            WeatherTriageService.TriageResult result = service.triage(List.of(loc));

            assertThat(result.viable()).containsExactly(loc);
            assertThat(result.rejected()).isEmpty();
        }

        @Test
        @DisplayName("All hours combined at 72% (24+24+24) — viable (all below threshold)")
        void allHoursAt74_viable() {
            // 24 per layer × 3 layers = 72% combined, which is < 75 → viable
            int[] allAt24 = {24, 24, 24, 24, 24, 24, 24};
            stubBatchReturnsCloudData(allAt24);

            LocationEntity loc = buildLocation(22L, "AllJustBelow", 55.0, -2.0, 2);
            WeatherTriageService.TriageResult result = service.triage(List.of(loc));

            assertThat(result.viable()).containsExactly(loc);
        }

        @Test
        @DisplayName("All hours at 100% — rejected")
        void allHoursAt100_rejected() {
            // low 100 + mid 100 + high 100 = 300, capped at 100 per hour
            int[] allAt100 = {100, 100, 100, 100, 100, 100, 100};
            stubBatchReturnsCloudData(allAt100);

            LocationEntity loc = buildLocation(23L, "TotalOvercast", 55.0, -2.0, 2);
            WeatherTriageService.TriageResult result = service.triage(List.of(loc));

            assertThat(result.rejected()).containsExactly(loc);
        }

        @Test
        @DisplayName("All hours at 0% — viable with cloud average of 0")
        void allHoursAt0_viable() {
            int[] allClear = {0, 0, 0, 0, 0, 0, 0};
            stubBatchReturnsCloudData(allClear);

            LocationEntity loc = buildLocation(24L, "PerfectlyClear", 55.0, -2.0, 2);
            WeatherTriageService.TriageResult result = service.triage(List.of(loc));

            assertThat(result.viable()).containsExactly(loc);
            assertThat(result.cloudByLocation().get(loc)).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("OVERCAST_THRESHOLD_PERCENT constant is 75")
    void defaultCloud_matchesThreshold() {
        assertThat(WeatherTriageService.OVERCAST_THRESHOLD_PERCENT).isEqualTo(75);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private LocationEntity buildLocation(long id, String name, double lat, double lon, int bortle) {
        return LocationEntity.builder()
                .id(id).name(name).lat(lat).lon(lon).bortleClass(bortle).build();
    }

    /**
     * Stubs the batch fetch to return an OpenMeteoForecastResponse with the given
     * cloud cover values for every grid point requested.
     */
    private void stubBatchReturnsCloudData(int[] cloudValues) {
        when(openMeteoClient.fetchCloudOnlyBatch(any())).thenAnswer(inv -> {
            List<double[]> coords = inv.getArgument(0);
            List<OpenMeteoForecastResponse> responses = new ArrayList<>();
            for (int i = 0; i < coords.size(); i++) {
                responses.add(buildCloudResponse(cloudValues));
            }
            return responses;
        });
    }

    private OpenMeteoForecastResponse buildCloudResponse(int[] cloudValues) {
        OpenMeteoForecastResponse resp = new OpenMeteoForecastResponse();
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();

        List<String> times = buildHourlyTimeStrings(cloudValues.length);
        List<Integer> lowCloud = new ArrayList<>();
        for (int v : cloudValues) {
            lowCloud.add(v);
        }

        hourly.setTime(times);
        hourly.setCloudCoverLow(lowCloud);
        hourly.setCloudCoverMid(lowCloud);
        hourly.setCloudCoverHigh(lowCloud);
        resp.setHourly(hourly);
        return resp;
    }

    @Test
    @DisplayName("triage() with empty candidate list returns empty result without calling API")
    void triage_emptyList_returnsEmptyResult() {
        WeatherTriageService.TriageResult result = service.triage(List.of());

        assertThat(result.viable()).isEmpty();
        assertThat(result.rejected()).isEmpty();
        assertThat(result.cloudByLocation()).isEmpty();
        org.mockito.Mockito.verify(openMeteoClient, org.mockito.Mockito.never())
                .fetchCloudOnlyBatch(any());
    }

    @Test
    @DisplayName("triage() calls fetchCloudOnlyBatch exactly once, not individual fetchCloudOnly")
    void triage_usesBatchNotIndividual() {
        stubBatchReturnsCloudData(new int[]{20, 20, 20, 20, 20, 20, 20});
        LocationEntity loc = buildLocation(10L, "TestLoc", 55.0, -2.0, 2);

        service.triage(List.of(loc));

        org.mockito.Mockito.verify(openMeteoClient).fetchCloudOnlyBatch(any());
    }

    private List<String> buildHourlyTimeStrings(int count) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
        ZonedDateTime base = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS);
        List<String> times = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            times.add(base.plusHours(i).format(fmt));
        }
        return times;
    }
}
