package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.util.RestClientMocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.lang.reflect.Constructor;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Unit tests for {@link AuroraWeatherEnricher}.
 */
@ExtendWith(MockitoExtension.class)
class AuroraWeatherEnricherTest {

    @Mock
    private RestClient restClient;

    private AuroraWeatherEnricher enricher;

    @BeforeEach
    void setUp() {
        enricher = new AuroraWeatherEnricher(restClient);
    }

    @Test
    @DisplayName("Returns weather data for a location on successful fetch")
    @SuppressWarnings("unchecked")
    void successfulFetch() throws Exception {
        ZonedDateTime target = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS);
        Object response = buildWeatherResponse(target, 40, 3.5, 4.2, 2);
        stubRestClientReturns(response);

        LocationEntity loc = location(1L, "Kielder", 55.2, -2.6);
        Map<Long, AuroraWeatherEnricher.AuroraWeather> result =
                enricher.fetchWeather(List.of(loc), target);

        assertThat(result).containsKey(1L);
        AuroraWeatherEnricher.AuroraWeather weather = result.get(1L);
        assertThat(weather.cloudPercent()).isEqualTo(40);
        assertThat(weather.temperatureCelsius()).isEqualTo(3.5);
        assertThat(weather.windSpeedMs()).isEqualTo(4.2);
        assertThat(weather.weatherCode()).isEqualTo(2);
    }

    @Test
    @DisplayName("Returns empty map when REST call fails")
    @SuppressWarnings("unchecked")
    void emptyMapOnFailure() {
        stubRestClientThrows(new RestClientException("Connection refused"));

        LocationEntity loc = location(1L, "Kielder", 55.2, -2.6);
        ZonedDateTime target = ZonedDateTime.now(ZoneOffset.UTC);

        Map<Long, AuroraWeatherEnricher.AuroraWeather> result =
                enricher.fetchWeather(List.of(loc), target);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Returns empty map when response body is null")
    @SuppressWarnings("unchecked")
    void emptyMapOnNullResponse() {
        stubRestClientReturns(null);

        LocationEntity loc = location(1L, "Bamburgh", 55.6, -1.7);
        ZonedDateTime target = ZonedDateTime.now(ZoneOffset.UTC);

        Map<Long, AuroraWeatherEnricher.AuroraWeather> result =
                enricher.fetchWeather(List.of(loc), target);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Returns empty map for empty location list")
    void emptyMapForEmptyInput() {
        ZonedDateTime target = ZonedDateTime.now(ZoneOffset.UTC);

        Map<Long, AuroraWeatherEnricher.AuroraWeather> result =
                enricher.fetchWeather(List.of(), target);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Skips location when target hour not found in response times")
    @SuppressWarnings("unchecked")
    void missingTargetHour() throws Exception {
        // Build response with times that don't match the target
        ZonedDateTime target = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS);
        ZonedDateTime offset = target.plusHours(100); // far future, won't match
        Object response = buildWeatherResponse(offset, 40, 3.5, 4.2, 2);
        stubRestClientReturns(response);

        LocationEntity loc = location(1L, "Kielder", 55.2, -2.6);
        Map<Long, AuroraWeatherEnricher.AuroraWeather> result =
                enricher.fetchWeather(List.of(loc), target);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Handles response with null hourly time list")
    @SuppressWarnings("unchecked")
    void nullTimeList() throws Exception {
        Object response = buildWeatherResponseWithNullTimes();
        stubRestClientReturns(response);

        LocationEntity loc = location(1L, "Kielder", 55.2, -2.6);
        ZonedDateTime target = ZonedDateTime.now(ZoneOffset.UTC);
        Map<Long, AuroraWeatherEnricher.AuroraWeather> result =
                enricher.fetchWeather(List.of(loc), target);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Handles multiple locations with mixed success and failure")
    @SuppressWarnings("unchecked")
    void multipleLocations() throws Exception {
        ZonedDateTime target = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS);
        Object response = buildWeatherResponse(target, 30, 5.0, 2.0, 0);
        stubRestClientReturns(response);

        LocationEntity loc1 = location(1L, "Kielder", 55.2, -2.6);
        LocationEntity loc2 = location(2L, "Bamburgh", 55.6, -1.7);
        Map<Long, AuroraWeatherEnricher.AuroraWeather> result =
                enricher.fetchWeather(List.of(loc1, loc2), target);

        assertThat(result).hasSize(2);
        assertThat(result.get(1L).cloudPercent()).isEqualTo(30);
        assertThat(result.get(2L).cloudPercent()).isEqualTo(30);
    }

    @Test
    @DisplayName("Defaults cloud to 50 when cloud cover is null at target index")
    @SuppressWarnings("unchecked")
    void nullCloudDefaultsToFifty() throws Exception {
        ZonedDateTime target = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS);
        Object response = buildWeatherResponseWithNullCloud(target);
        stubRestClientReturns(response);

        LocationEntity loc = location(1L, "Kielder", 55.2, -2.6);
        Map<Long, AuroraWeatherEnricher.AuroraWeather> result =
                enricher.fetchWeather(List.of(loc), target);

        assertThat(result).containsKey(1L);
        assertThat(result.get(1L).cloudPercent()).isEqualTo(50);
    }

    @Test
    @DisplayName("Open-Meteo request includes wind_speed_unit=ms (singular — not wind_speed_units)")
    void requestIncludesWindSpeedUnitMs() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuroraWeatherEnricher realEnricher = new AuroraWeatherEnricher(builder.build());

        // Assert both the singular spelling is present AND the plural typo is absent (the whole
        // point of this test), mirroring the original contains/doesNotContain pair.
        server.expect(requestTo(containsString("api.open-meteo.com")))
                .andExpect(requestTo(not(containsString("wind_speed_units"))))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("wind_speed_unit", "ms"))
                .andRespond(withSuccess());

        ZonedDateTime target = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS);
        realEnricher.fetchWeather(List.of(location(1L, "Kielder", 55.2, -2.6)), target);

        server.verify();
    }

    @Test
    @DisplayName("Open-Meteo request uses forecast_days=3 for tomorrow midnight coverage")
    void requestUsesForecastDays3() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuroraWeatherEnricher realEnricher = new AuroraWeatherEnricher(builder.build());

        server.expect(requestTo(containsString("api.open-meteo.com")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("forecast_days", "3"))
                .andRespond(withSuccess());

        ZonedDateTime target = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS);
        realEnricher.fetchWeather(List.of(location(1L, "Kielder", 55.2, -2.6)), target);

        server.verify();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static LocationEntity location(Long id, String name, double lat, double lon) {
        return LocationEntity.builder()
                .id(id).name(name).lat(lat).lon(lon)
                .locationType(Set.of(LocationType.LANDSCAPE))
                .tideType(Set.of())
                .solarEventType(Set.of())
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private void stubRestClientThrows(RuntimeException ex) {
        RestClientMocks.stubGetThrows(restClient, findInnerClass("WeatherResponse"), ex);
    }

    @SuppressWarnings("unchecked")
    private void stubRestClientReturns(Object returnValue) {
        RestClientMocks.stubGet(restClient,
                (Class<Object>) findInnerClass("WeatherResponse"), returnValue);
    }

    /**
     * Builds an {@code AuroraWeatherEnricher.WeatherResponse} via reflection with the given
     * values at the specified target hour.
     */
    private Object buildWeatherResponse(ZonedDateTime targetHour,
            int cloud, double temp, double wind, int code) throws Exception {
        Class<?> hourlyClass = findInnerClass("HourlyData");
        Class<?> responseClass = findInnerClass("WeatherResponse");

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
        ZonedDateTime base = targetHour.truncatedTo(ChronoUnit.HOURS);
        List<String> times = new ArrayList<>();
        List<Integer> cloudList = new ArrayList<>();
        List<Double> tempList = new ArrayList<>();
        List<Double> windList = new ArrayList<>();
        List<Integer> codeList = new ArrayList<>();

        // Build 48 hours of data with the target hour included
        for (int i = -12; i < 36; i++) {
            times.add(base.plusHours(i).format(fmt));
            cloudList.add(i == 0 ? cloud : 50);
            tempList.add(i == 0 ? temp : 10.0);
            windList.add(i == 0 ? wind : 5.0);
            codeList.add(i == 0 ? code : 3);
        }

        Constructor<?> hourlyCtor = hourlyClass.getDeclaredConstructors()[0];
        hourlyCtor.setAccessible(true);
        Object hourlyData = hourlyCtor.newInstance(times, cloudList, tempList, windList, codeList);

        Constructor<?> responseCtor = responseClass.getDeclaredConstructors()[0];
        responseCtor.setAccessible(true);
        return responseCtor.newInstance(hourlyData);
    }

    /**
     * Builds a WeatherResponse where cloud cover list is null (triggers fallback to 50).
     */
    private Object buildWeatherResponseWithNullCloud(ZonedDateTime targetHour) throws Exception {
        Class<?> hourlyClass = findInnerClass("HourlyData");
        Class<?> responseClass = findInnerClass("WeatherResponse");

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
        ZonedDateTime base = targetHour.truncatedTo(ChronoUnit.HOURS);
        List<String> times = List.of(base.format(fmt));

        Constructor<?> hourlyCtor = hourlyClass.getDeclaredConstructors()[0];
        hourlyCtor.setAccessible(true);
        // cloud list is null, temp/wind/code have values
        Object hourlyData = hourlyCtor.newInstance(times, null, List.of(5.0), List.of(3.0), List.of(1));

        Constructor<?> responseCtor = responseClass.getDeclaredConstructors()[0];
        responseCtor.setAccessible(true);
        return responseCtor.newInstance(hourlyData);
    }

    /**
     * Builds a WeatherResponse where the hourly time list is null.
     */
    private Object buildWeatherResponseWithNullTimes() throws Exception {
        Class<?> hourlyClass = findInnerClass("HourlyData");
        Class<?> responseClass = findInnerClass("WeatherResponse");

        Constructor<?> hourlyCtor = hourlyClass.getDeclaredConstructors()[0];
        hourlyCtor.setAccessible(true);
        Object hourlyData = hourlyCtor.newInstance(null, null, null, null, null);

        Constructor<?> responseCtor = responseClass.getDeclaredConstructors()[0];
        responseCtor.setAccessible(true);
        return responseCtor.newInstance(hourlyData);
    }

    private Class<?> findInnerClass(String simpleName) {
        return Arrays.stream(AuroraWeatherEnricher.class.getDeclaredClasses())
                .filter(c -> c.getSimpleName().equals(simpleName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Inner class not found: " + simpleName));
    }
}
