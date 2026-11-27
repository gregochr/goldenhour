package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.service.SchedulerFireOutcome.Report;
import com.gregochr.goldenhour.service.SnowStateWriter.Slot;
import com.gregochr.goldenhour.util.SnowSummits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SnowStateRefreshJob}, with the clock fixed at 2024-01-15 10:00 UTC (a historical winter
 * date, never the wall-clock day) so the five dates are 15 to 19 January. The real {@link SolarService} places
 * the slot hours; the Open-Meteo response series start at
 * 00:00 UTC seven days back and run fourteen days, as the real fetch does.
 */
@ExtendWith(MockitoExtension.class)
class SnowStateRefreshJobTest {

    private static final LocalDate TODAY = LocalDate.of(2024, 1, 15);
    private static final List<LocalDate> DATES = List.of(TODAY, TODAY.plusDays(1), TODAY.plusDays(2),
            TODAY.plusDays(3), TODAY.plusDays(4));
    private static final LocalDateTime NOW_UTC = LocalDateTime.of(2024, 1, 15, 10, 0);
    private static final LocalDateTime SERIES_START = TODAY.minusDays(7).atStartOfDay();
    private static final int SERIES_HOURS = 14 * 24;
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final int SUMMITS = SnowSummits.ALL.size();
    private static final double PLACE_LAT = 54.4313;
    private static final double PLACE_LON = -2.9947;
    private static final long RUN_ID = 77L;

    @Mock
    private LocationService locationService;

    @Mock
    private OpenMeteoClient openMeteoClient;

    @Mock
    private SnowStateWriter writer;

    @Mock
    private JobRunService jobRunService;

    @Mock
    private DynamicSchedulerService dynamicSchedulerService;

    @Mock
    private ServedBriefingEpoch servedEpoch;

    @Mock
    private LocationEnrichmentService enrichmentService;

