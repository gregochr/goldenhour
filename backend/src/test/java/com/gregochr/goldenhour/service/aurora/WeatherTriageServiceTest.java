package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.service.OpenMeteoClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
    // Empty input
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("triage returns empty result when candidates list is empty")
    void triage_emptyCandidates_emptyResult() {
        WeatherTriageService.TriageResult result = service.triage(List.of());

        assertThat(result.viable()).isEmpty();
        assertThat(result.rejected()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Fallback paths (HTTP error → default cloud = 75 = overcast → rejected)
    // -------------------------------------------------------------------------

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
    @DisplayName("triage computes average cloud and stores it in cloudByLocation")
    void triage_storesAverageCloud() {
        int[] cloudValues = {20, 20, 20, 20, 20, 20, 20};
        stubBatchReturnsCloudData(cloudValues);

        LocationEntity loc = buildLocation(5L, "Kielder", 55.2, -2.6, 2);

        WeatherTriageService.TriageResult result = service.triage(List.of(loc));

        assertThat(result.cloudByLocation()).containsKey(loc);
        assertThat(result.cloudByLocation().get(loc)).isLessThan(75);
    }

    // -------------------------------------------------------------------------
    // Multiple locations
    // -------------------------------------------------------------------------

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
