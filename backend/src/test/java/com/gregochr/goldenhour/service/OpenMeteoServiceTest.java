package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.ForecastRequest;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OpenMeteoService}.
 *
 * <p>Tests cover the data extraction logic ({@code extractAtmosphericData}) with static
 * response objects, and the integration with {@link OpenMeteoClient} for API calls.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpenMeteoServiceTest {

    @Mock
    private OpenMeteoClient openMeteoClient;

    @Mock
    private JobRunService jobRunService;

    private OpenMeteoService openMeteoService;

    @BeforeEach
    void setUp() {
        openMeteoService = new OpenMeteoService(openMeteoClient, jobRunService);
    }

    @Test
    @DisplayName("extractAtmosphericData() selects the forecast slot nearest to the solar event")
    void extractAtmosphericData_selectsNearestTimestamp() {
        LocalDateTime solarEvent = LocalDateTime.of(2026, 6, 21, 20, 47, 0);

        // Three slots: 1h before, 30s before (nearest), 1h after
        OpenMeteoForecastResponse forecast = buildForecastResponse(
                List.of("2026-06-21T19:47", "2026-06-21T20:46", "2026-06-21T21:47"),
                List.of(10, 20, 15),   // cloudCoverLow
                List.of(50, 60, 40),   // cloudCoverMid
                List.of(30, 40, 20),   // cloudCoverHigh
                List.of(25000.0, 22000.0, 28000.0),  // visibility
                List.of(4.0, 3.5, 5.0),              // windSpeed10m
                List.of(225, 245, 200),              // windDirection10m
                List.of(0.0, 0.1, 0.0),              // precipitation
                List.of(1, 3, 2),                    // weatherCode
                List.of(65, 62, 70),                 // humidity
                List.of(1100.0, 1200.0, 1000.0),     // boundaryLayerHeight
                List.of(100.0, 180.0, 120.0));       // shortwaveRadiation

        OpenMeteoAirQualityResponse airQuality = buildAirQualityResponse(
                List.of("2026-06-21T19:47", "2026-06-21T20:46", "2026-06-21T21:47"),
                List.of(5.0, 8.5, 6.0),
                List.of(1.0, 2.1, 1.5),
                List.of(0.08, 0.12, 0.09));

        AtmosphericData result = openMeteoService.extractAtmosphericData(
                forecast, airQuality, "Durham UK", solarEvent, TargetType.SUNSET);

        // Should select index 1 (nearest slot: 30s before event)
        assertThat(result.locationName()).isEqualTo("Durham UK");
        assertThat(result.targetType()).isEqualTo(TargetType.SUNSET);
        assertThat(result.solarEventTime()).isEqualTo(solarEvent);
        assertThat(result.lowCloudPercent()).isEqualTo(20);
        assertThat(result.midCloudPercent()).isEqualTo(60);
        assertThat(result.highCloudPercent()).isEqualTo(40);
        assertThat(result.visibilityMetres()).isEqualTo(22000);
        assertThat(result.windSpeedMs()).isEqualByComparingTo("3.50");
        assertThat(result.windDirectionDegrees()).isEqualTo(245);
        assertThat(result.precipitationMm()).isEqualByComparingTo("0.10");
        assertThat(result.humidityPercent()).isEqualTo(62);
        assertThat(result.weatherCode()).isEqualTo(3);
        assertThat(result.boundaryLayerHeightMetres()).isEqualTo(1200);
        assertThat(result.pm25()).isEqualByComparingTo("8.50");
        assertThat(result.dustUgm3()).isEqualByComparingTo("2.10");
        assertThat(result.aerosolOpticalDepth()).isEqualByComparingTo("0.120");
    }

    @Test
    @DisplayName("extractAtmosphericData() selects exact timestamp match")
    void extractAtmosphericData_exactTimestampMatch_selectsCorrectSlot() {
        LocalDateTime solarEvent = LocalDateTime.of(2026, 2, 20, 7, 30, 0);

        OpenMeteoForecastResponse forecast = buildForecastResponse(
                List.of("2026-02-20T06:30", "2026-02-20T07:30", "2026-02-20T08:30"),
                List.of(5, 25, 10), List.of(10, 55, 20), List.of(15, 35, 25),
                List.of(20000.0, 18000.0, 22000.0),
                List.of(3.0, 4.0, 2.5),
                List.of(180, 225, 200),
                List.of(0.0, 0.5, 0.0),
                List.of(1, 3, 2),
                List.of(70, 65, 68),
                List.of(900.0, 1100.0, 1000.0),
                List.of(80.0, 120.0, 100.0));

        OpenMeteoAirQualityResponse airQuality = buildAirQualityResponse(
                List.of("2026-02-20T06:30", "2026-02-20T07:30", "2026-02-20T08:30"),
                List.of(3.0, 6.0, 4.0), List.of(0.5, 1.0, 0.7), List.of(0.06, 0.10, 0.08));

        AtmosphericData result = openMeteoService.extractAtmosphericData(
                forecast, airQuality, "Durham UK", solarEvent, TargetType.SUNRISE);

        assertThat(result.lowCloudPercent()).isEqualTo(25);
        assertThat(result.humidityPercent()).isEqualTo(65);
    }

    @Test
    @DisplayName("extractAtmosphericData() defaults null air quality values to zero")
    void extractAtmosphericData_nullAirQualityValues_defaultToZero() {
        LocalDateTime solarEvent = LocalDateTime.of(2026, 6, 21, 20, 47, 0);

        OpenMeteoForecastResponse forecast = buildForecastResponse(
                List.of("2026-06-21T20:47"),
                List.of(15), List.of(40), List.of(60),
                List.of(22000.0), List.of(5.0), List.of(270),
                List.of(0.0), List.of(3), List.of(55),
                List.of(1300.0), List.of(200.0));

        OpenMeteoAirQualityResponse airQuality = buildAirQualityResponse(
                List.of("2026-06-21T20:47"),
                null, null, null);

        AtmosphericData result = openMeteoService.extractAtmosphericData(
                forecast, airQuality, "Durham UK", solarEvent, TargetType.SUNSET);

        assertThat(result.pm25()).isEqualByComparingTo("0.00");
        assertThat(result.dustUgm3()).isEqualByComparingTo("0.00");
        assertThat(result.aerosolOpticalDepth()).isEqualByComparingTo("0.000");
    }

    @Test
    @DisplayName("extractAtmosphericData() passes wind speed and direction directly")
    void extractAtmosphericData_windSpeedAndDirectionPassedThrough() {
        LocalDateTime solarEvent = LocalDateTime.of(2026, 6, 21, 5, 10, 0);

        OpenMeteoForecastResponse forecast = buildForecastResponse(
                List.of("2026-06-21T05:10"),
                List.of(0), List.of(0), List.of(0),
                List.of(30000.0), List.of(7.30), List.of(315),
                List.of(0.0), List.of(1), List.of(45),
                List.of(800.0), List.of(300.0));

        OpenMeteoAirQualityResponse airQuality = buildAirQualityResponse(
                List.of("2026-06-21T05:10"),
                List.of(3.0), List.of(0.5), List.of(0.05));

        AtmosphericData result = openMeteoService.extractAtmosphericData(
                forecast, airQuality, "Durham UK", solarEvent, TargetType.SUNRISE);

        assertThat(result.windSpeedMs()).isEqualByComparingTo("7.30");
        assertThat(result.windDirectionDegrees()).isEqualTo(315);
    }

    @Test
    @DisplayName("getAtmosphericData() fetches both APIs and returns merged data")
    void getAtmosphericData_fetchesBothApisAndReturnsMergedData() {
        LocalDateTime solarEvent = LocalDateTime.of(2026, 6, 21, 20, 47, 0);

        OpenMeteoForecastResponse forecast = buildForecastResponse(
                List.of("2026-06-21T20:47"),
                List.of(10), List.of(50), List.of(30),
                List.of(22000.0), List.of(4.0), List.of(225),
                List.of(0.0), List.of(1), List.of(62),
                List.of(1200.0), List.of(180.0));

        OpenMeteoAirQualityResponse airQuality = buildAirQualityResponse(
                List.of("2026-06-21T20:47"),
                List.of(8.5), List.of(2.1), List.of(0.12));

        when(openMeteoClient.fetchForecast(anyDouble(), anyDouble())).thenReturn(forecast);
        when(openMeteoClient.fetchAirQuality(anyDouble(), anyDouble())).thenReturn(airQuality);

        ForecastRequest request = new ForecastRequest(
                54.7753, -1.5849, "Durham UK", LocalDate.of(2026, 6, 21), TargetType.SUNSET);

        AtmosphericData result = openMeteoService.getAtmosphericData(request, solarEvent);

        assertThat(result).isNotNull();
        assertThat(result.locationName()).isEqualTo("Durham UK");
        assertThat(result.targetType()).isEqualTo(TargetType.SUNSET);
        assertThat(result.lowCloudPercent()).isEqualTo(10);
        assertThat(result.pm25()).isEqualByComparingTo("8.50");
    }

    @Test
    @DisplayName("getAtmosphericData() propagates non-retryable errors immediately")
    void getAtmosphericData_whenNonRetryableError_propagatesImmediately() {
        when(openMeteoClient.fetchForecast(anyDouble(), anyDouble()))
                .thenThrow(new RuntimeException("network failure"));

        ForecastRequest request = new ForecastRequest(
                54.7753, -1.5849, "Durham UK", LocalDate.of(2026, 6, 21), TargetType.SUNSET);

        assertThatThrownBy(() -> openMeteoService.getAtmosphericData(request,
                LocalDateTime.of(2026, 6, 21, 20, 47, 0)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("network failure");
    }

    @Test
    @DisplayName("getAtmosphericData() propagates error when air quality API fails")
    void getAtmosphericData_whenAirQualityApiFails_propagatesError() {
        OpenMeteoForecastResponse forecast = buildForecastResponse(
                List.of("2026-06-21T20:47"),
                List.of(10), List.of(50), List.of(30),
                List.of(22000.0), List.of(4.0), List.of(225),
                List.of(0.0), List.of(1), List.of(62),
                List.of(1200.0), List.of(180.0));

        when(openMeteoClient.fetchForecast(anyDouble(), anyDouble())).thenReturn(forecast);
        when(openMeteoClient.fetchAirQuality(anyDouble(), anyDouble()))
                .thenThrow(new RuntimeException("air quality API unavailable"));

        ForecastRequest request = new ForecastRequest(
                54.7753, -1.5849, "Durham UK", LocalDate.of(2026, 6, 21), TargetType.SUNSET);

        assertThatThrownBy(() -> openMeteoService.getAtmosphericData(request,
                LocalDateTime.of(2026, 6, 21, 20, 47, 0)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("air quality API unavailable");
    }

    // -------------------------------------------------------------------------
    // extractAtmosphericData — edge cases
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("extractAtmosphericData() returns last slot when event is after all forecast times")
    void extractAtmosphericData_eventAfterAllSlots_selectsLastSlot() {
        LocalDateTime solarEvent = LocalDateTime.of(2026, 6, 21, 23, 59, 0);

        OpenMeteoForecastResponse forecast = buildForecastResponse(
                List.of("2026-06-21T19:00", "2026-06-21T20:00", "2026-06-21T21:00"),
                List.of(5, 10, 99), List.of(5, 10, 99), List.of(5, 10, 99),
                List.of(20000.0, 21000.0, 22000.0),
                List.of(3.0, 4.0, 5.0), List.of(180, 200, 220),
                List.of(0.0, 0.0, 0.0), List.of(1, 1, 1), List.of(60, 65, 70),
                List.of(1000.0, 1100.0, 1200.0), List.of(100.0, 150.0, 200.0));

        OpenMeteoAirQualityResponse airQuality = buildAirQualityResponse(
                List.of("2026-06-21T19:00", "2026-06-21T20:00", "2026-06-21T21:00"),
                List.of(3.0, 4.0, 5.0), List.of(0.5, 0.6, 0.7), List.of(0.05, 0.06, 0.07));

        AtmosphericData result = openMeteoService.extractAtmosphericData(
                forecast, airQuality, "Durham UK", solarEvent, TargetType.SUNSET);

        assertThat(result.lowCloudPercent()).isEqualTo(99);
    }

    @Test
    @DisplayName("extractAtmosphericData() returns index 0 when there is only one slot")
    void extractAtmosphericData_singleSlot_returnsIndexZero() {
        LocalDateTime solarEvent = LocalDateTime.of(2026, 6, 21, 20, 47, 0);

        OpenMeteoForecastResponse forecast = buildForecastResponse(
                List.of("2026-06-21T06:00"),
                List.of(42), List.of(42), List.of(42),
                List.of(15000.0), List.of(2.0), List.of(90),
                List.of(0.0), List.of(1), List.of(50),
                List.of(800.0), List.of(50.0));

        OpenMeteoAirQualityResponse airQuality = buildAirQualityResponse(
                List.of("2026-06-21T06:00"),
                List.of(1.0), List.of(0.1), List.of(0.01));

        AtmosphericData result = openMeteoService.extractAtmosphericData(
                forecast, airQuality, "Durham UK", solarEvent, TargetType.SUNSET);

        assertThat(result.lowCloudPercent()).isEqualTo(42);
    }

    @Test
    @DisplayName("extractAtmosphericData() defaults aerosol values to zero when air quality list is shorter")
    void extractAtmosphericData_airQualityListShorterThanForecast_defaultsToZero() {
        LocalDateTime solarEvent = LocalDateTime.of(2026, 6, 21, 21, 00, 0);

        OpenMeteoForecastResponse forecast = buildForecastResponse(
                List.of("2026-06-21T19:00", "2026-06-21T20:00", "2026-06-21T21:00"),
                List.of(10, 20, 30), List.of(10, 20, 30), List.of(10, 20, 30),
                List.of(22000.0, 22000.0, 22000.0),
                List.of(4.0, 4.0, 4.0), List.of(225, 225, 225),
                List.of(0.0, 0.0, 0.0), List.of(1, 1, 1), List.of(60, 60, 60),
                List.of(1200.0, 1200.0, 1200.0), List.of(180.0, 180.0, 180.0));

        OpenMeteoAirQualityResponse airQuality = buildAirQualityResponse(
                List.of("2026-06-21T19:00"),
                List.of(9.9), List.of(1.1), List.of(0.9));

        AtmosphericData result = openMeteoService.extractAtmosphericData(
                forecast, airQuality, "Durham UK", solarEvent, TargetType.SUNSET);

        assertThat(result.pm25()).isEqualByComparingTo("0.00");
        assertThat(result.dustUgm3()).isEqualByComparingTo("0.00");
        assertThat(result.aerosolOpticalDepth()).isEqualByComparingTo("0.000");
    }

    @Test
    @DisplayName("extractAtmosphericData() applies HALF_UP rounding to wind speed")
    void extractAtmosphericData_windSpeedRoundedHalfUp() {
        LocalDateTime solarEvent = LocalDateTime.of(2026, 6, 21, 20, 47, 0);

        OpenMeteoForecastResponse forecast = buildForecastResponse(
                List.of("2026-06-21T20:47"),
                List.of(10), List.of(20), List.of(30),
                List.of(20000.0), List.of(3.555), List.of(180), // 3.555 → 3.56 HALF_UP
                List.of(0.0), List.of(1), List.of(60),
                List.of(1000.0), List.of(100.0));

        OpenMeteoAirQualityResponse airQuality = buildAirQualityResponse(
                List.of("2026-06-21T20:47"),
                List.of(1.0), List.of(0.5), List.of(0.05));

        AtmosphericData result = openMeteoService.extractAtmosphericData(
                forecast, airQuality, "Durham UK", solarEvent, TargetType.SUNSET);

        assertThat(result.windSpeedMs()).isEqualByComparingTo("3.56");
    }

    @Test
    @DisplayName("extractAtmosphericData() always sets tide fields to null")
    void extractAtmosphericData_tideFieldsAlwaysNull() {
        LocalDateTime solarEvent = LocalDateTime.of(2026, 6, 21, 20, 47, 0);

        OpenMeteoForecastResponse forecast = buildForecastResponse(
                List.of("2026-06-21T20:47"),
                List.of(10), List.of(20), List.of(30),
                List.of(20000.0), List.of(4.0), List.of(225),
                List.of(0.0), List.of(1), List.of(60),
                List.of(1000.0), List.of(100.0));

        OpenMeteoAirQualityResponse airQuality = buildAirQualityResponse(
                List.of("2026-06-21T20:47"),
                List.of(1.0), List.of(0.5), List.of(0.05));

        AtmosphericData result = openMeteoService.extractAtmosphericData(
                forecast, airQuality, "Durham UK", solarEvent, TargetType.SUNSET);

        assertThat(result.tideState()).isNull();
        assertThat(result.nextHighTideTime()).isNull();
        assertThat(result.nextHighTideHeightMetres()).isNull();
        assertThat(result.nextLowTideTime()).isNull();
        assertThat(result.nextLowTideHeightMetres()).isNull();
        assertThat(result.tideAligned()).isNull();
    }

    private OpenMeteoForecastResponse buildForecastResponse(
            List<String> time,
            List<Integer> cloudLow, List<Integer> cloudMid, List<Integer> cloudHigh,
            List<Double> visibility, List<Double> windSpeed, List<Integer> windDir,
            List<Double> precip, List<Integer> weatherCode, List<Integer> humidity,
            List<Double> boundaryLayer, List<Double> shortwave) {
        return buildForecastResponse(time, cloudLow, cloudMid, cloudHigh, visibility, windSpeed,
                windDir, precip, weatherCode, humidity, boundaryLayer, shortwave, null, null, null);
    }

    private OpenMeteoForecastResponse buildForecastResponse(
            List<String> time,
            List<Integer> cloudLow, List<Integer> cloudMid, List<Integer> cloudHigh,
            List<Double> visibility, List<Double> windSpeed, List<Integer> windDir,
            List<Double> precip, List<Integer> weatherCode, List<Integer> humidity,
            List<Double> boundaryLayer, List<Double> shortwave,
            List<Double> temperature, List<Double> apparentTemperature,
            List<Integer> precipProbability) {

        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
        hourly.setTime(time);
        hourly.setCloudCoverLow(cloudLow);
        hourly.setCloudCoverMid(cloudMid);
        hourly.setCloudCoverHigh(cloudHigh);
        hourly.setVisibility(visibility);
        hourly.setWindSpeed10m(windSpeed);
        hourly.setWindDirection10m(windDir);
        hourly.setPrecipitation(precip);
        hourly.setWeatherCode(weatherCode);
        hourly.setRelativeHumidity2m(humidity);
        hourly.setBoundaryLayerHeight(boundaryLayer);
        hourly.setShortwaveRadiation(shortwave);
        hourly.setTemperature2m(temperature);
        hourly.setApparentTemperature(apparentTemperature);
        hourly.setPrecipitationProbability(precipProbability);
        response.setHourly(hourly);
        return response;
    }

    @Test
    @DisplayName("extractAtmosphericData() extracts temperature, apparent temperature and precipitation probability")
    void extractAtmosphericData_extractsComfortFields() {
        LocalDateTime solarEvent = LocalDateTime.of(2026, 6, 21, 20, 47, 0);

        OpenMeteoForecastResponse forecast = buildForecastResponse(
                List.of("2026-06-21T20:47"),
                List.of(10), List.of(20), List.of(30),
                List.of(20000.0), List.of(4.0), List.of(225),
                List.of(0.0), List.of(1), List.of(60),
                List.of(1000.0), List.of(100.0),
                List.of(14.5), List.of(11.2), List.of(35));

        OpenMeteoAirQualityResponse airQuality = buildAirQualityResponse(
                List.of("2026-06-21T20:47"),
                List.of(1.0), List.of(0.5), List.of(0.05));

        AtmosphericData result = openMeteoService.extractAtmosphericData(
                forecast, airQuality, "Durham UK", solarEvent, TargetType.SUNSET);

        assertThat(result.temperatureCelsius()).isEqualTo(14.5);
        assertThat(result.apparentTemperatureCelsius()).isEqualTo(11.2);
        assertThat(result.precipitationProbability()).isEqualTo(35);
    }

    @Test
    @DisplayName("extractAtmosphericData() returns null comfort fields when not provided by API")
    void extractAtmosphericData_nullComfortFields_returnsNull() {
        LocalDateTime solarEvent = LocalDateTime.of(2026, 6, 21, 20, 47, 0);

        OpenMeteoForecastResponse forecast = buildForecastResponse(
                List.of("2026-06-21T20:47"),
                List.of(10), List.of(20), List.of(30),
                List.of(20000.0), List.of(4.0), List.of(225),
                List.of(0.0), List.of(1), List.of(60),
                List.of(1000.0), List.of(100.0),
                null, null, null);

        OpenMeteoAirQualityResponse airQuality = buildAirQualityResponse(
                List.of("2026-06-21T20:47"),
                List.of(1.0), List.of(0.5), List.of(0.05));

        AtmosphericData result = openMeteoService.extractAtmosphericData(
                forecast, airQuality, "Durham UK", solarEvent, TargetType.SUNSET);

        assertThat(result.temperatureCelsius()).isNull();
        assertThat(result.apparentTemperatureCelsius()).isNull();
        assertThat(result.precipitationProbability()).isNull();
    }

    @Test
    @DisplayName("getHourlyAtmosphericData() returns one slot per full UTC hour between from and to")
    void getHourlyAtmosphericData_returnsOneSlotPerHour() {
        LocalDateTime from = LocalDateTime.of(2026, 6, 21, 3, 30, 0);
        LocalDateTime to   = LocalDateTime.of(2026, 6, 21, 4, 45, 0);

        OpenMeteoForecastResponse forecast = buildForecastResponse(
                List.of("2026-06-21T03:00", "2026-06-21T04:00"),
                List.of(5, 10), List.of(20, 25), List.of(30, 35),
                List.of(20000.0, 21000.0), List.of(3.0, 4.0), List.of(225, 240),
                List.of(0.0, 0.0), List.of(1, 1), List.of(60, 65),
                List.of(1000.0, 1100.0), List.of(100.0, 120.0),
                List.of(12.0, 13.0), List.of(10.0, 11.0), List.of(20, 25));

        OpenMeteoAirQualityResponse airQuality = buildAirQualityResponse(
                List.of("2026-06-21T03:00", "2026-06-21T04:00"),
                List.of(2.0, 3.0), List.of(0.5, 0.6), List.of(0.05, 0.06));

        when(openMeteoClient.fetchForecast(anyDouble(), anyDouble())).thenReturn(forecast);
        when(openMeteoClient.fetchAirQuality(anyDouble(), anyDouble())).thenReturn(airQuality);

        ForecastRequest request = new ForecastRequest(
                54.7753, -1.5849, "Wildlife Reserve", LocalDate.of(2026, 6, 21), TargetType.SUNRISE);

        List<AtmosphericData> result = openMeteoService.getHourlyAtmosphericData(request, from, to);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).solarEventTime()).isEqualTo(LocalDateTime.of(2026, 6, 21, 3, 0, 0));
        assertThat(result.get(1).solarEventTime()).isEqualTo(LocalDateTime.of(2026, 6, 21, 4, 0, 0));
        assertThat(result.get(0).temperatureCelsius()).isEqualTo(12.0);
        assertThat(result.get(0).precipitationProbability()).isEqualTo(20);
    }

    // --- Directional cloud data tests ---

    @Test
    @DisplayName("fetchDirectionalCloudData() returns cloud layers at solar and antisolar points")
    void fetchDirectionalCloudData_returnsCloudAtBothPoints() {
        LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 47, 0);

        OpenMeteoForecastResponse solarForecast = buildCloudOnlyResponse(
                List.of("2026-06-21T20:00", "2026-06-21T21:00"),
                List.of(65, 70), List.of(20, 25), List.of(10, 15));
        OpenMeteoForecastResponse antisolarForecast = buildCloudOnlyResponse(
                List.of("2026-06-21T20:00", "2026-06-21T21:00"),
                List.of(5, 8), List.of(45, 50), List.of(30, 35));

        when(openMeteoClient.fetchCloudOnly(anyDouble(), anyDouble()))
                .thenReturn(solarForecast)
                .thenReturn(antisolarForecast);

        DirectionalCloudData result = openMeteoService.fetchDirectionalCloudData(
                54.7753, -1.5849, 245, eventTime, null);

        assertThat(result).isNotNull();
        assertThat(result.solarLowCloudPercent()).isEqualTo(70);
        assertThat(result.solarMidCloudPercent()).isEqualTo(25);
        assertThat(result.solarHighCloudPercent()).isEqualTo(15);
        assertThat(result.antisolarLowCloudPercent()).isEqualTo(8);
        assertThat(result.antisolarMidCloudPercent()).isEqualTo(50);
        assertThat(result.antisolarHighCloudPercent()).isEqualTo(35);
    }

    @Test
    @DisplayName("fetchDirectionalCloudData() returns null when API call fails")
    void fetchDirectionalCloudData_apiFailure_returnsNull() {
        LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 47, 0);

        when(openMeteoClient.fetchCloudOnly(anyDouble(), anyDouble()))
                .thenThrow(new RuntimeException("network error"));

        DirectionalCloudData result = openMeteoService.fetchDirectionalCloudData(
                54.7753, -1.5849, 245, eventTime, null);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("fetchDirectionalCloudData() selects nearest timestamp slot")
    void fetchDirectionalCloudData_selectsNearestSlot() {
        LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 30, 0);

        OpenMeteoForecastResponse response = buildCloudOnlyResponse(
                List.of("2026-06-21T20:00", "2026-06-21T21:00"),
                List.of(10, 80), List.of(20, 90), List.of(30, 95));

        when(openMeteoClient.fetchCloudOnly(anyDouble(), anyDouble()))
                .thenReturn(response);

        DirectionalCloudData result = openMeteoService.fetchDirectionalCloudData(
                54.7753, -1.5849, 245, eventTime, null);

        assertThat(result).isNotNull();
        // 20:30 is closer to 20:00 than 21:00
        assertThat(result.solarLowCloudPercent()).isEqualTo(10);
    }

    private OpenMeteoForecastResponse buildCloudOnlyResponse(
            List<String> time,
            List<Integer> cloudLow, List<Integer> cloudMid, List<Integer> cloudHigh) {
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
        hourly.setTime(time);
        hourly.setCloudCoverLow(cloudLow);
        hourly.setCloudCoverMid(cloudMid);
        hourly.setCloudCoverHigh(cloudHigh);
        response.setHourly(hourly);
        return response;
    }

    private OpenMeteoAirQualityResponse buildAirQualityResponse(
            List<String> time, List<Double> pm25, List<Double> dust, List<Double> aod) {

        OpenMeteoAirQualityResponse response = new OpenMeteoAirQualityResponse();
        OpenMeteoAirQualityResponse.Hourly hourly = new OpenMeteoAirQualityResponse.Hourly();
        hourly.setTime(time);
        hourly.setPm25(pm25);
        hourly.setDust(dust);
        hourly.setAerosolOpticalDepth(aod);
        response.setHourly(hourly);
        return response;
    }
}
