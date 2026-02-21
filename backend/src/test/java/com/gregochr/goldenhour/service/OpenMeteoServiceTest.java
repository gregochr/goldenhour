package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OpenMeteoService}.
 *
 * <p>Tests cover the data extraction logic ({@code extractAtmosphericData}) with static
 * response objects. The actual HTTP calls to the Open-Meteo APIs are not tested here.
 */
@ExtendWith(MockitoExtension.class)
class OpenMeteoServiceTest {

    @Mock
    private WebClient webClient;

    private OpenMeteoService openMeteoService;

    @BeforeEach
    void setUp() {
        openMeteoService = new OpenMeteoService(webClient);
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

    private OpenMeteoForecastResponse buildForecastResponse(
            List<String> time,
            List<Integer> cloudLow, List<Integer> cloudMid, List<Integer> cloudHigh,
            List<Double> visibility, List<Double> windSpeed, List<Integer> windDir,
            List<Double> precip, List<Integer> weatherCode, List<Integer> humidity,
            List<Double> boundaryLayer, List<Double> shortwave) {

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
