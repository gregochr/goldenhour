package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.PrefetchCall;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.service.DirectionalSamplingGeometry;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 21, 12, 0);

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
        when(openMeteoService.prefetchCloudBatch(pointsCaptor.capture(), anyList(), any()))
                .thenReturn(new CloudPointCache(Map.of()));

        prefetcher.prefetchBatchCloudPoints(List.of(candidate), prefetchedWeather, call -> { });

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
        when(openMeteoService.prefetchCloudBatch(pointsCaptor.capture(), anyList(), any()))
                .thenReturn(new CloudPointCache(Map.of()));

        prefetcher.prefetchBatchCloudPoints(List.of(candidate), null, call -> { });

        assertThat(pointsCaptor.getValue()).containsExactly(directionalPoint);
        verify(openMeteoService, org.mockito.Mockito.never())
                .computeUpwindPoint(anyDouble(), anyDouble(), anyInt(), anyDouble(),
                        any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("prefetchBatchCloudPoints passes one far-solar point per candidate as the far list")
    void prefetchBatchCloudPoints_passesFarSolarPointPerCandidate() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocationEntity a = new LocationEntity();
        a.setId(1L);
        a.setName("Bamburgh");
        a.setLat(55.0);
        a.setLon(-1.5);
        LocationEntity b = new LocationEntity();
        b.setId(2L);
        b.setName("Durham");
        b.setLat(54.8);
        b.setLon(-1.6);
        List<double[]> pointsA = fivePoints(55.0);
        List<double[]> pointsB = fivePoints(54.0);
        when(solarService.sunsetAzimuthDeg(55.0, -1.5, date)).thenReturn(250);
        when(solarService.sunsetAzimuthDeg(54.8, -1.6, date)).thenReturn(251);
        when(openMeteoService.computeDirectionalCloudPoints(55.0, -1.5, 250)).thenReturn(pointsA);
        when(openMeteoService.computeDirectionalCloudPoints(54.8, -1.6, 251)).thenReturn(pointsB);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<double[]>> farCaptor = ArgumentCaptor.forClass(List.class);
        when(openMeteoService.prefetchCloudBatch(anyList(), farCaptor.capture(), any()))
                .thenReturn(new CloudPointCache(Map.of()));

        prefetcher.prefetchBatchCloudPoints(List.of(
                new ForecastCandidate(a, date, TargetType.SUNSET),
                new ForecastCandidate(b, date, TargetType.SUNSET)), null, call -> { });

        assertThat(farCaptor.getValue()).hasSize(2);
        assertThat(farCaptor.getValue().get(0))
                .isSameAs(pointsA.get(DirectionalSamplingGeometry.FAR_SOLAR_POINT_INDEX));
        assertThat(farCaptor.getValue().get(1))
                .isSameAs(pointsB.get(DirectionalSamplingGeometry.FAR_SOLAR_POINT_INDEX));
    }

    private static LocationEntity located(long id, double lat, double lon, Double gridLat, Double gridLng) {
        LocationEntity loc = new LocationEntity();
        loc.setId(id);
        loc.setName("Loc" + id);
        loc.setLat(lat);
        loc.setLon(lon);
        loc.setGridLat(gridLat);
        loc.setGridLng(gridLng);
        return loc;
    }

    private static WeatherExtractionResult windyResult() {
        OpenMeteoForecastResponse forecast = new OpenMeteoForecastResponse();
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
        hourly.setTime(List.of("2026-06-21T20:00"));
        hourly.setWindDirection10m(List.of(270));
        hourly.setWindSpeed10m(List.of(5.0));
        forecast.setHourly(hourly);
        return new WeatherExtractionResult(null, forecast, new OpenMeteoAirQualityResponse());
    }

    @Test
    @DisplayName("prefetchBatchWeather fetches once per cell and per cell-less location, fanning out to all")
    void prefetchBatchWeather_dedupesByGridCell_andFansOut() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocationEntity a = located(1L, 55.0, -1.5, 55.01, -1.51);
        LocationEntity b = located(2L, 55.004, -1.502, 55.01, -1.51);
        LocationEntity c = located(3L, 54.0, -2.0, 54.02, -2.02);
        LocationEntity d = located(4L, 53.0, -3.0, null, null);
        LocationEntity e = located(5L, 53.5, -3.5, null, null);
        List<ForecastCandidate> candidates = List.of(
                new ForecastCandidate(a, date, TargetType.SUNRISE),
                new ForecastCandidate(a, date, TargetType.SUNSET),
                new ForecastCandidate(b, date, TargetType.SUNSET),
                new ForecastCandidate(c, date, TargetType.SUNSET),
                new ForecastCandidate(d, date, TargetType.SUNSET),
                new ForecastCandidate(e, date, TargetType.SUNSET));
        WeatherExtractionResult cellA = windyResult();
        WeatherExtractionResult cellC = windyResult();
        WeatherExtractionResult own = windyResult();
        Map<String, WeatherExtractionResult> fetched = new java.util.LinkedHashMap<>();
        fetched.put(OpenMeteoService.coordKey(55.0, -1.5), cellA);
        fetched.put(OpenMeteoService.coordKey(54.0, -2.0), cellC);
        fetched.put(OpenMeteoService.coordKey(53.0, -3.0), own);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<double[]>> coordsCaptor = ArgumentCaptor.forClass(List.class);
        when(openMeteoService.prefetchWeatherBatchResilient(coordsCaptor.capture(), any())).thenReturn(fetched);

        Map<String, WeatherExtractionResult> result = prefetcher.prefetchBatchWeather(candidates, call -> { });

        // 2 distinct cells + 2 cell-less locations = 4 fetches (not 5 distinct coordinates)
        assertThat(coordsCaptor.getValue()).hasSize(4);
        assertThat(coordsCaptor.getValue().get(0)).containsExactly(55.0, -1.5);
        assertThat(coordsCaptor.getValue().get(1)).containsExactly(54.0, -2.0);
        assertThat(coordsCaptor.getValue().get(2)).containsExactly(53.0, -3.0);
        assertThat(coordsCaptor.getValue().get(3)).containsExactly(53.5, -3.5);
        verify(openMeteoService).prefetchWeatherBatchResilient(eq(coordsCaptor.getValue()), any());
        // every coordinate is served: the cell fans out, the cell-less ones keep their own result
        assertThat(result.get(OpenMeteoService.coordKey(55.0, -1.5))).isSameAs(cellA);
        assertThat(result.get(OpenMeteoService.coordKey(55.004, -1.502))).isSameAs(cellA);
        assertThat(result.get(OpenMeteoService.coordKey(54.0, -2.0))).isSameAs(cellC);
        assertThat(result.get(OpenMeteoService.coordKey(53.0, -3.0))).isSameAs(own);
        assertThat(result).doesNotContainKey(OpenMeteoService.coordKey(53.5, -3.5));
    }

    @Test
    @DisplayName("prefetchBatchWeather returns an empty map when the fetch returns nothing")
    void prefetchBatchWeather_emptyFetch_returnsEmpty() {
        LocationEntity a = located(1L, 55.0, -1.5, 55.01, -1.51);
        when(openMeteoService.prefetchWeatherBatchResilient(
                argThat(BatchWeatherPrefetcherTest::isSingleCoord), any())).thenReturn(Map.of());

        assertThat(prefetcher.prefetchBatchWeather(List.of(
                new ForecastCandidate(a, LocalDate.of(2026, 6, 21), TargetType.SUNSET)),
                call -> { })).isEmpty();
    }

    @Test
    @DisplayName("prefetchBatchCloudPoints finds the fanned-out weather for every location in a cell")
    void prefetchBatchCloudPoints_fannedOutWeather_allLookupsHit() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocationEntity a = located(1L, 55.0, -1.5, 55.01, -1.51);
        LocationEntity b = located(2L, 55.004, -1.502, 55.01, -1.51);
        List<ForecastCandidate> candidates = List.of(
                new ForecastCandidate(a, date, TargetType.SUNSET),
                new ForecastCandidate(b, date, TargetType.SUNSET));
        when(openMeteoService.prefetchWeatherBatchResilient(
                argThat(BatchWeatherPrefetcherTest::isSingleCoord), any())).thenReturn(
                Map.of(OpenMeteoService.coordKey(55.0, -1.5), windyResult()));
        Map<String, WeatherExtractionResult> weather =
                prefetcher.prefetchBatchWeather(candidates, call -> { });

        LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 0);
        when(solarService.sunsetAzimuthDeg(55.0, -1.5, date)).thenReturn(250);
        when(solarService.sunsetAzimuthDeg(55.004, -1.502, date)).thenReturn(250);
        when(solarService.sunsetUtc(55.0, -1.5, date)).thenReturn(eventTime);
        when(solarService.sunsetUtc(55.004, -1.502, date)).thenReturn(eventTime);
        when(openMeteoService.computeDirectionalCloudPoints(55.0, -1.5, 250))
                .thenReturn(new ArrayList<>(List.of(new double[]{55.1, -1.5})));
        when(openMeteoService.computeDirectionalCloudPoints(55.004, -1.502, 250))
                .thenReturn(new ArrayList<>(List.of(new double[]{55.2, -1.5})));
        when(openMeteoService.computeUpwindPoint(eq(55.0), eq(-1.5), eq(270), eq(5.0),
                eq(NOW), eq(eventTime))).thenReturn(new double[]{55.3, -1.5});
        when(openMeteoService.computeUpwindPoint(eq(55.004), eq(-1.502), eq(270), eq(5.0),
                eq(NOW), eq(eventTime))).thenReturn(new double[]{55.4, -1.5});
        when(openMeteoService.prefetchCloudBatch(argThat(l -> l.size() == 4), eq(List.of()), any()))
                .thenReturn(new CloudPointCache(Map.of()));

        prefetcher.prefetchBatchCloudPoints(candidates, weather, call -> { });

        verify(openMeteoService).computeUpwindPoint(eq(55.0), eq(-1.5), eq(270), eq(5.0),
                eq(NOW), eq(eventTime));
        verify(openMeteoService).computeUpwindPoint(eq(55.004), eq(-1.502), eq(270), eq(5.0),
                eq(NOW), eq(eventTime));
    }

    @Test
    @DisplayName("prefetchBatchWeather hands the caller's sink to the service, so its calls reach the caller")
    void prefetchBatchWeather_passesCallersSinkThrough() {
        LocationEntity a = located(1L, 55.0, -1.5, 55.01, -1.51);
        PrefetchCall call = new PrefetchCall(ServiceName.OPEN_METEO_FORECAST,
                "batch-prefetch forecast chunk 1/1", 1, 1, NOW, 120L, 200, true, null, 1);
        when(openMeteoService.prefetchWeatherBatchResilient(
                argThat(BatchWeatherPrefetcherTest::isSingleCoord), any())).thenAnswer(invocation -> {
                    Consumer<PrefetchCall> sink = invocation.getArgument(1);
                    sink.accept(call);
                    return Map.of();
                });
        List<PrefetchCall> sunk = new ArrayList<>();

        prefetcher.prefetchBatchWeather(List.of(
                new ForecastCandidate(a, LocalDate.of(2026, 6, 21), TargetType.SUNSET)), sunk::add);

        assertThat(sunk).containsExactly(call);
    }

    @Test
    @DisplayName("prefetchBatchCloudPoints hands the caller's sink to the cloud pre-fetch")
    void prefetchBatchCloudPoints_passesCallersSinkThrough() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocationEntity a = located(1L, 55.0, -1.5, 55.01, -1.51);
        when(solarService.sunsetAzimuthDeg(55.0, -1.5, date)).thenReturn(250);
        when(openMeteoService.computeDirectionalCloudPoints(55.0, -1.5, 250))
                .thenReturn(new ArrayList<>(fivePoints(55.0)));
        PrefetchCall call = new PrefetchCall(ServiceName.OPEN_METEO_FORECAST,
                "batch-prefetch cloud points (5)", 5, 5, NOW, 80L, 200, true, null, 1);
        // The five directional points of the one candidate, and its one far-solar point (index 4 of them).
        when(openMeteoService.prefetchCloudBatch(
                argThat(points -> points.size() == 5 && points.get(0)[0] == 55.0),
                argThat(far -> far.size() == 1 && far.get(0)[0] == 57.0),
                any())).thenAnswer(invocation -> {
                    Consumer<PrefetchCall> sink = invocation.getArgument(2);
                    sink.accept(call);
                    return new CloudPointCache(Map.of());
                });
        List<PrefetchCall> sunk = new ArrayList<>();

        prefetcher.prefetchBatchCloudPoints(
                List.of(new ForecastCandidate(a, date, TargetType.SUNSET)), null, sunk::add);

        assertThat(sunk).containsExactly(call);
    }

    private static boolean isSingleCoord(List<double[]> coords) {
        return coords.size() == 1 && coords.get(0)[0] == 55.0 && coords.get(0)[1] == -1.5;
    }

    private static List<double[]> fivePoints(double latBase) {
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            points.add(new double[]{latBase + i * 0.5, -1.5});
        }
        return points;
    }
}
