package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CloudApproachData;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.ForecastRequest;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.MistTrend;
import com.gregochr.goldenhour.model.SolarCloudTrend;
import com.gregochr.goldenhour.model.UpwindCloudSample;
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
import static org.mockito.ArgumentMatchers.anyList;
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
        assertThat(result.cloud().lowCloudPercent()).isEqualTo(20);
        assertThat(result.cloud().midCloudPercent()).isEqualTo(60);
        assertThat(result.cloud().highCloudPercent()).isEqualTo(40);
        assertThat(result.weather().visibilityMetres()).isEqualTo(22000);
        assertThat(result.weather().windSpeedMs()).isEqualByComparingTo("3.50");
        assertThat(result.weather().windDirectionDegrees()).isEqualTo(245);
        assertThat(result.weather().precipitationMm()).isEqualByComparingTo("0.10");
        assertThat(result.weather().humidityPercent()).isEqualTo(62);
        assertThat(result.weather().weatherCode()).isEqualTo(3);
        assertThat(result.aerosol().boundaryLayerHeightMetres()).isEqualTo(1200);
        assertThat(result.aerosol().pm25()).isEqualByComparingTo("8.50");
        assertThat(result.aerosol().dustUgm3()).isEqualByComparingTo("2.10");
        assertThat(result.aerosol().aerosolOpticalDepth()).isEqualByComparingTo("0.120");
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

        assertThat(result.cloud().lowCloudPercent()).isEqualTo(25);
        assertThat(result.weather().humidityPercent()).isEqualTo(65);
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

        assertThat(result.aerosol().pm25()).isEqualByComparingTo("0.00");
        assertThat(result.aerosol().dustUgm3()).isEqualByComparingTo("0.00");
        assertThat(result.aerosol().aerosolOpticalDepth()).isEqualByComparingTo("0.000");
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

        assertThat(result.weather().windSpeedMs()).isEqualByComparingTo("7.30");
        assertThat(result.weather().windDirectionDegrees()).isEqualTo(315);
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
        assertThat(result.cloud().lowCloudPercent()).isEqualTo(10);
        assertThat(result.aerosol().pm25()).isEqualByComparingTo("8.50");
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

        assertThat(result.cloud().lowCloudPercent()).isEqualTo(99);
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

        assertThat(result.cloud().lowCloudPercent()).isEqualTo(42);
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

        assertThat(result.aerosol().pm25()).isEqualByComparingTo("0.00");
        assertThat(result.aerosol().dustUgm3()).isEqualByComparingTo("0.00");
        assertThat(result.aerosol().aerosolOpticalDepth()).isEqualByComparingTo("0.000");
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

        assertThat(result.weather().windSpeedMs()).isEqualByComparingTo("3.56");
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

        assertThat(result.tide()).isNull();
    }

    private OpenMeteoForecastResponse buildForecastResponse(
            List<String> time,
            List<Integer> cloudLow, List<Integer> cloudMid, List<Integer> cloudHigh,
            List<Double> visibility, List<Double> windSpeed, List<Integer> windDir,
            List<Double> precip, List<Integer> weatherCode, List<Integer> humidity,
            List<Double> boundaryLayer, List<Double> shortwave) {
        return buildForecastResponse(time, cloudLow, cloudMid, cloudHigh, visibility, windSpeed,
                windDir, precip, weatherCode, humidity, boundaryLayer, shortwave,
                null, null, null, null);
    }

    private OpenMeteoForecastResponse buildForecastResponse(
            List<String> time,
            List<Integer> cloudLow, List<Integer> cloudMid, List<Integer> cloudHigh,
            List<Double> visibility, List<Double> windSpeed, List<Integer> windDir,
            List<Double> precip, List<Integer> weatherCode, List<Integer> humidity,
            List<Double> boundaryLayer, List<Double> shortwave,
            List<Double> temperature, List<Double> apparentTemperature,
            List<Integer> precipProbability, List<Double> dewPoint2m) {

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
        hourly.setDewPoint2m(dewPoint2m);
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
                List.of(14.5), List.of(11.2), List.of(35), null);

        OpenMeteoAirQualityResponse airQuality = buildAirQualityResponse(
                List.of("2026-06-21T20:47"),
                List.of(1.0), List.of(0.5), List.of(0.05));

        AtmosphericData result = openMeteoService.extractAtmosphericData(
                forecast, airQuality, "Durham UK", solarEvent, TargetType.SUNSET);

        assertThat(result.comfort().temperatureCelsius()).isEqualTo(14.5);
        assertThat(result.comfort().apparentTemperatureCelsius()).isEqualTo(11.2);
        assertThat(result.comfort().precipitationProbability()).isEqualTo(35);
    }

    @Test
    @DisplayName("extractAtmosphericData() extracts surface pressure into WeatherData")
    void extractAtmosphericData_extractsSurfacePressure() {
        LocalDateTime solarEvent = LocalDateTime.of(2026, 6, 21, 20, 47, 0);

        OpenMeteoForecastResponse forecast = buildForecastResponse(
                List.of("2026-06-21T20:47"),
                List.of(10), List.of(20), List.of(30),
                List.of(20000.0), List.of(4.0), List.of(225),
                List.of(0.0), List.of(1), List.of(60),
                List.of(1000.0), List.of(100.0),
                List.of(14.5), List.of(11.2), List.of(35), null);
        forecast.getHourly().setSurfacePressure(List.of(985.5));

        OpenMeteoAirQualityResponse airQuality = buildAirQualityResponse(
                List.of("2026-06-21T20:47"),
                List.of(1.0), List.of(0.5), List.of(0.05));

        AtmosphericData result = openMeteoService.extractAtmosphericData(
                forecast, airQuality, "Durham UK", solarEvent, TargetType.SUNSET);

        assertThat(result.weather().pressureHpa()).isEqualTo(985.5);
    }

    @Test
    @DisplayName("extractAtmosphericData() returns null pressure when not in API response")
    void extractAtmosphericData_nullPressure_returnsNull() {
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

        assertThat(result.weather().pressureHpa()).isNull();
    }

    @Test
    @DisplayName("getAtmosphericDataWithResponse() returns both data and raw response")
    void getAtmosphericDataWithResponse_returnsBoth() {
        LocalDateTime solarEvent = LocalDateTime.of(2026, 6, 21, 20, 47, 0);

        OpenMeteoForecastResponse forecast = buildForecastResponse(
                List.of("2026-06-21T20:47"),
                List.of(10), List.of(50), List.of(30),
                List.of(22000.0), List.of(4.0), List.of(225),
                List.of(0.0), List.of(1), List.of(62),
                List.of(1200.0), List.of(180.0));
        forecast.getHourly().setSurfacePressure(List.of(1013.0));

        OpenMeteoAirQualityResponse airQuality = buildAirQualityResponse(
                List.of("2026-06-21T20:47"),
                List.of(8.5), List.of(2.1), List.of(0.12));

        when(openMeteoClient.fetchForecast(anyDouble(), anyDouble())).thenReturn(forecast);
        when(openMeteoClient.fetchAirQuality(anyDouble(), anyDouble())).thenReturn(airQuality);

        ForecastRequest request = new ForecastRequest(
                54.7753, -1.5849, "Durham UK", LocalDate.of(2026, 6, 21), TargetType.SUNSET);

        var result = openMeteoService.getAtmosphericDataWithResponse(request, solarEvent, null);

        assertThat(result.atmosphericData()).isNotNull();
        assertThat(result.forecastResponse()).isSameAs(forecast);
        assertThat(result.atmosphericData().weather().pressureHpa()).isEqualTo(1013.0);
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
                null, null, null, null);

        OpenMeteoAirQualityResponse airQuality = buildAirQualityResponse(
                List.of("2026-06-21T20:47"),
                List.of(1.0), List.of(0.5), List.of(0.05));

        AtmosphericData result = openMeteoService.extractAtmosphericData(
                forecast, airQuality, "Durham UK", solarEvent, TargetType.SUNSET);

        assertThat(result.comfort().temperatureCelsius()).isNull();
        assertThat(result.comfort().apparentTemperatureCelsius()).isNull();
        assertThat(result.comfort().precipitationProbability()).isNull();
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
                List.of(12.0, 13.0), List.of(10.0, 11.0), List.of(20, 25), null);

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
        assertThat(result.get(0).comfort().temperatureCelsius()).isEqualTo(12.0);
        assertThat(result.get(0).comfort().precipitationProbability()).isEqualTo(20);
    }

    // --- Directional cloud data tests ---

    @Test
    @DisplayName("fetchDirectionalCloudData() averages 3 solar cone points and returns antisolar")
    void fetchDirectionalCloudData_returnsCloudAtBothPoints() {
        LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 47, 0);

        // 3 solar cone responses (azimuth-15, azimuth, azimuth+15) with varying cloud
        OpenMeteoForecastResponse solarForecast1 = buildCloudOnlyResponse(
                List.of("2026-06-21T20:00", "2026-06-21T21:00"),
                List.of(60, 70), List.of(15, 25), List.of(9, 15));
        OpenMeteoForecastResponse solarForecast2 = buildCloudOnlyResponse(
                List.of("2026-06-21T20:00", "2026-06-21T21:00"),
                List.of(66, 70), List.of(21, 25), List.of(12, 15));
        OpenMeteoForecastResponse solarForecast3 = buildCloudOnlyResponse(
                List.of("2026-06-21T20:00", "2026-06-21T21:00"),
                List.of(69, 70), List.of(24, 25), List.of(9, 15));
        OpenMeteoForecastResponse antisolarForecast = buildCloudOnlyResponse(
                List.of("2026-06-21T20:00", "2026-06-21T21:00"),
                List.of(5, 8), List.of(45, 50), List.of(30, 35));

        // Far-solar response (index 4)
        OpenMeteoForecastResponse farSolarForecast = buildCloudOnlyResponse(
                List.of("2026-06-21T20:00", "2026-06-21T21:00"),
                List.of(40, 50), List.of(10, 10), List.of(5, 5));

        when(openMeteoClient.fetchCloudOnlyBatch(anyList()))
                .thenReturn(List.of(solarForecast1, solarForecast2, solarForecast3,
                        antisolarForecast, farSolarForecast));

        DirectionalCloudData result = openMeteoService.fetchDirectionalCloudData(
                54.7753, -1.5849, 245, eventTime, TargetType.SUNSET, null);

        assertThat(result).isNotNull();
        // Sunset at 20:47 picks 20:00 slot (index 0); solar values are averaged
        // Low: (60+66+69)/3 = 65, Mid: (15+21+24)/3 = 20, High: (9+12+9)/3 = 10
        assertThat(result.solarLowCloudPercent()).isEqualTo(65);
        assertThat(result.solarMidCloudPercent()).isEqualTo(20);
        assertThat(result.solarHighCloudPercent()).isEqualTo(10);
        assertThat(result.antisolarLowCloudPercent()).isEqualTo(5);
        assertThat(result.antisolarMidCloudPercent()).isEqualTo(45);
        assertThat(result.antisolarHighCloudPercent()).isEqualTo(30);
    }

    @Test
    @DisplayName("fetchDirectionalCloudData() returns null when API call fails")
    void fetchDirectionalCloudData_apiFailure_returnsNull() {
        LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 47, 0);

        when(openMeteoClient.fetchCloudOnlyBatch(anyList()))
                .thenThrow(new RuntimeException("network error"));

        DirectionalCloudData result = openMeteoService.fetchDirectionalCloudData(
                54.7753, -1.5849, 245, eventTime, TargetType.SUNSET, null);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("fetchDirectionalCloudData() for sunset selects slot before event time")
    void fetchDirectionalCloudData_sunset_selectsSlotBeforeEvent() {
        // Sunset at 20:47 — closer to 21:00, but should pick 20:00 (before sunset)
        LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 47, 0);

        OpenMeteoForecastResponse beforeResponse = buildCloudOnlyResponse(
                List.of("2026-06-21T20:00", "2026-06-21T21:00"),
                List.of(10, 80), List.of(20, 90), List.of(30, 95));

        // 5 points in 1 batch call, all return same response
        when(openMeteoClient.fetchCloudOnlyBatch(anyList()))
                .thenReturn(List.of(beforeResponse, beforeResponse, beforeResponse,
                        beforeResponse, beforeResponse));

        DirectionalCloudData result = openMeteoService.fetchDirectionalCloudData(
                54.7753, -1.5849, 245, eventTime, TargetType.SUNSET, null);

        assertThat(result).isNotNull();
        // Should pick 20:00 (before sunset), not 21:00 (after sunset, useless data)
        assertThat(result.solarLowCloudPercent()).isEqualTo(10);
    }

    // --- findBestIndex tests ---

    @Test
    @DisplayName("findBestIndex() for sunset picks slot before event, not the closer one after")
    void findBestIndex_sunset_prefersSlotBefore() {
        List<String> times = List.of("2026-03-05T17:00", "2026-03-05T18:00");
        // Sunset at 17:35 — 18:00 is closer (25 min) but 17:00 should be chosen (35 min before)
        LocalDateTime sunset = LocalDateTime.of(2026, 3, 5, 17, 35);

        int idx = openMeteoService.findBestIndex(times, sunset, TargetType.SUNSET);

        assertThat(idx).isEqualTo(0); // 17:00
    }

    @Test
    @DisplayName("findBestIndex() for sunrise picks slot after event, not the closer one before")
    void findBestIndex_sunrise_prefersSlotAfter() {
        List<String> times = List.of("2026-03-05T06:00", "2026-03-05T07:00");
        // Sunrise at 06:25 — 06:00 is closer (25 min) but 07:00 should be chosen (35 min after)
        LocalDateTime sunrise = LocalDateTime.of(2026, 3, 5, 6, 25);

        int idx = openMeteoService.findBestIndex(times, sunrise, TargetType.SUNRISE);

        assertThat(idx).isEqualTo(1); // 07:00
    }

    @Test
    @DisplayName("findBestIndex() falls back to nearest when no slot on preferred side")
    void findBestIndex_fallsBackToNearest() {
        List<String> times = List.of("2026-03-05T19:00", "2026-03-05T20:00");
        // Sunset at 17:35 — both slots are after sunset, should fall back to nearest (19:00)
        LocalDateTime sunset = LocalDateTime.of(2026, 3, 5, 17, 35);

        int idx = openMeteoService.findBestIndex(times, sunset, TargetType.SUNSET);

        assertThat(idx).isEqualTo(0); // 19:00 (nearest)
    }

    @Test
    @DisplayName("findBestIndex() for sunset picks exact match at event time")
    void findBestIndex_sunset_exactMatch() {
        List<String> times = List.of("2026-03-05T17:00", "2026-03-05T18:00");
        LocalDateTime sunset = LocalDateTime.of(2026, 3, 5, 17, 0);

        int idx = openMeteoService.findBestIndex(times, sunset, TargetType.SUNSET);

        assertThat(idx).isEqualTo(0); // exact match at 17:00
    }

    // --- Cloud approach data tests ---

    @Test
    @DisplayName("fetchCloudApproachData() returns trend and upwind sample on happy path")
    void fetchCloudApproachData_happyPath_returnsBothSignals() {
        LocalDateTime eventTime = LocalDateTime.of(2026, 3, 11, 17, 45, 0);
        LocalDateTime currentTime = LocalDateTime.of(2026, 3, 11, 13, 45, 0);

        // Solar trend response: T-3h=5%, T-2h=15%, T-1h=35%, T=7%
        OpenMeteoForecastResponse solarForecast = buildCloudOnlyResponse(
                List.of("2026-03-11T14:00", "2026-03-11T15:00",
                        "2026-03-11T16:00", "2026-03-11T17:00"),
                List.of(5, 15, 35, 7), List.of(0, 0, 0, 0), List.of(80, 80, 80, 80));

        // Upwind response: current at 14:00=70%, event at 17:00=15%
        OpenMeteoForecastResponse upwindForecast = buildCloudOnlyResponse(
                List.of("2026-03-11T14:00", "2026-03-11T15:00",
                        "2026-03-11T16:00", "2026-03-11T17:00"),
                List.of(70, 55, 30, 15), List.of(0, 0, 0, 0), List.of(50, 50, 50, 50));

        when(openMeteoClient.fetchCloudOnlyBatch(anyList()))
                .thenReturn(List.of(solarForecast, upwindForecast));

        CloudApproachData result = openMeteoService.fetchCloudApproachData(
                54.8975, -1.5076, 245, eventTime, currentTime,
                TargetType.SUNSET, 228, 5.7, null);

        assertThat(result).isNotNull();

        // Solar trend
        assertThat(result.solarTrend()).isNotNull();
        assertThat(result.solarTrend().slots()).hasSize(4);
        assertThat(result.solarTrend().slots().getFirst().lowCloudPercent()).isEqualTo(5);
        assertThat(result.solarTrend().slots().getLast().lowCloudPercent()).isEqualTo(7);

        // Upwind
        assertThat(result.upwindSample()).isNotNull();
        assertThat(result.upwindSample().currentLowCloudPercent()).isEqualTo(70);
        assertThat(result.upwindSample().eventLowCloudPercent()).isEqualTo(15);
        assertThat(result.upwindSample().windFromBearing()).isEqualTo(228);
    }

    @Test
    @DisplayName("fetchCloudApproachData() returns null when API fails")
    void fetchCloudApproachData_apiFailure_returnsNull() {
        LocalDateTime eventTime = LocalDateTime.of(2026, 3, 11, 17, 45, 0);
        LocalDateTime currentTime = LocalDateTime.of(2026, 3, 11, 13, 45, 0);

        when(openMeteoClient.fetchCloudOnlyBatch(anyList()))
                .thenThrow(new RuntimeException("network error"));

        CloudApproachData result = openMeteoService.fetchCloudApproachData(
                54.8975, -1.5076, 245, eventTime, currentTime,
                TargetType.SUNSET, 228, 5.7, null);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("fetchCloudApproachData() skips upwind when wind speed is zero")
    void fetchCloudApproachData_zeroWind_skipsUpwind() {
        LocalDateTime eventTime = LocalDateTime.of(2026, 3, 11, 17, 45, 0);
        LocalDateTime currentTime = LocalDateTime.of(2026, 3, 11, 13, 45, 0);

        OpenMeteoForecastResponse solarForecast = buildCloudOnlyResponse(
                List.of("2026-03-11T14:00", "2026-03-11T15:00",
                        "2026-03-11T16:00", "2026-03-11T17:00"),
                List.of(5, 10, 15, 20), List.of(0, 0, 0, 0), List.of(80, 80, 80, 80));

        // Only solar point, no upwind (wind=0)
        when(openMeteoClient.fetchCloudOnlyBatch(anyList()))
                .thenReturn(List.of(solarForecast));

        CloudApproachData result = openMeteoService.fetchCloudApproachData(
                54.8975, -1.5076, 245, eventTime, currentTime,
                TargetType.SUNSET, 228, 0.0, null);

        assertThat(result).isNotNull();
        assertThat(result.solarTrend()).isNotNull();
        assertThat(result.upwindSample()).isNull();
    }

    @Test
    @DisplayName("fetchCloudApproachData() skips upwind when event has passed")
    void fetchCloudApproachData_eventPassed_skipsUpwind() {
        LocalDateTime eventTime = LocalDateTime.of(2026, 3, 11, 13, 0, 0);
        LocalDateTime currentTime = LocalDateTime.of(2026, 3, 11, 14, 0, 0);

        OpenMeteoForecastResponse solarForecast = buildCloudOnlyResponse(
                List.of("2026-03-11T10:00", "2026-03-11T11:00",
                        "2026-03-11T12:00", "2026-03-11T13:00"),
                List.of(5, 10, 15, 20), List.of(0, 0, 0, 0), List.of(80, 80, 80, 80));

        // Only solar point, no upwind (event passed)
        when(openMeteoClient.fetchCloudOnlyBatch(anyList()))
                .thenReturn(List.of(solarForecast));

        CloudApproachData result = openMeteoService.fetchCloudApproachData(
                54.8975, -1.5076, 245, eventTime, currentTime,
                TargetType.SUNSET, 228, 5.7, null);

        assertThat(result).isNotNull();
        assertThat(result.solarTrend()).isNotNull();
        assertThat(result.upwindSample()).isNull();
    }

    // --- extractSolarTrend tests ---

    @Test
    @DisplayName("extractSolarTrend() extracts T-3h through T for a sunset event")
    void extractSolarTrend_sunsetEvent_extractsFourSlots() {
        OpenMeteoForecastResponse forecast = buildCloudOnlyResponse(
                List.of("2026-03-11T14:00", "2026-03-11T15:00",
                        "2026-03-11T16:00", "2026-03-11T17:00", "2026-03-11T18:00"),
                List.of(5, 12, 25, 7, 80), List.of(0, 0, 0, 0, 0), List.of(80, 80, 80, 80, 80));

        SolarCloudTrend trend = openMeteoService.extractSolarTrend(
                forecast, LocalDateTime.of(2026, 3, 11, 17, 45), TargetType.SUNSET);

        assertThat(trend).isNotNull();
        assertThat(trend.slots()).hasSize(4);
        assertThat(trend.slots().get(0).hoursBeforeEvent()).isEqualTo(3);
        assertThat(trend.slots().get(0).lowCloudPercent()).isEqualTo(5);
        assertThat(trend.slots().get(3).hoursBeforeEvent()).isEqualTo(0);
        assertThat(trend.slots().get(3).lowCloudPercent()).isEqualTo(7);
    }

    @Test
    @DisplayName("extractSolarTrend() returns fewer slots when event is near start of data")
    void extractSolarTrend_nearStartOfData_returnsFewerSlots() {
        OpenMeteoForecastResponse forecast = buildCloudOnlyResponse(
                List.of("2026-03-11T06:00", "2026-03-11T07:00"),
                List.of(30, 45), List.of(0, 0), List.of(80, 80));

        SolarCloudTrend trend = openMeteoService.extractSolarTrend(
                forecast, LocalDateTime.of(2026, 3, 11, 7, 15), TargetType.SUNRISE);

        assertThat(trend).isNotNull();
        assertThat(trend.slots()).hasSizeLessThanOrEqualTo(2);
    }

    // --- extractUpwindSample tests ---

    @Test
    @DisplayName("extractUpwindSample() extracts current and event-time low cloud")
    void extractUpwindSample_extractsBothSlots() {
        OpenMeteoForecastResponse forecast = buildCloudOnlyResponse(
                List.of("2026-03-11T13:00", "2026-03-11T14:00",
                        "2026-03-11T15:00", "2026-03-11T16:00", "2026-03-11T17:00"),
                List.of(80, 70, 50, 30, 15), List.of(0, 0, 0, 0, 0), List.of(50, 50, 50, 50, 50));

        UpwindCloudSample sample = openMeteoService.extractUpwindSample(
                forecast, LocalDateTime.of(2026, 3, 11, 17, 45),
                LocalDateTime.of(2026, 3, 11, 13, 30), TargetType.SUNSET, 87, 228);

        assertThat(sample).isNotNull();
        assertThat(sample.distanceKm()).isEqualTo(87);
        assertThat(sample.windFromBearing()).isEqualTo(228);
        assertThat(sample.currentLowCloudPercent()).isEqualTo(80);
        assertThat(sample.eventLowCloudPercent()).isEqualTo(15);
    }

    // --- dew point and mist trend tests ---

    @Test
    @DisplayName("extractAtmosphericData() populates dewPointCelsius from API response")
    void extractAtmosphericData_populatesDewPoint() {
        // Use SUNSET at 18:30 — selects slot at or before event, so index 0 (18:00)
        LocalDateTime solarEvent = LocalDateTime.of(2026, 3, 21, 18, 30, 0);

        OpenMeteoForecastResponse forecast = buildForecastResponse(
                List.of("2026-03-21T18:00", "2026-03-21T19:00"),
                List.of(5, 10), List.of(20, 25), List.of(30, 35),
                List.of(4200.0, 6500.0), List.of(3.0, 4.0), List.of(180, 200),
                List.of(0.0, 0.0), List.of(1, 1), List.of(94, 90),
                List.of(500.0, 600.0), List.of(10.0, 20.0),
                List.of(3.8, 5.2), List.of(2.5, 3.8), List.of(10, 15),
                List.of(2.2, 3.0));

        OpenMeteoAirQualityResponse airQuality = buildAirQualityResponse(
                List.of("2026-03-21T18:00", "2026-03-21T19:00"),
                List.of(2.0, 2.5), List.of(0.5, 0.6), List.of(0.05, 0.06));

        AtmosphericData result = openMeteoService.extractAtmosphericData(
                forecast, airQuality, "Embleton Bay", solarEvent, TargetType.SUNSET);

        assertThat(result.weather().dewPointCelsius()).isEqualTo(2.2);
    }

    @Test
    @DisplayName("extractAtmosphericData() returns null dewPointCelsius when not in response")
    void extractAtmosphericData_nullDewPoint_returnsNull() {
        LocalDateTime solarEvent = LocalDateTime.of(2026, 3, 21, 6, 15, 0);

        OpenMeteoForecastResponse forecast = buildForecastResponse(
                List.of("2026-03-21T06:00"),
                List.of(5), List.of(20), List.of(30),
                List.of(4200.0), List.of(3.0), List.of(180),
                List.of(0.0), List.of(1), List.of(94),
                List.of(500.0), List.of(10.0),
                List.of(3.8), List.of(2.5), List.of(10),
                null);

        OpenMeteoAirQualityResponse airQuality = buildAirQualityResponse(
                List.of("2026-03-21T06:00"),
                List.of(2.0), List.of(0.5), List.of(0.05));

        AtmosphericData result = openMeteoService.extractAtmosphericData(
                forecast, airQuality, "Embleton Bay", solarEvent, TargetType.SUNRISE);

        assertThat(result.weather().dewPointCelsius()).isNull();
    }

    @Test
    @DisplayName("extractMistTrend() extracts T-3h to T+2h visibility and dew point slots")
    void extractMistTrend_extractsSixSlots() {
        // 7 hourly slots from index 0 (T-3h) to 6 (T+3h); event at index 3
        OpenMeteoForecastResponse.Hourly h = new OpenMeteoForecastResponse.Hourly();
        h.setTime(List.of("2026-03-21T03:00", "2026-03-21T04:00", "2026-03-21T05:00",
                "2026-03-21T06:00", "2026-03-21T07:00", "2026-03-21T08:00", "2026-03-21T09:00"));
        h.setVisibility(List.of(20000.0, 15000.0, 8000.0, 4200.0, 2500.0, 1800.0, 3000.0));
        h.setDewPoint2m(List.of(1.0, 1.5, 2.0, 2.2, 2.3, 2.4, 2.2));
        h.setTemperature2m(List.of(6.0, 5.5, 4.5, 3.8, 3.2, 3.1, 3.5));

        MistTrend trend = openMeteoService.extractMistTrend(h, 3);

        assertThat(trend).isNotNull();
        assertThat(trend.slots()).hasSize(6); // T-3h through T+2h
        assertThat(trend.slots().get(0).hoursRelativeToEvent()).isEqualTo(-3);
        assertThat(trend.slots().get(0).visibilityMetres()).isEqualTo(20000);
        assertThat(trend.slots().get(3).hoursRelativeToEvent()).isEqualTo(0); // event slot
        assertThat(trend.slots().get(3).visibilityMetres()).isEqualTo(4200);
        assertThat(trend.slots().get(3).dewPointCelsius()).isEqualTo(2.2);
        assertThat(trend.slots().get(3).temperatureCelsius()).isEqualTo(3.8);
        // gap at event = 3.8 - 2.2 = 1.6°C (near dew point)
        assertThat(trend.slots().get(3).temperatureCelsius() - trend.slots().get(3).dewPointCelsius())
                .isCloseTo(1.6, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("extractMistTrend() returns null when dew point data is absent")
    void extractMistTrend_noDewPointData_returnsNull() {
        OpenMeteoForecastResponse.Hourly h = new OpenMeteoForecastResponse.Hourly();
        h.setTime(List.of("2026-03-21T06:00"));
        h.setVisibility(List.of(4200.0));
        // dewPoint2m not set (null)

        MistTrend trend = openMeteoService.extractMistTrend(h, 0);

        assertThat(trend).isNull();
    }

    @Test
    @DisplayName("extractMistTrend() handles event near start of data with fewer back-slots")
    void extractMistTrend_nearStartOfData_returnsAvailableSlots() {
        OpenMeteoForecastResponse.Hourly h = new OpenMeteoForecastResponse.Hourly();
        h.setTime(List.of("2026-03-21T06:00", "2026-03-21T07:00", "2026-03-21T08:00"));
        h.setVisibility(List.of(8000.0, 6000.0, 4000.0));
        h.setDewPoint2m(List.of(2.0, 2.2, 2.5));
        h.setTemperature2m(List.of(4.5, 4.0, 3.5));

        // Event at index 0 — no slots before it, T+1h and T+2h available
        MistTrend trend = openMeteoService.extractMistTrend(h, 0);

        assertThat(trend).isNotNull();
        assertThat(trend.slots()).hasSize(3); // only event, T+1h, T+2h
        assertThat(trend.slots().get(0).hoursRelativeToEvent()).isEqualTo(0);
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

    // --- Batch prefetch and cache tests ---

    @Test
    @DisplayName("prefetchWeatherBatch() returns map keyed by coordKey for each location")
    void prefetchWeatherBatch_returnsMapKeyedByCoords() {
        OpenMeteoForecastResponse f1 = buildForecastResponse(
                List.of("2026-06-21T06:00"), List.of(10), List.of(20), List.of(30),
                List.of(10000.0), List.of(5.0), List.of(180), List.of(0.0), List.of(3),
                List.of(80), List.of(200.0), List.of(500.0));
        OpenMeteoForecastResponse f2 = buildForecastResponse(
                List.of("2026-06-21T06:00"), List.of(50), List.of(40), List.of(60),
                List.of(10000.0), List.of(5.0), List.of(180), List.of(0.0), List.of(3),
                List.of(80), List.of(200.0), List.of(500.0));
        OpenMeteoAirQualityResponse aq1 = buildAirQualityResponse(
                List.of("2026-06-21T06:00"), List.of(5.0), List.of(10.0), List.of(0.1));
        OpenMeteoAirQualityResponse aq2 = buildAirQualityResponse(
                List.of("2026-06-21T06:00"), List.of(8.0), List.of(15.0), List.of(0.2));

        List<double[]> coords = List.of(new double[]{55.0, -1.5}, new double[]{54.0, -2.0});
        when(openMeteoClient.fetchForecastBatch(anyList())).thenReturn(List.of(f1, f2));
        when(openMeteoClient.fetchAirQualityBatch(anyList())).thenReturn(List.of(aq1, aq2));

        var cache = openMeteoService.prefetchWeatherBatch(coords, null);

        assertThat(cache).hasSize(2);
        assertThat(cache).containsKey("55.0,-1.5");
        assertThat(cache).containsKey("54.0,-2.0");
        assertThat(cache.get("55.0,-1.5").forecastResponse()).isSameAs(f1);
        assertThat(cache.get("54.0,-2.0").forecastResponse()).isSameAs(f2);
    }

    @Test
    @DisplayName("getAtmosphericDataFromCache() extracts data from pre-fetched cache")
    void getAtmosphericDataFromCache_extractsFromPrefetched() {
        OpenMeteoForecastResponse forecast = buildForecastResponse(
                List.of("2026-06-21T06:00"), List.of(10), List.of(20), List.of(30),
                List.of(10000.0), List.of(5.0), List.of(180), List.of(0.0), List.of(3),
                List.of(80), List.of(200.0), List.of(500.0));
        OpenMeteoAirQualityResponse aq = buildAirQualityResponse(
                List.of("2026-06-21T06:00"), List.of(5.0), List.of(10.0), List.of(0.1));

        var cache = java.util.Map.of("55.0,-1.5",
                new com.gregochr.goldenhour.model.WeatherExtractionResult(null, forecast, aq));

        ForecastRequest request = new ForecastRequest(55.0, -1.5, "Durham",
                LocalDate.of(2026, 6, 21), TargetType.SUNRISE);
        LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 6, 0);

        var result = openMeteoService.getAtmosphericDataFromCache(request, eventTime, cache);

        assertThat(result).isNotNull();
        assertThat(result.atmosphericData()).isNotNull();
        assertThat(result.atmosphericData().cloud().lowCloudPercent()).isEqualTo(10);
        assertThat(result.forecastResponse()).isSameAs(forecast);
    }

    @Test
    @DisplayName("getAtmosphericDataFromCache() returns null for missing location")
    void getAtmosphericDataFromCache_returnsNullForMissing() {
        ForecastRequest request = new ForecastRequest(99.0, 99.0, "Unknown",
                LocalDate.of(2026, 6, 21), TargetType.SUNRISE);
        var result = openMeteoService.getAtmosphericDataFromCache(
                request, LocalDateTime.of(2026, 6, 21, 6, 0), java.util.Map.of());
        assertThat(result).isNull();
    }
}
