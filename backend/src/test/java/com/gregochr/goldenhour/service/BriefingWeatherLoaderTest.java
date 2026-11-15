package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.util.GeoUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BriefingWeatherLoader}: grid-cell capture on the per-location batch, and the
 * solar-horizon cloud batch with its per-location lookup.
 */
@ExtendWith(MockitoExtension.class)
class BriefingWeatherLoaderTest {

    private static final long JOB_RUN_ID = 77L;
    private static final LocalDate DATE = LocalDate.of(2026, 11, 16);
    private static final int SUNRISE_AZIMUTH = 135;
    private static final int SUNSET_AZIMUTH = 225;

    @Mock
    private OpenMeteoClient openMeteoClient;

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private JobRunService jobRunService;

    @Mock
    private ServedBriefingEpoch servedEpoch;

    @Mock
    private SolarService solarService;

    private BriefingWeatherLoader loader;
    private JobRunEntity jobRun;

    @BeforeEach
    void setUp() {
        loader = new BriefingWeatherLoader(openMeteoClient, locationRepository, jobRunService, servedEpoch,
                solarService);
        jobRun = new JobRunEntity();
        jobRun.setId(JOB_RUN_ID);
    }

    private static LocationEntity location(long id, double lat, double lon) {
        return LocationEntity.builder().id(id).name("Loc" + id).lat(lat).lon(lon).enabled(true).build();
    }

    private static LocationEntity withGridCell(LocationEntity loc, double gridLat, double gridLng) {
        loc.setGridLat(gridLat);
        loc.setGridLng(gridLng);
        return loc;
    }

    private static OpenMeteoForecastResponse forecastAt(Double lat, Double lon) {
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        response.setLatitude(lat);
        response.setLongitude(lon);
        return response;
    }

    /** Matches a coordinate batch holding exactly these points, in this order. */
    private static List<double[]> coordsEqual(double[]... expected) {
        return argThat(actual -> actual != null && actual.size() == expected.length
                && java.util.stream.IntStream.range(0, expected.length)
                        .allMatch(i -> Arrays.equals(actual.get(i), expected[i])));
    }

    private static double[] pointAt(double lat, double lon, double bearing) {
        return GeoUtils.offsetPoint(lat, lon, bearing, 113_000.0);
    }

    /** Stubs the sunrise (135) and sunset (225) azimuths for a location on {@link #DATE}. */
    private void stubAzimuths(LocationEntity loc) {
        when(solarService.sunriseAzimuthDeg(loc.getLat(), loc.getLon(), DATE)).thenReturn(SUNRISE_AZIMUTH);
        when(solarService.sunsetAzimuthDeg(loc.getLat(), loc.getLon(), DATE)).thenReturn(SUNSET_AZIMUTH);
    }

    /** The four expected horizon points for a location: sunrise sun, sunrise anti-sun, sunset sun, anti-sun. */
    private static List<double[]> fourPoints(LocationEntity loc) {
        return List.of(
                pointAt(loc.getLat(), loc.getLon(), SUNRISE_AZIMUTH),
                pointAt(loc.getLat(), loc.getLon(), SUNRISE_AZIMUTH + 180.0),
                pointAt(loc.getLat(), loc.getLon(), SUNSET_AZIMUTH),
                pointAt(loc.getLat(), loc.getLon(), SUNSET_AZIMUTH - 180.0));
    }

    @Test
    @DisplayName("locations already carrying a grid cell share one fetch and are not written back")
    void knownGridCell_isNotPersistedAgain() {
        LocationEntity a = withGridCell(location(1L, 54.51, -1.51), 54.5, -1.5);
        LocationEntity b = withGridCell(location(2L, 54.52, -1.52), 54.5, -1.5);
        OpenMeteoForecastResponse forecast = forecastAt(54.5, -1.5);
        ArgumentCaptor<List<double[]>> coords = ArgumentCaptor.forClass(List.class);
        when(openMeteoClient.fetchForecastBriefingBatch(coords.capture())).thenReturn(List.of(forecast));

        List<BriefingSlotBuilder.LocationWeather> result = loader.fetchWeatherSequential(List.of(a, b), jobRun);

        assertThat(coords.getValue()).hasSize(1);
        assertThat(coords.getValue().get(0)).containsExactly(54.51, -1.51);
        assertThat(result).extracting(BriefingSlotBuilder.LocationWeather::location).containsExactly(a, b);
        assertThat(result).extracting(BriefingSlotBuilder.LocationWeather::forecast)
                .containsExactly(forecast, forecast);
        verifyNoInteractions(locationRepository);
        verifyNoInteractions(servedEpoch);
    }