    private final SolarService solarService = new SolarService();
    private SnowStateRefreshJob job;
    private JobRunEntity jobRun;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2024-01-15T10:00:00Z"), ZoneOffset.UTC);
        job = new SnowStateRefreshJob(locationService, openMeteoClient, solarService, writer, jobRunService,
                dynamicSchedulerService, servedEpoch, enrichmentService, clock);
        jobRun = JobRunEntity.builder().id(RUN_ID).build();
    }

    private void stubRun() {
        when(jobRunService.startRun(RunType.SNOW_STATE, false, null)).thenReturn(jobRun);
    }

    private static LocationEntity place(long id, Integer elevation) {
        return LocationEntity.builder().id(id).name("Place " + id).lat(PLACE_LAT).lon(PLACE_LON)
                .elevationMetres(elevation).build();
    }

    private void stubPlaces(LocationEntity... places) {
        when(locationService.findAllEnabled()).thenReturn(List.of(places));
    }

    /** The fetch answered with exactly this list (matched by size, the points being checked via the captor). */
    private void stubFetch(List<OpenMeteoForecastResponse> responses, int pointCount) {
        when(openMeteoClient.fetchSnowStateBatch(argThat(
                points -> points != null && points.size() == pointCount))).thenReturn(responses);
    }

    /** Matches a list of [lat, lon] pairs exactly, array contents included. */
    private static ArgumentMatcher<List<double[]>> sameCoordinates(List<double[]> expected) {
        return actual -> actual != null && Arrays.deepEquals(actual.toArray(), expected.toArray());
    }

    private static List<double[]> coordinatesOf(LocationEntity... places) {
        return Arrays.stream(places).map(p -> new double[]{p.getLat(), p.getLon()}).toList();
    }

    private static LocationEntity placeAt(long id, double lat, double lon) {
        return LocationEntity.builder().id(id).name("Place " + id).lat(lat).lon(lon).build();
    }

    private static List<OpenMeteoForecastResponse> nulls(int count) {
        return new ArrayList<>(Collections.nCopies(count, null));
    }

    /** A full fourteen-day response: cold, dry, still, with 10 mm of snow at each listed hour (UTC). */
    private static OpenMeteoForecastResponse response(Integer cloud, LocalDateTime... snowHours) {
        List<String> times = new ArrayList<>();
        List<Double> precipitation = new ArrayList<>();
        List<Double> temperature = new ArrayList<>();
        List<Integer> humidity = new ArrayList<>();
        List<Double> wind = new ArrayList<>();
        List<Double> shortwave = new ArrayList<>();
        List<Integer> lowCloud = new ArrayList<>();
        List<LocalDateTime> snowing = List.of(snowHours);
        for (int i = 0; i < SERIES_HOURS; i++) {
            LocalDateTime time = SERIES_START.plusHours(i);
            times.add(time.format(HOUR));
            precipitation.add(snowing.contains(time) ? 10.0 : 0.0);
            temperature.add(-5.0);
            humidity.add(90);
            wind.add(0.0);
            shortwave.add(0.0);
            lowCloud.add(cloud);
        }
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
        hourly.setTime(times);
        hourly.setPrecipitation(precipitation);
        hourly.setTemperature2m(temperature);
        hourly.setRelativeHumidity2m(humidity);
        hourly.setWindSpeed10m(wind);
        hourly.setShortwaveRadiation(shortwave);
        hourly.setCloudCoverLow(lowCloud);
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        response.setHourly(hourly);
        return response;
    }

    private LocalDateTime sunriseHour(LocalDate date) {
        return solarService.sunriseUtc(PLACE_LAT, PLACE_LON, date).truncatedTo(ChronoUnit.HOURS);
    }

    private LocalDateTime sunsetHour(LocalDate date) {
        return solarService.sunsetUtc(PLACE_LAT, PLACE_LON, date).truncatedTo(ChronoUnit.HOURS);
    }

    private List<Slot> writtenSlots() {
        ArgumentCaptor<List<Slot>> captor = ArgumentCaptor.captor();
        verify(writer).write(captor.capture(), eq(TODAY), eq(NOW_UTC));
        return captor.getValue();
    }

    // ── Registration and the run ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("registers a manual-aware target under the key V231 seeds, and the manual flag reaches the run")
    void registerJob_registersUnderTheSeededKey() {
        job.registerJob();

        // The literal is asserted, not JOB_KEY: the key is the join to the scheduler_job_config row V231 seeds.
        ArgumentCaptor<Consumer<Boolean>> target = ArgumentCaptor.captor();
        verify(dynamicSchedulerService).registerManualAwareJobTarget(eq("snow_state_refresh"), target.capture());
        when(jobRunService.startRun(RunType.SNOW_STATE, true, null)).thenReturn(jobRun);
        when(locationService.findAllEnabled()).thenReturn(List.of());
        when(openMeteoClient.fetchSnowStateBatch(argThat(
                points -> points != null && points.size() == SUMMITS))).thenReturn(nulls(SUMMITS));

        target.getValue().accept(true);

        verify(jobRunService).startRun(RunType.SNOW_STATE, true, null);
    }

    @Test
    @DisplayName("summits are fetched first at their OS height, then places at their recorded height")
    void pointsAreSummitsFirstThenPlaces() {
        stubRun();
        stubPlaces(place(5L, 59));
        stubFetch(nulls(SUMMITS + 1), SUMMITS + 1);

        SchedulerFireOutcome.capture(() -> job.run(false));

        ArgumentCaptor<List<double[]>> captor = ArgumentCaptor.captor();
        verify(openMeteoClient).fetchSnowStateBatch(captor.capture());
        List<double[]> points = captor.getValue();
        assertThat(points).hasSize(SUMMITS + 1);
        SnowSummits.Summit first = SnowSummits.ALL.get(0);
        assertThat(points.get(0)).containsExactly(first.lat(), first.lon(), first.heightM());
        assertThat(points.get(SUMMITS)).containsExactly(PLACE_LAT, PLACE_LON, 59.0);
    }

    @Test
    @DisplayName("a place with no recorded height is looked up and sent at that height, and no point carries NaN")
    void placeWithNoHeightRunsAtItsLookedUpHeight() {
        stubRun();
        LocationEntity noHeight = place(6L, null);
        stubPlaces(place(5L, 59), noHeight);
        when(enrichmentService.lookupSurfaceHeights(argThat(sameCoordinates(coordinatesOf(noHeight)))))
                .thenReturn(List.of(94.6));
        stubFetch(nulls(SUMMITS + 2), SUMMITS + 2);

        SchedulerFireOutcome.capture(() -> job.run(false));

        ArgumentCaptor<List<double[]>> captor = ArgumentCaptor.captor();
        verify(openMeteoClient).fetchSnowStateBatch(captor.capture());
        List<double[]> points = captor.getValue();
        assertThat(points).hasSize(SUMMITS + 2);
        assertThat(points.get(SUMMITS)).containsExactly(PLACE_LAT, PLACE_LON, 59.0);
        assertThat(points.get(SUMMITS + 1)).containsExactly(PLACE_LAT, PLACE_LON, 94.6);
        assertThat(points).allSatisfy(p -> assertThat(p[2]).isNotNaN());
        verify(jobRunService).logApiCall(eq(RUN_ID), eq(ServiceName.OPEN_METEO_FORECAST), eq("GET"),
                eq("elevation-lookup(1)"), isNull(), longThat(d -> d >= 0), eq(200), isNull(), eq(true), isNull());
    }

    @Test
    @DisplayName("a place with a recorded height is never looked up, and the cool-off is not even asked")
    void recordedHeightAlwaysWins() {
        stubRun();
        stubPlaces(place(5L, 59), place(6L, 0));
        stubFetch(nulls(SUMMITS + 2), SUMMITS + 2);

        SchedulerFireOutcome.capture(() -> job.run(false));

        verifyNoInteractions(enrichmentService);
        verify(openMeteoClient, never()).isCoolingOff();
    }

    @Test
    @DisplayName("a second run reuses the cached height: one lookup across two runs")
    void secondRunReusesTheCachedHeight() {
        when(jobRunService.startRun(RunType.SNOW_STATE, false, null)).thenReturn(jobRun);
        LocationEntity noHeight = place(6L, null);
        stubPlaces(noHeight);
        when(enrichmentService.lookupSurfaceHeights(argThat(sameCoordinates(coordinatesOf(noHeight)))))
                .thenReturn(List.of(94.6));
        stubFetch(nulls(SUMMITS + 1), SUMMITS + 1);

        SchedulerFireOutcome.capture(() -> job.run(false));
        SchedulerFireOutcome.capture(() -> job.run(false));

        verify(enrichmentService, times(1))
                .lookupSurfaceHeights(argThat(sameCoordinates(coordinatesOf(noHeight))));
        ArgumentCaptor<List<double[]>> captor = ArgumentCaptor.captor();
        verify(openMeteoClient, times(2)).fetchSnowStateBatch(captor.capture());
        assertThat(captor.getAllValues().get(1).get(SUMMITS)).containsExactly(PLACE_LAT, PLACE_LON, 94.6);
    }

    @Test
    @DisplayName("a place that has moved is looked up again at its new position")
    void movedPlaceIsLookedUpAgain() {
        when(jobRunService.startRun(RunType.SNOW_STATE, false, null)).thenReturn(jobRun);
        LocationEntity before = placeAt(6L, PLACE_LAT, PLACE_LON);
        LocationEntity after = placeAt(6L, PLACE_LAT + 0.01, PLACE_LON);
        when(locationService.findAllEnabled()).thenReturn(List.of(before), List.of(after));
        when(enrichmentService.lookupSurfaceHeights(argThat(sameCoordinates(coordinatesOf(before)))))
                .thenReturn(List.of(94.6));
        when(enrichmentService.lookupSurfaceHeights(argThat(sameCoordinates(coordinatesOf(after)))))
                .thenReturn(List.of(310.2));
        stubFetch(nulls(SUMMITS + 1), SUMMITS + 1);

        SchedulerFireOutcome.capture(() -> job.run(false));
        SchedulerFireOutcome.capture(() -> job.run(false));

        ArgumentCaptor<List<double[]>> captor = ArgumentCaptor.captor();
        verify(openMeteoClient, times(2)).fetchSnowStateBatch(captor.capture());
        assertThat(captor.getAllValues().get(0).get(SUMMITS)).containsExactly(PLACE_LAT, PLACE_LON, 94.6);
        assertThat(captor.getAllValues().get(1).get(SUMMITS)).containsExactly(PLACE_LAT + 0.01, PLACE_LON, 310.2);
    }

    @Test
    @DisplayName("a failed lookup leaves the place out: not sent, no row, counted failed, the others still run")
    void failedLookupLeavesThePlaceOut() {
        stubRun();
        LocationEntity noHeight = place(6L, null);
        stubPlaces(place(5L, 59), noHeight);
        when(enrichmentService.lookupSurfaceHeights(argThat(sameCoordinates(coordinatesOf(noHeight)))))
                .thenReturn(Collections.singletonList(null));
        List<OpenMeteoForecastResponse> responses = nulls(SUMMITS + 1);
        responses.set(SUMMITS, response(35, sunriseHour(TODAY)));
        stubFetch(responses, SUMMITS + 1);

        Report report = SchedulerFireOutcome.capture(() -> job.run(false));

        List<Slot> slots = writtenSlots();
        assertThat(slots.stream().map(Slot::pointKey).distinct()).containsExactly("loc:5");
        // 15 summits and place 5 are sent, place 6 is skipped: a total of 17, 1 fetched, 16 failed
        assertThat(jobRun.getLocationsProcessed()).isEqualTo(SUMMITS + 2);
        verify(jobRunService).completeRun(eq(jobRun), eq(1), eq(SUMMITS + 1), eq(DATES));
        assertThat(report.status()).isEqualTo(SchedulerFireStatus.PARTIAL);
        assertThat(report.summary()).isEqualTo("1 of 17 points fetched · 0 rows written");
        verify(jobRunService).logApiCall(eq(RUN_ID), eq(ServiceName.OPEN_METEO_FORECAST), eq("GET"),
                eq("elevation-lookup(1)"), isNull(), longThat(d -> d >= 0), isNull(), isNull(), eq(false),
                eq("elevation lookup failed for part of the chunk"));
    }

    @Test
    @DisplayName("while Open-Meteo is cooling off nothing is looked up, and a place with no height counts as failed")
    void coolOffSkipsTheLookup() {
        stubRun();
        stubPlaces(place(6L, null));
        when(openMeteoClient.isCoolingOff()).thenReturn(true);
        stubFetch(nulls(SUMMITS), SUMMITS);

        Report report = SchedulerFireOutcome.capture(() -> job.run(false));

        verifyNoInteractions(enrichmentService);
        assertThat(jobRun.getLocationsProcessed()).isEqualTo(SUMMITS + 1);
        verify(jobRunService).completeRun(eq(jobRun), eq(0), eq(SUMMITS + 1), eq(DATES));
        assertThat(report.status()).isEqualTo(SchedulerFireStatus.FAILED);
        assertThat(report.summary()).isEqualTo("0 of 16 points fetched · 0 rows written");
    }

    @Test
    @DisplayName("more than 100 places to look up are logged one call per chunk, and a failed chunk spares the other")
    void lookupIsLoggedPerChunk() {
        stubRun();
        List<LocationEntity> places = new ArrayList<>();
        List<Double> heights = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            places.add(placeAt(1000L + i, PLACE_LAT + i * 0.001, PLACE_LON));
            heights.add(i < 100 ? Double.valueOf(100 + i) : null);
        }
        when(locationService.findAllEnabled()).thenReturn(places);
        when(enrichmentService.lookupSurfaceHeights(
                argThat(sameCoordinates(coordinatesOf(places.toArray(new LocationEntity[0]))))))
                .thenReturn(heights);
        stubFetch(nulls(SUMMITS + 100), SUMMITS + 100);

        SchedulerFireOutcome.capture(() -> job.run(false));

        verify(jobRunService).logApiCall(eq(RUN_ID), eq(ServiceName.OPEN_METEO_FORECAST), eq("GET"),
                eq("elevation-lookup(100)"), isNull(), longThat(d -> d >= 0), eq(200), isNull(), eq(true),
                isNull());
        verify(jobRunService).logApiCall(eq(RUN_ID), eq(ServiceName.OPEN_METEO_FORECAST), eq("GET"),
                eq("elevation-lookup(20)"), isNull(), longThat(d -> d >= 0), isNull(), isNull(), eq(false),
                eq("elevation lookup failed for part of the chunk"));
        assertThat(jobRun.getLocationsProcessed()).isEqualTo(SUMMITS + 120);
    }

    @Test
    @DisplayName("a partial run writes only the points it fetched, bumps the epoch and reports PARTIAL")
    void partialRun() {
        stubRun();
        stubPlaces(place(5L, 59));
        List<OpenMeteoForecastResponse> responses = nulls(SUMMITS + 1);
        responses.set(0, response(20, sunriseHour(TODAY)));
        responses.set(SUMMITS, response(35, sunriseHour(TODAY)));
        stubFetch(responses, SUMMITS + 1);
        when(writer.write(argThat(slots -> slots.size() == 20), eq(TODAY), eq(NOW_UTC))).thenReturn(20);

        Report report = SchedulerFireOutcome.capture(() -> job.run(false));

        List<Slot> slots = writtenSlots();
        // two fetched points, five dates, two events each
        assertThat(slots).hasSize(20);
        assertThat(slots.stream().map(Slot::pointKey).distinct()).containsExactlyInAnyOrder("summit:helvellyn",
                "loc:5");
        assertThat(slots.stream().map(Slot::date).distinct()).containsExactlyInAnyOrder(TODAY, TODAY.plusDays(1),
                TODAY.plusDays(2), TODAY.plusDays(3), TODAY.plusDays(4));
        verify(servedEpoch).bump();
        verify(jobRunService).completeRun(eq(jobRun), eq(2), eq(SUMMITS - 1),
                eq(DATES));
        assertThat(report.status()).isEqualTo(SchedulerFireStatus.PARTIAL);
        assertThat(report.summary()).isEqualTo("2 of 16 points fetched · 20 rows written");
        verify(jobRunService).logApiCall(eq(RUN_ID), eq(ServiceName.OPEN_METEO_FORECAST), eq("GET"),
                eq("snow-state-batch(16)"), isNull(), longThat(d -> d >= 0), eq(200), isNull(), eq(true), isNull());
    }

    @Test
    @DisplayName("a place's sunrise and sunset slots read their own hour: snow at the sunset hour is not at sunrise")
    void slotHoursAreTheSolarHours() {
        stubRun();
        stubPlaces(place(5L, 59));
        List<OpenMeteoForecastResponse> responses = nulls(SUMMITS + 1);
        responses.set(SUMMITS, response(35, sunsetHour(TODAY)));
        stubFetch(responses, SUMMITS + 1);

        SchedulerFireOutcome.capture(() -> job.run(false));

        List<Slot> slots = writtenSlots();
        Slot sunrise = slots.stream().filter(s -> s.date().equals(TODAY) && s.eventType() == TargetType.SUNRISE)
                .findFirst().orElseThrow();
        Slot sunset = slots.stream().filter(s -> s.date().equals(TODAY) && s.eventType() == TargetType.SUNSET)
                .findFirst().orElseThrow();
        assertThat(sunrise.snowWaterMm()).isZero();
        assertThat(sunset.snowWaterMm()).isCloseTo(10.0, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(sunset.snowfall24hCm()).isCloseTo(7.0, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(sunset.lowCloudPct()).isEqualTo(35);
    }

    @Test
    @DisplayName("the low cloud at the slot hour rides along; a response without it stores null for it")
    void lowCloudRidesAlongAndMayBeMissing() {
        stubRun();
        stubPlaces(place(5L, 59));
        List<OpenMeteoForecastResponse> responses = nulls(SUMMITS + 1);
        OpenMeteoForecastResponse noCloud = response(35);
        noCloud.getHourly().setCloudCoverLow(null);
        responses.set(SUMMITS, noCloud);
        stubFetch(responses, SUMMITS + 1);

        SchedulerFireOutcome.capture(() -> job.run(false));

        assertThat(writtenSlots()).hasSize(10).allSatisfy(s -> assertThat(s.lowCloudPct()).isNull());
    }

    @Test
    @DisplayName("a series that ends early skips the dates it does not reach, and a short list of responses is safe")
    void seriesEndingEarly() {
        stubRun();
        stubPlaces(place(5L, 59));
        OpenMeteoForecastResponse shortSeries = response(10);
        int keep = (int) ChronoUnit.HOURS.between(SERIES_START, TODAY.plusDays(1).atTime(23, 0)) + 1;
        OpenMeteoForecastResponse.Hourly hourly = shortSeries.getHourly();
        hourly.setTime(new ArrayList<>(hourly.getTime().subList(0, keep)));
        // the other arrays stay full length: a series is only as long as its time axis
        List<OpenMeteoForecastResponse> responses = new ArrayList<>(Collections.nCopies(SUMMITS, null));
        responses.add(shortSeries);
        stubFetch(responses, SUMMITS + 1);

        SchedulerFireOutcome.capture(() -> job.run(false));

        assertThat(writtenSlots().stream().map(Slot::date).distinct())
                .containsExactlyInAnyOrder(TODAY, TODAY.plusDays(1));
    }

    @Test
    @DisplayName("fewer responses than points leaves the unanswered points unfetched")
    void shortResponseList() {
        stubRun();
        stubPlaces(place(5L, 59));
        stubFetch(new ArrayList<>(List.of(response(10))), SUMMITS + 1);

        Report report = SchedulerFireOutcome.capture(() -> job.run(false));

        assertThat(writtenSlots().stream().map(Slot::pointKey).distinct()).containsExactly("summit:helvellyn");
        assertThat(report.status()).isEqualTo(SchedulerFireStatus.PARTIAL);
    }

    // ── What a point's response may be ────────────────────────────────────────────────────

    @Test
    @DisplayName("a lookback the response does not cover writes nothing for that point")
    void lookbackNotCoveredWritesNothing() {
        stubRun();
        stubPlaces(place(5L, 59));
        OpenMeteoForecastResponse noHistory = response(10);
        OpenMeteoForecastResponse.Hourly hourly = noHistory.getHourly();
        // drop the past: the series now starts at today 00:00, so no slot has 168 hours behind it
        hourly.setTime(new ArrayList<>(hourly.getTime().subList(7 * 24, SERIES_HOURS)));
        List<OpenMeteoForecastResponse> responses = nulls(SUMMITS + 1);
        responses.set(SUMMITS, noHistory);
        stubFetch(responses, SUMMITS + 1);

        SchedulerFireOutcome.capture(() -> job.run(false));

        assertThat(writtenSlots()).isEmpty();
    }

    @Test
    @DisplayName("a response with no hourly block, no times, a gap in the hours or a malformed time loses only itself")
    void unusableResponsesLoseOnlyThemselves() {
        stubRun();
        stubPlaces(place(5L, 59), place(6L, 59), place(7L, 59), place(8L, 59), place(9L, 59));
        OpenMeteoForecastResponse noBlock = new OpenMeteoForecastResponse();
        OpenMeteoForecastResponse noTimes = new OpenMeteoForecastResponse();
        noTimes.setHourly(new OpenMeteoForecastResponse.Hourly());
        OpenMeteoForecastResponse emptyTimes = response(10);
        emptyTimes.getHourly().setTime(new ArrayList<>());
        OpenMeteoForecastResponse gap = response(10);
        gap.getHourly().getTime().remove(100);
        OpenMeteoForecastResponse malformed = response(10);
        malformed.getHourly().getTime().set(0, "not-a-time");
        List<OpenMeteoForecastResponse> responses = nulls(SUMMITS + 5);
        responses.set(SUMMITS, noBlock);
        responses.set(SUMMITS + 1, noTimes);
        responses.set(SUMMITS + 2, emptyTimes);
        responses.set(SUMMITS + 3, gap);
        responses.set(SUMMITS + 4, malformed);
        responses.set(0, response(10));
        stubFetch(responses, SUMMITS + 5);

        SchedulerFireOutcome.capture(() -> job.run(false));

        assertThat(writtenSlots().stream().map(Slot::pointKey).distinct()).containsExactly("summit:helvellyn");
    }

    // ── Failures ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a fetch that throws fails the run, logs the failed call, writes nothing and does not bump")
    void fetchThrows() {
        stubRun();
        stubPlaces(place(5L, 59));
        when(openMeteoClient.fetchSnowStateBatch(argThat(
                points -> points != null && points.size() == SUMMITS + 1)))
                .thenThrow(new IllegalStateException("limiter"));

        Report report = SchedulerFireOutcome.capture(() -> job.run(false));

        verifyNoInteractions(writer);
        verify(servedEpoch, never()).bump();
        verify(jobRunService).completeRun(eq(jobRun), eq(0), eq(SUMMITS + 1), eq(DATES));
        verify(jobRunService).logApiCall(eq(RUN_ID), eq(ServiceName.OPEN_METEO_FORECAST), eq("GET"),
                eq("snow-state-batch(16)"), isNull(), longThat(d -> d >= 0), isNull(), isNull(), eq(false),
                eq("limiter"));
        assertThat(report.status()).isEqualTo(SchedulerFireStatus.FAILED);
        assertThat(report.summary()).isEqualTo("Open-Meteo fetch failed: limiter");
    }

    @Test
    @DisplayName("nothing fetched (a cool-off or a refusal) fails the run, still prunes, and does not bump")
    void nothingFetched() {
        stubRun();
        stubPlaces(place(5L, 59));
        stubFetch(nulls(SUMMITS + 1), SUMMITS + 1);

        Report report = SchedulerFireOutcome.capture(() -> job.run(false));

        assertThat(writtenSlots()).isEmpty();
        verify(servedEpoch, never()).bump();
        verify(jobRunService).completeRun(eq(jobRun), eq(0), eq(SUMMITS + 1), eq(DATES));
        verify(jobRunService).logApiCall(eq(RUN_ID), eq(ServiceName.OPEN_METEO_FORECAST), eq("GET"),
                eq("snow-state-batch(16)"), isNull(), longThat(d -> d >= 0), isNull(), isNull(), eq(false),
                eq("no point fetched (cool-off, refusal or failure)"));
        assertThat(report.status()).isEqualTo(SchedulerFireStatus.FAILED);
    }

    @Test
    @DisplayName("a write that throws fails the run, keeps the previous rows and does not bump")
    void writeThrows() {
        stubRun();
        stubPlaces(place(5L, 59));
        List<OpenMeteoForecastResponse> responses = nulls(SUMMITS + 1);
        responses.set(SUMMITS, response(10));
        stubFetch(responses, SUMMITS + 1);
        when(writer.write(argThat(slots -> slots.size() == 10), eq(TODAY), eq(NOW_UTC)))
                .thenThrow(new IllegalStateException("db down"));

        Report report = SchedulerFireOutcome.capture(() -> job.run(false));

        verify(servedEpoch, never()).bump();
        verify(jobRunService).completeRun(eq(jobRun), eq(0), eq(SUMMITS + 1), eq(DATES));
        assertThat(report.status()).isEqualTo(SchedulerFireStatus.FAILED);
        assertThat(report.summary()).isEqualTo("Writing snow state failed: db down");
    }

    @Test
    @DisplayName("a run that fetched everything reports SUCCEEDED, and a write of zero rows does not bump")
    void fullRunAndNoRowsWritten() {
        stubRun();
        stubPlaces(place(5L, 59));
        List<OpenMeteoForecastResponse> responses = new ArrayList<>();
        for (int i = 0; i < SUMMITS + 1; i++) {
            responses.add(response(10));
        }
        stubFetch(responses, SUMMITS + 1);
        when(writer.write(argThat(slots -> slots.size() == 160), eq(TODAY), eq(NOW_UTC))).thenReturn(0);

        Report report = SchedulerFireOutcome.capture(() -> job.run(false));

        verify(servedEpoch, never()).bump();
        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(report.summary()).isEqualTo("16 of 16 points fetched · 0 rows written");
    }

    @Test
    @DisplayName("a second fire while one is running is skipped, not queued")
    void concurrentFireIsSkipped() {
        Report[] inner = new Report[1];
        when(jobRunService.startRun(RunType.SNOW_STATE, false, null)).thenAnswer(invocation -> {
            inner[0] = SchedulerFireOutcome.capture(() -> job.run(false));
            return jobRun;
        });
        when(locationService.findAllEnabled()).thenReturn(List.of());
        stubFetch(nulls(SUMMITS), SUMMITS);

        SchedulerFireOutcome.capture(() -> job.run(false));

        assertThat(inner[0].status()).isEqualTo(SchedulerFireStatus.SKIPPED);
        assertThat(inner[0].summary()).isEqualTo("Already running — skipped");
        verify(jobRunService, org.mockito.Mockito.times(1)).startRun(RunType.SNOW_STATE, false, null);
    }
}
