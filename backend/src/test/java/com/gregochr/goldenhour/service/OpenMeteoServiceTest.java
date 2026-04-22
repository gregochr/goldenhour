package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.ServiceName;
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
import org.junit.jupiter.api.Nested;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

    @Test
    @DisplayName("coordKey() formats lat/lon as 'lat,lon' string including negatives")
    void coordKey_formatsLatLon() {
        assertThat(OpenMeteoService.coordKey(54.77, -1.57)).isEqualTo("54.77,-1.57");
        assertThat(OpenMeteoService.coordKey(-33.87, 151.21)).isEqualTo("-33.87,151.21");
    }

    @Test
    @DisplayName("prefetchWeatherBatch() calls both batch APIs exactly once")
    void prefetchWeatherBatch_callsBothApis() {
        OpenMeteoForecastResponse f1 = buildForecastResponse(
                List.of("2026-06-21T06:00"), List.of(10), List.of(20), List.of(30),
                List.of(10000.0), List.of(5.0), List.of(180), List.of(0.0), List.of(3),
                List.of(80), List.of(200.0), List.of(500.0));
        OpenMeteoAirQualityResponse aq1 = buildAirQualityResponse(
                List.of("2026-06-21T06:00"), List.of(5.0), List.of(10.0), List.of(0.1));

        List<double[]> coords = List.of(new double[]{55.0, -1.5});
        when(openMeteoClient.fetchForecastBatch(anyList())).thenReturn(List.of(f1));
        when(openMeteoClient.fetchAirQualityBatch(anyList())).thenReturn(List.of(aq1));

        openMeteoService.prefetchWeatherBatch(coords, null);

        verify(openMeteoClient).fetchForecastBatch(anyList());
        verify(openMeteoClient).fetchAirQualityBatch(anyList());
    }

    @Test
    @DisplayName("prefetchWeatherBatch() propagates exception when forecast batch fails")
    void prefetchWeatherBatch_apiFailure_propagatesException() {
        when(openMeteoClient.fetchForecastBatch(anyList()))
                .thenThrow(new RuntimeException("batch API timeout"));

        List<double[]> coords = List.of(new double[]{55.0, -1.5});

        assertThatThrownBy(() -> openMeteoService.prefetchWeatherBatch(coords, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("batch API timeout");
    }

    @Test
    @DisplayName("prefetchWeatherBatch() returns empty map for empty coord list without calling APIs")
    void prefetchWeatherBatch_emptyCoordList_returnsEmptyMap() {
        var cache = openMeteoService.prefetchWeatherBatch(List.of(), null);

        assertThat(cache).isEmpty();
        // Kills mutation: removing the empty guard — no API calls should be made
        verify(openMeteoClient, never()).fetchForecastBatch(anyList());
        verify(openMeteoClient, never()).fetchAirQualityBatch(anyList());
    }

    @Test
    @DisplayName("prefetchWeatherBatch() omits locations with null forecast entry from cache")
    void prefetchWeatherBatch_nullForecastEntry_omittedFromCache() {
        OpenMeteoForecastResponse f1 = buildForecastResponse(
                List.of("2026-06-21T06:00"), List.of(10), List.of(20), List.of(30),
                List.of(10000.0), List.of(5.0), List.of(180), List.of(0.0), List.of(3),
                List.of(80), List.of(200.0), List.of(500.0));
        OpenMeteoAirQualityResponse aq1 = buildAirQualityResponse(
                List.of("2026-06-21T06:00"), List.of(5.0), List.of(10.0), List.of(0.1));
        OpenMeteoAirQualityResponse aq2 = buildAirQualityResponse(
                List.of("2026-06-21T06:00"), List.of(5.0), List.of(10.0), List.of(0.1));

        List<double[]> coords = List.of(new double[]{55.0, -1.5}, new double[]{54.0, -2.0});
        // First coord has a valid forecast; second's chunk failed and was left as null
        when(openMeteoClient.fetchForecastBatch(
                org.mockito.ArgumentMatchers.argThat(
                        (java.util.List<double[]> c) -> c.size() == 2)))
                .thenReturn(java.util.Arrays.asList(f1, null));
        when(openMeteoClient.fetchAirQualityBatch(
                org.mockito.ArgumentMatchers.argThat(
                        (java.util.List<double[]> c) -> c.size() == 2)))
                .thenReturn(List.of(aq1, aq2));

        var cache = openMeteoService.prefetchWeatherBatch(coords, null);

        assertThat(cache).hasSize(1);
        assertThat(cache).containsKey("55.0,-1.5");
        assertThat(cache).doesNotContainKey("54.0,-2.0");
        assertThat(cache.get("55.0,-1.5").forecastResponse()).isSameAs(f1);
    }

    @Test
    @DisplayName("getAtmosphericDataFromCache() does not call individual weather APIs")
    void getAtmosphericDataFromCache_doesNotCallApi() {
        OpenMeteoForecastResponse forecast = buildForecastResponse(
                List.of("2026-06-21T06:00"), List.of(10), List.of(20), List.of(30),
                List.of(10000.0), List.of(5.0), List.of(180), List.of(0.0), List.of(3),
                List.of(80), List.of(200.0), List.of(500.0));
        OpenMeteoAirQualityResponse aq = buildAirQualityResponse(
                List.of("2026-06-21T06:00"), List.of(5.0), List.of(10.0), List.of(0.1));

        when(openMeteoClient.fetchForecast(anyDouble(), anyDouble()))
                .thenThrow(new RuntimeException("Should not be called"));
        when(openMeteoClient.fetchAirQuality(anyDouble(), anyDouble()))
                .thenThrow(new RuntimeException("Should not be called"));

        var cache = java.util.Map.of("55.0,-1.5",
                new com.gregochr.goldenhour.model.WeatherExtractionResult(null, forecast, aq));

        ForecastRequest request = new ForecastRequest(55.0, -1.5, "Durham",
                LocalDate.of(2026, 6, 21), TargetType.SUNRISE);

        var result = openMeteoService.getAtmosphericDataFromCache(
                request, LocalDateTime.of(2026, 6, 21, 6, 0), cache);

        assertThat(result).isNotNull();
        verify(openMeteoClient, never()).fetchForecast(anyDouble(), anyDouble());
        verify(openMeteoClient, never()).fetchAirQuality(anyDouble(), anyDouble());
    }

    // --- CloudPointCache grid snapping tests ---

    @Test
    @DisplayName("CloudPointCache.gridKey() snaps nearby coordinates to the same cell")
    void cloudPointCache_gridKey_snapsNearbyToSameCell() {
        // Two points ~200m apart should snap to same 0.1° grid cell
        String key1 = com.gregochr.goldenhour.model.CloudPointCache.gridKey(54.7753, -1.5849);
        String key2 = com.gregochr.goldenhour.model.CloudPointCache.gridKey(54.7755, -1.5851);
        assertThat(key1).isEqualTo(key2);

        // Point ~15km away should be in a different cell
        String key3 = com.gregochr.goldenhour.model.CloudPointCache.gridKey(54.90, -1.5849);
        assertThat(key1).isNotEqualTo(key3);
    }

    @Test
    @DisplayName("CloudPointCache.get() snaps coordinates before lookup")
    void cloudPointCache_get_snapsBeforeLookup() {
        String gridKey = com.gregochr.goldenhour.model.CloudPointCache.gridKey(54.7753, -1.5849);
        OpenMeteoForecastResponse response = buildCloudOnlyResponse(
                List.of("2026-06-21T06:00"), List.of(30), List.of(40), List.of(50));

        var cache = new com.gregochr.goldenhour.model.CloudPointCache(
                java.util.Map.of(gridKey, response));

        // Slightly different coords that snap to same cell should find the data
        assertThat(cache.get(54.7755, -1.5851)).isSameAs(response);
        // Different cell should return null
        assertThat(cache.get(54.90, -1.58)).isNull();
    }

    // --- computeDirectionalCloudPoints tests ---

    @Test
    @DisplayName("computeDirectionalCloudPoints() returns exactly 5 points in correct order")
    void computeDirectionalCloudPoints_returns5Points() {
        List<double[]> points = openMeteoService.computeDirectionalCloudPoints(
                54.7753, -1.5849, 270);

        assertThat(points).hasSize(5);
        // All 5 should be different from the observer point
        for (double[] p : points) {
            assertThat(p[0]).isNotEqualTo(54.7753);
        }
    }

    // --- computeUpwindPoint tests ---

    @Test
    @DisplayName("computeUpwindPoint() returns null when wind speed is zero")
    void computeUpwindPoint_zeroWind_returnsNull() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 21, 17, 0);
        LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 47);

        double[] result = openMeteoService.computeUpwindPoint(
                54.77, -1.58, 180, 0.0, now, eventTime);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("computeUpwindPoint() returns null when event has passed")
    void computeUpwindPoint_eventPassed_returnsNull() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 21, 21, 0);
        LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 47);

        double[] result = openMeteoService.computeUpwindPoint(
                54.77, -1.58, 180, 5.0, now, eventTime);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("computeUpwindPoint() caps distance at 200 km")
    void computeUpwindPoint_capsDistanceAt200km() {
        // 20 m/s wind × 3 hours = 216 km → should cap at 200 km
        LocalDateTime now = LocalDateTime.of(2026, 6, 21, 17, 0);
        LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 0);

        double[] capped = openMeteoService.computeUpwindPoint(
                54.77, -1.58, 0, 20.0, now, eventTime);
        // 5 m/s wind × 3 hours = 54 km → not capped
        double[] uncapped = openMeteoService.computeUpwindPoint(
                54.77, -1.58, 0, 5.0, now, eventTime);

        assertThat(capped).isNotNull();
        assertThat(uncapped).isNotNull();
        // Capped point should be closer to observer than uncapped distance ratio suggests
        // At 200km cap vs 54km, latitude offset differs
        double cappedOffset = Math.abs(capped[0] - 54.77);
        double uncappedOffset = Math.abs(uncapped[0] - 54.77);
        // 200km / 54km ≈ 3.7x, but capped so ratio should be less than 4x
        assertThat(cappedOffset / uncappedOffset).isLessThan(4.0);
    }

    // --- prefetchCloudBatch tests ---

    @Test
    @DisplayName("prefetchCloudBatch() deduplicates points by grid key")
    void prefetchCloudBatch_deduplicatesByGridKey() {
        OpenMeteoForecastResponse r1 = buildCloudOnlyResponse(
                List.of("2026-06-21T06:00"), List.of(10), List.of(20), List.of(30));
        OpenMeteoForecastResponse r2 = buildCloudOnlyResponse(
                List.of("2026-06-21T06:00"), List.of(50), List.of(60), List.of(70));

        // 3 raw points, but first two snap to same grid cell → 2 unique
        List<double[]> points = List.of(
                new double[]{54.7753, -1.5849},
                new double[]{54.7755, -1.5851},   // same grid cell as first
                new double[]{54.90, -1.58});       // different cell

        when(openMeteoClient.fetchCloudOnlyBatch(
                org.mockito.ArgumentMatchers.argThat(
                        (java.util.List<double[]> coords) -> coords.size() == 2)))
                .thenReturn(List.of(r1, r2));

        com.gregochr.goldenhour.model.CloudPointCache cache =
                openMeteoService.prefetchCloudBatch(points, null);

        assertThat(cache.size()).isEqualTo(2);
        // Both nearby points should resolve to same cached data
        assertThat(cache.get(54.7753, -1.5849)).isSameAs(cache.get(54.7755, -1.5851));
    }

    @Test
    @DisplayName("prefetchCloudBatch() returns empty cache and makes no API call for empty input")
    void prefetchCloudBatch_emptyInput_noApiCall() {
        com.gregochr.goldenhour.model.CloudPointCache cache =
                openMeteoService.prefetchCloudBatch(List.of(), null);

        assertThat(cache.size()).isEqualTo(0);
        verify(openMeteoClient, never()).fetchCloudOnlyBatch(anyList());
    }

    @Test
    @DisplayName("prefetchCloudBatch() returns empty cache on API failure (graceful degradation)")
    void prefetchCloudBatch_apiFailure_returnsEmptyCache() {
        when(openMeteoClient.fetchCloudOnlyBatch(anyList()))
                .thenThrow(new RuntimeException("API down"));

        com.gregochr.goldenhour.model.CloudPointCache cache =
                openMeteoService.prefetchCloudBatch(
                        List.of(new double[]{54.77, -1.58}), null);

        assertThat(cache.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("prefetchCloudBatch() omits grid cells whose batch entry is null")
    void prefetchCloudBatch_nullEntry_omittedFromCache() {
        OpenMeteoForecastResponse r1 = buildCloudOnlyResponse(
                List.of("2026-06-21T06:00"), List.of(20), List.of(10), List.of(5));

        List<double[]> points = List.of(
                new double[]{54.77, -1.58},
                new double[]{55.90, -2.10});

        when(openMeteoClient.fetchCloudOnlyBatch(
                org.mockito.ArgumentMatchers.argThat(
                        (java.util.List<double[]> coords) -> coords.size() == 2)))
                .thenReturn(java.util.Arrays.asList(r1, null));

        com.gregochr.goldenhour.model.CloudPointCache cache =
                openMeteoService.prefetchCloudBatch(points, null);

        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.get(54.77, -1.58)).isSameAs(r1);
        assertThat(cache.get(55.90, -2.10)).isNull();
    }

    // --- fetchDirectionalCloudDataFromCache tests ---

    @Test
    @DisplayName("fetchDirectionalCloudDataFromCache() averages 3 solar cone points correctly")
    void fetchDirectionalCloudDataFromCache_averagesCone() {
        // Build responses: cone points have 30, 40, 50 low cloud → avg 40
        List<String> times = List.of("2026-06-21T20:00", "2026-06-21T21:00");
        OpenMeteoForecastResponse cone1 = buildCloudOnlyResponse(times,
                List.of(30, 30), List.of(10, 10), List.of(5, 5));
        OpenMeteoForecastResponse cone2 = buildCloudOnlyResponse(times,
                List.of(40, 40), List.of(20, 20), List.of(15, 15));
        OpenMeteoForecastResponse cone3 = buildCloudOnlyResponse(times,
                List.of(50, 50), List.of(30, 30), List.of(25, 25));
        OpenMeteoForecastResponse antisolar = buildCloudOnlyResponse(times,
                List.of(60, 60), List.of(70, 70), List.of(80, 80));
        OpenMeteoForecastResponse farSolar = buildCloudOnlyResponse(times,
                List.of(90, 90), List.of(5, 5), List.of(5, 5));

        // Compute the actual points for azimuth 270 so we know their grid keys
        List<double[]> points = openMeteoService.computeDirectionalCloudPoints(
                54.77, -1.58, 270);
        java.util.Map<String, OpenMeteoForecastResponse> map = new java.util.LinkedHashMap<>();
        map.put(com.gregochr.goldenhour.model.CloudPointCache.gridKey(
                points.get(0)[0], points.get(0)[1]), cone1);
        map.put(com.gregochr.goldenhour.model.CloudPointCache.gridKey(
                points.get(1)[0], points.get(1)[1]), cone2);
        map.put(com.gregochr.goldenhour.model.CloudPointCache.gridKey(
                points.get(2)[0], points.get(2)[1]), cone3);
        map.put(com.gregochr.goldenhour.model.CloudPointCache.gridKey(
                points.get(3)[0], points.get(3)[1]), antisolar);
        map.put(com.gregochr.goldenhour.model.CloudPointCache.gridKey(
                points.get(4)[0], points.get(4)[1]), farSolar);

        var cloudCache = new com.gregochr.goldenhour.model.CloudPointCache(map);
        LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 47);

        var result = openMeteoService.fetchDirectionalCloudDataFromCache(
                54.77, -1.58, 270, eventTime, TargetType.SUNSET, cloudCache);

        assertThat(result).isNotNull();
        // Cone average: (30+40+50)/3 = 40 low, (10+20+30)/3 = 20 mid, (5+15+25)/3 = 15 high
        // MathMutator (×3 instead of /3) would give 360, 180, 135 — asserting all three kills it
        assertThat(result.solarLowCloudPercent()).isEqualTo(40);
        assertThat(result.solarMidCloudPercent()).isEqualTo(20);
        assertThat(result.solarHighCloudPercent()).isEqualTo(15);
        assertThat(result.antisolarLowCloudPercent()).isEqualTo(60);
        assertThat(result.farSolarLowCloudPercent()).isEqualTo(90);
    }

    @Test
    @DisplayName("fetchDirectionalCloudDataFromCache() returns null when solar cone point missing")
    void fetchDirectionalCloudDataFromCache_cacheMiss_returnsNull() {
        // Empty cache — no points available
        var cloudCache = new com.gregochr.goldenhour.model.CloudPointCache(java.util.Map.of());
        LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 47);

        var result = openMeteoService.fetchDirectionalCloudDataFromCache(
                54.77, -1.58, 270, eventTime, TargetType.SUNSET, cloudCache);

        assertThat(result).isNull();
    }

    // --- API call logging tests ---

    /**
     * Verifies that logApiCall is invoked whenever a non-null jobRun is supplied.
     * Kills RemoveConditionalMutator on all {@code if (jobRun != null)} guards and
     * VoidMethodCallMutator on the logApiCall call sites.
     */
    @Nested
    @DisplayName("API call logging with non-null jobRun")
    class ApiCallLoggingTests {

        private final JobRunEntity jobRun = JobRunEntity.builder()
                .id(1L).runType(RunType.BRIEFING).build();
        private final LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 0);
        private final ForecastRequest request = new ForecastRequest(
                55.0, -1.5, "Test", LocalDate.of(2026, 6, 21), TargetType.SUNSET);

        private OpenMeteoForecastResponse singleSlotForecast() {
            return buildForecastResponse(
                    List.of("2026-06-21T20:00"),
                    List.of(10), List.of(20), List.of(30),
                    List.of(20000.0), List.of(4.0), List.of(225),
                    List.of(0.0), List.of(1), List.of(60),
                    List.of(1000.0), List.of(100.0));
        }

        private OpenMeteoAirQualityResponse singleSlotAq() {
            return buildAirQualityResponse(
                    List.of("2026-06-21T20:00"),
                    List.of(3.0), List.of(0.5), List.of(0.05));
        }

        private OpenMeteoForecastResponse singleSlotCloud() {
            return buildCloudOnlyResponse(
                    List.of("2026-06-21T20:00"), List.of(10), List.of(10), List.of(10));
        }

        @Test
        @DisplayName("getAtmosphericDataWithResponse success logs both forecast and air-quality calls")
        void getAtmosphericDataWithResponse_success_logsBothApiCalls() {
            when(openMeteoClient.fetchForecast(anyDouble(), anyDouble()))
                    .thenReturn(singleSlotForecast());
            when(openMeteoClient.fetchAirQuality(anyDouble(), anyDouble()))
                    .thenReturn(singleSlotAq());

            openMeteoService.getAtmosphericDataWithResponse(request, eventTime, jobRun);

            verify(jobRunService).logApiCall(eq(1L), eq(ServiceName.OPEN_METEO_FORECAST),
                    any(), any(), any(), anyLong(), any(), any(), eq(true), any());
            verify(jobRunService).logApiCall(eq(1L), eq(ServiceName.OPEN_METEO_AIR_QUALITY),
                    any(), any(), any(), anyLong(), any(), any(), eq(true), any());
        }

        @Test
        @DisplayName("getAtmosphericDataWithResponse failure logs forecast call with success=false")
        void getAtmosphericDataWithResponse_failure_logsApiCallFailure() {
            when(openMeteoClient.fetchForecast(anyDouble(), anyDouble()))
                    .thenThrow(new RuntimeException("timeout"));

            assertThatThrownBy(() ->
                    openMeteoService.getAtmosphericDataWithResponse(request, eventTime, jobRun))
                    .isInstanceOf(RuntimeException.class);

            verify(jobRunService).logApiCall(eq(1L), eq(ServiceName.OPEN_METEO_FORECAST),
                    any(), any(), any(), anyLong(), any(), any(), eq(false), any());
        }

        @Test
        @DisplayName("prefetchWeatherBatch success logs forecast and air-quality batch calls")
        void prefetchWeatherBatch_success_logsBothBatchCalls() {
            when(openMeteoClient.fetchForecastBatch(anyList()))
                    .thenReturn(List.of(singleSlotForecast()));
            when(openMeteoClient.fetchAirQualityBatch(anyList()))
                    .thenReturn(List.of(singleSlotAq()));

            openMeteoService.prefetchWeatherBatch(List.of(new double[]{55.0, -1.5}), jobRun);

            verify(jobRunService).logApiCall(eq(1L), eq(ServiceName.OPEN_METEO_FORECAST),
                    any(), any(), any(), anyLong(), any(), any(), eq(true), any());
            verify(jobRunService).logApiCall(eq(1L), eq(ServiceName.OPEN_METEO_AIR_QUALITY),
                    any(), any(), any(), anyLong(), any(), any(), eq(true), any());
        }

        @Test
        @DisplayName("prefetchWeatherBatch failure logs forecast batch call with success=false")
        void prefetchWeatherBatch_failure_logsApiCallFailure() {
            when(openMeteoClient.fetchForecastBatch(anyList()))
                    .thenThrow(new RuntimeException("batch timeout"));

            assertThatThrownBy(() ->
                    openMeteoService.prefetchWeatherBatch(
                            List.of(new double[]{55.0, -1.5}), jobRun))
                    .isInstanceOf(RuntimeException.class);

            verify(jobRunService).logApiCall(eq(1L), eq(ServiceName.OPEN_METEO_FORECAST),
                    any(), any(), any(), anyLong(), any(), any(), eq(false), any());
        }

        @Test
        @DisplayName("getHourlyAtmosphericData success logs both forecast and air-quality calls")
        void getHourlyAtmosphericData_success_logsBothApiCalls() {
            when(openMeteoClient.fetchForecast(anyDouble(), anyDouble()))
                    .thenReturn(singleSlotForecast());
            when(openMeteoClient.fetchAirQuality(anyDouble(), anyDouble()))
                    .thenReturn(singleSlotAq());

            openMeteoService.getHourlyAtmosphericData(request,
                    LocalDateTime.of(2026, 6, 21, 20, 0),
                    LocalDateTime.of(2026, 6, 21, 20, 0), jobRun);

            verify(jobRunService).logApiCall(eq(1L), eq(ServiceName.OPEN_METEO_FORECAST),
                    any(), any(), any(), anyLong(), any(), any(), eq(true), any());
            verify(jobRunService).logApiCall(eq(1L), eq(ServiceName.OPEN_METEO_AIR_QUALITY),
                    any(), any(), any(), anyLong(), any(), any(), eq(true), any());
        }

        @Test
        @DisplayName("getHourlyAtmosphericData failure logs both calls with success=false")
        void getHourlyAtmosphericData_failure_logsBothApiCallsFailure() {
            when(openMeteoClient.fetchForecast(anyDouble(), anyDouble()))
                    .thenThrow(new RuntimeException("API down"));

            assertThatThrownBy(() ->
                    openMeteoService.getHourlyAtmosphericData(request,
                            LocalDateTime.of(2026, 6, 21, 20, 0),
                            LocalDateTime.of(2026, 6, 21, 20, 0), jobRun))
                    .isInstanceOf(RuntimeException.class);

            verify(jobRunService).logApiCall(eq(1L), eq(ServiceName.OPEN_METEO_FORECAST),
                    any(), any(), any(), anyLong(), any(), any(), eq(false), any());
            verify(jobRunService).logApiCall(eq(1L), eq(ServiceName.OPEN_METEO_AIR_QUALITY),
                    any(), any(), any(), anyLong(), any(), any(), eq(false), any());
        }

        @Test
        @DisplayName("fetchDirectionalCloudData success logs cloud batch call with success=true")
        void fetchDirectionalCloudData_success_logsApiCall() {
            OpenMeteoForecastResponse cloud = singleSlotCloud();
            when(openMeteoClient.fetchCloudOnlyBatch(anyList()))
                    .thenReturn(List.of(cloud, cloud, cloud, cloud, cloud));

            openMeteoService.fetchDirectionalCloudData(
                    55.0, -1.5, 270, eventTime, TargetType.SUNSET, jobRun);

            verify(jobRunService).logApiCall(eq(1L), eq(ServiceName.OPEN_METEO_FORECAST),
                    any(), any(), any(), anyLong(), any(), any(), eq(true), any());
        }

        @Test
        @DisplayName("fetchDirectionalCloudData failure logs cloud batch call with success=false")
        void fetchDirectionalCloudData_failure_logsApiCallFailure() {
            when(openMeteoClient.fetchCloudOnlyBatch(anyList()))
                    .thenThrow(new RuntimeException("cloud API error"));

            var result = openMeteoService.fetchDirectionalCloudData(
                    55.0, -1.5, 270, eventTime, TargetType.SUNSET, jobRun);

            assertThat(result).isNull();
            verify(jobRunService).logApiCall(eq(1L), eq(ServiceName.OPEN_METEO_FORECAST),
                    any(), any(), any(), anyLong(), any(), any(), eq(false), any());
        }

        @Test
        @DisplayName("fetchCloudApproachData success (no upwind) logs cloud-approach call with success=true")
        void fetchCloudApproachData_success_logsApiCall() {
            // windSpeedMs=0 → no upwind point → 1 coord in batch
            when(openMeteoClient.fetchCloudOnlyBatch(anyList()))
                    .thenReturn(List.of(singleSlotCloud()));

            var result = openMeteoService.fetchCloudApproachData(
                    55.0, -1.5, 270, eventTime,
                    LocalDateTime.of(2026, 6, 21, 19, 0),
                    TargetType.SUNSET, 270, 0.0, jobRun);

            assertThat(result).isNotNull();
            verify(jobRunService).logApiCall(eq(1L), eq(ServiceName.OPEN_METEO_FORECAST),
                    any(), any(), any(), anyLong(), any(), any(), eq(true), any());
        }

        @Test
        @DisplayName("fetchCloudApproachData failure logs cloud-approach call with success=false")
        void fetchCloudApproachData_failure_logsApiCallFailure() {
            when(openMeteoClient.fetchCloudOnlyBatch(anyList()))
                    .thenThrow(new RuntimeException("approach API error"));

            var result = openMeteoService.fetchCloudApproachData(
                    55.0, -1.5, 270, eventTime,
                    LocalDateTime.of(2026, 6, 21, 19, 0),
                    TargetType.SUNSET, 270, 0.0, jobRun);

            assertThat(result).isNull();
            verify(jobRunService).logApiCall(eq(1L), eq(ServiceName.OPEN_METEO_FORECAST),
                    any(), any(), any(), anyLong(), any(), any(), eq(false), any());
        }

        @Test
        @DisplayName("prefetchCloudBatch success logs cloud batch call with success=true")
        void prefetchCloudBatch_success_logsApiCall() {
            when(openMeteoClient.fetchCloudOnlyBatch(anyList()))
                    .thenReturn(List.of(singleSlotCloud()));

            openMeteoService.prefetchCloudBatch(List.of(new double[]{55.0, -1.5}), jobRun);

            verify(jobRunService).logApiCall(eq(1L), eq(ServiceName.OPEN_METEO_FORECAST),
                    any(), any(), any(), anyLong(), any(), any(), eq(true), any());
        }

        @Test
        @DisplayName("prefetchCloudBatch failure logs cloud batch call with success=false")
        void prefetchCloudBatch_failure_logsApiCallFailure() {
            when(openMeteoClient.fetchCloudOnlyBatch(anyList()))
                    .thenThrow(new RuntimeException("batch error"));

            // prefetchCloudBatch swallows the exception and returns an empty cache
            openMeteoService.prefetchCloudBatch(List.of(new double[]{55.0, -1.5}), jobRun);

            verify(jobRunService).logApiCall(eq(1L), eq(ServiceName.OPEN_METEO_FORECAST),
                    any(), any(), any(), anyLong(), any(), any(), eq(false), any());
        }
    }

    // --- computeUpwindPoint boundary tests ---

    /**
     * Kills ConditionalsBoundaryMutator on the {@code dist < MIN_UPWIND_DISTANCE_M} (5 km) guard.
     */
    @Nested
    @DisplayName("computeUpwindPoint boundary at MIN_UPWIND_DISTANCE_M (5 km)")
    class UpwindBoundaryTests {

        @Test
        @DisplayName("exactly 5 km upwind distance returns non-null (boundary: < not <=)")
        void computeUpwindPoint_exactlyMinDistance_returnsNonNull() {
            // 5.0 m/s × 1000 s = 5000 m = MIN_UPWIND_DISTANCE_M exactly
            // Real: 5000 < 5000 → false → returns offset point (non-null)
            // Mutant (<=): 5000 <= 5000 → true → returns null
            LocalDateTime now = LocalDateTime.of(2026, 6, 21, 19, 43, 20); // 1000s before event
            LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 0, 0);

            double[] result = openMeteoService.computeUpwindPoint(
                    54.77, -1.58, 270, 5.0, now, eventTime);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("just below 5 km upwind distance returns null")
        void computeUpwindPoint_justBelowMinDistance_returnsNull() {
            // 5.0 m/s × 999 s = 4995 m < 5000 → null
            LocalDateTime now = LocalDateTime.of(2026, 6, 21, 19, 43, 21); // 999s before event
            LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 0, 0);

            double[] result = openMeteoService.computeUpwindPoint(
                    54.77, -1.58, 270, 5.0, now, eventTime);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("fetchCloudApproachData at exactly 5 km includes upwind sample")
        void fetchCloudApproachData_exactlyMinUpwindDistance_includesUpwindSample() {
            // 5.0 m/s × 1000 s = 5000 m = MIN exactly; upwindDistanceM >= MIN → included
            // Mutant (> MIN): 5000 > 5000 → false → upwindPoint null → upwindSample null
            LocalDateTime currentTime = LocalDateTime.of(2026, 6, 21, 19, 43, 20);
            LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 0, 0);
            OpenMeteoForecastResponse cloud = buildCloudOnlyResponse(
                    List.of("2026-06-21T20:00"), List.of(15), List.of(10), List.of(5));

            // 2 coords (solar + upwind) → 2 responses
            when(openMeteoClient.fetchCloudOnlyBatch(
                    org.mockito.ArgumentMatchers.argThat(
                            (java.util.List<double[]> c) -> c != null && c.size() == 2)))
                    .thenReturn(java.util.Arrays.asList(cloud, cloud));

            var result = openMeteoService.fetchCloudApproachData(
                    55.0, -1.5, 270, eventTime, currentTime,
                    TargetType.SUNSET, 270, 5.0, null);

            assertThat(result).isNotNull();
            assertThat(result.upwindSample()).isNotNull();
        }
    }

    // --- upwind distance arithmetic test ---

    /**
     * Kills MathMutator on the {@code (int) (upwindDistanceM / 1000)} division
     * in {@code fetchCloudApproachData}.
     */
    @Test
    @DisplayName("fetchCloudApproachData passes distanceKm = upwindDistanceM / 1000 (not multiplied)")
    void fetchCloudApproachData_distanceKmIsDistanceDividedBy1000() {
        // 10.0 m/s × 5000 s = 50 000 m → distanceKm should be 50, not 50 000 000
        LocalDateTime currentTime = LocalDateTime.of(2026, 6, 21, 18, 36, 40);
        LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 0, 0); // 5000 s later
        OpenMeteoForecastResponse cloud = buildCloudOnlyResponse(
                List.of("2026-06-21T20:00"), List.of(20), List.of(10), List.of(5));

        when(openMeteoClient.fetchCloudOnlyBatch(anyList()))
                .thenReturn(java.util.Arrays.asList(cloud, cloud));

        var result = openMeteoService.fetchCloudApproachData(
                55.0, -1.5, 270, eventTime, currentTime,
                TargetType.SUNSET, 270, 10.0, null);

        assertThat(result).isNotNull();
        assertThat(result.upwindSample()).isNotNull();
        assertThat(result.upwindSample().distanceKm()).isEqualTo(50);
    }

    // --- findNearestIndex correctness via extractUpwindSample ---

    /**
     * Kills BooleanReturnValsMutator / IncrementsMutator on {@code return bestIdx} in
     * {@code findNearestIndex}: a mutant that always returns 0 would pick the wrong cloud value.
     */
    @Test
    @DisplayName("extractUpwindSample uses findNearestIndex to pick current-time cloud (not slot 0)")
    void extractUpwindSample_findNearestIndex_picksCorrectCurrentTimeSlot() {
        // Three hourly slots; event at T18:00 (idx 0), current time at T20:00 (idx 2)
        // currentLowCloud = slot[2] = 30; eventLowCloud = slot[0] = 10
        // Mutant (findNearestIndex always returns 0): currentLowCloud = slot[0] = 10
        List<String> times = List.of(
                "2026-06-21T18:00", "2026-06-21T19:00", "2026-06-21T20:00");
        OpenMeteoForecastResponse forecast = buildCloudOnlyResponse(
                times, List.of(10, 20, 30), List.of(0, 0, 0), List.of(0, 0, 0));

        LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 18, 0);
        LocalDateTime currentTime = LocalDateTime.of(2026, 6, 21, 20, 0);

        UpwindCloudSample result = openMeteoService.extractUpwindSample(
                forecast, eventTime, currentTime, TargetType.SUNSET, 50, 270);

        assertThat(result.currentLowCloudPercent()).isEqualTo(30);
        assertThat(result.eventLowCloudPercent()).isEqualTo(10);
    }

    // --- extractMistTrend tests ---

    /**
     * Kills RemoveConditionalMutator on the null-check guard in {@code extractMistTrend}
     * and the boundary mutant on {@code idx >= 0}.
     */
    @Nested
    @DisplayName("extractMistTrend boundary and null-guard tests")
    class ExtractMistTrendTests {

        @Test
        @DisplayName("null visibility returns null (kills RemoveConditionalMutator on null guard)")
        void extractMistTrend_nullVisibility_returnsNull() {
            // Build a forecast with null visibility so the null guard fires
            // Mutant (removes null check): proceeds, tries vis.size() on null → NPE
            OpenMeteoForecastResponse forecast = buildForecastResponse(
                    List.of("2026-06-21T20:00"),
                    List.of(10), List.of(20), List.of(30),
                    List.of(20000.0), List.of(4.0), List.of(225),
                    List.of(0.0), List.of(1), List.of(60),
                    List.of(1000.0), List.of(100.0),
                    List.of(14.0), List.of(12.0), null, List.of(5.0));
            forecast.getHourly().setVisibility(null);

            MistTrend result = openMeteoService.extractMistTrend(forecast.getHourly(), 0);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("single slot at eventIdx=0 returns non-null (kills ConditionalsBoundaryMutator on idx>=0)")
        void extractMistTrend_singleSlotAtEventIdx0_returnsNonNull() {
            // eventIdx=0, offsets -3,-2,-1 are out of bounds; offset=0 gives idx=0
            // Real: 0 >= 0 → true → slot added → non-null
            // Mutant (> instead of >=): 0 > 0 → false → slot skipped → empty → null
            OpenMeteoForecastResponse forecast = buildForecastResponse(
                    List.of("2026-06-21T20:00"),
                    List.of(10), List.of(20), List.of(30),
                    List.of(20000.0), List.of(4.0), List.of(225),
                    List.of(0.0), List.of(1), List.of(60),
                    List.of(1000.0), List.of(100.0),
                    List.of(14.0), List.of(12.0), null, List.of(5.0));

            MistTrend result = openMeteoService.extractMistTrend(forecast.getHourly(), 0);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("all dew values null produces empty slots and returns null")
        void extractMistTrend_nullDewValues_emptySlots_returnsNull() {
            // dew list contains one null element → dewVal == null for each valid slot
            // Real: slots.isEmpty() = true → return null
            // Mutant (isEmpty always false): returns new MistTrend(empty list), i.e. non-null
            OpenMeteoForecastResponse forecast = buildForecastResponse(
                    List.of("2026-06-21T20:00"),
                    List.of(10), List.of(20), List.of(30),
                    List.of(20000.0), List.of(4.0), List.of(225),
                    List.of(0.0), List.of(1), List.of(60),
                    List.of(1000.0), List.of(100.0),
                    List.of(14.0), List.of(12.0), null, null);
            java.util.List<Double> dewWithNull = new java.util.ArrayList<>();
            dewWithNull.add(null);
            forecast.getHourly().setDewPoint2m(dewWithNull);

            MistTrend result = openMeteoService.extractMistTrend(forecast.getHourly(), 0);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("null temperature returns null (kills RemoveConditionalMutator on temp==null)")
        void extractMistTrend_nullTemperature_returnsNull() {
            // vis and dew are non-null; only temp is null
            // Real: temp==null → return null
            // Mutant (temp==null check → false): skips null guard, enters loop,
            //   calls temp.size() on null → NPE → test fails (no null return) → KILLED
            OpenMeteoForecastResponse forecast = buildForecastResponse(
                    List.of("2026-06-21T20:00"),
                    List.of(10), List.of(20), List.of(30),
                    List.of(20000.0), List.of(4.0), List.of(225),
                    List.of(0.0), List.of(1), List.of(60),
                    List.of(1000.0), List.of(100.0),
                    null,           // temperature2m = null
                    null, null, List.of(5.0)); // dewPoint2m non-null
            // vis and dew are now non-null; clear temp explicitly
            forecast.getHourly().setTemperature2m(null);

            MistTrend result = openMeteoService.extractMistTrend(forecast.getHourly(), 0);

            assertThat(result).isNull();
        }
    }

    // --- extractSolarTrend tests ---

    /**
     * Kills ConditionalsBoundaryMutator on {@code idx >= 0} in {@code extractSolarTrend}.
     */
    @Test
    @DisplayName("extractSolarTrend with single slot at eventIdx=0 returns non-null")
    void extractSolarTrend_singleSlotAtEventIdx0_returnsNonNull() {
        // TREND_HOURS_BACK=3: h=3,2,1 give idx=-3,-2,-1 (skipped); h=0 gives idx=0
        // Real: 0 >= 0 → true → slot added → non-null
        // Mutant (> instead of >=): 0 > 0 → false → slot skipped → empty → null
        OpenMeteoForecastResponse cloud = buildCloudOnlyResponse(
                List.of("2026-06-21T20:00"), List.of(40), List.of(10), List.of(5));

        SolarCloudTrend result = openMeteoService.extractSolarTrend(
                cloud, LocalDateTime.of(2026, 6, 21, 20, 0), TargetType.SUNSET);

        assertThat(result).isNotNull();
    }

    /**
     * Kills RemoveConditionalMutator on {@code return slots.isEmpty() ? null : new SolarCloudTrend(slots)}.
     *
     * <p>When {@code lowCloud} is an empty list, every candidate {@code idx} fails the
     * {@code idx < lowCloud.size()} guard so {@code slots} stays empty.  Real code returns
     * {@code null}.  The EQUAL_ELSE mutant replaces {@code slots.isEmpty()} with {@code false},
     * forcing the branch to always return {@code new SolarCloudTrend(slots)} — non-null —
     * which fails the assertion.
     */
    @Test
    @DisplayName("extractSolarTrend with empty low-cloud list returns null (no valid slots)")
    void extractSolarTrend_emptyLowCloudList_returnsNull() {
        // time has one entry so findBestIndex returns 0; lowCloud is empty so
        // idx(0) < lowCloud.size()(0) is false — no slot is added → slots is empty
        // Real: null.  Mutant (isEmpty→false): returns SolarCloudTrend(emptyList) ≠ null → KILLED
        OpenMeteoForecastResponse cloud = buildCloudOnlyResponse(
                List.of("2026-06-21T20:00"),
                List.of(), List.of(), List.of());

        SolarCloudTrend result = openMeteoService.extractSolarTrend(
                cloud, LocalDateTime.of(2026, 6, 21, 20, 0), TargetType.SUNSET);

        assertThat(result).isNull();
    }

    // --- fetchCloudApproachDataFromCache tests ---

    /**
     * Kills NullReturnValsMutator on {@code return new CloudApproachData(trend, upwind)} and
     * NullReturnValsMutator on {@code computeSolarHorizonPoint}.
     */
    @Test
    @DisplayName("fetchCloudApproachDataFromCache returns non-null when solar point is in cache")
    void fetchCloudApproachDataFromCache_solarPointInCache_returnsNonNull() {
        // Build a cache containing the exact grid cell for the solar horizon point
        double[] solarPoint = openMeteoService.computeSolarHorizonPoint(55.0, -1.5, 270);
        String key = com.gregochr.goldenhour.model.CloudPointCache.gridKey(
                solarPoint[0], solarPoint[1]);
        OpenMeteoForecastResponse cloud = buildCloudOnlyResponse(
                List.of("2026-06-21T20:00"), List.of(15), List.of(10), List.of(5));
        var cacheMap = new java.util.LinkedHashMap<String,
                com.gregochr.goldenhour.model.OpenMeteoForecastResponse>();
        cacheMap.put(key, cloud);
        var cloudCache = new com.gregochr.goldenhour.model.CloudPointCache(cacheMap);

        var result = openMeteoService.fetchCloudApproachDataFromCache(
                55.0, -1.5, 270,
                LocalDateTime.of(2026, 6, 21, 20, 0),
                LocalDateTime.of(2026, 6, 21, 19, 0),
                TargetType.SUNSET, 270, 0.0, cloudCache);

        assertThat(result).isNotNull();
    }

    // --- utility boundary tests ---

    /**
     * Kills ConditionalsBoundaryMutator on the {@code idx >= values.size()} guards in
     * getAirQualityValue, getDoubleValue, and getIntegerValue.
     *
     * <p>When idx equals list.size() exactly (one past the end), real code returns
     * null/defaults; the {@code >} mutant proceeds to get(idx) which throws IOOBE.
     */
    @Test
    @DisplayName("extractAtmosphericData gracefully handles idx == list.size() for optional fields")
    void extractAtmosphericData_idxAtListSizeExactly_returnsDefaults() {
        // 3 forecast slots; event at T08:00 → idx = 2
        // AQ lists have only 2 entries → idx (2) == list.size() (2): boundary triggers
        // dewPoint2m and precipProbability also have only 2 entries
        // Mutant (> instead of >=): get(2) on size-2 list → IndexOutOfBoundsException
        OpenMeteoForecastResponse forecast = buildForecastResponse(
                List.of("2026-06-21T06:00", "2026-06-21T07:00", "2026-06-21T08:00"),
                List.of(5, 10, 15), List.of(20, 25, 30), List.of(10, 15, 20),
                List.of(20000.0, 20000.0, 20000.0), List.of(4.0, 4.0, 4.0),
                List.of(225, 225, 225),
                List.of(0.0, 0.0, 0.0), List.of(1, 1, 1), List.of(60, 60, 60),
                List.of(1000.0, 1000.0, 1000.0), List.of(100.0, 100.0, 100.0),
                List.of(14.0, 15.0),          // temperature: 2 entries, idx=2 → null
                null,                          // apparentTemperature
                List.of(30, 40),               // precipProbability: 2 entries, idx=2 → null
                List.of(5.0, 6.0));            // dewPoint2m: 2 entries, idx=2 → null

        // AQ has only 2 entries — idx=2 equals list.size() exactly
        OpenMeteoAirQualityResponse aq = buildAirQualityResponse(
                List.of("2026-06-21T06:00", "2026-06-21T07:00"),
                List.of(5.0, 6.0), List.of(1.0, 1.5), List.of(0.05, 0.06));

        AtmosphericData result = openMeteoService.extractAtmosphericData(
                forecast, aq, "Test", LocalDateTime.of(2026, 6, 21, 8, 0),
                TargetType.SUNSET);

        // AQ values fall back to zero (getAirQualityValue returns null → toDecimal(null) = ZERO)
        assertThat(result.aerosol().pm25()).isEqualByComparingTo("0.00");
        assertThat(result.aerosol().dustUgm3()).isEqualByComparingTo("0.00");
        assertThat(result.aerosol().aerosolOpticalDepth()).isEqualByComparingTo("0.000");
        // precipProbability and temperature fall back to null (getIntegerValue / getDoubleValue)
        assertThat(result.comfort().precipitationProbability()).isNull();
        assertThat(result.comfort().temperatureCelsius()).isNull();
    }

    // --- extractPressureTrend tests ---

    @Nested
    @DisplayName("extractPressureTrend")
    class ExtractPressureTrendTests {

        @Test
        @DisplayName("event at index 12 with 120 values extracts 7 values (indices 9-15)")
        void extractPressureTrend_midArray_extractsSevenValues() {
            OpenMeteoForecastResponse.Hourly h = new OpenMeteoForecastResponse.Hourly();
            // Build 120 hourly pressure values — linear ramp from 1010 to 1012
            List<Double> pressures = new java.util.ArrayList<>();
            for (int i = 0; i < 120; i++) {
                pressures.add(1010.0 + (i * 0.02));
            }
            h.setPressureMsl(pressures);

            com.gregochr.goldenhour.model.PressureTrend result =
                    openMeteoService.extractPressureTrend(h, 12);

            assertThat(result).isNotNull();
            assertThat(result.pressureHpa()).hasSize(7);
            // indices 9-15: pressures[9]=1010.18, pressures[15]=1010.30
            var tolerance = org.assertj.core.data.Offset.offset(0.001);
            assertThat(result.pressureHpa().getFirst())
                    .isCloseTo(pressures.get(9), tolerance);
            assertThat(result.pressureHpa().getLast())
                    .isCloseTo(pressures.get(15), tolerance);
            // tendency = pressures[15] - pressures[9] = 0.12 → STEADY
            assertThat(result.tendencyHpa6h()).isCloseTo(0.12, org.assertj.core.data.Offset.offset(0.001));
            assertThat(result.tendencyLabel()).isEqualTo("STEADY");
        }

        @Test
        @DisplayName("event at index 1 (near start) handles boundary without exception")
        void extractPressureTrend_nearStart_noBoundaryError() {
            OpenMeteoForecastResponse.Hourly h = new OpenMeteoForecastResponse.Hourly();
            h.setPressureMsl(List.of(1015.0, 1014.5, 1014.0, 1013.5, 1013.0));

            com.gregochr.goldenhour.model.PressureTrend result =
                    openMeteoService.extractPressureTrend(h, 1);

            assertThat(result).isNotNull();
            // T-3h clamps to index 0; T+3h = 4; extracts indices 0,0,0,1,2,3,4
            assertThat(result.pressureHpa()).hasSize(7);
        }

        @Test
        @DisplayName("tendency -4.2 hPa produces FALLING_RAPIDLY label")
        void extractPressureTrend_fallingRapidly() {
            OpenMeteoForecastResponse.Hourly h = new OpenMeteoForecastResponse.Hourly();
            // Falling from 1020 to 1015.8 over 7 slots
            h.setPressureMsl(List.of(1020.0, 1019.3, 1018.6, 1017.9, 1017.2, 1016.5, 1015.8));

            com.gregochr.goldenhour.model.PressureTrend result =
                    openMeteoService.extractPressureTrend(h, 3);

            // tendency = 1015.8 - 1020.0 = -4.2
            assertThat(result.tendencyHpa6h()).isCloseTo(-4.2, org.assertj.core.data.Offset.offset(0.001));
            assertThat(result.tendencyLabel()).isEqualTo("FALLING_RAPIDLY");
        }

        @Test
        @DisplayName("tendency -2.1 hPa produces FALLING label")
        void extractPressureTrend_falling() {
            OpenMeteoForecastResponse.Hourly h = new OpenMeteoForecastResponse.Hourly();
            h.setPressureMsl(List.of(1020.0, 1019.65, 1019.3, 1018.95, 1018.6, 1018.25, 1017.9));

            com.gregochr.goldenhour.model.PressureTrend result =
                    openMeteoService.extractPressureTrend(h, 3);

            assertThat(result.tendencyHpa6h()).isCloseTo(-2.1, org.assertj.core.data.Offset.offset(0.001));
            assertThat(result.tendencyLabel()).isEqualTo("FALLING");
        }

        @Test
        @DisplayName("tendency +0.3 hPa produces STEADY label")
        void extractPressureTrend_steady() {
            OpenMeteoForecastResponse.Hourly h = new OpenMeteoForecastResponse.Hourly();
            h.setPressureMsl(List.of(1013.0, 1013.05, 1013.1, 1013.15, 1013.2, 1013.25, 1013.3));

            com.gregochr.goldenhour.model.PressureTrend result =
                    openMeteoService.extractPressureTrend(h, 3);

            assertThat(result.tendencyHpa6h()).isCloseTo(0.3, org.assertj.core.data.Offset.offset(0.001));
            assertThat(result.tendencyLabel()).isEqualTo("STEADY");
        }

        @Test
        @DisplayName("tendency +1.8 hPa produces RISING label")
        void extractPressureTrend_rising() {
            OpenMeteoForecastResponse.Hourly h = new OpenMeteoForecastResponse.Hourly();
            h.setPressureMsl(List.of(1010.0, 1010.3, 1010.6, 1010.9, 1011.2, 1011.5, 1011.8));

            com.gregochr.goldenhour.model.PressureTrend result =
                    openMeteoService.extractPressureTrend(h, 3);

            assertThat(result.tendencyHpa6h()).isCloseTo(1.8, org.assertj.core.data.Offset.offset(0.001));
            assertThat(result.tendencyLabel()).isEqualTo("RISING");
        }

        @Test
        @DisplayName("null pressureMsl returns null without exception")
        void extractPressureTrend_nullPressure_returnsNull() {
            OpenMeteoForecastResponse.Hourly h = new OpenMeteoForecastResponse.Hourly();
            h.setPressureMsl(null);

            com.gregochr.goldenhour.model.PressureTrend result =
                    openMeteoService.extractPressureTrend(h, 0);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("empty pressureMsl list returns null")
        void extractPressureTrend_emptyList_returnsNull() {
            OpenMeteoForecastResponse.Hourly h = new OpenMeteoForecastResponse.Hourly();
            h.setPressureMsl(List.of());

            com.gregochr.goldenhour.model.PressureTrend result =
                    openMeteoService.extractPressureTrend(h, 0);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("event at last index clamps T+3 to array end")
        void extractPressureTrend_lastIndex_clampsToEnd() {
            OpenMeteoForecastResponse.Hourly h = new OpenMeteoForecastResponse.Hourly();
            // 10 values; event at index 9 (last)
            List<Double> pressures = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                pressures.add(1010.0 + i * 0.5);
            }
            h.setPressureMsl(pressures);

            com.gregochr.goldenhour.model.PressureTrend result =
                    openMeteoService.extractPressureTrend(h, 9);

            assertThat(result).isNotNull();
            // T-3h = index 6, T+3h clamps to index 9
            // indices 6,7,8,9,9,9,9 → 7 values collected
            assertThat(result.pressureHpa()).hasSize(7);
            // last value clamped to pressures[9]
            assertThat(result.pressureHpa().getLast())
                    .isCloseTo(pressures.get(9),
                            org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        @DisplayName("all null values in pressureMsl list returns null")
        void extractPressureTrend_allNullValues_returnsNull() {
            OpenMeteoForecastResponse.Hourly h = new OpenMeteoForecastResponse.Hourly();
            List<Double> pressures = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                pressures.add(null);
            }
            h.setPressureMsl(pressures);

            com.gregochr.goldenhour.model.PressureTrend result =
                    openMeteoService.extractPressureTrend(h, 5);

            assertThat(result).isNull();
        }
    }
}
