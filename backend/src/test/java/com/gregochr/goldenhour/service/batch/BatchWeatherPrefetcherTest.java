package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.service.OpenMeteoService;
import com.gregochr.goldenhour.service.SolarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BatchWeatherPrefetcher}, covering the upwind cloud-sampling branch of
 * {@code prefetchBatchCloudPoints} that the collector-level tests do not exercise (they use a
 * forecast response with a null {@code Hourly}, so no wind data is available).
 */
@ExtendWith(MockitoExtension.class)
class BatchWeatherPrefetcherTest {

    @Mock
    private OpenMeteoService openMeteoService;

    @Mock
    private SolarService solarService;

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-21T12:00:00Z"), ZoneOffset.UTC);

    private BatchWeatherPrefetcher prefetcher;

    @BeforeEach
    void setUp() {
        prefetcher = new BatchWeatherPrefetcher(openMeteoService, solarService, clock);
    }

    @Test
    @DisplayName("prefetchBatchCloudPoints adds an upwind sample point when prefetched weather carries wind data")
    void prefetchBatchCloudPoints_withWindData_addsUpwindPoint() {
        double lat = 55.0;
        double lon = -1.5;
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocationEntity loc = new LocationEntity();
        loc.setId(1L);
        loc.setName("Bamburgh");
        loc.setLat(lat);
        loc.setLon(lon);
        ForecastCandidate candidate = new ForecastCandidate(loc, date, TargetType.SUNSET);

        OpenMeteoForecastResponse forecast = new OpenMeteoForecastResponse();
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
        hourly.setTime(List.of("2026-06-21T20:00"));
        hourly.setWindDirection10m(List.of(270));
        hourly.setWindSpeed10m(List.of(5.0));
        forecast.setHourly(hourly);
        Map<String, WeatherExtractionResult> prefetchedWeather = Map.of(
                OpenMeteoService.coordKey(lat, lon),
                new WeatherExtractionResult(null, forecast, new OpenMeteoAirQualityResponse()));

        LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 0);
        double[] directionalPoint = {55.1, -1.5};
        double[] upwindPoint = {55.3, -1.5};
        when(solarService.sunsetAzimuthDeg(lat, lon, date)).thenReturn(250);
        when(solarService.sunsetUtc(lat, lon, date)).thenReturn(eventTime);
        when(openMeteoService.computeDirectionalCloudPoints(lat, lon, 250))
                .thenReturn(new ArrayList<>(List.of(directionalPoint)));
        when(openMeteoService.computeUpwindPoint(eq(lat), eq(lon), eq(270), eq(5.0),
                any(LocalDateTime.class), eq(eventTime))).thenReturn(upwindPoint);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<double[]>> pointsCaptor = ArgumentCaptor.forClass(List.class);
        when(openMeteoService.prefetchCloudBatch(pointsCaptor.capture(), isNull()))
                .thenReturn(new CloudPointCache(Map.of()));

        prefetcher.prefetchBatchCloudPoints(List.of(candidate), prefetchedWeather);

        // The upwind branch executed: wind read from the prefetched Hourly and an upwind point added.
        verify(openMeteoService).computeUpwindPoint(eq(lat), eq(lon), eq(270), eq(5.0),
                any(LocalDateTime.class), eq(eventTime));
        assertThat(pointsCaptor.getValue()).contains(directionalPoint, upwindPoint);
    }

    @Test
    @DisplayName("prefetchBatchCloudPoints skips the upwind sample when no prefetched weather is supplied")
    void prefetchBatchCloudPoints_noPrefetchedWeather_directionalOnly() {
        double lat = 55.0;
        double lon = -1.5;
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocationEntity loc = new LocationEntity();
        loc.setId(1L);
        loc.setName("Bamburgh");
        loc.setLat(lat);
        loc.setLon(lon);
        ForecastCandidate candidate = new ForecastCandidate(loc, date, TargetType.SUNSET);

        double[] directionalPoint = {55.1, -1.5};
        when(solarService.sunsetAzimuthDeg(lat, lon, date)).thenReturn(250);
        when(openMeteoService.computeDirectionalCloudPoints(lat, lon, 250))
                .thenReturn(new ArrayList<>(List.of(directionalPoint)));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<double[]>> pointsCaptor = ArgumentCaptor.forClass(List.class);
        when(openMeteoService.prefetchCloudBatch(pointsCaptor.capture(), isNull()))
                .thenReturn(new CloudPointCache(Map.of()));

        prefetcher.prefetchBatchCloudPoints(List.of(candidate), null);

        assertThat(pointsCaptor.getValue()).containsExactly(directionalPoint);
        verify(openMeteoService, org.mockito.Mockito.never())
                .computeUpwindPoint(anyDouble(), anyDouble(), anyInt(), anyDouble(),
                        any(LocalDateTime.class), any(LocalDateTime.class));
    }
}
