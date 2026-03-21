package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.entity.LocationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.lang.reflect.Constructor;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WeatherTriageService} cloud-based location triage.
 */
@ExtendWith(MockitoExtension.class)
class WeatherTriageServiceTest {

    @Mock
    private RestClient restClient;

    @SuppressWarnings("rawtypes")
    @Mock
    private RestClient.RequestHeadersUriSpec uriSpec;

    @SuppressWarnings("rawtypes")
    @Mock
    private RestClient.RequestHeadersSpec headersSpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    private WeatherTriageService service;

    @BeforeEach
    void setUp() {
        service = new WeatherTriageService(restClient);
    }

    // -------------------------------------------------------------------------
    // Empty input
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("triage returns empty result when candidates list is empty")
    void triage_emptyCandidates_returnsEmpty() {
        WeatherTriageService.TriageResult result = service.triage(List.of());

        assertThat(result.viable()).isEmpty();
        assertThat(result.rejected()).isEmpty();
        assertThat(result.cloudByLocation()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Fallback paths (HTTP error → default cloud = 75 = overcast → rejected)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("triage rejects location when HTTP call throws RestClientException")
    @SuppressWarnings("unchecked")
    void triage_httpException_locationRejected() {
        stubRestClientThrows(new RestClientException("timeout"));

        LocationEntity loc = buildLocation(1L, "Northumberland", 55.2, -2.0, 2);

        WeatherTriageService.TriageResult result = service.triage(List.of(loc));

        assertThat(result.viable()).isEmpty();
        assertThat(result.rejected()).containsExactly(loc);
        // Default cloud (75) is returned on failure
        assertThat(result.cloudByLocation()).containsKey(loc);
        assertThat(result.cloudByLocation().get(loc)).isEqualTo(75);
    }

    @Test
    @DisplayName("triage rejects location when HTTP response is null")
    @SuppressWarnings("unchecked")
    void triage_nullResponse_locationRejected() {
        stubRestClientReturns(null);

        LocationEntity loc = buildLocation(2L, "Galloway", 54.9, -4.5, 3);

        WeatherTriageService.TriageResult result = service.triage(List.of(loc));

        assertThat(result.viable()).isEmpty();
        assertThat(result.rejected()).containsExactly(loc);
    }

    // -------------------------------------------------------------------------
    // Viable vs rejected discrimination (reflection-based cloud data)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("triage passes location when at least one hour has cloud < 75%")
    @SuppressWarnings("unchecked")
    void triage_anyHourClear_locationIsViable() throws Exception {
        // Mix of overcast and clear hours — location should pass
        int[] cloudValues = {80, 90, 20, 85, 80, 75, 80}; // hour index 2 is clear
        stubRestClientReturnsCloudData(cloudValues);

        LocationEntity loc = buildLocation(3L, "Cairngorms", 57.1, -3.8, 1);

        WeatherTriageService.TriageResult result = service.triage(List.of(loc));

        assertThat(result.viable()).containsExactly(loc);
        assertThat(result.rejected()).isEmpty();
    }

    @Test
    @DisplayName("triage rejects location when all hours have cloud >= 75%")
    @SuppressWarnings("unchecked")
    void triage_allHoursOvercast_locationRejected() throws Exception {
        int[] cloudValues = {80, 90, 75, 85, 80, 75, 80}; // none < 75
        stubRestClientReturnsCloudData(cloudValues);

        LocationEntity loc = buildLocation(4L, "Skye", 57.5, -6.2, 2);

        WeatherTriageService.TriageResult result = service.triage(List.of(loc));

        assertThat(result.viable()).isEmpty();
        assertThat(result.rejected()).containsExactly(loc);
    }

    @Test
    @DisplayName("triage computes average cloud and stores it in cloudByLocation")
    @SuppressWarnings("unchecked")
    void triage_storesAverageCloud() throws Exception {
        // 3 transect points each get these cloud values → avg stored
        int[] cloudValues = {40, 40, 40, 40, 40, 40, 40}; // avg = 40
        stubRestClientReturnsCloudData(cloudValues);

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
    @SuppressWarnings("unchecked")
    void triage_twoLocations_bothRejectedWhenOvercast() throws Exception {
        Object overcastResponse = buildCloudResponse(new int[]{80, 80, 80, 80, 80, 80, 80});
        stubRestClientReturns(overcastResponse);

        LocationEntity locA = buildLocation(6L, "A", 55.0, -2.0, 2);
        LocationEntity locB = buildLocation(7L, "B", 57.0, -4.0, 2);

        WeatherTriageService.TriageResult result = service.triage(List.of(locA, locB));

        assertThat(result.viable()).isEmpty();
        assertThat(result.rejected()).containsExactlyInAnyOrder(locA, locB);
    }

    @Test
    @DisplayName("triage handles two locations independently when both clear")
    @SuppressWarnings("unchecked")
    void triage_twoLocations_bothViableWhenClear() throws Exception {
        Object clearResponse = buildCloudResponse(new int[]{20, 20, 20, 20, 20, 20, 20});
        stubRestClientReturns(clearResponse);

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

    @SuppressWarnings("unchecked")
    private void stubRestClientThrows(RuntimeException ex) {
        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(any(Function.class))).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(any(Class.class))).thenThrow(ex);
    }

    @SuppressWarnings("unchecked")
    private void stubRestClientReturns(Object returnValue) {
        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(any(Function.class))).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(any(Class.class))).thenReturn(returnValue);
    }

    @SuppressWarnings("unchecked")
    private void stubRestClientReturnsCloudData(int[] cloudValues) throws Exception {
        Object cloudResponse = buildCloudResponse(cloudValues);
        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(any(Function.class))).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(any(Class.class))).thenReturn(cloudResponse);
    }

    /**
     * Builds a WeatherTriageService$CloudResponse instance via reflection,
     * with time strings starting at the current UTC hour and the given cloud values.
     */
    private Object buildCloudResponse(int[] cloudValues) throws Exception {
        Class<?> hourlyClass = findInnerClass("HourlyCloudData");
        Class<?> responseClass = findInnerClass("CloudResponse");

        List<String> times = buildHourlyTimeStrings(cloudValues.length);
        List<Integer> cloud = new ArrayList<>();
        for (int v : cloudValues) {
            cloud.add(v);
        }

        Constructor<?> hourlyCtor = hourlyClass.getDeclaredConstructors()[0];
        hourlyCtor.setAccessible(true);
        Object hourlyData = hourlyCtor.newInstance(times, cloud);

        Constructor<?> responseCtor = responseClass.getDeclaredConstructors()[0];
        responseCtor.setAccessible(true);
        return responseCtor.newInstance(hourlyData);
    }

    private Class<?> findInnerClass(String simpleName) {
        return Arrays.stream(WeatherTriageService.class.getDeclaredClasses())
                .filter(c -> c.getSimpleName().equals(simpleName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Inner class not found: " + simpleName));
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