    @Test
    @DisplayName("a response with no snapped coordinates leaves an ungrouped location's grid cell alone")
    void responseWithoutCoordinates_persistsNothing() {
        LocationEntity ungrouped = location(3L, 55.0, -2.0);
        OpenMeteoForecastResponse noCoordinates = forecastAt(null, null);
        when(openMeteoClient.fetchForecastBriefingBatch(coordsEqual(new double[] {55.0, -2.0})))
                .thenReturn(List.of(noCoordinates));

        List<BriefingSlotBuilder.LocationWeather> result =
                loader.fetchWeatherSequential(List.of(ungrouped), jobRun);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).forecast()).isSameAs(noCoordinates);
        verifyNoInteractions(locationRepository);
        verifyNoInteractions(servedEpoch);
    }

    @Test
    @DisplayName("the horizon fetch samples the sun and anti-sun point of each event on the real azimuth,"
            + " in one batch, and logs a successful call")
    void horizonCloud_successfulFetch_mapsEachLocationToItsCells() {
        LocationEntity a = location(1L, 54.5, -1.5);
        LocationEntity b = location(2L, 54.6, -3.0);
        stubAzimuths(a);
        stubAzimuths(b);
        List<double[]> expected = new ArrayList<>(fourPoints(a));
        expected.addAll(fourPoints(b));
        List<OpenMeteoForecastResponse> responses = new ArrayList<>();
        for (double[] p : expected) {
            responses.add(forecastAt(p[0], p[1]));
        }
        ArgumentCaptor<List<double[]>> coords = ArgumentCaptor.forClass(List.class);
        when(openMeteoClient.fetchCloudOnlyBatch(coords.capture())).thenReturn(responses);

        BriefingWeatherLoader.HorizonCloudData data = loader.fetchHorizonCloud(List.of(a, b), DATE, jobRun);

        assertThat(coords.getValue()).hasSize(8);
        for (int i = 0; i < 8; i++) {
            assertThat(coords.getValue().get(i)).containsExactly(expected.get(i));
        }
        assertThat(data.getForLocation(1L, TargetType.SUNRISE)).isSameAs(responses.get(0));
        assertThat(data.getAntiSunForLocation(1L, TargetType.SUNRISE)).isSameAs(responses.get(1));
        assertThat(data.getForLocation(1L, TargetType.SUNSET)).isSameAs(responses.get(2));
        assertThat(data.getAntiSunForLocation(1L, TargetType.SUNSET)).isSameAs(responses.get(3));
        assertThat(data.getForLocation(2L, TargetType.SUNRISE)).isSameAs(responses.get(4));
        assertThat(data.getAntiSunForLocation(2L, TargetType.SUNRISE)).isSameAs(responses.get(5));
        assertThat(data.getForLocation(2L, TargetType.SUNSET)).isSameAs(responses.get(6));
        assertThat(data.getAntiSunForLocation(2L, TargetType.SUNSET)).isSameAs(responses.get(7));
        verify(openMeteoClient, times(1)).fetchCloudOnlyBatch(coords.getValue());
        ArgumentCaptor<Long> duration = ArgumentCaptor.forClass(Long.class);
        verify(jobRunService).logApiCall(eq(JOB_RUN_ID), eq(ServiceName.OPEN_METEO_FORECAST), eq("GET"),
                eq("horizon-cloud-batch(8)"), isNull(), duration.capture(), eq(200), isNull(), eq(true),
                isNull());
        assertThat(duration.getValue()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("the sun point lies on the azimuth bearing and the anti-sun point on azimuth + 180")
    void horizonCloud_pointsFollowAzimuthAndAntiSun() {
        LocationEntity a = location(1L, 54.5, -1.5);
        stubAzimuths(a);
        ArgumentCaptor<List<double[]>> coords = ArgumentCaptor.forClass(List.class);
        when(openMeteoClient.fetchCloudOnlyBatch(coords.capture()))
                .thenReturn(Arrays.asList(null, null, null, null));

        loader.fetchHorizonCloud(List.of(a), DATE, jobRun);

        assertThat(coords.getValue().get(0)).containsExactly(pointAt(54.5, -1.5, 135.0));
        assertThat(coords.getValue().get(1)).containsExactly(pointAt(54.5, -1.5, 315.0));
        assertThat(coords.getValue().get(2)).containsExactly(pointAt(54.5, -1.5, 225.0));
        assertThat(coords.getValue().get(3)).containsExactly(pointAt(54.5, -1.5, 45.0));
    }

    @Test
    @DisplayName("a sunrise azimuth failure falls back to due east for that event only")
    void horizonCloud_sunriseAzimuthFailure_fallsBackToEast() {
        LocationEntity a = location(1L, 54.5, -1.5);
        when(solarService.sunriseAzimuthDeg(54.5, -1.5, DATE)).thenThrow(new IllegalStateException("no sun"));
        when(solarService.sunsetAzimuthDeg(54.5, -1.5, DATE)).thenReturn(SUNSET_AZIMUTH);
        ArgumentCaptor<List<double[]>> coords = ArgumentCaptor.forClass(List.class);
        when(openMeteoClient.fetchCloudOnlyBatch(coords.capture()))
                .thenReturn(Arrays.asList(null, null, null, null));

        loader.fetchHorizonCloud(List.of(a), DATE, jobRun);

        assertThat(coords.getValue().get(0)).containsExactly(pointAt(54.5, -1.5, 90.0));
        assertThat(coords.getValue().get(1)).containsExactly(pointAt(54.5, -1.5, 270.0));
        assertThat(coords.getValue().get(2)).containsExactly(pointAt(54.5, -1.5, 225.0));
        assertThat(coords.getValue().get(3)).containsExactly(pointAt(54.5, -1.5, 45.0));
    }

    @Test
    @DisplayName("a sunset azimuth failure falls back to due west")
    void horizonCloud_sunsetAzimuthFailure_fallsBackToWest() {
        LocationEntity a = location(1L, 54.5, -1.5);
        when(solarService.sunriseAzimuthDeg(54.5, -1.5, DATE)).thenReturn(SUNRISE_AZIMUTH);
        when(solarService.sunsetAzimuthDeg(54.5, -1.5, DATE)).thenThrow(new IllegalStateException("no sun"));
        ArgumentCaptor<List<double[]>> coords = ArgumentCaptor.forClass(List.class);
        when(openMeteoClient.fetchCloudOnlyBatch(coords.capture()))
                .thenReturn(Arrays.asList(null, null, null, null));

        loader.fetchHorizonCloud(List.of(a), DATE, jobRun);

        assertThat(coords.getValue().get(2)).containsExactly(pointAt(54.5, -1.5, 270.0));
        assertThat(coords.getValue().get(3)).containsExactly(pointAt(54.5, -1.5, 90.0));
    }

    @Test
    @DisplayName("locations whose horizon points share a grid cell are fetched once")
    void horizonCloud_sharedGridCell_isFetchedOnce() {
        LocationEntity a = location(1L, 54.5000, -1.5000);
        LocationEntity b = location(2L, 54.5010, -1.5010);
        stubAzimuths(a);
        stubAzimuths(b);
        List<OpenMeteoForecastResponse> shared = new ArrayList<>();
        for (double[] p : fourPoints(a)) {
            shared.add(forecastAt(p[0], p[1]));
        }
        ArgumentCaptor<List<double[]>> coords = ArgumentCaptor.forClass(List.class);
        when(openMeteoClient.fetchCloudOnlyBatch(coords.capture())).thenReturn(shared);

        BriefingWeatherLoader.HorizonCloudData data = loader.fetchHorizonCloud(List.of(a, b), DATE, jobRun);

        assertThat(coords.getValue()).hasSize(4);
        assertThat(data.getForLocation(1L, TargetType.SUNRISE)).isSameAs(shared.get(0));
        assertThat(data.getForLocation(2L, TargetType.SUNRISE)).isSameAs(shared.get(0));
        assertThat(data.getAntiSunForLocation(2L, TargetType.SUNRISE)).isSameAs(shared.get(1));
        assertThat(data.getForLocation(2L, TargetType.SUNSET)).isSameAs(shared.get(2));
        assertThat(data.getAntiSunForLocation(2L, TargetType.SUNSET)).isSameAs(shared.get(3));
    }

    @Test
    @DisplayName("a grid cell the batch could not fetch reads as no horizon data, not an error")
    void horizonCloud_missingCell_readsAsNull() {
        LocationEntity a = location(1L, 54.5, -1.5);
        stubAzimuths(a);
        when(openMeteoClient.fetchCloudOnlyBatch(coordsEqual(fourPoints(a).toArray(new double[0][]))))
                .thenReturn(Arrays.asList(null, null, null, null));

        BriefingWeatherLoader.HorizonCloudData data = loader.fetchHorizonCloud(List.of(a), DATE, jobRun);

        assertThat(data.getForLocation(1L, TargetType.SUNRISE)).isNull();
        assertThat(data.getForLocation(1L, TargetType.SUNSET)).isNull();
        assertThat(data.getAntiSunForLocation(1L, TargetType.SUNSET)).isNull();
    }

    @Test
    @DisplayName("an unknown location, or an event type the loader never fetched, has no horizon forecast")
    void horizonCloud_lookupMisses_returnNull() {
        LocationEntity a = location(1L, 54.5, -1.5);
        stubAzimuths(a);
        when(openMeteoClient.fetchCloudOnlyBatch(coordsEqual(fourPoints(a).toArray(new double[0][]))))
                .thenReturn(Arrays.asList(forecastAt(54.5, -0.5), forecastAt(54.5, -2.5),
                        forecastAt(54.5, -2.6), forecastAt(54.5, -0.6)));

        BriefingWeatherLoader.HorizonCloudData data = loader.fetchHorizonCloud(List.of(a), DATE, jobRun);

        assertThat(data.getForLocation(99L, TargetType.SUNRISE)).isNull();
        assertThat(data.getForLocation(1L, TargetType.HOURLY)).isNull();
        assertThat(data.getAntiSunForLocation(99L, TargetType.SUNSET)).isNull();
        assertThat(data.getAntiSunForLocation(1L, TargetType.HOURLY)).isNull();
    }

    @Test
    @DisplayName("a failed horizon batch is logged as a failed call and yields no horizon data")
    void horizonCloud_failedFetch_isLoggedAndEmpty() {
        LocationEntity a = location(1L, 54.5, -1.5);
        stubAzimuths(a);
        when(openMeteoClient.fetchCloudOnlyBatch(coordsEqual(fourPoints(a).toArray(new double[0][]))))
                .thenThrow(new IllegalStateException("429"));

        BriefingWeatherLoader.HorizonCloudData data = loader.fetchHorizonCloud(List.of(a), DATE, jobRun);

        assertThat(data.getForLocation(1L, TargetType.SUNRISE)).isNull();
        ArgumentCaptor<Long> duration = ArgumentCaptor.forClass(Long.class);
        verify(jobRunService).logApiCall(eq(JOB_RUN_ID), eq(ServiceName.OPEN_METEO_FORECAST), eq("GET"),
                eq("horizon-cloud-batch(4)"), isNull(), duration.capture(), isNull(),
                isNull(), eq(false), eq("429"));
        assertThat(duration.getValue()).isGreaterThanOrEqualTo(0L);
    }
}
